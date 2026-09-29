package com.opensync.foldersync.videoedit

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.opensync.foldersync.scanFile
import com.opensync.foldersync.uniqueFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Containers an MP4 export can safely be written back into under the same name.
private val MP4_EXTS = setOf("mp4", "m4v", "mov", "3gp")
private val MP4_MIMES = setOf("video/mp4", "video/quicktime", "video/3gpp")

private const val TIMELINE_FRAMES = 10
private const val MAX_OVERLAY_SIDE = 1920
private const val GIF_MAX_SIDE = 480
private const val GIF_FPS = 12
private const val GIF_MAX_FRAMES = 12 * 20 // 20 seconds

// ---- Reading the source ----

internal fun readVideoMeta(context: Context, uri: Uri): VideoMeta {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        val frames = if (Build.VERSION.SDK_INT >= 28) {
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
        } else null
        val fps = if (frames != null && duration > 0) frames * 1000f / duration else 30f
        require(w > 0 && h > 0 && duration > 0) { "Not a playable video" }
        val sideways = rotation % 180 != 0
        return VideoMeta(if (sideways) h else w, if (sideways) w else h, duration, hasAudio, fps.coerceIn(1f, 240f))
    } finally {
        runCatching { r.release() }
    }
}

/** Evenly spaced, small frames for the trim filmstrip (and the filter thumbnails / Auto). */
internal fun readFrames(context: Context, uri: Uri, durationMs: Long): List<Bitmap> {
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

internal fun canOverwrite(context: Context, uri: Uri): Boolean =
    if (uri.scheme == "file") {
        (uri.path?.substringAfterLast('.', "")?.lowercase() ?: "") in MP4_EXTS
    } else {
        (runCatching { context.contentResolver.getType(uri) }.getOrNull()?.lowercase() ?: "") in MP4_MIMES
    }

// ---- Effects ----

/** Rotate/flip/straighten and crop, in the same order the photo editor applies them. */
@OptIn(UnstableApi::class)
internal fun geometryEffects(e: VideoEdits, includeCrop: Boolean): List<Effect> {
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
    return list
}

/** Scales every channel by [gain] (0..1), for the video's own sound or the music. */
@OptIn(UnstableApi::class)
private fun volumeProcessor(gain: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
    for (channels in 1..8) {
        val m = FloatArray(channels * channels) { i -> if (i / channels == i % channels) gain else 0f }
        putChannelMixingMatrix(ChannelMixingMatrix(channels, channels, m))
    }
}

/**
 * Everything the saved file needs: trim, look, geometry, decorations, size, speed and sound.
 * [gif] leaves the sound out and targets a small frame, since it's only a step towards a GIF.
 */
@OptIn(UnstableApi::class)
private fun buildComposition(uri: Uri, e: VideoEdits, meta: VideoMeta, res: ResolutionOption, gif: Boolean): Composition {
    val clipping = MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(e.trimStartMs)
        .apply { e.trimEndMs?.let { setEndPositionMs(it) } }
        .build()
    val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping).build()

    val video = ArrayList<Effect>(geometryEffects(e, includeCrop = true))
    if (e.lookModified) video += ToneEffect(ToneParams.from(e.color, e.tone))
    val edited = editedSize(meta, e)
    if (e.hasOverlay) {
        // Drawn at (at most) 1920 px and stretched over the frame; the overlay's own size is
        // otherwise taken relative to the frame, so scale it to cover it exactly.
        val (ew, eh) = edited
        val k = min(1f, MAX_OVERLAY_SIDE.toFloat() / max(ew, eh))
        val ow = (ew * k).roundToInt().coerceAtLeast(1)
        val oh = (eh * k).roundToInt().coerceAtLeast(1)
        val bmp = DecorationRenderer.renderBitmap(ow, oh, e.decorations, e.strokes)
        val settings = OverlaySettings.Builder().setScale(ew.toFloat() / ow, eh.toFloat() / oh).build()
        video += OverlayEffect(ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(bmp, settings)))
    }
    val target = if (gif) {
        val k = min(1f, GIF_MAX_SIDE.toFloat() / max(edited.first, edited.second))
        outputSize(edited, ResolutionOption("GIF", (min(edited.first, edited.second) * k).roundToInt()))
    } else {
        outputSize(edited, res)
    }
    // Resize when asked to, and after any crop/straighten so the encoder gets even dimensions.
    if (target != edited || e.geometryModified) {
        video += Presentation.createForWidthAndHeight(target.first, target.second, Presentation.LAYOUT_SCALE_TO_FIT)
    }
    if (e.speed != 1f) {
        video += SpeedChangeEffect(e.speed)
        // Fast-forward multiplies the frame rate; keep it within what encoders accept.
        if (meta.frameRate * e.speed > 60f) video += FrameDropEffect.createDefaultFrameDropEffect(min(60f, max(30f, meta.frameRate)))
    }

    val audio = ArrayList<AudioProcessor>()
    if (e.volume != 1f) audio += volumeProcessor(e.volume)
    if (e.speed != 1f) audio += SonicAudioProcessor().apply { setSpeed(e.speed) }

    val main = EditedMediaItem.Builder(item)
        .setRemoveAudio(gif || e.volume <= 0f)
        .setEffects(Effects(audio, video))
        .build()
    val sequences = mutableListOf(EditedMediaItemSequence(listOf(main)))
    val music = e.music
    if (music != null && !gif) {
        val track = EditedMediaItem.Builder(MediaItem.fromUri(music.uri))
            .setRemoveVideo(true)
            .setEffects(Effects(listOf(volumeProcessor(music.volume)), emptyList()))
            .build()
        // Looping: the music repeats (or is cut) to fit the video.
        sequences += EditedMediaItemSequence(listOf(track), /* isLooping= */ true)
    }
    return Composition.Builder(sequences)
        .apply {
            // The look is tuned for ordinary (SDR) video; bring HDR down to SDR when it's changed.
            if (e.lookModified) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        }
        .build()
}

/** Runs Transformer to write [out] (MP4). Must be cancellable: the user may leave mid-way. */
@OptIn(UnstableApi::class)
private suspend fun runTransformer(context: Context, composition: Composition, out: File, onProgress: (Int) -> Unit) =
    withContext(Dispatchers.Main) {
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
            transformer.start(composition, out.absolutePath)

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

private fun tempFile(context: Context, ext: String) =
    File(File(context.cacheDir, "video_edit").apply { mkdirs() }, "export_${System.nanoTime()}.$ext")

/** The folder a copy goes into: next to the original when it's a plain file, else [publicDir]/OpenSync. */
private fun copyDir(uri: Uri, publicDir: String): File =
    uri.takeIf { it.scheme == "file" }?.path?.let { File(it).parentFile }
        ?: File(Environment.getExternalStoragePublicDirectory(publicDir), "OpenSync").apply { mkdirs() }

private fun baseName(uri: Uri, fallback: String) =
    uri.takeIf { it.scheme == "file" }?.path?.let { File(it).nameWithoutExtension } ?: fallback

/** Exports the edited video and puts it over the original or beside it as a copy. */
internal suspend fun saveEditedVideo(
    context: Context,
    uri: Uri,
    edits: VideoEdits,
    meta: VideoMeta,
    res: ResolutionOption,
    overwrite: Boolean,
    onProgress: (Int) -> Unit
): File? {
    val tmp = tempFile(context, "mp4")
    try {
        runTransformer(context, buildComposition(uri, edits, meta, res, gif = false), tmp, onProgress)
        return withContext(Dispatchers.IO) {
            when {
                overwrite && uri.scheme == "file" -> {
                    val f = File(uri.path!!)
                    tmp.inputStream().use { input -> FileOutputStream(f).use { input.copyTo(it) } }
                    scanFile(context, f)
                    f
                }
                overwrite -> { // content:// original — overwrite in place, no rescan needed.
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                    null
                }
                else -> {
                    val f = uniqueFile(copyDir(uri, Environment.DIRECTORY_MOVIES), "${baseName(uri, "video")}_edited", "mp4")
                    tmp.inputStream().use { input -> FileOutputStream(f).use { input.copyTo(it) } }
                    scanFile(context, f)
                    f
                }
            }
        }
    } finally {
        tmp.delete()
    }
}

/**
 * Makes an animated GIF of the edited clip: renders a small, silent MP4 with every edit, then
 * samples it at [GIF_FPS] (up to 20 s of output) into a looping GIF next to the original.
 */
internal suspend fun saveEditedGif(
    context: Context,
    uri: Uri,
    edits: VideoEdits,
    meta: VideoMeta,
    onProgress: (Int) -> Unit
): File {
    val tmp = tempFile(context, "mp4")
    try {
        runTransformer(context, buildComposition(uri, edits, meta, RESOLUTIONS.first(), gif = true), tmp) { onProgress(it * 7 / 10) }
        return withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            val out = uniqueFile(copyDir(uri, Environment.DIRECTORY_PICTURES), "${baseName(uri, "video")}_edited", "gif")
            try {
                r.setDataSource(tmp.absolutePath)
                val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val count = ((durMs * GIF_FPS / 1000L).toInt()).coerceIn(1, GIF_MAX_FRAMES)
                var encoder: GifEncoder? = null
                var gw = 0
                var gh = 0
                FileOutputStream(out).use { fos ->
                    for (i in 0 until count) {
                        coroutineContext.ensureActive()
                        val frame = r.getFrameAtTime(i * 1_000_000L / GIF_FPS, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                        if (encoder == null) {
                            gw = frame.width
                            gh = frame.height
                            encoder = GifEncoder(fos, gw, gh)
                        }
                        val f = if (frame.width == gw && frame.height == gh) frame else Bitmap.createScaledBitmap(frame, gw, gh, true)
                        val px = IntArray(gw * gh)
                        f.getPixels(px, 0, gw, 0, 0, gw, gh)
                        encoder!!.addFrame(px, 1000 / GIF_FPS)
                        onProgress(70 + 30 * (i + 1) / count)
                    }
                    checkNotNull(encoder) { "Couldn't read the rendered frames" }.finish()
                }
            } catch (t: Throwable) {
                out.delete()
                throw t
            } finally {
                runCatching { r.release() }
            }
            scanFile(context, out)
            out
        }
    } finally {
        tmp.delete()
    }
}

/** Saves a still (already rendered with the edits) as a JPEG next to the video. */
internal suspend fun saveFrameCapture(context: Context, uri: Uri, frame: Bitmap): File = withContext(Dispatchers.IO) {
    val f = uniqueFile(copyDir(uri, Environment.DIRECTORY_PICTURES), "${baseName(uri, "video")}_capture", "jpg")
    FileOutputStream(f).use { frame.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    scanFile(context, f)
    f
}
