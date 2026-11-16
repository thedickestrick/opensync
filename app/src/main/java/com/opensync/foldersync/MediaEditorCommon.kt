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
import androidx.compose.material.icons.filled.AutoFixHigh
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
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
    val filter: Int = 0,
    val filterStrength: Float = 1f // 0..1: how much of the filter preset to apply
) {
    val modified: Boolean
        get() = brightness != 0f || contrast != 0f || saturation != 0f || warmth != 0f || tint != 0f ||
            (filter != 0 && filterStrength > 0f)
}

/** Tone controls on top of [ColorEdits] (each -1..1). */
internal data class ToneEdits(
    val exposure: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val sharpness: Float = 0f, // negative softens
    val vignette: Float = 0f // positive darkens the corners, negative brightens them
) {
    val modified get() = exposure != 0f || highlights != 0f || shadows != 0f || sharpness != 0f || vignette != 0f
}

/**
 * Keeps edit history for undo/redo. Successive changes with the same key close together in time
 * (one slider drag, one stroke) collapse into a single step; changes without a key never do.
 */
internal class EditHistory<T>(private val limit: Int = 60) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    private var lastChangeAt = 0L
    private var lastKey: Any? = null

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    /** Call with the state *before* a change; [key] identifies the control or gesture making it. */
    fun record(before: T, key: Any? = null, now: Long = System.currentTimeMillis()) {
        val continues = key != null && key == lastKey && now - lastChangeAt <= COALESCE_MS && past.isNotEmpty()
        if (!continues) {
            past.addLast(before)
            if (past.size > limit) past.removeFirst()
        }
        future.clear()
        lastChangeAt = now
        lastKey = key
    }

    fun undo(current: T): T? {
        val prev = past.removeLastOrNull() ?: return null
        future.addLast(current)
        lastKey = null
        return prev
    }

    fun redo(current: T): T? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        lastKey = null
        return next
    }

    private companion object {
        const val COALESCE_MS = 700L
    }
}

/**
 * One-tap "Auto" adjustment from a sample image: pulls the average brightness toward the middle,
 * stretches flat contrast a little and adds a touch of colour. Returns [color] and [tone] with the
 * values Auto sets replaced and everything else kept.
 */
internal fun autoTone(frame: Bitmap, color: ColorEdits, tone: ToneEdits): Pair<ColorEdits, ToneEdits> {
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
    return color.copy(contrast = contrast, saturation = saturation) to
        tone.copy(exposure = exposure, shadows = shadows, highlights = highlights)
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
    },
    FilterPreset("Bright") {
        GfxColorMatrix().apply {
            setSaturation(1.1f)
            postConcat(offsetMatrix(r = 22f, g = 22f, b = 22f))
        }
    },
    FilterPreset("Dramatic") {
        GfxColorMatrix().apply {
            setSaturation(0.8f)
            postConcat(contrastMatrix(1.4f))
            postConcat(offsetMatrix(r = -8f, g = -8f, b = -4f))
        }
    },
    FilterPreset("Vintage") {
        GfxColorMatrix().apply {
            setSaturation(0.65f)
            postConcat(contrastMatrix(0.9f))
            postConcat(offsetMatrix(r = 28f, g = 14f, b = -16f))
        }
    },
    FilterPreset("Film") {
        // Teal shadows, warm highlights.
        GfxColorMatrix(
            floatArrayOf(
                1.05f, 0.05f, -0.05f, 0f, -4f,
                0.02f, 1.0f, 0.02f, 0f, 2f,
                -0.05f, 0.08f, 0.95f, 0f, 10f,
                0f, 0f, 0f, 1f, 0f
            )
        ).apply { postConcat(contrastMatrix(1.1f)) }
    },
    FilterPreset("Summer") {
        GfxColorMatrix().apply {
            setSaturation(1.3f)
            postConcat(offsetMatrix(r = 18f, g = 8f, b = -10f))
        }
    },
    FilterPreset("Autumn") {
        GfxColorMatrix().apply {
            setSaturation(0.9f)
            postConcat(offsetMatrix(r = 30f, g = 6f, b = -30f))
            postConcat(contrastMatrix(1.08f))
        }
    },
    FilterPreset("Ocean") {
        GfxColorMatrix().apply {
            setSaturation(1.1f)
            postConcat(offsetMatrix(r = -24f, g = 6f, b = 30f))
        }
    },
    FilterPreset("Chrome") {
        GfxColorMatrix().apply {
            setSaturation(1.2f)
            postConcat(contrastMatrix(1.25f))
        }
    },
    FilterPreset("Silver") {
        // Bright, low-contrast black & white.
        GfxColorMatrix().apply {
            setSaturation(0f)
            postConcat(contrastMatrix(0.85f))
            postConcat(offsetMatrix(r = 20f, g = 20f, b = 24f))
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
    FILTERS.getOrNull(e.filter)?.build?.invoke()?.let { cm.postConcat(scaledFilter(it, e.filterStrength)) }
    return cm
}

/** Blend a filter preset with "no filter": 0 = identity, 1 = the full preset. */
private fun scaledFilter(f: GfxColorMatrix, strength: Float): GfxColorMatrix {
    val k = strength.coerceIn(0f, 1f)
    if (k >= 1f) return f
    val identity = GfxColorMatrix().array
    val a = f.array
    return GfxColorMatrix(FloatArray(20) { i -> identity[i] + k * (a[i] - identity[i]) })
}

private class ToneControl(
    val label: String,
    val get: (ColorEdits, ToneEdits) -> Float,
    val set: (ColorEdits, ToneEdits, Float) -> Pair<ColorEdits, ToneEdits>
)

private val TONE_CONTROLS = listOf(
    ToneControl("Brightness", { c, _ -> c.brightness }) { c, t, v -> c.copy(brightness = v) to t },
    ToneControl("Exposure", { _, t -> t.exposure }) { c, t, v -> c to t.copy(exposure = v) },
    ToneControl("Contrast", { c, _ -> c.contrast }) { c, t, v -> c.copy(contrast = v) to t },
    ToneControl("Highlights", { _, t -> t.highlights }) { c, t, v -> c to t.copy(highlights = v) },
    ToneControl("Shadows", { _, t -> t.shadows }) { c, t, v -> c to t.copy(shadows = v) },
    ToneControl("Saturation", { c, _ -> c.saturation }) { c, t, v -> c.copy(saturation = v) to t },
    ToneControl("Warmth", { c, _ -> c.warmth }) { c, t, v -> c.copy(warmth = v) to t },
    ToneControl("Tint", { c, _ -> c.tint }) { c, t, v -> c.copy(tint = v) to t },
    ToneControl("Sharpness", { _, t -> t.sharpness }) { c, t, v -> c to t.copy(sharpness = v) },
    ToneControl("Vignette", { _, t -> t.vignette }) { c, t, v -> c to t.copy(vignette = v) }
)

/** Samsung-style tone tools: pick an adjustment, then one slider for it. Tap the number to reset it. */
@Composable
internal fun TonePanel(
    color: ColorEdits,
    tone: ToneEdits,
    selected: Int,
    onSelect: (Int) -> Unit,
    onChange: (ColorEdits, ToneEdits) -> Unit,
    onAuto: () -> Unit
) {
    val control = TONE_CONTROLS[selected.coerceIn(0, TONE_CONTROLS.lastIndex)]
    val value = control.get(color, tone)
    fun set(v: Float) {
        val (c, t) = control.set(color, tone, v)
        onChange(c, t)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(control.label, color = Color(0xFFDDDDDD), fontSize = 13.sp, modifier = Modifier.width(84.dp))
            Slider(value = value, onValueChange = { set(it) }, valueRange = -1f..1f, modifier = Modifier.weight(1f))
            Text(
                "${(value * 100).roundToInt()}",
                color = Color(0xFF999999),
                fontSize = 12.sp,
                modifier = Modifier.width(36.dp).clickable { set(0f) }
            )
        }
        LazyRow(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                ToneChip("Auto", null, selected = false, icon = Icons.Filled.AutoFixHigh, onClick = onAuto)
            }
            items(TONE_CONTROLS.size) { i ->
                val c = TONE_CONTROLS[i]
                ToneChip(c.label, (c.get(color, tone) * 100).roundToInt(), selected = i == selected) { onSelect(i) }
            }
        }
    }
}

@Composable
private fun ToneChip(label: String, value: Int?, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0x333B82F6) else Color(0xFF26262C))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val tint = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFCCCCCC)
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        else Text(if (value == null || value == 0) "0" else "%+d".format(value), color = if (value != 0) tint else Color(0xFF888888), fontSize = 11.sp)
        Text(label, color = tint, fontSize = 12.sp)
    }
}

/** Filter thumbnails, plus an intensity slider once a filter is picked. */
@Composable
internal fun FilterPanel(
    thumb: Bitmap?,
    selected: Int,
    strength: Float,
    onSelect: (Int) -> Unit,
    onStrength: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        FilterThumbnails(thumb, selected, onSelect)
        if (selected != 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Intensity", color = Color(0xFFDDDDDD), fontSize = 13.sp, modifier = Modifier.width(90.dp))
                Slider(value = strength, onValueChange = onStrength, valueRange = 0f..1f, modifier = Modifier.weight(1f))
                Text("${(strength * 100).toInt()}", color = Color(0xFF999999), fontSize = 12.sp, modifier = Modifier.width(36.dp))
            }
        }
    }
}

@Composable
private fun FilterThumbnails(thumb: Bitmap?, selected: Int, onSelect: (Int) -> Unit) {
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

/**
 * The crop box over the image: drag inside to move it, drag an edge or corner to resize it. With
 * an [aspect] ratio picked, resizing keeps that ratio.
 */
@Composable
internal fun CropOverlay(imageAspect: Float, crop: NormRect, aspect: Float?, onCrop: (NormRect) -> Unit) {
    val cropState = rememberUpdatedState(crop)
    val aspectState = rememberUpdatedState(aspect)

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
                val p = down.position
                // Edges are grabbable a finger's width outside the box, but at most a third of the
                // way in, so even a small box keeps a middle to move it by.
                val reach = 24.dp.toPx()
                if (p.x < cLeft - reach || p.x > cRight + reach || p.y < cTop - reach || p.y > cBottom + reach) return@awaitEachGesture
                val inX = min(reach, (cRight - cLeft) / 3f)
                val inY = min(reach, (cBottom - cTop) / 3f)
                val nearL = p.x < cLeft + inX
                val nearR = p.x > cRight - inX
                val nearT = p.y < cTop + inY
                val nearB = p.y > cBottom - inY
                val onHandle = nearL || nearR || nearT || nearB
                down.consume()
                val ratio = aspectState.value?.let { it / imageAspect } // as a width/height of the 0..1 rect
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    // Measured from where the finger went down, so pushing past an edge and back works.
                    val dx = (ch.position.x - p.x) / img.width
                    val dy = (ch.position.y - p.y) / img.height
                    onCrop(if (onHandle) resizeCrop(c0, dx, dy, nearL, nearR, nearT, nearB, ratio) else moveCrop(c0, dx, dy))
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
        // Corner handles.
        val arm = min(18.dp.toPx(), min(right - left, bottom - top) / 2f)
        val sw = 3.dp.toPx()
        val o = sw / 2f
        for ((x, sx) in listOf(left - o to 1f, right + o to -1f)) {
            for ((y, sy) in listOf(top - o to 1f, bottom + o to -1f)) {
                drawLine(Color.White, Offset(x, y), Offset(x + sx * arm, y), sw)
                drawLine(Color.White, Offset(x, y), Offset(x, y + sy * arm), sw)
            }
        }
    }
}

internal fun moveCrop(c: NormRect, dx: Float, dy: Float): NormRect {
    val l = (c.l + dx).coerceIn(0f, 1f - c.width)
    val t = (c.t + dy).coerceIn(0f, 1f - c.height)
    return NormRect(l, t, l + c.width, t + c.height)
}

/**
 * Drags the chosen edges of [c] by [dx]/[dy] (fractions of the image). With a [ratio] (the box's
 * width/height in 0..1 units) the box keeps its shape: a corner drag pins the opposite corner, a
 * side drag grows the other dimension evenly about the middle.
 */
internal fun resizeCrop(
    c: NormRect,
    dx: Float,
    dy: Float,
    left: Boolean,
    right: Boolean,
    top: Boolean,
    bottom: Boolean,
    ratio: Float?,
    minSize: Float = 0.05f
): NormRect {
    var l = c.l; var t = c.t; var r = c.r; var b = c.b
    if (left) l = (c.l + dx).coerceIn(0f, c.r - minSize)
    if (right) r = (c.r + dx).coerceIn(c.l + minSize, 1f)
    if (top) t = (c.t + dy).coerceIn(0f, c.b - minSize)
    if (bottom) b = (c.b + dy).coerceIn(c.t + minSize, 1f)
    if (ratio == null || ratio <= 0f) return NormRect(l, t, r, b)

    val horiz = left || right
    val vert = top || bottom
    var w = r - l
    var h = b - t
    if (horiz && vert) {
        // Follow whichever way the finger moved further (compared in width units).
        if (abs(w - c.width) >= abs(h - c.height) * ratio) h = w / ratio else w = h * ratio
        val maxW = if (left) c.r else 1f - c.l
        val maxH = if (top) c.b else 1f - c.t
        if (w > maxW) { w = maxW; h = w / ratio }
        if (h > maxH) { h = maxH; w = h * ratio }
        l = if (left) c.r - w else c.l
        t = if (top) c.b - h else c.t
        r = l + w
        b = t + h
    } else if (horiz) {
        val cy = (c.t + c.b) / 2f
        h = w / ratio
        val maxH = 2f * min(cy, 1f - cy)
        if (h > maxH) {
            h = maxH
            w = h * ratio
            if (left) l = r - w else r = l + w
        }
        t = cy - h / 2f
        b = cy + h / 2f
    } else if (vert) {
        val cx = (c.l + c.r) / 2f
        w = h * ratio
        val maxW = 2f * min(cx, 1f - cx)
        if (w > maxW) {
            w = maxW
            h = w / ratio
            if (top) t = b - h else b = t + h
        }
        l = cx - w / 2f
        r = cx + w / 2f
    }
    return NormRect(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
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

/** The editor's bottom row of tools (Transform / Filters / Tone / …), each an icon over a label. */
@Composable
internal fun <T> EditorToolBar(
    tools: List<T>,
    selected: T,
    label: (T) -> String,
    icon: (T) -> ImageVector,
    onSelect: (T) -> Unit
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = if (tools.size <= 5) Arrangement.SpaceEvenly else Arrangement.spacedBy(2.dp)
    ) {
        items(tools) { t ->
            val tint = if (t == selected) MaterialTheme.colorScheme.primary else Color(0xFFBBBBBB)
            Column(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(t) }.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon(t), null, tint = tint, modifier = Modifier.size(22.dp))
                Text(label(t), color = tint, fontSize = 11.sp)
            }
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
