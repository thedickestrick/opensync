package com.opensync.foldersync

import com.opensync.foldersync.videoedit.Decoration
import com.opensync.foldersync.videoedit.DrawStroke
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Everything the photo editor can change, applied in this order: transform, crop, tone & colour, decorations. */
internal data class PhotoEdits(
    val color: ColorEdits = ColorEdits(),
    val tone: ToneEdits = ToneEdits(),
    val rotation: Int = 0, // number of 90° clockwise steps
    val flipH: Boolean = false, // flips of the source image, applied before rotating
    val flipV: Boolean = false,
    val straighten: Float = 0f, // fine rotation in degrees (-45..45); the picture zooms to fill the frame
    val crop: NormRect = NormRect(),
    val decorations: List<Decoration> = emptyList(),
    val strokes: List<DrawStroke> = emptyList()
) {
    val geometryModified get() = rotation != 0 || flipH || flipV || straighten != 0f || !crop.isFull
    val hasOverlay get() = decorations.isNotEmpty() || strokes.isNotEmpty()
    val modified get() = color.modified || tone.modified || geometryModified || hasOverlay

    /** A quarter turn, with the crop box turning along with the picture. */
    fun rotated(clockwise: Boolean): PhotoEdits = copy(
        rotation = (rotation + if (clockwise) 1 else 3) % 4,
        crop = if (clockwise) NormRect(1f - crop.b, crop.l, 1f - crop.t, crop.r)
        else NormRect(crop.t, 1f - crop.r, crop.b, 1f - crop.l)
    )

    /**
     * Mirrored left-to-right as it looks on screen. Flips apply to the source before it is turned, so
     * on a sideways picture that's the other flip; the tilt mirrors too, and so does the crop box.
     */
    fun mirrored(): PhotoEdits = copy(
        flipH = if (rotation % 2 == 0) !flipH else flipH,
        flipV = if (rotation % 2 == 1) !flipV else flipV,
        straighten = -straighten,
        crop = NormRect(1f - crop.r, crop.t, 1f - crop.l, crop.b)
    )

    /** Flipped top-to-bottom as it looks on screen. */
    fun flipped(): PhotoEdits = copy(
        flipV = if (rotation % 2 == 0) !flipV else flipV,
        flipH = if (rotation % 2 == 1) !flipH else flipH,
        straighten = -straighten,
        crop = NormRect(crop.l, 1f - crop.b, crop.r, 1f - crop.t)
    )
}

/**
 * How far a [w] x [h] picture turned by [degrees] must be enlarged to cover its own frame with no
 * empty corners.
 */
internal fun straightenScale(w: Float, h: Float, degrees: Float): Float {
    if (degrees == 0f || w <= 0f || h <= 0f) return 1f
    val rad = Math.toRadians(abs(degrees).toDouble())
    return (cos(rad) + max(w / h, h / w) * sin(rad)).toFloat()
}

/** Pixel rectangle [x, y, w, h] of [c] in a [w] x [h] image, at least one pixel and inside it. */
internal fun cropPixels(c: NormRect, w: Int, h: Int): IntArray {
    val x = (c.l * w).roundToInt().coerceIn(0, w - 1)
    val y = (c.t * h).roundToInt().coerceIn(0, h - 1)
    val cw = (c.width * w).roundToInt().coerceIn(1, w - x)
    val ch = (c.height * h).roundToInt().coerceIn(1, h - y)
    return intArrayOf(x, y, cw, ch)
}

/** Size of the finished picture for a [srcW] x [srcH] upright source, before any resize. */
internal fun editedPhotoSize(srcW: Int, srcH: Int, e: PhotoEdits): Pair<Int, Int> {
    val w = if (e.rotation % 2 == 1) srcH else srcW
    val h = if (e.rotation % 2 == 1) srcW else srcH
    val px = cropPixels(e.crop, w, h)
    return px[2] to px[3]
}

/** Save sizes; [longSide] null keeps the edited picture's own size. */
internal data class PhotoSize(val label: String, val longSide: Int?)

internal val PHOTO_SIZES = listOf(
    PhotoSize("Original", null),
    PhotoSize("Large", 2560),
    PhotoSize("Medium", 1600),
    PhotoSize("Small", 1024)
)

/** Scaled down so the longer side is at most [PhotoSize.longSide] (never up). */
internal fun photoOutputSize(w: Int, h: Int, size: PhotoSize): Pair<Int, Int> {
    val k = size.longSide?.let { min(1f, it.toFloat() / max(w, h)) } ?: 1f
    return (w * k).roundToInt().coerceAtLeast(1) to (h * k).roundToInt().coerceAtLeast(1)
}

internal enum class PhotoFormat(val ext: String) { JPEG("jpg"), PNG("png"), WEBP("webp") }

/**
 * The format to write when saving over a photo with this file name (or extension) and MIME type,
 * or null if it's a kind we can't write back (HEIC, GIF, RAW, …) — those are saved as a JPEG copy.
 */
internal fun writableFormat(name: String?, mime: String?): PhotoFormat? {
    val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
    when (ext) {
        "jpg", "jpeg", "jpe" -> return PhotoFormat.JPEG
        "png" -> return PhotoFormat.PNG
        "webp" -> return PhotoFormat.WEBP
        in OTHER_IMAGE_EXTS -> return null
    }
    // No (recognised) extension: go by the MIME type.
    return when (mime?.lowercase()) {
        "image/jpeg", "image/jpg", "image/pjpeg" -> PhotoFormat.JPEG
        "image/png" -> PhotoFormat.PNG
        "image/webp" -> PhotoFormat.WEBP
        else -> null
    }
}

private val OTHER_IMAGE_EXTS = setOf(
    "heic", "heif", "avif", "gif", "bmp", "tif", "tiff", "dng", "raw", "cr2", "cr3", "nef", "arw", "orf", "rw2", "ico", "svg"
)
