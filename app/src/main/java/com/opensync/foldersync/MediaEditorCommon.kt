package com.opensync.foldersync

import android.graphics.Bitmap
import android.media.MediaScannerConnection
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.abs
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix
import android.graphics.ColorMatrix as GfxColorMatrix

/*
 * Pieces shared by the photo and video editors: the light/colour model and filter presets, the
 * crop rectangle, and the tool panels built on them — so both editors look and behave the same.
 */

internal val EDITOR_BG = Color(0xFF101013)
internal val EDITOR_PANEL_BG = Color(0xFF17171B)

internal data class NormRect(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f) {
    val width get() = r - l
    val height get() = b - t
    val isFull get() = l <= 0.0005f && t <= 0.0005f && r >= 0.9995f && b >= 0.9995f
}

/** Light & colour adjustments (each -1..1) plus the chosen [FILTERS] preset. */
internal data class ColorEdits(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val filter: Int = 0
) {
    val modified: Boolean
        get() = brightness != 0f || contrast != 0f || saturation != 0f || warmth != 0f || tint != 0f || filter != 0
}

internal data class AspectOption(val label: String, val ratio: Float?)

internal val ASPECTS = listOf(
    AspectOption("Free", null),
    AspectOption("1:1", 1f),
    AspectOption("4:3", 4f / 3f),
    AspectOption("3:4", 3f / 4f),
    AspectOption("16:9", 16f / 9f),
    AspectOption("9:16", 9f / 16f)
)

internal data class FilterPreset(val name: String, val build: () -> GfxColorMatrix?)

internal val FILTERS = listOf(
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
internal fun colorMatrixFor(e: ColorEdits): GfxColorMatrix {
    val cm = GfxColorMatrix()
    cm.postConcat(GfxColorMatrix().apply { setSaturation((1f + e.saturation).coerceAtLeast(0f)) })
    cm.postConcat(contrastMatrix(1f + e.contrast))
    cm.postConcat(offsetMatrix(r = e.brightness * 100f, g = e.brightness * 100f, b = e.brightness * 100f))
    cm.postConcat(offsetMatrix(r = e.warmth * 40f, g = e.tint * 25f, b = -e.warmth * 40f))
    FILTERS.getOrNull(e.filter)?.build?.invoke()?.let { cm.postConcat(it) }
    return cm
}

@Composable
internal fun AdjustPanel(edits: ColorEdits, onChange: (ColorEdits) -> Unit) {
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
internal fun FilterPanel(thumb: Bitmap?, selected: Int, onSelect: (Int) -> Unit) {
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
internal fun CropPanel(
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
internal fun CropOverlay(imageAspect: Float, crop: NormRect, aspect: Float?, onCrop: (NormRect) -> Unit) {
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

internal fun centeredCrop(imageAspect: Float, ratio: Float?): NormRect {
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

/** The editor's bottom tab row (Light & colour / Filters / Crop / …). */
@Composable
internal fun <T> ToolTabs(tools: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tools.forEach { t ->
            Text(
                label(t),
                color = if (selected == t) MaterialTheme.colorScheme.primary else Color(0xFFBBBBBB),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(t) }.padding(10.dp)
            )
        }
    }
}

internal fun scanFile(context: android.content.Context, f: File) {
    runCatching { MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null) }
}

internal fun uniqueFile(dir: File, base: String, ext: String): File {
    var candidate = File(dir, "$base.$ext")
    var i = 1
    while (candidate.exists()) { candidate = File(dir, "$base ($i).$ext"); i++ }
    return candidate
}
