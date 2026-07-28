package com.opensync.foldersync

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.opensync.foldersync.ui.theme.OpenSyncTheme

/**
 * Standalone full-screen viewer for a single image or video handed to us via ACTION_VIEW.
 * Lets OpenSync be set as the device's default picture / video player.
 */
class MediaViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val data: Uri? = intent?.data
        if (data == null) { finish(); return }

        val mime = intent?.type
            ?: runCatching { contentResolver.getType(data) }.getOrNull()
        val isVideo = if (mime != null) mime.startsWith("video/") else looksLikeVideo(data)

        setContent {
            OpenSyncTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    if (isVideo) VideoScreen(data) { finish() }
                    else ImageScreen(data) { finish() }
                }
            }
        }
    }

    private fun looksLikeVideo(uri: Uri?): Boolean {
        val s = uri?.toString()?.lowercase() ?: return false
        return listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".3gp", ".m4v", ".ts").any { s.endsWith(it) }
    }
}

@Composable
private fun ImageScreen(uri: Uri, onClose: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
        )
        CloseButton(onClose)
    }
}

@Composable
private fun VideoScreen(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val exo = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(uri) { onDispose { exo.release() } }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = exo } },
            modifier = Modifier.fillMaxSize()
        )
        CloseButton(onClose)
    }
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
