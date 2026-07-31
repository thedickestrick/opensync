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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
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

private data class NormRect(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f) {
    val width get() = r - l
    val height get() = b - t
    val isFull get() = l <= 0.0005f && t <= 0.0005f && r >= 0.9995f && b >= 0.9995f
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
    val flipV: Boolean = false,
    val straighten: Float = 0f, // fine rotation in degrees (-45..45)
    val crop: NormRect = NormRect()
) {
    val modified: Boolean
        get() = brightness != 0f || contrast != 0f || saturation != 0f || warmth != 0f || tint != 0f ||
            filter != 0 || rotation != 0 || flipH || flipV || straighten != 0f || !crop.isFull
}

private enum class Tool(val label: String) { ADJUST("Light & colour"), FILTERS("Filters"), CROP("Crop") }

private data class AspectOption(val label: String, val ratio: Float?)

private val ASPECTS = listOf(
    AspectOption("Free", null),
    AspectOption("1:1", 1f),
    AspectOption("4:3", 4f / 3f),
    AspectOption("3:4", 3f / 4f),
    AspectOption("16:9", 16f / 9f),
    AspectOption("9:16", 9f / 16f)
)

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

    // background before systemBarsPadding: dark fills behind the bars, content is inset off them.
    Column(Modifier.fillMaxSize().background(Color(0xFF101013)).systemBarsPadding()) {
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
        Column(Modifier.fillMaxWidth().background(Color(0xFF17171B)).padding(bottom = 6.dp)) {
            when (tool) {
                Tool.ADJUST -> AdjustPanel(edits) { edits = it }
                Tool.FILTERS -> FilterPanel(thumb, edits.filter) { edits = edits.copy(filter = it) }
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
private fun CropPanel(
    aspect: Float?,
    straighten: Float,
    onAspect: (Float?) -> Unit,
    onStraighten: (Float) -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        // Straighten
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Straighten", color = Color(0xFFDDDDDD), fontSize = 13.sp, modifier = Modifier.width(90.dp))
            Slider(value = straighten, onValueChange = onStraighten, valueRange = -45f..45f, modifier = Modifier.weight(1f))
            Text("${straighten.toInt()}°", color = Color(0xFF999999), fontSize = 12.sp, modifier = Modifier.width(36.dp))
        }
        // Aspect ratios
        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ASPECTS) { a ->
                val sel = a.ratio == aspect
                Text(
                    a.label,
                    color = if (sel) MaterialTheme.colorScheme.primary else Color(0xFFCCCCCC),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) Color(0x333B82F6) else Color(0xFF26262C))
                        .clickable { onAspect(a.ratio) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
        // Rotate / flip
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TransformButton(Icons.Filled.Rotate90DegreesCcw, "Rotate left", onRotateLeft)
            TransformButton(Icons.Filled.Rotate90DegreesCw, "Rotate right", onRotateRight)
            TransformButton(Icons.Filled.Flip, "Flip H", onFlipH)
            TransformButton(Icons.Filled.Flip, "Flip V", onFlipV, rotate = true)
        }
    }
}

@Composable
private fun CropOverlay(imageAspect: Float, crop: NormRect, aspect: Float?, onCrop: (NormRect) -> Unit) {
    val cropState = rememberUpdatedState(crop)

    Canvas(
        Modifier.fillMaxSize().pointerInput(imageAspect) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val img = fittedRect(size.width.toFloat(), size.height.toFloat(), imageAspect)
                val c0 = cropState.value
                val cLeft = img.left + c0.l * img.width
                val cTop = img.top + c0.t * img.height
                val cRight = img.left + c0.r * img.width
                val cBottom = img.top + c0.b * img.height
                val hr = 56f
                val p = down.position
                val nearL = abs(p.x - cLeft) < hr
                val nearR = abs(p.x - cRight) < hr
                val nearT = abs(p.y - cTop) < hr
                val nearB = abs(p.y - cBottom) < hr
                val onHandle = nearL || nearR || nearT || nearB
                val inside = p.x in cLeft..cRight && p.y in cTop..cBottom
                if (!onHandle && !inside) return@awaitEachGesture
                down.consume()
                var cur = c0
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    val dx = (ch.position.x - ch.previousPosition.x) / img.width
                    val dy = (ch.position.y - ch.previousPosition.y) / img.height
                    cur = if (!onHandle) {
                        val nl = (cur.l + dx).coerceIn(0f, 1f - cur.width)
                        val nt = (cur.t + dy).coerceIn(0f, 1f - cur.height)
                        NormRect(nl, nt, nl + cur.width, nt + cur.height)
                    } else {
                        var l = cur.l; var t = cur.t; var r = cur.r; var b = cur.b
                        if (nearL) l = (l + dx).coerceIn(0f, r - 0.05f)
                        if (nearR) r = (r + dx).coerceIn(l + 0.05f, 1f)
                        if (nearT) t = (t + dy).coerceIn(0f, b - 0.05f)
                        if (nearB) b = (b + dy).coerceIn(t + 0.05f, 1f)
                        NormRect(l, t, r, b)
                    }
                    onCrop(cur)
                    ch.consume()
                    if (ev.changes.none { it.pressed }) break
                }
            }
        }
    ) {
        val img = fittedRect(size.width, size.height, imageAspect)
        val c = cropState.value
        val left = img.left + c.l * img.width
        val top = img.top + c.t * img.height
        val right = img.left + c.r * img.width
        val bottom = img.top + c.b * img.height
        val scrim = Color(0x99000000)
        drawRect(scrim, Offset(img.left, img.top), Size(img.width, top - img.top))
        drawRect(scrim, Offset(img.left, bottom), Size(img.width, img.bottom - bottom))
        drawRect(scrim, Offset(img.left, top), Size(left - img.left, bottom - top))
        drawRect(scrim, Offset(right, top), Size(img.right - right, bottom - top))
        drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(width = 2f))
        for (k in 1..2) {
            val gx = left + (right - left) * k / 3f
            val gy = top + (bottom - top) * k / 3f
            drawLine(Color(0x66FFFFFF), Offset(gx, top), Offset(gx, bottom), 1f)
            drawLine(Color(0x66FFFFFF), Offset(left, gy), Offset(right, gy), 1f)
        }
    }
}

private fun fittedRect(boxW: Float, boxH: Float, imageAspect: Float): Rect {
    val boxAspect = boxW / boxH
    return if (boxAspect > imageAspect) {
        val w = boxH * imageAspect
        Rect((boxW - w) / 2f, 0f, (boxW - w) / 2f + w, boxH)
    } else {
        val h = boxW / imageAspect
        Rect(0f, (boxH - h) / 2f, boxW, (boxH - h) / 2f + h)
    }
}

private fun centeredCrop(imageAspect: Float, ratio: Float?): NormRect {
    if (ratio == null) return NormRect()
    val normWH = ratio / imageAspect
    val w: Float; val h: Float
    if (normWH >= 1f) { w = 1f; h = 1f / normWH } else { h = 1f; w = normWH }
    val l = (1f - w) / 2f; val t = (1f - h) / 2f
    return NormRect(l, t, l + w, t + h)
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
