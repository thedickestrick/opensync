package com.opensync.foldersync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.ui.theme.OpenSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * A self-contained photo editor: live (GPU) light/colour adjustments, filter presets, and rotate/flip,
 * with a full-resolution save. Reachable internally (edit_path extra) and as a system image editor
 * (ACTION_EDIT). The panels and colour model are shared with [VideoEditorActivity] (MediaEditorCommon.kt).
 */
class PhotoEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent?.data
            ?: intent?.getStringExtra("edit_path")?.let { Uri.fromFile(File(it)) }
        if (uri == null) { finish(); return }
        setContent {
            OpenSyncTheme {
                PhotoEditorScreen(uri = uri, onClose = { finish() })
            }
        }
    }
}

private data class Edits(
    val color: ColorEdits = ColorEdits(),
    val rotation: Int = 0, // number of 90° clockwise steps
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val straighten: Float = 0f, // fine rotation in degrees (-45..45)
    val crop: NormRect = NormRect()
) {
    val modified: Boolean
        get() = color.modified || rotation != 0 || flipH || flipV || straighten != 0f || !crop.isFull
}

private enum class Tool(val label: String) { ADJUST("Light & colour"), FILTERS("Filters"), CROP("Crop") }

@Composable
private fun PhotoEditorScreen(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val base by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { decodeUpright(context, uri, 2048) }.getOrNull() }
    }
    val thumb by produceState<Bitmap?>(initialValue = null, base) {
        value = base?.let { withContext(Dispatchers.Default) { Bitmap.createScaledBitmap(it, 140, (140f * it.height / it.width).toInt().coerceAtLeast(1), true) } }
    }

    var edits by remember { mutableStateOf(Edits()) }
    var tool by remember { mutableStateOf(Tool.ADJUST) }
    var aspect by remember { mutableStateOf<Float?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }

    // Preview: rotate/flip/straighten the (small) preview bitmap; colour is a live GPU filter.
    val transformed = remember(base, edits.rotation, edits.flipH, edits.flipV, edits.straighten) {
        base?.let { applyTransform(it, edits.rotation, edits.flipH, edits.flipV, edits.straighten) }
    }
    val cropped = remember(transformed, edits.crop) {
        transformed?.let { cropBitmap(it, edits.crop) }
    }
    val colorFilter = remember(edits.color) {
        ColorFilter.colorMatrix(ComposeColorMatrix(colorMatrixFor(edits.color).array))
    }

    fun save(overwrite: Boolean) {
        showSave = false
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { renderAndSave(context, uri, edits, overwrite) }
            }
            saving = false
            result.onSuccess {
                Toast.makeText(context, if (overwrite) "Saved" else "Saved a copy", Toast.LENGTH_SHORT).show()
                onClose()
            }.onFailure {
                Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show()
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
            IconButton(onClick = { edits = Edits() }, enabled = edits.modified) {
                Icon(Icons.Filled.Refresh, "Reset", tint = if (edits.modified) Color.White else Color.Gray)
            }
            TextButton(onClick = { showSave = true }, enabled = edits.modified && !saving) {
                Icon(Icons.Filled.Save, null, tint = if (edits.modified) Color.White else Color.Gray)
                Spacer(Modifier.width(6.dp))
                Text("Save", color = if (edits.modified) Color.White else Color.Gray)
            }
        }

        // Preview
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val cropping = tool == Tool.CROP
            val d = if (cropping) transformed else cropped
            if (d == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = d.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = colorFilter,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (cropping) {
                        CropOverlay(
                            imageAspect = d.width.toFloat() / d.height.toFloat(),
                            crop = edits.crop,
                            aspect = aspect,
                            onCrop = { edits = edits.copy(crop = it) }
                        )
                    }
                }
            }
            if (saving) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        // Tool panel
        Column(Modifier.fillMaxWidth().background(EDITOR_PANEL_BG).padding(bottom = 6.dp)) {
            when (tool) {
                Tool.ADJUST -> AdjustPanel(edits.color) { edits = edits.copy(color = it) }
                Tool.FILTERS -> FilterPanel(thumb, edits.color.filter) { edits = edits.copy(color = edits.color.copy(filter = it)) }
                Tool.CROP -> CropPanel(
                    aspect = aspect,
                    straighten = edits.straighten,
                    onAspect = { r ->
                        aspect = r
                        val ia = transformed?.let { it.width.toFloat() / it.height.toFloat() } ?: 1f
                        edits = edits.copy(crop = centeredCrop(ia, r))
                    },
                    onStraighten = { edits = edits.copy(straighten = it) },
                    onRotateLeft = { edits = edits.copy(rotation = (edits.rotation + 3) % 4) },
                    onRotateRight = { edits = edits.copy(rotation = (edits.rotation + 1) % 4) },
                    onFlipH = { edits = edits.copy(flipH = !edits.flipH) },
                    onFlipV = { edits = edits.copy(flipV = !edits.flipV) }
                )
            }
            ToolTabs(Tool.entries, tool, { it.label }) { tool = it }
        }
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save edited photo") },
            text = { Text("Overwrite the original, or keep it and save a copy?") },
            confirmButton = { TextButton(onClick = { save(false) }) { Text("Save copy") } },
            dismissButton = { TextButton(onClick = { save(true) }) { Text("Overwrite") } }
        )
    }
}

// ---- Image processing ----

private fun applyTransform(src: Bitmap, rotation: Int, flipH: Boolean, flipV: Boolean, straighten: Float): Bitmap {
    if (rotation == 0 && !flipH && !flipV && straighten == 0f) return src
    val m = Matrix()
    m.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
    m.postRotate(rotation * 90f + straighten)
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

private fun cropBitmap(src: Bitmap, c: NormRect): Bitmap {
    if (c.isFull) return src
    val x = (c.l * src.width).toInt().coerceIn(0, src.width - 1)
    val y = (c.t * src.height).toInt().coerceIn(0, src.height - 1)
    val w = (c.width * src.width).toInt().coerceIn(1, src.width - x)
    val h = (c.height * src.height).toInt().coerceIn(1, src.height - y)
    return Bitmap.createBitmap(src, x, y, w, h)
}

/** Decode a downsampled bitmap and correct for EXIF orientation so the preview is upright. */
private fun decodeUpright(context: android.content.Context, uri: Uri, maxDim: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    val bigger = maxOf(bounds.outWidth, bounds.outHeight)
    while (bigger / sample > maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: throw IllegalStateException("Could not read image")
    val orientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
        ) } ?: android.media.ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
    return applyExif(bmp, orientation)
}

private fun applyExif(bmp: Bitmap, orientation: Int): Bitmap {
    val m = Matrix()
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        else -> return bmp
    }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

/** Load the full-resolution image, bake in every edit, and write it out. */
private fun renderAndSave(context: android.content.Context, uri: Uri, edits: Edits, overwrite: Boolean) {
    var full = decodeUpright(context, uri, 4096)
    full = applyTransform(full, edits.rotation, edits.flipH, edits.flipV, edits.straighten)
    full = cropBitmap(full, edits.crop)
    val out = Bitmap.createBitmap(full.width, full.height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(
        full, 0f, 0f,
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            colorFilter = ColorMatrixColorFilter(colorMatrixFor(edits.color))
        }
    )

    when {
        overwrite && uri.scheme == "file" -> {
            val f = File(uri.path!!)
            FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            scanFile(context, f)
        }
        overwrite -> // content:// original — overwrite in place, no rescan needed.
            context.contentResolver.openOutputStream(uri, "wt")!!.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        else -> {
            val srcFile = uri.takeIf { it.scheme == "file" }?.path?.let { File(it) }
            val dir = srcFile?.parentFile
                ?: File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "OpenSync").apply { mkdirs() }
            val baseName = srcFile?.nameWithoutExtension ?: "photo"
            val f = uniqueFile(dir, "${baseName}_edited", "jpg")
            FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            scanFile(context, f)
        }
    }
}
