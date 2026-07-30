package com.opensync.foldersync.ui.common

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Everything needed to describe a file/media item; probing (dimensions, etc.) happens lazily. */
data class DetailsInfo(
    val name: String,
    val isDirectory: Boolean,
    val typeLabel: String,
    val location: String? = null,
    val size: Long? = null,
    val modified: Long? = null,
    val uri: Uri? = null,
    val localPath: String? = null,
    val isImage: Boolean = false,
    val isVideo: Boolean = false
)

private data class DetailsExtra(
    val size: Long? = null,
    val modified: Long? = null,
    val dimensions: String? = null,
    val duration: String? = null,
    val itemCount: Int? = null
)

@Composable
fun FileDetailsDialog(info: DetailsInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val extra by produceState<DetailsExtra?>(initialValue = null, info) {
        value = withContext(Dispatchers.IO) { loadExtra(context, info) }
    }

    val size = extra?.size ?: info.size
    val modified = extra?.modified ?: info.modified

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(info.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Type", info.typeLabel)
                if (!info.isDirectory && size != null) DetailRow("Size", formatBytes(size))
                extra?.itemCount?.let { DetailRow("Contains", "$it item(s)") }
                extra?.dimensions?.let { DetailRow("Dimensions", it) }
                extra?.duration?.let { DetailRow("Duration", it) }
                info.location?.let { DetailRow("Location", it) }
                if (modified != null && modified > 0) DetailRow("Modified", formatDate(modified))
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Friendly type label from a filename (or "Folder"). */
fun fileTypeLabel(name: String, isDirectory: Boolean): String {
    if (isDirectory) return "Folder"
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return "File"
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    return if (mime != null) "${ext.uppercase()} · $mime" else "${ext.uppercase()} file"
}

private fun loadExtra(context: Context, info: DetailsInfo): DetailsExtra {
    if (info.isDirectory) {
        val count = info.localPath?.let { runCatching { File(it).listFiles()?.size }.getOrNull() }
        return DetailsExtra(itemCount = count)
    }

    val file = info.localPath?.let { File(it) }?.takeIf { it.isFile }
    var size = info.size
    var modified = info.modified
    if (file != null) {
        size = file.length()
        modified = file.lastModified()
    } else if (info.uri != null) {
        runCatching {
            context.contentResolver.query(info.uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    c.getColumnIndex("date_modified").takeIf { it >= 0 && !c.isNull(it) }?.let {
                        modified = c.getLong(it) * 1000L // MediaStore stores seconds
                    }
                }
            }
        }
    }

    var dimensions: String? = null
    var duration: String? = null
    if (info.isImage) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(context, file, info.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) dimensions = "${opts.outWidth} × ${opts.outHeight}"
        }
    } else if (info.isVideo) {
        val retriever = MediaMetadataRetriever()
        runCatching {
            if (file != null) retriever.setDataSource(file.absolutePath)
            else if (info.uri != null) retriever.setDataSource(context, info.uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (!w.isNullOrEmpty() && !h.isNullOrEmpty()) dimensions = "$w × $h"
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.let { duration = formatDuration(it) }
        }
        runCatching { retriever.release() }
    }
    return DetailsExtra(size = size, modified = modified, dimensions = dimensions, duration = duration)
}

private fun openStream(context: Context, file: File?, uri: Uri?) =
    when {
        file != null -> file.inputStream()
        uri != null -> context.contentResolver.openInputStream(uri)
        else -> null
    }

private fun formatBytes(bytes: Long): String {
    val human = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
    return if (bytes >= 1_000) "$human (${"%,d".format(bytes)} bytes)" else human
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
