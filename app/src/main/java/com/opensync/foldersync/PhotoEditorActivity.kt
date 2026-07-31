package com.opensync.foldersync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opensync.foldersync.ui.theme.OpenSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.graphics.ColorMatrix as GfxColorMatrix

/**
 * A self-contained photo editor: live (GPU) light/colour adjustments, filter presets, and rotate/flip,
 * with a full-resolution save. Reachable internally (edit_path extra) and as a system image editor
 * (ACTION_EDIT). Crop/straighten are planned follow-ups.
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
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val filter: Int = 0,
    val rotation: Int = 0, // number of 90° clockwise steps
    val flipH: Boolean = false,
    val flipV: Boolean = false
) {
    val modified: Boolean
        get() = brightness != 0f || contrast != 0f || saturation != 0f || warmth != 0f || tint != 0f ||
            filter != 0 || rotation != 0 || flipH || flipV
}

private enum class Tool(val label: String) { ADJUST("Light & colour"), FILTERS("Filters"), TRANSFORM("Rotate") }

private data class FilterPreset(val name: String, val build: () -> GfxColorMatrix?)

private val FILTERS = listOf(
    FilterPreset("Original") { null },
    FilterPreset("Vivid") { GfxColorMatrix().apply { setSaturation(1.5f) } },
    FilterPreset("Mono") { GfxColorMatrix().apply { setSaturation(0f) } },
    FilterPreset("Noir") {
        GfxColorMatrix().apply {
            setSaturation(0f)
            postConcat(contrastMatrix(1.35f))
        }
    },
    FilterPreset("Warm") { offsetMatrix(r = 25f, b = -25f) },
    FilterPreset("Cool") { offsetMatrix(r = -20f, b = 25f) },
    FilterPreset("Sepia") {
        GfxColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                GfxColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, 40f,
                        0f, 1f, 0f, 0f, 20f,
                        0f, 0f, 1f, 0f, -20f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
    },
    FilterPreset("Fade") {
        // Lifted blacks + reduced contrast for a soft film look.
        GfxColorMatrix().apply {
            postConcat(contrastMatrix(0.8f))
            postConcat(offsetMatrix(r = 12f, g = 12f, b = 18f))
        }
    }
)

private fun contrastMatrix(c: Float): GfxColorMatrix {
    val t = 128f * (1f - c)
    return GfxColorMatrix(
        floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

private fun offsetMatrix(r: Float = 0f, g: Float = 0f, b: Float = 0f): GfxColorMatrix =
    GfxColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, r,
            0f, 1f, 0f, 0f, g,
            0f, 0f, 1f, 0f, b,
            0f, 0f, 0f, 1f, 0f
        )
    )

/** Combine every adjustment + the chosen filter into one colour matrix (applied on the GPU). */
private fun colorMatrixFor(e: Edits): GfxColorMatrix {
    val cm = GfxColorMatrix()
    cm.postConcat(GfxColorMatrix().apply { setSaturation((1f + e.saturation).coerceAtLeast(0f)) })
    cm.postConcat(contrastMatrix(1f + e.contrast))
    cm.postConcat(offsetMatrix(r = e.brightness * 100f, g = e.brightness * 100f, b = e.brightness * 100f))
    cm.postConcat(offsetMatrix(r = e.warmth * 40f, g = e.tint * 25f, b = -e.warmth * 40f))
    FILTERS.getOrNull(e.filter)?.build?.invoke()?.let { cm.postConcat(it) }
    return cm
}

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
    var saving by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }

    // Preview: rotate/flip the (small) preview bitmap; colour is a live GPU filter.
    val display = remember(base, edits.rotation, edits.flipH, edits.flipV) {
        base?.let { applyTransform(it, edits.rotation, edits.flipH, edits.flipV) }
    }
    val colorFilter = remember(edits.brightness, edits.contrast, edits.saturation, edits.warmth, edits.tint, edits.filter) {
        ColorFilter.colorMatrix(ComposeColorMatrix(colorMatrixFor(edits).array))
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

    Column(Modifier.fillMaxSize().background(Color(0xFF101013))) {
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
            val d = display
            if (d == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Image(
                    bitmap = d.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = colorFilter,
                    modifier = Modifier.fillMaxSize().padding(8.dp)
                )
            }
            if (saving) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        // Tool panel
        Column(Modifier.fillMaxWidth().background(Color(0xFF17171B)).padding(bottom = 6.dp)) {
            when (tool) {
                Tool.ADJUST -> AdjustPanel(edits) { edits = it }
                Tool.FILTERS -> FilterPanel(thumb, edits.filter) { edits = edits.copy(filter = it) }
                Tool.TRANSFORM -> TransformPanel(
                    onRotateLeft = { edits = edits.copy(rotation = (edits.rotation + 3) % 4) },
                    onRotateRight = { edits = edits.copy(rotation = (edits.rotation + 1) % 4) },
                    onFlipH = { edits = edits.copy(flipH = !edits.flipH) },
                    onFlipV = { edits = edits.copy(flipV = !edits.flipV) }
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Tool.entries.forEach { t ->
                    Text(
                        t.label,
                        color = if (tool == t) MaterialTheme.colorScheme.primary else Color(0xFFBBBBBB),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { tool = t }.padding(10.dp)
                    )
                }
            }
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

@Composable
private fun AdjustPanel(edits: Edits, onChange: (Edits) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        AdjustSlider("Brightness", edits.brightness) { onChange(edits.copy(brightness = it)) }
        AdjustSlider("Contrast", edits.contrast) { onChange(edits.copy(contrast = it)) }
        AdjustSlider("Saturation", edits.saturation) { onChange(edits.copy(saturation = it)) }
        AdjustSlider("Warmth", edits.warmth) { onChange(edits.copy(warmth = it)) }
        AdjustSlider("Tint", edits.tint) { onChange(edits.copy(tint = it)) }
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, onValue: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xFFDDDDDD), fontSize = 13.sp, modifier = Modifier.width(90.dp))
            Slider(
                value = value,
                onValueChange = onValue,
                valueRange = -1f..1f,
                modifier = Modifier.weight(1f)
            )
            Text("${(value * 100).toInt()}", color = Color(0xFF999999), fontSize = 12.sp, modifier = Modifier.width(36.dp))
        }
    }
}

@Composable
private fun FilterPanel(thumb: Bitmap?, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(FILTERS.indices.toList()) { i ->
            val cf = remember(i) {
                val cm = GfxColorMatrix()
                FILTERS[i].build()?.let { cm.postConcat(it) }
                ColorFilter.colorMatrix(ComposeColorMatrix(cm.array))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A30))
                        .clickable { onSelect(i) }
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = FILTERS[i].name,
                            contentScale = ContentScale.Crop,
                            colorFilter = cf,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (selected == i) {
                        Box(
                            Modifier.fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x333B82F6))
                        )
                    }
                }
                Text(
                    FILTERS[i].name,
                    color = if (selected == i) MaterialTheme.colorScheme.primary else Color(0xFFBBBBBB),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).width(64.dp)
                )
            }
        }
    }
}

@Composable
private fun TransformPanel(
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TransformButton(Icons.Filled.Rotate90DegreesCcw, "Rotate left", onRotateLeft)
        TransformButton(Icons.Filled.Rotate90DegreesCw, "Rotate right", onRotateRight)
        TransformButton(Icons.Filled.Flip, "Flip horizontal", onFlipH)
        TransformButton(Icons.Filled.Flip, "Flip vertical", onFlipV, rotate = true)
    }
}

@Composable
private fun TransformButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, rotate: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(8.dp)) {
        Icon(icon, label, tint = Color.White, modifier = if (rotate) Modifier.size(28.dp).graphicsLayer(rotationZ = 90f) else Modifier.size(28.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color(0xFFBBBBBB), fontSize = 11.sp)
    }
}

// ---- Image processing ----

private fun applyTransform(src: Bitmap, rotation: Int, flipH: Boolean, flipV: Boolean): Bitmap {
    if (rotation == 0 && !flipH && !flipV) return src
    val m = Matrix()
    m.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
    m.postRotate(rotation * 90f)
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
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
    full = applyTransform(full, edits.rotation, edits.flipH, edits.flipV)
    val out = Bitmap.createBitmap(full.width, full.height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(
        full, 0f, 0f,
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            colorFilter = ColorMatrixColorFilter(colorMatrixFor(edits))
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

private fun scanFile(context: android.content.Context, f: File) {
    runCatching { MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null) }
}

private fun uniqueFile(dir: File, base: String, ext: String): File {
    var candidate = File(dir, "$base.$ext")
    var i = 1
    while (candidate.exists()) { candidate = File(dir, "$base ($i).$ext"); i++ }
    return candidate
}
