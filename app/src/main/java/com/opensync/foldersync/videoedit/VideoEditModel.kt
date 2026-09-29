package com.opensync.foldersync.videoedit

import android.graphics.Bitmap
import com.opensync.foldersync.ColorEdits
import com.opensync.foldersync.NormRect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Tone controls the video editor adds on top of the shared [ColorEdits] (each -1..1). */
internal data class ToneEdits(
    val exposure: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val sharpness: Float = 0f, // negative softens
    val vignette: Float = 0f // positive darkens the corners, negative brightens them
) {
    val modified get() = exposure != 0f || highlights != 0f || shadows != 0f || sharpness != 0f || vignette != 0f
}

/** Text, emoji stickers and drawn strokes, all placed relative to the finished frame (0..1). */
internal sealed interface Decoration {
    val id: Long
    val x: Float // centre, 0..1 of frame width
    val y: Float // centre, 0..1 of frame height
    val scale: Float // size as a fraction of the frame's shorter side
    fun moved(x: Float, y: Float, scale: Float): Decoration
}

internal enum class LabelStyle { PLAIN, OUTLINE, BOX }

internal data class TextDecoration(
    override val id: Long,
    val text: String,
    val color: Int,
    val style: LabelStyle = LabelStyle.OUTLINE,
    override val x: Float = 0.5f,
    override val y: Float = 0.5f,
    override val scale: Float = 0.08f
) : Decoration {
    override fun moved(x: Float, y: Float, scale: Float) = copy(x = x, y = y, scale = scale)
}

internal data class StickerDecoration(
    override val id: Long,
    val emoji: String,
    override val x: Float = 0.5f,
    override val y: Float = 0.5f,
    override val scale: Float = 0.18f
) : Decoration {
    override fun moved(x: Float, y: Float, scale: Float) = copy(x = x, y = y, scale = scale)
}

/** A freehand stroke; points are 0..1 of the frame, width is a fraction of the shorter side. */
internal data class DrawStroke(val points: List<Pair<Float, Float>>, val color: Int, val width: Float)

/** Background music mixed under (or instead of) the video's own sound. */
internal data class MusicTrack(val uri: String, val name: String, val volume: Float = 0.8f)

internal data class VideoEdits(
    val color: ColorEdits = ColorEdits(),
    val tone: ToneEdits = ToneEdits(),
    val rotation: Int = 0, // number of 90° clockwise steps
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val straighten: Float = 0f, // fine rotation in degrees (-45..45)
    val crop: NormRect = NormRect(),
    val trimStartMs: Long = 0L,
    val trimEndMs: Long? = null, // null = to the end
    val speed: Float = 1f,
    val volume: Float = 1f, // the video's own sound, 0 = removed
    val music: MusicTrack? = null,
    val decorations: List<Decoration> = emptyList(),
    val strokes: List<DrawStroke> = emptyList()
) {
    val geometryModified get() = rotation != 0 || flipH || flipV || straighten != 0f || !crop.isFull
    val lookModified get() = color.modified || tone.modified
    val hasOverlay get() = decorations.isNotEmpty() || strokes.isNotEmpty()
    val modified: Boolean
        get() = lookModified || geometryModified || trimStartMs > 0L || trimEndMs != null || speed != 1f ||
            volume != 1f || music != null || hasOverlay
}

internal val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)

internal fun speedLabel(s: Float): String = when (s) {
    0.25f -> "¼×"
    0.5f -> "½×"
    0.75f -> "¾×"
    else -> if (s == s.toInt().toFloat()) "${s.toInt()}×" else "${s}×"
}

/** Output size choices; [shortSide] null keeps the edited frame's own size. */
internal data class ResolutionOption(val label: String, val shortSide: Int?)

internal val RESOLUTIONS = listOf(
    ResolutionOption("Original", null),
    ResolutionOption("1080p", 1080),
    ResolutionOption("720p", 720),
    ResolutionOption("480p", 480)
)

/** Display size (rotation metadata already applied), length, frame rate and sound track. */
internal data class VideoMeta(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val hasAudio: Boolean,
    val frameRate: Float
)

/** Frame size after rotate/straighten (before crop): the rotated frame's bounding box. */
internal fun transformedSize(meta: VideoMeta, e: VideoEdits): Pair<Float, Float> {
    val w = (if (e.rotation % 2 == 1) meta.height else meta.width).toFloat()
    val h = (if (e.rotation % 2 == 1) meta.width else meta.height).toFloat()
    val rad = Math.toRadians(e.straighten.toDouble())
    val c = abs(cos(rad)).toFloat()
    val s = abs(sin(rad)).toFloat()
    return (w * c + h * s) to (w * s + h * c)
}

/** Pixel size of the finished frame (rotated and cropped), before any resolution change. */
internal fun editedSize(meta: VideoMeta, e: VideoEdits): Pair<Int, Int> {
    val (tw, th) = transformedSize(meta, e)
    return (tw * e.crop.width).roundToInt().coerceAtLeast(2) to (th * e.crop.height).roundToInt().coerceAtLeast(2)
}

/**
 * Size to encode at for [res]: scaled down so the shorter side is at most [ResolutionOption.shortSide]
 * (never up), rounded to even numbers as video encoders require.
 */
internal fun outputSize(edited: Pair<Int, Int>, res: ResolutionOption): Pair<Int, Int> {
    val (w, h) = edited
    val short = minOf(w, h)
    val k = res.shortSide?.let { minOf(1f, it.toFloat() / short) } ?: 1f
    fun even(v: Float) = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
    return even(w * k) to even(h * k)
}

/** Keeps edit history for undo/redo; quick successive changes (a slider drag) collapse into one step. */
internal class EditHistory(private val limit: Int = 60) {
    private val past = ArrayDeque<VideoEdits>()
    private val future = ArrayDeque<VideoEdits>()
    private var lastChangeAt = 0L

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    /** Call with the state *before* a change. */
    fun record(before: VideoEdits, now: Long = System.currentTimeMillis()) {
        if (now - lastChangeAt > COALESCE_MS || past.isEmpty()) {
            past.addLast(before)
            if (past.size > limit) past.removeFirst()
        }
        future.clear()
        lastChangeAt = now
    }

    fun undo(current: VideoEdits): VideoEdits? {
        val prev = past.removeLastOrNull() ?: return null
        future.addLast(current)
        lastChangeAt = 0L
        return prev
    }

    fun redo(current: VideoEdits): VideoEdits? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        lastChangeAt = 0L
        return next
    }

    private companion object {
        const val COALESCE_MS = 700L
    }
}

/**
 * One-tap "Auto" adjustment from a sample frame: pulls the average brightness toward the middle,
 * stretches flat contrast a little and adds a touch of colour.
 */
internal fun autoTone(frame: Bitmap): Pair<ColorEdits, ToneEdits> {
    val w = 64
    val h = (64f * frame.height / frame.width).roundToInt().coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(frame, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    if (small !== frame) small.recycle()
    var sum = 0.0
    var sumSq = 0.0
    var satSum = 0.0
    for (p in px) {
        val r = (p ushr 16 and 0xFF) / 255.0
        val g = (p ushr 8 and 0xFF) / 255.0
        val b = (p and 0xFF) / 255.0
        val l = 0.2126 * r + 0.7152 * g + 0.0722 * b
        sum += l
        sumSq += l * l
        satSum += maxOf(r, g, b) - minOf(r, g, b)
    }
    val n = px.size.toDouble()
    val mean = sum / n
    val std = sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
    val sat = satSum / n
    val exposure = ((0.48 - mean) * 1.6).toFloat().coerceIn(-0.6f, 0.6f)
    val contrast = ((0.22 - std) * 1.5).toFloat().coerceIn(-0.2f, 0.35f)
    val saturation = ((0.3 - sat) * 0.8).toFloat().coerceIn(-0.1f, 0.3f)
    val shadows = if (mean < 0.4) 0.25f else 0.1f
    val highlights = if (mean > 0.55) -0.25f else -0.1f
    return ColorEdits(contrast = contrast, saturation = saturation) to
        ToneEdits(exposure = exposure, shadows = shadows, highlights = highlights)
}

internal fun formatTime(ms: Long): String {
    val tenths = ms.coerceAtLeast(0L) / 100
    val s = tenths / 10
    return "%d:%02d.%d".format(s / 60, s % 60, tenths % 10)
}
