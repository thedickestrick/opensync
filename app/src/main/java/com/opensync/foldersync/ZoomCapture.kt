package com.opensync.foldersync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.opensync.foldersync.videoedit.saveFrameCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/*
 * Saving what's on screen in the gallery viewer, like Samsung Gallery: the zoomed-in part of a photo,
 * or the frame a video is showing (just the zoomed-in part of it, if zoomed). Both are cut from the
 * full-size picture rather than the screen, so they're as sharp as the original allows.
 */

/** Below this the viewer counts as not zoomed in. */
internal const val ZOOMED_IN = 1.01f

/**
 * The part of a picture that's on screen, as fractions of the picture (0..1 each way); null if none
 * of it is. The picture ([contentW] x [contentH], any units) is fitted and centred in a [boxW] x
 * [boxH] box, then scaled by [scale] about the box's centre and moved by [offsetX], [offsetY].
 */
internal fun visibleRegion(
    boxW: Float,
    boxH: Float,
    contentW: Float,
    contentH: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float
): NormRect? {
    if (boxW <= 0f || boxH <= 0f || contentW <= 0f || contentH <= 0f || scale <= 0f) return null
    // Where the fitted picture sits in the box before zooming.
    val fit = min(boxW / contentW, boxH / contentH)
    val fw = contentW * fit
    val fh = contentH * fit
    val left = (boxW - fw) / 2f
    val top = (boxH - fh) / 2f
    // The screen (the box) mapped back through the zoom: p = centre + (screen - centre - offset) / scale.
    val cx = boxW / 2f
    val cy = boxH / 2f
    val l = max(cx + (0f - cx - offsetX) / scale, left)
    val r = min(cx + (boxW - cx - offsetX) / scale, left + fw)
    val t = max(cy + (0f - cy - offsetY) / scale, top)
    val b = min(cy + (boxH - cy - offsetY) / scale, top + fh)
    if (r - l < 0.5f || b - t < 0.5f) return null
    return NormRect(
        ((l - left) / fw).coerceIn(0f, 1f),
        ((t - top) / fh).coerceIn(0f, 1f),
        ((r - left) / fw).coerceIn(0f, 1f),
        ((b - top) / fh).coerceIn(0f, 1f)
    )
}

/**
 * Saves [region] of the photo at [uri] as a new picture named "…_capture" beside it, at full size in
 * the photo's own format with its details (date taken, location, camera). A photo downloaded from
 * an account ([cloud]) goes back to that account.
 */
internal suspend fun savePhotoArea(
    context: Context,
    uri: Uri,
    cloud: CloudOrigin?,
    region: NormRect,
    onUploading: () -> Unit = {},
    onUploadProgress: ((UploadStatus) -> Unit)? = null
): SavedEdit = saveEditedPhoto(
    context, uri, PhotoEdits(crop = region), PHOTO_SIZES.first(), overwrite = false, cloud,
    onUploading, onUploadProgress, copySuffix = "capture"
)

/**
 * Saves the frame of the video at [uri] at [positionMs] — only [region] of it, when given — as a
 * JPEG named "…_capture" beside the video, or on its account ([cloud]).
 */
internal suspend fun saveVideoStill(
    context: Context,
    uri: Uri,
    cloud: CloudOrigin?,
    positionMs: Long,
    region: NormRect?,
    onUploading: () -> Unit = {},
    onUploadProgress: ((UploadStatus) -> Unit)? = null
): SavedEdit {
    val still = withContext(Dispatchers.Default) {
        val frame = videoFrameAt(context, uri, positionMs)
        val part = region?.let { cropBitmap(frame, it) } ?: frame
        if (part !== frame) frame.recycle()
        part
    }
    try {
        return saveFrameCapture(context, uri, cloud, still, onUploading, onUploadProgress)
    } finally {
        still.recycle()
    }
}

/** The video's frame at [positionMs], at full size and upright. */
private fun videoFrameAt(context: Context, uri: Uri, positionMs: Long): Bitmap {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        val frame = r.getFrameAtTime(positionMs.coerceAtLeast(0L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: r.getFrameAtTime(positionMs.coerceAtLeast(0L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: throw IllegalStateException("Couldn't read this frame")
        // Android turns frames upright itself; this only catches a device that hands one over sideways.
        val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        if ((rotation == 90 || rotation == 270) && w != h && frame.width == w && frame.height == h) {
            val turned = Bitmap.createBitmap(frame, 0, 0, w, h, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            if (turned !== frame) frame.recycle()
            return turned
        }
        return frame
    } finally {
        runCatching { r.release() }
    }
}
