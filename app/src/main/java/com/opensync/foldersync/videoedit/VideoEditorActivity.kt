package com.opensync.foldersync.videoedit

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.opensync.foldersync.CropPanel
import com.opensync.foldersync.EDITOR_BG
import com.opensync.foldersync.EDITOR_PANEL_BG
import com.opensync.foldersync.FilterPanel
import com.opensync.foldersync.centeredCrop
import com.opensync.foldersync.CropOverlay
import com.opensync.foldersync.ui.theme.OpenSyncTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A video editor modelled on the Samsung Gallery one, sharing its look tools with
 * [com.opensync.foldersync.PhotoEditorActivity]:
 * - Trim with a filmstrip; Transform (crop, aspect ratios, straighten, rotate, flip)
 * - Filters with intensity; Tone (brightness, exposure, contrast, highlights, shadows,
 *   saturation, warmth, tint, sharpness, vignette, and Auto)
 * - Decorate: text, emoji stickers and freehand drawing
 * - Speed (¼× – 4×); Audio: the video's own volume and background music
 * - Undo/redo, capture the current frame as a photo, save as MP4 (copy or overwrite, choice of
 *   resolution) or as an animated GIF.
 * The preview plays through Media3's effect pipeline with the same effects the save uses.
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

private enum class VideoTool(val label: String, val icon: ImageVector) {
    TRIM("Trim", Icons.Filled.ContentCut),
    TRANSFORM("Transform", Icons.Filled.Crop),
    FILTERS("Filters", Icons.Filled.PhotoFilter),
    TONE("Tone", Icons.Filled.Tune),
    DECORATE("Decorate", Icons.Filled.EmojiEmotions),
    SPEED("Speed", Icons.Filled.Speed),
    AUDIO("Audio", Icons.Filled.MusicNote)
}

private data class ToneControl(val label: String, val get: (VideoEdits) -> Float, val set: (VideoEdits, Float) -> VideoEdits)

private val TONE_CONTROLS = listOf(
    ToneControl("Brightness", { it.color.brightness }) { e, v -> e.copy(color = e.color.copy(brightness = v)) },
    ToneControl("Exposure", { it.tone.exposure }) { e, v -> e.copy(tone = e.tone.copy(exposure = v)) },
    ToneControl("Contrast", { it.color.contrast }) { e, v -> e.copy(color = e.color.copy(contrast = v)) },
    ToneControl("Highlights", { it.tone.highlights }) { e, v -> e.copy(tone = e.tone.copy(highlights = v)) },
    ToneControl("Shadows", { it.tone.shadows }) { e, v -> e.copy(tone = e.tone.copy(shadows = v)) },
    ToneControl("Saturation", { it.color.saturation }) { e, v -> e.copy(color = e.color.copy(saturation = v)) },
    ToneControl("Warmth", { it.color.warmth }) { e, v -> e.copy(color = e.color.copy(warmth = v)) },
    ToneControl("Tint", { it.color.tint }) { e, v -> e.copy(color = e.color.copy(tint = v)) },
    ToneControl("Sharpness", { it.tone.sharpness }) { e, v -> e.copy(tone = e.tone.copy(sharpness = v)) },
    ToneControl("Vignette", { it.tone.vignette }) { e, v -> e.copy(tone = e.tone.copy(vignette = v)) }
)

private const val MIN_TRIM_MS = 500L
private const val SCRUB_SEEK_INTERVAL_MS = 80L

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

    // Edits + undo history. Every change goes through update() so it can be undone.
    var edits by remember { mutableStateOf(VideoEdits()) }
    val history = remember { EditHistory() }
    var historyVersion by remember { mutableIntStateOf(0) }
    fun update(new: VideoEdits, key: Any? = null) {
        if (new == edits) return
        history.record(edits, key)
        edits = new
        historyVersion++
    }

    var tool by remember { mutableStateOf(VideoTool.TRIM) }
    var aspect by remember { mutableStateOf<Float?>(null) }
    var toneIndex by remember { mutableIntStateOf(0) }
    var decorateMode by remember { mutableStateOf(DecorateMode.TEXT) }
    var selectedDecoration by remember { mutableStateOf<Long?>(null) }
    var drawColor by remember { mutableIntStateOf(DECO_COLORS[2]) }
    var drawWidth by remember { mutableStateOf(0.012f) }
    var textDialog by remember { mutableStateOf<TextDecoration?>(null) }
    var showTextDialog by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var showSave by remember { mutableStateOf(false) }
    var resolution by remember { mutableStateOf(RESOLUTIONS.first()) }
    var playing by remember { mutableStateOf(false) }
    // Read only by the small composables that show it, so the playhead doesn't recompose everything.
    val position = remember { mutableLongStateOf(0L) }
    val textureView = remember { arrayOfNulls<TextureView>(1) }

    val durationMs = meta?.durationMs ?: 0L
    val trimStart = edits.trimStartMs
    val trimEnd = edits.trimEndMs ?: durationMs
    val cropping = tool == VideoTool.TRANSFORM
    val canOverwrite = remember(uri) { canOverwrite(context, uri) }

    // The preview player. Effects must be set before prepare() to build the effect pipeline;
    // after that they can be swapped live.
    val toneEffect = remember { ToneEffect(ToneParams.from(edits.color, edits.tone)) }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setVideoEffects(listOf<Effect>(toneEffect))
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    val musicPlayer = remember { ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE } }
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            musicPlayer.release()
        }
    }

    // Pause when the app goes to the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Keep the screen on while a (possibly long) export runs, and don't let Back abandon it.
    val view = LocalView.current
    DisposableEffect(saving) {
        view.keepScreenOn = saving
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = saving) {}

    // While paused nothing is drawn — re-seek in place so an edit shows on the current frame.
    fun redraw() {
        if (!player.isPlaying) player.seekTo(player.currentPosition)
    }

    // Look changes are picked up by the running shader; only the paused-frame redraw is debounced.
    LaunchedEffect(edits.color, edits.tone) {
        toneEffect.params = ToneParams.from(edits.color, edits.tone)
        delay(60)
        redraw()
    }
    // Geometry changes rebuild the effect chain, so wait for a slider to settle first.
    LaunchedEffect(edits.rotation, edits.flipH, edits.flipV, edits.straighten, if (cropping) null else edits.crop) {
        delay(120)
        // While transforming, show the whole frame under the crop box (like the photo editor).
        player.setVideoEffects(geometryEffects(edits, includeCrop = !cropping) + toneEffect)
        redraw()
    }
    LaunchedEffect(edits.volume) { player.volume = edits.volume }
    LaunchedEffect(edits.speed) { player.setPlaybackSpeed(edits.speed) }

    // Background music preview, started from the matching point whenever the video plays.
    val musicUri = edits.music?.uri
    LaunchedEffect(musicUri) {
        musicPlayer.stop()
        musicPlayer.clearMediaItems()
        if (musicUri != null) {
            musicPlayer.setMediaItem(MediaItem.fromUri(musicUri))
            musicPlayer.prepare()
        }
    }
    LaunchedEffect(edits.music?.volume) { musicPlayer.volume = edits.music?.volume ?: 0f }
    LaunchedEffect(playing, musicUri) {
        if (playing && musicUri != null) {
            val offset = ((player.currentPosition - trimStart).coerceAtLeast(0L) / edits.speed).toLong()
            val d = musicPlayer.duration
            musicPlayer.seekTo(if (d > 0) offset % d else offset)
            musicPlayer.play()
        } else {
            musicPlayer.pause()
        }
    }

    // Scrubbing: seek at most every SCRUB_SEEK_INTERVAL_MS while the finger moves, exactly on release.
    val scrub = remember { longArrayOf(0L, -1L) } // [last seek time, pending position]

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
            if (scrub[1] < 0) position.longValue = player.currentPosition // else the scrub owns it
            playing = player.isPlaying
            delay(30)
        }
    }

    fun scrubTo(ms: Long) {
        player.pause()
        position.longValue = ms
        val now = SystemClock.uptimeMillis()
        if (now - scrub[0] >= SCRUB_SEEK_INTERVAL_MS) {
            player.seekTo(ms)
            scrub[0] = now
            scrub[1] = -1L
        } else {
            scrub[1] = ms
        }
    }
    fun scrubEnd() {
        if (scrub[1] >= 0) player.seekTo(scrub[1])
        scrub[1] = -1L
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

    fun runSave(what: String, closeAfter: Boolean, block: suspend () -> File?) {
        showSave = false
        saving = true
        progress = 0
        // Free the preview's decoder for the export; many devices only have a couple.
        player.pause()
        player.stop()
        musicPlayer.pause()
        scope.launch {
            val result = try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e // the editor was closed; nothing to report
            } catch (e: Throwable) {
                Result.failure(e)
            }
            saving = false
            result.onSuccess { f ->
                Toast.makeText(context, f?.let { "$what saved to ${it.parentFile?.name ?: "storage"}" } ?: "$what saved", Toast.LENGTH_SHORT).show()
                if (closeAfter) onClose() else player.prepare()
            }.onFailure {
                Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show()
                player.prepare()
            }
        }
    }

    fun saveVideo(overwrite: Boolean) {
        val m = meta ?: return
        val e = edits
        val res = resolution
        runSave(if (overwrite) "Video" else "Copy", closeAfter = true) {
            saveEditedVideo(context, uri, e, m, res, overwrite) { progress = it }
        }
    }

    fun saveGif() {
        val m = meta ?: return
        val e = edits
        runSave("GIF", closeAfter = false) { saveEditedGif(context, uri, e, m) { progress = it } }
    }

    fun capture() {
        val tv = textureView[0] ?: return
        player.pause()
        val shot = tv.bitmap ?: return
        val bmp = shot.copy(Bitmap.Config.ARGB_8888, true)
        if (shot !== bmp) shot.recycle()
        DecorationRenderer.draw(android.graphics.Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), edits.decorations, edits.strokes)
        scope.launch {
            runCatching { saveFrameCapture(context, uri, bmp) }
                .onSuccess { Toast.makeText(context, "Frame saved to ${it.parentFile?.name ?: "Pictures"}", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Couldn't save the frame: ${it.message}", Toast.LENGTH_LONG).show() }
            bmp.recycle()
        }
    }

    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            val name = runCatching {
                context.contentResolver.query(picked, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: "Music"
            update(edits.copy(music = MusicTrack(picked.toString(), name, edits.music?.volume ?: 0.8f)))
        }
    }

    // background before systemBarsPadding: dark fills behind the bars, content is inset off them.
    Column(Modifier.fillMaxSize().background(EDITOR_BG).systemBarsPadding()) {
        // Top bar
        val canUndo = historyVersion >= 0 && history.canUndo
        val canRedo = historyVersion >= 0 && history.canRedo
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose, enabled = !saving) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { history.undo(edits)?.let { edits = it; historyVersion++ } }, enabled = canUndo && !saving) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (canUndo) Color.White else Color.Gray)
            }
            IconButton(onClick = { history.redo(edits)?.let { edits = it; historyVersion++ } }, enabled = canRedo && !saving) {
                Icon(Icons.AutoMirrored.Filled.Redo, "Redo", tint = if (canRedo) Color.White else Color.Gray)
            }
            IconButton(onClick = { capture() }, enabled = meta != null && !saving && !cropping) {
                Icon(Icons.Filled.PhotoCamera, "Capture this frame as a photo", tint = if (meta != null && !cropping) Color.White else Color.Gray)
            }
            IconButton(onClick = { update(VideoEdits()); aspect = null; selectedDecoration = null }, enabled = edits.modified && !saving) {
                Icon(Icons.Filled.Refresh, "Revert all edits", tint = if (edits.modified) Color.White else Color.Gray)
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
                            factory = { ctx ->
                                TextureView(ctx).also {
                                    textureView[0] = it
                                    player.setVideoTextureView(it)
                                }
                            },
                            onRelease = {
                                player.clearVideoTextureView(it)
                                textureView[0] = null
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (cropping) {
                            CropOverlay(
                                imageAspect = frameAspect,
                                crop = edits.crop,
                                aspect = aspect,
                                onCrop = { update(edits.copy(crop = it), "crop") }
                            )
                        } else {
                            // Decorations belong to the finished (cropped) frame, so they're hidden while cropping.
                            DecorationLayer(
                                decorations = edits.decorations,
                                strokes = edits.strokes,
                                mode = if (tool == VideoTool.DECORATE) decorateMode else null,
                                selectedId = selectedDecoration,
                                drawColor = drawColor,
                                drawWidth = drawWidth,
                                onSelect = { selectedDecoration = it },
                                onUpdate = { d -> update(edits.copy(decorations = edits.decorations.map { if (it.id == d.id) d else it }), "deco:${d.id}") },
                                // One key per stroke: a stroke is one undo step, the next is another.
                                onStrokes = { update(edits.copy(strokes = it), "stroke:${it.size}") }
                            )
                            if (tool != VideoTool.DECORATE) {
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
            }
            if (saving) {
                // Swallows touches so nothing can be edited mid-save.
                Box(
                    Modifier.fillMaxSize().background(Color(0x99000000))
                        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text("Saving… $progress%", color = Color.White, fontSize = 13.sp)
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
                PlayheadLabel(position, trimStart, trimEnd, edits.speed)
                Spacer(Modifier.weight(1f))
                if (meta.hasAudio) {
                    val muted = edits.volume <= 0f
                    IconButton(onClick = { update(edits.copy(volume = if (muted) 1f else 0f)) }, enabled = !saving) {
                        Icon(
                            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            if (muted) "Sound removed — tap to keep it" else "Remove sound",
                            tint = if (muted) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                }
            }
        }

        // Tool panel
        Column(Modifier.fillMaxWidth().background(EDITOR_PANEL_BG).padding(bottom = 4.dp)) {
            when (tool) {
                VideoTool.TRIM -> TrimPanel(
                    frames = frames,
                    durationMs = durationMs,
                    startMs = trimStart,
                    endMs = trimEnd,
                    position = position,
                    onTrim = { s, e, movingStart ->
                        update(edits.copy(trimStartMs = s, trimEndMs = e.takeIf { it < durationMs }), if (movingStart) "trimStart" else "trimEnd")
                        scrubTo(if (movingStart) s else e)
                    },
                    onSeek = { scrubTo(it) },
                    onGestureEnd = { scrubEnd() }
                )
                VideoTool.TRANSFORM -> CropPanel(
                    aspect = aspect,
                    straighten = edits.straighten,
                    onAspect = { r ->
                        aspect = r
                        val ia = meta?.let { transformedSize(it, edits).let { (w, h) -> w / h } } ?: 1f
                        update(edits.copy(crop = centeredCrop(ia, r)))
                    },
                    onStraighten = { update(edits.copy(straighten = it), "straighten") },
                    onRotateLeft = { update(edits.copy(rotation = (edits.rotation + 3) % 4)) },
                    onRotateRight = { update(edits.copy(rotation = (edits.rotation + 1) % 4)) },
                    onFlipH = { update(edits.copy(flipH = !edits.flipH)) },
                    onFlipV = { update(edits.copy(flipV = !edits.flipV)) }
                )
                VideoTool.FILTERS -> FilterPanel(
                    thumb = thumb,
                    selected = edits.color.filter,
                    strength = edits.color.filterStrength,
                    onSelect = { update(edits.copy(color = edits.color.copy(filter = it, filterStrength = 1f))) },
                    onStrength = { update(edits.copy(color = edits.color.copy(filterStrength = it)), "filterStrength") }
                )
                VideoTool.TONE -> TonePanel(
                    edits = edits,
                    selected = toneIndex,
                    onSelect = { toneIndex = it },
                    onChange = { update(it, "tone:$toneIndex") },
                    onAuto = {
                        thumb?.let { f ->
                            val (c, t) = autoTone(f)
                            update(edits.copy(color = edits.color.copy(contrast = c.contrast, saturation = c.saturation), tone = edits.tone.copy(exposure = t.exposure, highlights = t.highlights, shadows = t.shadows)))
                        }
                    }
                )
                VideoTool.DECORATE -> DecoratePanel(
                    mode = decorateMode,
                    selected = edits.decorations.firstOrNull { it.id == selectedDecoration },
                    drawColor = drawColor,
                    drawWidth = drawWidth,
                    hasStrokes = edits.strokes.isNotEmpty(),
                    onMode = { decorateMode = it; selectedDecoration = null },
                    onAddText = { textDialog = null; showTextDialog = true },
                    onEditText = { textDialog = it; showTextDialog = true },
                    onAddSticker = { e ->
                        val s = StickerDecoration(id = System.nanoTime(), emoji = e)
                        update(edits.copy(decorations = edits.decorations + s))
                        selectedDecoration = s.id
                    },
                    onUpdate = { d -> update(edits.copy(decorations = edits.decorations.map { if (it.id == d.id) d else it }), "deco:${d.id}") },
                    onDelete = { d ->
                        update(edits.copy(decorations = edits.decorations.filterNot { it.id == d.id }))
                        selectedDecoration = null
                    },
                    onDrawColor = { drawColor = it },
                    onDrawWidth = { drawWidth = it },
                    onUndoStroke = { update(edits.copy(strokes = edits.strokes.dropLast(1))) },
                    onClearStrokes = { update(edits.copy(strokes = emptyList())) }
                )
                VideoTool.SPEED -> SpeedPanel(edits.speed, trimEnd - trimStart) { update(edits.copy(speed = it)) }
                VideoTool.AUDIO -> AudioPanel(
                    hasAudio = meta?.hasAudio == true,
                    volume = edits.volume,
                    music = edits.music,
                    onVolume = { update(edits.copy(volume = it), "volume") },
                    onPickMusic = { musicPicker.launch(arrayOf("audio/*")) },
                    onMusicVolume = { v -> edits.music?.let { update(edits.copy(music = it.copy(volume = v)), "musicVolume") } },
                    onRemoveMusic = { update(edits.copy(music = null)) }
                )
            }
            ToolBar(tool) {
                tool = it
                selectedDecoration = null
            }
        }
    }

    if (showTextDialog) {
        val editing = textDialog
        TextDecorationDialog(
            initial = editing,
            onDismiss = { showTextDialog = false },
            onConfirm = { text, color, style ->
                showTextDialog = false
                if (editing == null) {
                    val t = TextDecoration(id = System.nanoTime(), text = text, color = color, style = style)
                    update(edits.copy(decorations = edits.decorations + t))
                    selectedDecoration = t.id
                } else {
                    val t = editing.copy(text = text, color = color, style = style)
                    update(edits.copy(decorations = edits.decorations.map { if (it.id == t.id) t else it }))
                }
            }
        )
    }

    if (showSave && meta != null) {
        val edited = editedSize(meta, edits)
        val options = RESOLUTIONS.filter { it.shortSide == null || it.shortSide < min(edited.first, edited.second) }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save edited video") },
            text = {
                Column {
                    Text(
                        if (canOverwrite) "Overwrite the original, or keep it and save a copy?"
                        else "The edited video will be saved as a new MP4 copy; the original is kept."
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Resolution", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        options.forEach { r ->
                            val sel = r == resolution
                            val size = outputSize(edited, r)
                            Column(
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { resolution = r }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(r.label, fontSize = 13.sp)
                                Text("${size.first}×${size.second}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { saveGif() }) { Text("Save as animated GIF instead") }
                }
            },
            confirmButton = { TextButton(onClick = { saveVideo(false) }) { Text("Save copy") } },
            dismissButton = {
                if (canOverwrite) TextButton(onClick = { saveVideo(true) }) { Text("Overwrite") }
                else TextButton(onClick = { showSave = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PlayheadLabel(position: MutableLongState, trimStart: Long, trimEnd: Long, speed: Float) {
    val pos = ((position.longValue - trimStart) / speed).toLong()
    val len = ((trimEnd - trimStart) / speed).toLong()
    Text("${formatTime(pos)} / ${formatTime(len)}", color = Color(0xFFDDDDDD), fontSize = 13.sp)
}

@Composable
private fun ToolBar(selected: VideoTool, onSelect: (VideoTool) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(VideoTool.entries) { t ->
            val tint = if (t == selected) MaterialTheme.colorScheme.primary else Color(0xFFBBBBBB)
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(t) }.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(t.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                Text(t.label, color = tint, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun TrimPanel(
    frames: List<Bitmap>,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    position: MutableLongState,
    onTrim: (startMs: Long, endMs: Long, movingStart: Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onGestureEnd: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        TrimTimeline(frames, durationMs, startMs, endMs, position, onTrim, onSeek, onGestureEnd)
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
    position: MutableLongState,
    onTrim: (startMs: Long, endMs: Long, movingStart: Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onGestureEnd: () -> Unit
) {
    val start by rememberUpdatedState(startMs)
    val end by rememberUpdatedState(endMs)
    val trim by rememberUpdatedState(onTrim)
    val seek by rememberUpdatedState(onSeek)
    val gestureEnd by rememberUpdatedState(onGestureEnd)
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
                    if (target == 2) seek(msAt(down.position.x).coerceIn(s0, e0))
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val deltaMs = ((ch.position.x - down.position.x) / w * durationMs).toLong()
                        when (target) {
                            0 -> trim((s0 + deltaMs).coerceIn(0L, end - minGap), end, true)
                            1 -> trim(start, (e0 + deltaMs).coerceIn(start + minGap, durationMs), false)
                            else -> seek(msAt(ch.position.x).coerceIn(start, end))
                        }
                        ch.consume()
                    }
                    gestureEnd()
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
            // Playhead (read here, in the draw phase, so it only redraws this canvas)
            val p = position.longValue
            if (p in startMs..endMs) {
                val xp = p.toFloat() / durationMs * w
                drawLine(Color.White, Offset(xp, 0f), Offset(xp, h), 2.dp.toPx())
            }
        }
    }
}

/** Samsung-style tone tools: pick an adjustment, then one slider for it. */
@Composable
private fun TonePanel(
    edits: VideoEdits,
    selected: Int,
    onSelect: (Int) -> Unit,
    onChange: (VideoEdits) -> Unit,
    onAuto: () -> Unit
) {
    val control = TONE_CONTROLS[selected]
    val value = control.get(edits)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(control.label, color = Color(0xFFDDDDDD), fontSize = 13.sp, modifier = Modifier.width(84.dp))
            Slider(value = value, onValueChange = { onChange(control.set(edits, it)) }, valueRange = -1f..1f, modifier = Modifier.weight(1f))
            Text(
                "${(value * 100).roundToInt()}",
                color = Color(0xFF999999),
                fontSize = 12.sp,
                modifier = Modifier.width(36.dp).clickable { onChange(control.set(edits, 0f)) }
            )
        }
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                ToneChip("Auto", null, selected = false, icon = Icons.Filled.AutoFixHigh, onClick = onAuto)
            }
            items(TONE_CONTROLS.size) { i ->
                val c = TONE_CONTROLS[i]
                ToneChip(c.label, (c.get(edits) * 100).roundToInt(), selected = i == selected) { onSelect(i) }
            }
        }
    }
}

@Composable
private fun ToneChip(label: String, value: Int?, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0x333B82F6) else Color(0xFF26262C))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val tint = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFCCCCCC)
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        else Text(if (value == null || value == 0) "0" else "%+d".format(value), color = if (value != 0) tint else Color(0xFF888888), fontSize = 11.sp)
        Text(label, color = tint, fontSize = 12.sp)
    }
}

@Composable
private fun SpeedPanel(speed: Float, trimmedMs: Long, onSpeed: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(SPEEDS) { s ->
                val sel = s == speed
                Text(
                    speedLabel(s),
                    color = if (sel) MaterialTheme.colorScheme.primary else Color(0xFFCCCCCC),
                    fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (sel) Color(0x333B82F6) else Color(0xFF26262C))
                        .clickable { onSpeed(s) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        Text(
            "Length after speed change: ${formatTime((trimmedMs / speed).toLong())}",
            color = Color(0xFF999999),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun AudioPanel(
    hasAudio: Boolean,
    volume: Float,
    music: MusicTrack?,
    onVolume: (Float) -> Unit,
    onPickMusic: () -> Unit,
    onMusicVolume: (Float) -> Unit,
    onRemoveMusic: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (hasAudio) {
            VolumeRow("Video sound", volume, onVolume)
        } else {
            Text("This video has no sound of its own.", color = Color(0xFF888888), fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (music == null) {
            TextButton(onClick = onPickMusic) {
                Icon(Icons.Filled.MusicNote, null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("Add background music", color = Color.White)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(music.name, color = Color(0xFFDDDDDD), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = onPickMusic) { Text("Change") }
                TextButton(onClick = onRemoveMusic) { Text("Remove") }
            }
            VolumeRow("Music", music.volume, onMusicVolume)
        }
    }
}

@Composable
private fun VolumeRow(label: String, value: Float, onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFFDDDDDD), fontSize = 13.sp, modifier = Modifier.width(90.dp))
        Slider(value = value, onValueChange = onValue, valueRange = 0f..1f, modifier = Modifier.weight(1f))
        Text("${(value * 100).roundToInt()}%", color = Color(0xFF999999), fontSize = 12.sp, modifier = Modifier.width(40.dp))
    }
}
