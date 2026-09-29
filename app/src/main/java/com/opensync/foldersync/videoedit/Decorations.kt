package com.opensync.foldersync.videoedit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import androidx.compose.foundation.Canvas as ComposeCanvas

internal enum class DecorateMode(val label: String) { TEXT("Text"), STICKER("Stickers"), DRAW("Draw") }

internal val DECO_COLORS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
    0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFEC407A
).map { it.toInt() }

internal val STICKERS = listOf(
    "😀", "😂", "😍", "🥰", "😎", "🤩", "🥳", "😮", "😢", "😡", "👍", "👏", "🙌", "💪", "🙏",
    "❤️", "💖", "💯", "🔥", "✨", "⭐", "🌟", "🎉", "🎂", "🎁", "🎈", "🌈", "☀️", "🌙", "⚡",
    "🌸", "🌻", "🍀", "🐶", "🐱", "🦄", "🍕", "🍔", "☕", "🍺", "⚽", "🏀", "🎵", "📷", "✈️", "🏖️"
)

/** Draws decorations with plain Android graphics — used for the live preview and for export. */
internal object DecorationRenderer {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun draw(canvas: Canvas, w: Float, h: Float, decorations: List<Decoration>, strokes: List<DrawStroke>) {
        val unit = min(w, h)
        for (s in strokes) {
            if (s.points.isEmpty()) continue
            strokePaint.color = s.color
            strokePaint.strokeWidth = s.width * unit
            val path = Path()
            val (x0, y0) = s.points.first()
            path.moveTo(x0 * w, y0 * h)
            if (s.points.size == 1) path.lineTo(x0 * w + 0.1f, y0 * h) // a tap draws a dot
            for (i in 1 until s.points.size) {
                val (x, y) = s.points[i]
                path.lineTo(x * w, y * h)
            }
            canvas.drawPath(path, strokePaint)
        }
        for (d in decorations) {
            when (d) {
                is TextDecoration -> drawText(canvas, d, w, h)
                is StickerDecoration -> {
                    emojiPaint.textSize = d.scale * unit
                    val fm = emojiPaint.fontMetrics
                    canvas.drawText(d.emoji, d.x * w, d.y * h - (fm.ascent + fm.descent) / 2f, emojiPaint)
                }
            }
        }
    }

    private fun drawText(canvas: Canvas, d: TextDecoration, w: Float, h: Float) {
        val lines = d.text.split('\n')
        textPaint.textSize = d.scale * min(w, h)
        val fm = textPaint.fontMetrics
        val lineH = fm.descent - fm.ascent
        val cx = d.x * w
        val top = d.y * h - lineH * lines.size / 2f
        if (d.style == LabelStyle.BOX) {
            val b = bounds(d, w, h)
            fillPaint.color = d.color
            canvas.drawRoundRect(b, lineH * 0.25f, lineH * 0.25f, fillPaint)
        }
        lines.forEachIndexed { i, line ->
            val baseline = top + i * lineH - fm.ascent
            when (d.style) {
                LabelStyle.OUTLINE -> {
                    textPaint.style = Paint.Style.STROKE
                    textPaint.strokeWidth = textPaint.textSize * 0.14f
                    textPaint.strokeJoin = Paint.Join.ROUND
                    textPaint.color = if (isLight(d.color)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                    canvas.drawText(line, cx, baseline, textPaint)
                    textPaint.style = Paint.Style.FILL
                    textPaint.color = d.color
                }
                LabelStyle.BOX -> textPaint.color = if (isLight(d.color)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                LabelStyle.PLAIN -> textPaint.color = d.color
            }
            textPaint.style = Paint.Style.FILL
            canvas.drawText(line, cx, baseline, textPaint)
        }
    }

    /** Screen-space bounds of [d] in a [w] x [h] frame, used for hit-testing and the selection box. */
    fun bounds(d: Decoration, w: Float, h: Float): RectF {
        val unit = min(w, h)
        return when (d) {
            is TextDecoration -> {
                textPaint.textSize = d.scale * unit
                val lines = d.text.split('\n')
                val fm = textPaint.fontMetrics
                val lineH = fm.descent - fm.ascent
                val tw = lines.maxOf { textPaint.measureText(it) }
                val pad = lineH * 0.3f
                val halfW = tw / 2f + pad
                val halfH = lineH * lines.size / 2f + pad * 0.5f
                RectF(d.x * w - halfW, d.y * h - halfH, d.x * w + halfW, d.y * h + halfH)
            }
            is StickerDecoration -> {
                val half = d.scale * unit * 0.6f
                RectF(d.x * w - half, d.y * h - half, d.x * w + half, d.y * h + half)
            }
        }
    }

    /** A transparent bitmap of everything drawn, for baking into the exported video. */
    fun renderBitmap(w: Int, h: Int, decorations: List<Decoration>, strokes: List<DrawStroke>): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), w.toFloat(), h.toFloat(), decorations, strokes)
        return bmp
    }

    private fun isLight(c: Int): Boolean {
        val r = c ushr 16 and 0xFF
        val g = c ushr 8 and 0xFF
        val b = c and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b > 160
    }
}

/**
 * The decorations drawn over the preview frame, with editing gestures: in [DecorateMode.DRAW] a
 * finger draws; otherwise tap selects, drag moves and pinch resizes a decoration.
 */
@Composable
internal fun DecorationLayer(
    decorations: List<Decoration>,
    strokes: List<DrawStroke>,
    mode: DecorateMode?,
    selectedId: Long?,
    drawColor: Int,
    drawWidth: Float,
    onSelect: (Long?) -> Unit,
    onUpdate: (Decoration) -> Unit,
    onStrokes: (List<DrawStroke>) -> Unit,
    modifier: Modifier = Modifier
) {
    val decos by rememberUpdatedState(decorations)
    val strokeList by rememberUpdatedState(strokes)
    val color by rememberUpdatedState(drawColor)
    val width by rememberUpdatedState(drawWidth)
    val select by rememberUpdatedState(onSelect)
    val update by rememberUpdatedState(onUpdate)
    val setStrokes by rememberUpdatedState(onStrokes)
    val accent = MaterialTheme.colorScheme.primary

    val gestures = if (mode == null) Modifier else Modifier.pointerInput(mode) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            if (mode == DecorateMode.DRAW) {
                down.consume()
                val base = strokeList
                val points = ArrayList<Pair<Float, Float>>()
                points += (down.position.x / w).coerceIn(0f, 1f) to (down.position.y / h).coerceIn(0f, 1f)
                setStrokes(base + DrawStroke(points.toList(), color, width))
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    points += (ch.position.x / w).coerceIn(0f, 1f) to (ch.position.y / h).coerceIn(0f, 1f)
                    setStrokes(base + DrawStroke(points.toList(), color, width))
                    ch.consume()
                }
                return@awaitEachGesture
            }
            val hit = decos.lastOrNull { DecorationRenderer.bounds(it, w, h).contains(down.position.x, down.position.y) }
            select(hit?.id)
            if (hit == null) return@awaitEachGesture
            down.consume()
            var cur: Decoration = hit
            while (true) {
                val ev = awaitPointerEvent()
                if (ev.changes.none { it.pressed }) break
                val pan = ev.calculatePan()
                val zoom = ev.calculateZoom()
                cur = cur.moved(
                    x = (cur.x + pan.x / w).coerceIn(0f, 1f),
                    y = (cur.y + pan.y / h).coerceIn(0f, 1f),
                    scale = (cur.scale * zoom).coerceIn(0.02f, 1.2f)
                )
                update(cur)
                ev.changes.forEach { it.consume() }
            }
        }
    }

    ComposeCanvas(modifier.fillMaxSize().then(gestures)) {
        drawIntoCanvas { DecorationRenderer.draw(it.nativeCanvas, size.width, size.height, decorations, strokes) }
        val sel = decorations.firstOrNull { it.id == selectedId }
        if (sel != null && mode != null && mode != DecorateMode.DRAW) {
            val b = DecorationRenderer.bounds(sel, size.width, size.height)
            drawRect(
                accent,
                Offset(b.left, b.top),
                Size(b.width(), b.height()),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
internal fun DecoratePanel(
    mode: DecorateMode,
    selected: Decoration?,
    drawColor: Int,
    drawWidth: Float,
    hasStrokes: Boolean,
    onMode: (DecorateMode) -> Unit,
    onAddText: () -> Unit,
    onEditText: (TextDecoration) -> Unit,
    onAddSticker: (String) -> Unit,
    onUpdate: (Decoration) -> Unit,
    onDelete: (Decoration) -> Unit,
    onDrawColor: (Int) -> Unit,
    onDrawWidth: (Float) -> Unit,
    onUndoStroke: () -> Unit,
    onClearStrokes: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            DecorateMode.entries.forEach { m ->
                Text(
                    m.label,
                    color = if (m == mode) MaterialTheme.colorScheme.primary else Color(0xFFBBBBBB),
                    fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (m == mode) Color(0x333B82F6) else Color.Transparent)
                        .clickable { onMode(m) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        when (mode) {
            DecorateMode.TEXT -> {
                val t = selected as? TextDecoration
                if (t == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = onAddText) {
                            Icon(Icons.Filled.Add, null, tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text("Add text", color = Color.White)
                        }
                    }
                    Hint("Tap text on the video to change it; drag to move, pinch to resize.")
                } else {
                    ColorRow(t.color) { onUpdate(t.copy(color = it)) }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LabelStyle.entries.forEach { s ->
                            Chip(s.name.lowercase().replaceFirstChar { it.uppercase() }, s == t.style) { onUpdate(t.copy(style = s)) }
                            Spacer(Modifier.width(6.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onEditText(t) }) { Icon(Icons.Filled.Edit, "Edit text", tint = Color.White) }
                        IconButton(onClick = { onDelete(t) }) { Icon(Icons.Filled.Delete, "Delete", tint = Color.White) }
                    }
                    SizeSlider(t.scale, 0.03f, 0.3f) { onUpdate(t.copy(scale = it)) }
                }
            }
            DecorateMode.STICKER -> {
                LazyRow(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(STICKERS) { e ->
                        Text(
                            e,
                            fontSize = 28.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onAddSticker(e) }.padding(6.dp)
                        )
                    }
                }
                val s = selected as? StickerDecoration
                if (s != null) {
                    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SizeSlider(s.scale, 0.06f, 0.6f) { onUpdate(s.copy(scale = it)) } }
                        IconButton(onClick = { onDelete(s) }) { Icon(Icons.Filled.Delete, "Delete", tint = Color.White) }
                    }
                } else {
                    Hint("Tap a sticker to add it; drag to move, pinch to resize.")
                }
            }
            DecorateMode.DRAW -> {
                ColorRow(drawColor, onDrawColor)
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SizeSlider(drawWidth, 0.004f, 0.05f, label = "Pen") { onDrawWidth(it) } }
                    IconButton(onClick = onUndoStroke, enabled = hasStrokes) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo last stroke", tint = if (hasStrokes) Color.White else Color.Gray)
                    }
                    TextButton(onClick = onClearStrokes, enabled = hasStrokes) {
                        Text("Clear", color = if (hasStrokes) Color.White else Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = Color(0xFF888888),
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFCCCCCC),
        fontSize = 12.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0x333B82F6) else Color(0xFF26262C))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
internal fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(DECO_COLORS) { c ->
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else Color(0x66FFFFFF), CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
private fun SizeSlider(value: Float, minV: Float, maxV: Float, label: String = "Size", onValue: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFFDDDDDD), fontSize = 13.sp, modifier = Modifier.width(50.dp))
        Slider(value = value.coerceIn(minV, maxV), onValueChange = onValue, valueRange = minV..maxV, modifier = Modifier.weight(1f))
    }
}

/** Add or edit a text decoration: the words, a colour and a style. */
@Composable
internal fun TextDecorationDialog(
    initial: TextDecoration?,
    onDismiss: () -> Unit,
    onConfirm: (text: String, color: Int, style: LabelStyle) -> Unit
) {
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: DECO_COLORS.first()) }
    var style by remember { mutableStateOf(initial?.style ?: LabelStyle.OUTLINE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add text" else "Edit text") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DECO_COLORS) { c ->
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(Color(c))
                                .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                                .clickable { color = c }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    LabelStyle.entries.forEach { s ->
                        Text(
                            s.name.lowercase().replaceFirstChar { it.uppercase() },
                            color = if (s == style) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { style = s }.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim(), color, style) }, enabled = text.isNotBlank()) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
