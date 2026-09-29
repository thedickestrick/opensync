package com.opensync.foldersync

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.opensync.foldersync.ui.theme.OpenSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import android.graphics.ColorMatrix as GfxColorMatrix

/**
 * A video editor in the same shape as [PhotoEditorActivity]: trim, the same light/colour
 * adjustments and filter presets, and crop / straighten / rotate / flip, plus removing the sound.
 * The preview plays through Media3's effect pipeline and the save re-encodes with Media3
 * Transformer using the very same effects, so what you see is what gets written (as MP4).
 * Reachable internally (edit_path extra) and as a system video editor (ACTION_EDIT).
 */
class VideoEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent?.data
            ?: intent?.getStringExtra("edit_path")?.let { Uri.fromFile(File(it)) }
        if (uri == null) { finish(); return }
        setContent {
            OpenSyncTheme {
                VideoEditorScreen(uri = uri, onClose = { finish() })
            }
        }
    }
}

private data class VideoEdits(
    val color: ColorEdits = ColorEdits(),
    val rotation: Int = 0, // number of 90° clockwise steps
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val straighten: Float = 0f, // fine rotation in degrees (-45..45)
    val crop: NormRect = NormRect(),
    val trimStartMs: Long = 0L,
    val trimEndMs: Long? = null, // null = to the end
    val mute: Boolean = false
) {
    val trimmed get() = trimStartMs > 0L || trimEndMs != null
    val modified: Boolean
        get() = color.modified || rotation != 0 || flipH || flipV || straighten != 0f || !crop.isFull ||
            trimmed || mute
}

private enum class VideoTool(val label: String) { TRIM("Trim"), ADJUST("Light & colour"), FILTERS("Filters"), CROP("Crop") }

/** Display size (rotation metadata already applied), length, and whether there's a sound track. */
private data class VideoMeta(val width: Int, val height: Int, val durationMs: Long, val hasAudio: Boolean)

private const val MIN_TRIM_MS = 500L
private const val TIMELINE_FRAMES = 10

// Containers an MP4 export can safely be written back into under the same name.
private val MP4_EXTS = setOf("mp4", "m4v", "mov", "3gp")
private val MP4_MIMES = setOf("video/mp4", "video/quicktime", "video/3gpp")

/** An [RgbMatrix] whose matrix can be swapped while the preview plays; it's re-read every frame. */
@OptIn(UnstableApi::class)
private class ColorMatrixEffect(@Volatile var matrix: FloatArray = identityMatrix()) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix
}

private fun identityMatrix() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

/**
 * Converts an Android 4x5 colour matrix (0..255 offsets) into the column-major 4x4 GL matrix Media3
 * applies to 0..1 RGB, so video gets exactly the photo editor's look.
 */
private fun glRgbMatrix(cm: GfxColorMatrix): FloatArray {
    val a = cm.array
    val m = FloatArray(16)
    for (r in 0..2) {
        for (c in 0..2) m[c * 4 + r] = a[r * 5 + c]
        m[12 + r] = a[r * 5 + 3] + a[r * 5 + 4] / 255f // frames are opaque: fold alpha into the offset
    }
    m[15] = 1f
    return m
}

/** Frame size after rotate/straighten (before crop) — the bounding box, as for photos. */
private fun transformedSize(meta: VideoMeta, e: VideoEdits): Pair<Float, Float> {
    val w = (if (e.rotation % 2 == 1) meta.height else meta.width).toFloat()
    val h = (if (e.rotation % 2 == 1) meta.width else meta.height).toFloat()
    val rad = Math.toRadians(e.straighten.toDouble())
    val c = abs(cos(rad)).toFloat()
    val s = abs(sin(rad)).toFloat()
    return (w * c + h * s) to (w * s + h * c)
}

/** The geometry + colour effects for [e], in the same order the photo editor applies them. */
@OptIn(UnstableApi::class)
private fun videoEffects(e: VideoEdits, color: RgbMatrix?, includeCrop: Boolean): List<Effect> {
    val list = ArrayList<Effect>()
    val degrees = e.rotation * 90f + e.straighten
    if (degrees != 0f || e.flipH || e.flipV) {
        // Media3 rotates counter-clockwise (y up); the editor's rotation is clockwise.
        list += ScaleAndRotateTransformation.Builder()
            .setScale(if (e.flipH) -1f else 1f, if (e.flipV) -1f else 1f)
            .setRotationDegrees(-degrees)
            .build()
    }
    if (includeCrop && !e.crop.isFull) {
        // NormRect is top-down in 0..1; Crop takes normalized device coordinates (-1..1, y up).
        val c = e.crop
        list += Crop(c.l * 2f - 1f, c.r * 2f - 1f, 1f - c.b * 2f, 1f - c.t * 2f)
    }
    if (color != null) list += color
    return list
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoEditorScreen(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val metaResult by produceState<Result<VideoMeta>?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { readVideoMeta(context, uri) } }
    }
    val meta = metaResult?.getOrNull()
    val frames by produceState(initialValue = emptyList<Bitmap>(), meta) {
        meta?.let { m -> value = withContext(Dispatchers.IO) { runCatching { readFrames(context, uri, m.durationMs) }.getOrDefault(emptyList()) } }
    }
    val thumb = frames.getOrNull(frames.size / 2)

    var edits by remember { mutableStateOf(VideoEdits()) }
    var tool by remember { mutableStateOf(VideoTool.TRIM) }
    var aspect by remember { mutableStateOf<Float?>(null) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var showSave by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }

    val durationMs = meta?.durationMs ?: 0L
    val trimStart = edits.trimStartMs
    val trimEnd = edits.trimEndMs ?: durationMs
    val cropping = tool == VideoTool.CROP
    val canOverwrite = remember(uri) { canOverwrite(context, uri) }

    // The preview player. Effects must be set before prepare() to build the effect pipeline;
    // after that they can be swapped live.
    val colorEffect = remember { ColorMatrixEffect() }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setVideoEffects(listOf<Effect>(colorEffect))
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    // Pause when the app goes to the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Keep the screen on while a (possibly long) export runs.
    val view = LocalView.current
    DisposableEffect(saving) {
        view.keepScreenOn = saving
        onDispose { view.keepScreenOn = false }
    }

    // While paused, nothing is drawn — re-seek in place so an edit shows on the current frame.
    fun redraw() {
        if (!player.isPlaying) player.seekTo(player.currentPosition)
    }

    LaunchedEffect(edits.color) {
        colorEffect.matrix = glRgbMatrix(colorMatrixFor(edits.color))
        redraw()
    }
    LaunchedEffect(edits.rotation, edits.flipH, edits.flipV, edits.straighten, if (cropping) null else edits.crop) {
        // While cropping, show the whole frame under the crop box (like the photo editor).
        player.setVideoEffects(videoEffects(edits, colorEffect, includeCrop = !cropping))
        redraw()
    }
    LaunchedEffect(edits.mute) { player.volume = if (edits.mute) 0f else 1f }

    // Track the playhead and keep playback inside the trimmed range.
    val trimStartState = rememberUpdatedState(trimStart)
    val trimEndState = rememberUpdatedState(trimEnd)
    LaunchedEffect(player) {
        while (isActive) {
            val p = player.currentPosition
            if (player.isPlaying && trimEndState.value > 0 && p >= trimEndState.value) {
                player.pause()
                player.seekTo(trimStartState.value)
            }
            positionMs = player.currentPosition
            playing = player.isPlaying
            delay(30)
        }
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            val p = player.currentPosition
            if (p < trimStart || p >= trimEnd - 100 || player.playbackState == Player.STATE_ENDED) player.seekTo(trimStart)
            player.play()
        }
    }

    fun save(overwrite: Boolean) {
        showSave = false
        saving = true
        progress = 0
        // Free the preview's decoder for the export; many devices only have a couple.
        player.pause()
        player.stop()
        scope.launch {
            val result = runCatching {
                renderAndSaveVideo(context, uri, edits, overwrite) { progress = it }
            }
            saving = false
            result.onSuccess {
                Toast.makeText(context, if (overwrite) "Saved" else "Saved a copy", Toast.LENGTH_SHORT).show()
                onClose()
            }.onFailure {
                Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show()
                player.prepare()
            }
        }
    }

    // background before systemBarsPadding: dark fills behind the bars, content is inset off them.
    Column(Modifier.fillMaxSize().background(EDITOR_BG).systemBarsPadding()) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { edits = VideoEdits(); aspect = null }, enabled = edits.modified && !saving) {
                Icon(Icons.Filled.Refresh, "Reset", tint = if (edits.modified) Color.White else Color.Gray)
            }
            TextButton(onClick = { showSave = true }, enabled = edits.modified && !saving && meta != null) {
                Icon(Icons.Filled.Save, null, tint = if (edits.modified) Color.White else Color.Gray)
                Spacer(Modifier.width(6.dp))
                Text("Save", color = if (edits.modified) Color.White else Color.Gray)
            }
        }

        // Preview
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            when {
                metaResult == null -> CircularProgressIndicator(color = Color.White)
                meta == null -> Text("Couldn't open this video", color = Color(0xFFBBBBBB))
                else -> {
                    val (tw, th) = transformedSize(meta, edits)
                    val frameAspect = if (cropping) tw / th else (tw * edits.crop.width) / (th * edits.crop.height)
                    Box(Modifier.aspectRatio(frameAspect.coerceIn(0.05f, 20f))) {
                        AndroidView(
                            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
                            onRelease = { player.clearVideoTextureView(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (cropping) {
                            CropOverlay(
                                imageAspect = frameAspect,
                                crop = edits.crop,
                                aspect = aspect,
                                onCrop = { edits = edits.copy(crop = it) }
                            )
                        } else {
                            Box(
                                Modifier.fillMaxSize().clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { togglePlay() }
                            )
                        }
                    }
                }
            }
            if (saving) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text("Saving video… $progress%", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }

        // Play / position / sound
        if (meta != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { togglePlay() }, enabled = !saving) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White)
                }
                Text(
                    "${formatTime(positionMs - trimStart)} / ${formatTime(trimEnd - trimStart)}",
                    color = Color(0xFFDDDDDD), fontSize = 13.sp
                )
                Spacer(Modifier.weight(1f))
                if (meta.hasAudio) {
                    IconButton(onClick = { edits = edits.copy(mute = !edits.mute) }, enabled = !saving) {
                        Icon(
                            if (edits.mute) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            if (edits.mute) "Sound removed — tap to keep it" else "Remove sound",
                            tint = if (edits.mute) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                }
            }
        }

        // Tool panel
        Column(Modifier.fillMaxWidth().background(EDITOR_PANEL_BG).padding(bottom = 6.dp)) {
            when (tool) {
                VideoTool.TRIM -> TrimPanel(
                    frames = frames,
                    durationMs = durationMs,
                    startMs = trimStart,
                    endMs = trimEnd,
                    positionMs = positionMs,
                    onTrim = { s, e, movingStart ->
                        player.pause()
                        edits = edits.copy(trimStartMs = s, trimEndMs = e.takeIf { it < durationMs })
                        player.seekTo(if (movingStart) s else e)
                    },
                    onSeek = { player.seekTo(it) }
                )
                VideoTool.ADJUST -> AdjustPanel(edits.color) { edits = edits.copy(color = it) }
                VideoTool.FILTERS -> FilterPanel(thumb, edits.color.filter) { edits = edits.copy(color = edits.color.copy(filter = it)) }
                VideoTool.CROP -> CropPanel(
                    aspect = aspect,
                    straighten = edits.straighten,
                    onAspect = { r ->
                        aspect = r
                        val ia = meta?.let { transformedSize(it, edits).let { (w, h) -> w / h } } ?: 1f
                        edits = edits.copy(crop = centeredCrop(ia, r))
                    },
                    onStraighten = { edits = edits.copy(straighten = it) },
                    onRotateLeft = { edits = edits.copy(rotation = (edits.rotation + 3) % 4) },
                    onRotateRight = { edits = edits.copy(rotation = (edits.rotation + 1) % 4) },
                    onFlipH = { edits = edits.copy(flipH = !edits.flipH) },
                    onFlipV = { edits = edits.copy(flipV = !edits.flipV) }
                )
            }
            ToolTabs(VideoTool.entries, tool, { it.label }) { tool = it }
        }
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save edited video") },
            text = {
                Text(
                    if (canOverwrite) "Overwrite the original, or keep it and save a copy?"
                    else "The edited video will be saved as a new MP4 copy; the original is kept."
                )
            },
            confirmButton = { TextButton(onClick = { save(false) }) { Text("Save copy") } },
            dismissButton = {
                if (canOverwrite) TextButton(onClick = { save(true) }) { Text("Overwrite") }
                else TextButton(onClick = { showSave = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun TrimPanel(
    frames: List<Bitmap>,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    onTrim: (startMs: Long, endMs: Long, movingStart: Boolean) -> Unit,
    onSeek: (Long) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        TrimTimeline(frames, durationMs, startMs, endMs, positionMs, onTrim, onSeek)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Start ${formatTime(startMs)}", color = Color(0xFF999999), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("Length ${formatTime(endMs - startMs)}", color = Color(0xFFDDDDDD), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("End ${formatTime(endMs)}", color = Color(0xFF999999), fontSize = 12.sp)
        }
    }
}

/** Filmstrip with draggable start/end handles; tap or drag between them to scrub. */
@Composable
private fun TrimTimeline(
    frames: List<Bitmap>,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    onTrim: (startMs: Long, endMs: Long, movingStart: Boolean) -> Unit,
    onSeek: (Long) -> Unit
) {
    val start by rememberUpdatedState(startMs)
    val end by rememberUpdatedState(endMs)
    val accent = MaterialTheme.colorScheme.primary

    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A30))) {
        Row(Modifier.fillMaxSize()) {
            frames.forEach {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
        Canvas(
            Modifier.fillMaxSize().pointerInput(durationMs) {
                if (durationMs <= 0L) return@pointerInput
                val minGap = min(MIN_TRIM_MS, durationMs)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    fun msAt(x: Float) = (x / w * durationMs).toLong().coerceIn(0L, durationMs)
                    val s0 = start
                    val e0 = end
                    val slop = 28.dp.toPx()
                    val dStart = abs(down.position.x - s0.toFloat() / durationMs * w)
                    val dEnd = abs(down.position.x - e0.toFloat() / durationMs * w)
                    // 0 = start handle, 1 = end handle, 2 = scrub
                    val target = when {
                        dStart <= slop && dStart <= dEnd -> 0
                        dEnd <= slop -> 1
                        else -> 2
                    }
                    down.consume()
                    if (target == 2) onSeek(msAt(down.position.x).coerceIn(s0, e0))
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val deltaMs = ((ch.position.x - down.position.x) / w * durationMs).toLong()
                        when (target) {
                            0 -> onTrim((s0 + deltaMs).coerceIn(0L, end - minGap), end, true)
                            1 -> onTrim(start, (e0 + deltaMs).coerceIn(start + minGap, durationMs), false)
                            else -> onSeek(msAt(ch.position.x).coerceIn(start, end))
                        }
                        ch.consume()
                    }
                }
            }
        ) {
            if (durationMs <= 0L) return@Canvas
            val w = size.width
            val h = size.height
            val xs = startMs.toFloat() / durationMs * w
            val xe = endMs.toFloat() / durationMs * w
            val hw = 10.dp.toPx()
            val edge = 3.dp.toPx()
            val scrim = Color(0xB3000000)
            drawRect(scrim, Offset(0f, 0f), Size(xs, h))
            drawRect(scrim, Offset(xe, 0f), Size(w - xe, h))
            // Frame around the kept range, with a grip handle at each end.
            drawRect(accent, Offset(xs, 0f), Size(xe - xs, edge))
            drawRect(accent, Offset(xs, h - edge), Size(xe - xs, edge))
            drawRect(accent, Offset(xs, 0f), Size(hw, h))
            drawRect(accent, Offset(xe - hw, 0f), Size(hw, h))
            val grip = Color(0x99000000)
            drawLine(grip, Offset(xs + hw / 2, h * 0.35f), Offset(xs + hw / 2, h * 0.65f), 2.dp.toPx())
            drawLine(grip, Offset(xe - hw / 2, h * 0.35f), Offset(xe - hw / 2, h * 0.65f), 2.dp.toPx())
            // Playhead
            if (positionMs in startMs..endMs) {
                val xp = positionMs.toFloat() / durationMs * w
                drawLine(Color.White, Offset(xp, 0f), Offset(xp, h), 2.dp.toPx())
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val tenths = ms.coerceAtLeast(0L) / 100
    val s = tenths / 10
    return "%d:%02d.%d".format(s / 60, s % 60, tenths % 10)
}

// ---- Video processing ----

private fun readVideoMeta(context: Context, uri: Uri): VideoMeta {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        require(w > 0 && h > 0 && duration > 0) { "Not a playable video" }
        val sideways = rotation % 180 != 0
        return VideoMeta(if (sideways) h else w, if (sideways) w else h, duration, hasAudio)
    } finally {
        runCatching { r.release() }
    }
}

/** Evenly spaced, small frames for the trim filmstrip (and the filter thumbnails). */
private fun readFrames(context: Context, uri: Uri, durationMs: Long): List<Bitmap> {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        val out = ArrayList<Bitmap>()
        for (i in 0 until TIMELINE_FRAMES) {
            val atUs = durationMs * 1000L * (2 * i + 1) / (2 * TIMELINE_FRAMES)
            val f = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
            val th = 120
            val tw = (th.toFloat() * f.width / f.height).toInt().coerceAtLeast(1)
            out += Bitmap.createScaledBitmap(f, tw, th, true).also { if (it !== f) f.recycle() }
        }
        return out
    } finally {
        runCatching { r.release() }
    }
}

private fun canOverwrite(context: Context, uri: Uri): Boolean =
    if (uri.scheme == "file") {
        (uri.path?.substringAfterLast('.', "")?.lowercase() ?: "") in MP4_EXTS
    } else {
        (runCatching { context.contentResolver.getType(uri) }.getOrNull()?.lowercase() ?: "") in MP4_MIMES
    }

/** Re-encode [uri] with every edit baked in, writing an MP4 to [out]. Runs on the main looper. */
@OptIn(UnstableApi::class)
private suspend fun exportVideo(context: Context, uri: Uri, edits: VideoEdits, out: File, onProgress: (Int) -> Unit) =
    withContext(Dispatchers.Main) {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(edits.trimStartMs)
            .apply { edits.trimEndMs?.let { setEndPositionMs(it) } }
            .build()
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping).build()
        // Leave the colour matrix out when unused so a trim-only edit needn't touch the pixels.
        val color = if (edits.color.modified) ColorMatrixEffect(glRgbMatrix(colorMatrixFor(edits.color))) else null
        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(edits.mute)
            .setEffects(Effects(emptyList(), videoEffects(edits, color, includeCrop = true)))
            .build()

        suspendCancellableCoroutine<Unit> { cont ->
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(edited, out.absolutePath)

            val handler = Handler(Looper.getMainLooper())
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    handler.postDelayed(this, 250)
                }
            }
            handler.post(poll)
            // The transformer may only be touched on its own (main) thread.
            cont.invokeOnCancellation { handler.post { handler.removeCallbacks(poll); transformer.cancel() } }
        }
    }

/** Export to a temp file, then put it where it belongs (over the original, or as a copy). */
private suspend fun renderAndSaveVideo(
    context: Context,
    uri: Uri,
    edits: VideoEdits,
    overwrite: Boolean,
    onProgress: (Int) -> Unit
) {
    val tmp = File(File(context.cacheDir, "video_edit").apply { mkdirs() }, "export_${System.currentTimeMillis()}.mp4")
    try {
        exportVideo(context, uri, edits, tmp, onProgress)
        withContext(Dispatchers.IO) {
            when {
                overwrite && uri.scheme == "file" -> {
                    val f = File(uri.path!!)
                    tmp.inputStream().use { input -> FileOutputStream(f).use { input.copyTo(it) } }
                    scanFile(context, f)
                }
                overwrite -> // content:// original — overwrite in place, no rescan needed.
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                else -> {
                    val srcFile = uri.takeIf { it.scheme == "file" }?.path?.let { File(it) }
                    val dir = srcFile?.parentFile
                        ?: File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "OpenSync").apply { mkdirs() }
                    val baseName = srcFile?.nameWithoutExtension ?: "video"
                    val f = uniqueFile(dir, "${baseName}_edited", "mp4")
                    tmp.inputStream().use { input -> FileOutputStream(f).use { input.copyTo(it) } }
                    scanFile(context, f)
                }
            }
        }
    } finally {
        tmp.delete()
    }
}
