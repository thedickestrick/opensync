package com.opensync.foldersync

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.opensync.foldersync.ui.theme.OpenSyncTheme
import java.io.File
import kotlin.math.abs

private data class MediaEntry(val uri: Uri, val isVideo: Boolean)

private val IMAGE_EXTS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif")
private val VIDEO_EXTS = listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".3gp", ".m4v", ".ts")

/**
 * Standalone full-screen viewer for images/videos handed to us via ACTION_VIEW, so OpenSync can be
 * a default picture/video player. Nothing but the picture is shown (no bars, no controls). For
 * file:// opens the folder is enumerated so a horizontal swipe jumps straight to the next item.
 */
class MediaViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()

        val data: Uri? = intent?.data
        if (data == null) { finish(); return }

        val (items, startIndex) = buildPlaylist(data)
        setContent {
            OpenSyncTheme {
                MediaSwiper(items, startIndex)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun buildPlaylist(data: Uri): Pair<List<MediaEntry>, Int> {
        if (data.scheme == "file") {
            val file = File(data.path.orEmpty())
            val parent = file.parentFile
            if (parent != null && file.exists()) {
                val siblings = parent.listFiles()
                    ?.filter { it.isFile && (isImage(it.name) || isVideo(it.name)) }
                    ?.sortedBy { it.name.lowercase() }
                    .orEmpty()
                if (siblings.isNotEmpty()) {
                    val entries = siblings.map { MediaEntry(Uri.fromFile(it), isVideo(it.name)) }
                    val idx = siblings.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
                    return entries to idx
                }
            }
        }
        val mime = intent?.type ?: runCatching { contentResolver.getType(data) }.getOrNull()
        val video = if (mime != null) mime.startsWith("video/") else isVideo(data.toString())
        return listOf(MediaEntry(data, video)) to 0
    }
}

private fun isImage(name: String) = name.lowercase().let { n -> IMAGE_EXTS.any { n.endsWith(it) } }
private fun isVideo(name: String) = name.lowercase().let { n -> VIDEO_EXTS.any { n.endsWith(it) } }

@Composable
private fun MediaSwiper(items: List<MediaEntry>, startIndex: Int) {
    var index by remember { mutableStateOf(startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }
    Box(Modifier.fillMaxSize()) {
        val item = items.getOrNull(index) ?: return@Box
        if (item.isVideo) {
            VideoPage(item.uri)
        } else {
            SwipeImage(
                uri = item.uri,
                onPrev = { if (index > 0) index-- },
                onNext = { if (index < items.size - 1) index++ }
            )
        }
    }
}

/** Full-screen image with pinch/double-tap zoom; a horizontal swipe switches item instantly (no animation). */
@Composable
private fun SwipeImage(uri: Uri, onPrev: () -> Unit, onNext: () -> Unit) {
    var scale by remember(uri) { mutableStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(uri) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(uri) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var dy = 0f
                    var zoomed = false
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        if (multiTouch || scale > 1f) {
                            zoomed = true
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else {
                            val pan = event.calculatePan()
                            dx += pan.x; dy += pan.y
                            if (abs(dx) > abs(dy)) event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!zoomed && scale <= 1f && abs(dx) > abs(dy)) {
                        val threshold = size.width * 0.15f
                        if (dx <= -threshold) onNext() else if (dx >= threshold) onPrev()
                    }
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
    )
}

@Composable
private fun VideoPage(uri: Uri) {
    val context = LocalContext.current
    val exo = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(uri) { onDispose { exo.release() } }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { player = exo } },
        modifier = Modifier.fillMaxSize()
    )
}
