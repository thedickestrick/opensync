package com.opensync.foldersync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import com.opensync.foldersync.videoedit.DecorationRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The photo editor's image work: decoding upright, rotate/flip/straighten, crop, the tone pass and
 * saving. Preview and save run the same code; the preview just starts from a smaller image.
 */

/** The largest image the save decodes (pixels); bigger ones are read at half or quarter size. */
private const val SAVE_MAX_PIXELS = 25_000_000L

/** What we know about the photo being edited before decoding it. */
internal data class PhotoSource(
    val name: String?, // file or display name
    val format: PhotoFormat?, // the format it's written back in; null = saved as a JPEG copy
    val inAppStorage: Boolean, // a private download (e.g. from the cloud): overwriting it would change nothing
    val cloud: CloudOrigin?, // the account file it was downloaded from; saving goes back there
    val saveWidth: Int, // upright size the save will work at
    val saveHeight: Int
) {
    val canOverwrite get() = format != null && (cloud != null || !inAppStorage)
}

internal fun readPhotoSource(context: Context, uri: Uri, cloud: CloudOrigin?): PhotoSource {
    val name = if (uri.scheme == "file") uri.path?.let { File(it).name } else runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    val mime = if (uri.scheme == "file") null else runCatching { context.contentResolver.getType(uri) }.getOrNull()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val sample = sampleFor(bounds.outWidth, bounds.outHeight, Int.MAX_VALUE, SAVE_MAX_PIXELS)
    var w = (bounds.outWidth + sample - 1) / sample
    var h = (bounds.outHeight + sample - 1) / sample
    if (readOrientation(context, uri).let { it in SIDEWAYS_ORIENTATIONS }) { val t = w; w = h; h = t }
    return PhotoSource(name, writableFormat(name, mime ?: bounds.outMimeType), isInAppStorage(context, uri), cloud, w, h)
}

/** A file in OpenSync's own storage (e.g. a download to edit): saving there would be lost to the user. */
internal fun isInAppStorage(context: Context, uri: Uri): Boolean {
    val path = uri.takeIf { it.scheme == "file" }?.path?.let { runCatching { File(it).canonicalPath }.getOrNull() } ?: return false
    return listOfNotNull(context.cacheDir, context.filesDir, context.externalCacheDir, context.getExternalFilesDir(null))
        .any { dir -> runCatching { dir.canonicalPath }.getOrNull()?.let { path.startsWith(it + File.separator) } == true }
}

private val SIDEWAYS_ORIENTATIONS = setOf(
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_ROTATE_270,
    ExifInterface.ORIENTATION_TRANSPOSE,
    ExifInterface.ORIENTATION_TRANSVERSE
)

private fun sampleFor(w: Int, h: Int, maxDim: Int, maxPixels: Long): Int {
    var sample = 1
    while (max(w, h) / sample > maxDim || (w.toLong() / sample) * (h.toLong() / sample) > maxPixels) sample *= 2
    return sample
}

private fun readOrientation(context: Context, uri: Uri): Int = runCatching {
    context.contentResolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

/** Decode a downsampled bitmap, turned upright according to its EXIF orientation. */
internal fun decodeUpright(context: Context, uri: Uri, maxDim: Int = Int.MAX_VALUE, maxPixels: Long = Long.MAX_VALUE): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxDim, maxPixels)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: throw IllegalStateException("Could not read image")
    return applyExif(bmp, readOrientation(context, uri))
}

private fun applyExif(bmp: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { m.postScale(-1f, 1f); m.postRotate(270f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { m.postScale(-1f, 1f); m.postRotate(90f) }
        else -> return bmp
    }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
}

/**
 * Flip, quarter-turn and straighten [src]. Straightening zooms in just enough that the turned picture
 * still fills the frame, so there are never empty corners.
 */
internal fun applyTransform(src: Bitmap, e: PhotoEdits): Bitmap {
    if (e.rotation == 0 && !e.flipH && !e.flipV && e.straighten == 0f) return src
    val sideways = e.rotation % 2 == 1
    val w = if (sideways) src.height else src.width
    val h = if (sideways) src.width else src.height
    if (e.straighten == 0f) {
        val m = Matrix()
        m.postScale(if (e.flipH) -1f else 1f, if (e.flipV) -1f else 1f)
        m.postRotate(e.rotation * 90f)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
    // A hair over the exact cover, so rounding never leaves an empty pixel in a corner.
    val k = straightenScale(w.toFloat(), h.toFloat(), e.straighten) * (1f + 2f / min(w, h))
    val m = Matrix()
    m.postTranslate(-src.width / 2f, -src.height / 2f)
    m.postScale(if (e.flipH) -k else k, if (e.flipV) -k else k)
    m.postRotate(e.rotation * 90f + e.straighten)
    m.postTranslate(w / 2f, h / 2f)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setHasAlpha(src.hasAlpha())
    Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    return out
}

internal fun cropBitmap(src: Bitmap, c: NormRect): Bitmap {
    if (c.isFull) return src
    val (x, y, w, h) = cropPixels(c, src.width, src.height)
    return Bitmap.createBitmap(src, x, y, w, h)
}

/**
 * The tone pass — sharpen/soften, exposure, highlights, shadows and vignette — plus, when given,
 * the 4x5 colour [matrix] (brightness, contrast, saturation, warmth, tint and the filter). Returns
 * [src] itself when there's nothing to do; otherwise a new bitmap. Bands of rows run on every core.
 */
internal suspend fun renderLook(src: Bitmap, tone: ToneEdits, matrix: FloatArray?): Bitmap {
    if (!tone.modified && matrix == null) return src
    val w = src.width
    val h = src.height
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setHasAlpha(src.hasAlpha())

    // A box blur whose size follows the image, so a save sharpens like its smaller preview did.
    val radius = if (tone.sharpness != 0f) max(1, (max(w, h) / 1500f).roundToInt()) else 0
    val sharpAmount = if (tone.sharpness > 0f) tone.sharpness * 1.5f else tone.sharpness * 0.9f
    val exposureGain = 2f.pow(tone.exposure * 2f / 2.2f)
    val shadeOn = tone.shadows != 0f || tone.highlights != 0f
    // Vignette: distance from the centre in a frame-shaped ellipse, 0 at the middle, 1 at the corners.
    val vignetteOn = tone.vignette != 0f
    val aspect = w.toFloat() / h
    val norm = 1f / sqrt(0.25f * aspect * aspect + 0.25f)
    val dx2 = FloatArray(if (vignetteOn) w else 0) { x -> ((x + 0.5f) / w - 0.5f).let { it * aspect * it * aspect } }
    val m = matrix

    val bandH = 32
    val bands = (h + bandH - 1) / bandH
    val next = AtomicInteger(0)
    val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    coroutineScope {
        (0 until workers).map {
            async(Dispatchers.Default) {
                val rowsMax = bandH + 2 * radius
                val input = IntArray(w * rowsMax)
                val result = IntArray(w * bandH)
                val blurSize = if (radius > 0) w * rowsMax else 0
                val sumR = IntArray(blurSize)
                val sumG = IntArray(blurSize)
                val sumB = IntArray(blurSize)
                val countX = IntArray(if (radius > 0) w else 0) { x -> min(w - 1, x + radius) - max(0, x - radius) + 1 }
                while (true) {
                    val band = next.getAndIncrement()
                    if (band >= bands) break
                    ensureActive()
                    val y0 = band * bandH
                    val y1 = min(h, y0 + bandH)
                    val ry0 = max(0, y0 - radius)
                    val ry1 = min(h, y1 + radius)
                    synchronized(src) { src.getPixels(input, 0, w, 0, ry0, w, ry1 - ry0) }

                    if (radius > 0) {
                        // Horizontal running sums of each row, per channel.
                        for (row in 0 until ry1 - ry0) {
                            val o = row * w
                            var r = 0; var g = 0; var b = 0
                            for (x in 0..min(w - 1, radius)) {
                                val p = input[o + x]
                                r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF
                            }
                            for (x in 0 until w) {
                                sumR[o + x] = r; sumG[o + x] = g; sumB[o + x] = b
                                val add = x + radius + 1
                                if (add < w) {
                                    val p = input[o + add]
                                    r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF
                                }
                                val drop = x - radius
                                if (drop >= 0) {
                                    val p = input[o + drop]
                                    r -= p shr 16 and 0xFF; g -= p shr 8 and 0xFF; b -= p and 0xFF
                                }
                            }
                        }
                    }

                    for (y in y0 until y1) {
                        val inRow = (y - ry0) * w
                        val outRow = (y - y0) * w
                        val wy0 = max(0, y - radius) - ry0
                        val wy1 = min(h - 1, y + radius) - ry0
                        val dy = (y + 0.5f) / h - 0.5f
                        val dy2 = dy * dy
                        for (x in 0 until w) {
                            val p = input[inRow + x]
                            val a = p ushr 24
                            var r = (p shr 16 and 0xFF) / 255f
                            var g = (p shr 8 and 0xFF) / 255f
                            var b = (p and 0xFF) / 255f

                            if (radius > 0) {
                                var sr = 0; var sg = 0; var sb = 0
                                for (yy in wy0..wy1) {
                                    val i = yy * w + x
                                    sr += sumR[i]; sg += sumG[i]; sb += sumB[i]
                                }
                                val n = 255f * countX[x] * (wy1 - wy0 + 1)
                                r += sharpAmount * (r - sr / n)
                                g += sharpAmount * (g - sg / n)
                                b += sharpAmount * (b - sb / n)
                            }
                            if (exposureGain != 1f) {
                                r *= exposureGain; g *= exposureGain; b *= exposureGain
                            }
                            r = r.coerceIn(0f, 1f); g = g.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
                            if (shadeOn) {
                                val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                                val add = tone.shadows * 0.35f * (1f - smoothstep(0f, 0.55f, l)) +
                                    tone.highlights * 0.35f * smoothstep(0.45f, 1f, l)
                                r = (r + add).coerceIn(0f, 1f); g = (g + add).coerceIn(0f, 1f); b = (b + add).coerceIn(0f, 1f)
                            }
                            if (vignetteOn) {
                                val v = smoothstep(0.3f, 1f, sqrt(dx2[x] + dy2) * norm)
                                if (tone.vignette > 0f) {
                                    val k = 1f - tone.vignette * 0.85f * v
                                    r *= k; g *= k; b *= k
                                } else {
                                    val k = -tone.vignette * 0.7f * v
                                    r += (1f - r) * k; g += (1f - g) * k; b += (1f - b) * k
                                }
                            }
                            var af = a / 255f
                            if (m != null) {
                                val nr = m[0] * r + m[1] * g + m[2] * b + m[3] * af + m[4] / 255f
                                val ng = m[5] * r + m[6] * g + m[7] * b + m[8] * af + m[9] / 255f
                                val nb = m[10] * r + m[11] * g + m[12] * b + m[13] * af + m[14] / 255f
                                af = (m[15] * r + m[16] * g + m[17] * b + m[18] * af + m[19] / 255f).coerceIn(0f, 1f)
                                r = nr; g = ng; b = nb
                            }
                            result[outRow + x] = ((af * 255f + 0.5f).toInt() shl 24) or
                                (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
                        }
                    }
                    synchronized(out) { out.setPixels(result, 0, w, 0, y0, w, y1 - y0) }
                }
            }
        }.awaitAll()
    }
    return out
}

private fun to255(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Recycles this bitmap if [next] replaced it. */
private fun Bitmap.then(next: Bitmap): Bitmap {
    if (next !== this) recycle()
    return next
}

/** The finished picture at full size: every edit baked in. Tries a smaller decode if memory runs out. */
private suspend fun renderFull(context: Context, uri: Uri, e: PhotoEdits, size: PhotoSize): Bitmap {
    var budget = SAVE_MAX_PIXELS
    while (true) {
        try {
            var bmp = decodeUpright(context, uri, maxPixels = budget)
            bmp = bmp.then(applyTransform(bmp, e))
            bmp = bmp.then(cropBitmap(bmp, e.crop))
            val (ow, oh) = photoOutputSize(bmp.width, bmp.height, size)
            if (ow != bmp.width || oh != bmp.height) bmp = bmp.then(Bitmap.createScaledBitmap(bmp, ow, oh, true))
            val matrix = if (e.color.modified) colorMatrixFor(e.color).array else null
            bmp = bmp.then(renderLook(bmp, e.tone, matrix))
            if (e.hasOverlay) {
                if (!bmp.isMutable) bmp = bmp.then(bmp.copy(Bitmap.Config.ARGB_8888, true))
                DecorationRenderer.draw(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), e.decorations, e.strokes)
            }
            return bmp
        } catch (oom: OutOfMemoryError) {
            if (budget <= SAVE_MAX_PIXELS / 16) throw oom
            budget /= 4
        }
    }
}

/**
 * Renders the edited photo and writes it over the original or beside it as a copy. It's encoded to a
 * temporary file first, so a failure never leaves the original half-written. The copy keeps the
 * original's format when we can write it (else JPEG), and JPEGs keep the original's EXIF details —
 * date taken, location, camera — so the edit sorts next to the original. A photo downloaded from
 * one of the user's accounts ([cloud]) is uploaded back there; [onUploading] says when that starts
 * and [onUploadProgress] how far it has got. A copy is named "[name]_[copySuffix]".
 */
internal suspend fun saveEditedPhoto(
    context: Context,
    uri: Uri,
    edits: PhotoEdits,
    size: PhotoSize,
    overwrite: Boolean,
    cloud: CloudOrigin?,
    onUploading: () -> Unit = {},
    onUploadProgress: ((UploadStatus) -> Unit)? = null,
    copySuffix: String = "edited"
): SavedEdit = withContext(Dispatchers.IO) {
    val source = readPhotoSource(context, uri, cloud)
    check(!overwrite || source.canOverwrite) { "This photo can only be saved as a copy" }
    val format = source.format ?: PhotoFormat.JPEG
    val tmp = File(File(context.cacheDir, "photo_edit").apply { mkdirs() }, "save_${System.nanoTime()}.${format.ext}")
    try {
        val bmp = renderFull(context, uri, edits, size)
        val (w, h) = bmp.width to bmp.height
        try {
            FileOutputStream(tmp).use { out ->
                check(bmp.compress(compressFormat(format), if (format == PhotoFormat.PNG) 100 else 95, out)) { "Couldn't encode the image" }
            }
        } finally {
            bmp.recycle()
        }
        if (format == PhotoFormat.JPEG) copyExif(context, uri, tmp, w, h)
        ensureActive()

        val srcFile = uri.takeIf { it.scheme == "file" }?.path?.let { File(it) }
        val baseName = (srcFile?.nameWithoutExtension ?: source.name?.substringBeforeLast('.'))
            ?.takeIf { it.isNotBlank() } ?: "photo"
        when {
            cloud != null ->
                deliverToCloud(
                    context, cloud, tmp, overwrite, "${baseName}_$copySuffix", format.ext, Environment.DIRECTORY_PICTURES,
                    onUploading, onUploadProgress
                )
            overwrite && srcFile != null -> {
                tmp.inputStream().use { input -> FileOutputStream(srcFile).use { input.copyTo(it) } }
                scanFile(context, srcFile)
                SavedEdit(file = srcFile, overwrote = true)
            }
            overwrite -> { // content:// original — overwrite in place; its provider updates it.
                val out = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                    ?: context.contentResolver.openOutputStream(uri, "w")
                    ?: throw IllegalStateException("Couldn't write to the original")
                out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                SavedEdit(overwrote = true)
            }
            else -> {
                val dir = srcFile?.parentFile?.takeIf { !source.inAppStorage && it.canWrite() }
                    ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "OpenSync").apply { mkdirs() }
                val f = uniqueFile(dir, "${baseName}_$copySuffix", format.ext)
                tmp.inputStream().use { input -> FileOutputStream(f).use { input.copyTo(it) } }
                scanFile(context, f)
                SavedEdit(file = f)
            }
        }
    } finally {
        tmp.delete()
    }
}

@Suppress("DEPRECATION")
private fun compressFormat(f: PhotoFormat): Bitmap.CompressFormat = when (f) {
    PhotoFormat.JPEG -> Bitmap.CompressFormat.JPEG
    PhotoFormat.PNG -> Bitmap.CompressFormat.PNG
    PhotoFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
}

/** EXIF details worth keeping on an edited photo. Orientation is reset (the pixels are upright now). */
private val KEPT_EXIF_TAGS = arrayOf(
    "DateTime", "DateTimeOriginal", "DateTimeDigitized", "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
    "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized",
    "Make", "Model", "LensMake", "LensModel", "Artist", "Copyright", "ImageDescription", "UserComment",
    "ExposureTime", "FNumber", "ExposureProgram", "PhotographicSensitivity", "ISOSpeedRatings", "ShutterSpeedValue",
    "ApertureValue", "BrightnessValue", "ExposureBiasValue", "MaxApertureValue", "MeteringMode", "LightSource", "Flash",
    "FocalLength", "FocalLengthIn35mmFilm", "WhiteBalance", "ExposureMode", "DigitalZoomRatio", "SceneCaptureType",
    "GPSVersionID", "GPSLatitudeRef", "GPSLatitude", "GPSLongitudeRef", "GPSLongitude", "GPSAltitudeRef", "GPSAltitude",
    "GPSTimeStamp", "GPSDateStamp", "GPSProcessingMethod", "GPSImgDirectionRef", "GPSImgDirection", "GPSSpeedRef", "GPSSpeed"
)

private fun copyExif(context: Context, uri: Uri, target: File, width: Int, height: Int) {
    runCatching {
        val src = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return
        val dst = ExifInterface(target.absolutePath)
        var any = false
        for (tag in KEPT_EXIF_TAGS) {
            src.getAttribute(tag)?.let { dst.setAttribute(tag, it); any = true }
        }
        if (!any) return
        dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        dst.setAttribute("PixelXDimension", width.toString())
        dst.setAttribute("PixelYDimension", height.toString())
        dst.saveAttributes()
    }
}
