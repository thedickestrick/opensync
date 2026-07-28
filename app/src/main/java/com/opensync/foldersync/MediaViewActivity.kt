package com.opensync.foldersync

import android.app.Activity
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.opensync.foldersync.ui.theme.OpenSyncTheme
import java.io.File

private data class MediaEntry(val uri: Uri, val isVideo: Boolean)

private val IMAGE_EXTS = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif")
private val VIDEO_EXTS = listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".3gp", ".m4v", ".ts")

/**
 * Standalone full-screen viewer for images/videos handed to us via ACTION_VIEW, so OpenSync can be
 * a default picture/video player. For file:// opens it enumerates the folder so you can swipe
 * between siblings; content:// opens (no reliable siblings) show just the one item.
 */
class MediaViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val data: Uri? = intent?.data
        if (data == null) { finish(); return }

        val (items, startIndex) = buildPlaylist(data)
        setContent {
            OpenSyncTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    MediaPager(items, startIndex) { finish() }
                }
            }
        }
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
        // content:// or anything else — a single item, type from the intent / resolver / extension.
        val mime = intent?.type ?: runCatching { contentResolver.getType(data) }.getOrNull()
        val video = if (mime != null) mime.startsWith("video/") else isVideo(data.toString())
        return listOf(MediaEntry(data, video)) to 0
    }
}

private fun isImage(name: String) = name.lowercase().let { n -> IMAGE_EXTS.any { n.endsWith(it) } }
private fun isVideo(name: String) = name.lowercase().let { n -> VIDEO_EXTS.any { n.endsWith(it) } }

@Composable
private fun MediaPager(items: List<MediaEntry>, startIndex: Int, onClose: () -> Unit) {
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) {
        items.size
    }
    // Controls hidden by default for a clean full-screen picture; tap toggles them.
    var chromeVisible by remember { mutableStateOf(false) }

    val view = LocalView.current
    LaunchedEffect(chromeVisible) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chromeVisible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val item = items[page]
            if (item.isVideo) VideoPage(item.uri, active = pagerState.currentPage == page)
            else ImagePage(item.uri, onTap = { chromeVisible = !chromeVisible })
        }
        if (chromeVisible) {
            CloseButton(onClose)
            if (items.size > 1) {
                Text(
                    "${pagerState.currentPage + 1} / ${items.size}",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun ImagePage(uri: Uri, onTap: () -> Unit) {
    var scale by remember(uri) { mutableStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = uri,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(uri) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    }
                )
            }
            // Only capture drags when pinching or zoomed, so single-finger swipes reach the pager.
            .pointerInput(uri) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        if (multiTouch || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
    )
}

@Composable
private fun VideoPage(uri: Uri, active: Boolean) {
    val context = LocalContext.current
    val exo = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    // Only the current page plays; swiping away pauses it.
    LaunchedEffect(active) { exo.playWhenReady = active }
    DisposableEffect(uri) { onDispose { exo.release() } }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { player = exo } },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun BoxScope.CloseButton(onClose: () -> Unit) {
    IconButton(
        onClick = onClose,
        modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
    }
}
