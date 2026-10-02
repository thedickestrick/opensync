package com.opensync.foldersync.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Lays a note out onto PDF pages with Android's own text engine, so it handles every script and
 * emoji the phone can show. Long paragraphs and code blocks break across pages line by line.
 */
object NotePdf {

    private const val MARGIN = 54f   // ¾ inch, in points
    private const val BODY = 11f
    private val INK = Color.rgb(0x1B, 0x1B, 0x1F)
    private val MUTED = Color.rgb(0x66, 0x66, 0x6E)
    private val RULE = Color.rgb(0xB9, 0xB9, 0xC4)
    private val SHADE = Color.rgb(0xF0, 0xF0, 0xF4)
    private val LINK = Color.rgb(0x05, 0x63, 0xC1)

    fun render(blocks: List<Block>, baseDir: File?, letter: Boolean): ByteArray {
        val pageW = if (letter) 612 else 595
        val pageH = if (letter) 792 else 842
        val w = pageW - 2 * MARGIN
        val bottom = pageH - MARGIN
        val pdf = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = MARGIN

        fun newPage(): Canvas {
            page?.let { pdf.finishPage(it) }
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            y = MARGIN
            return page!!.canvas
        }
        var canvas = newPage()

        /**
         * Draws [layout] at [x], moving to a new page whenever the next line won't fit. [behind]
         * paints anything that sits under each page's share of it (a code block's shading).
         */
        fun flow(layout: StaticLayout, x: Float, pad: Float = 0f, behind: ((Canvas, Float, Float) -> Unit)? = null) {
            var line = 0
            while (line < layout.lineCount) {
                if (y + pad + (layout.getLineBottom(line) - layout.getLineTop(line)) > bottom && y > MARGIN) canvas = newPage()
                val top = layout.getLineTop(line)
                var last = line
                while (last + 1 < layout.lineCount && y + pad + layout.getLineBottom(last + 1) - top <= bottom - pad) last++
                val h = (layout.getLineBottom(last) - top).toFloat()
                behind?.invoke(canvas, y, y + h + 2 * pad)
                canvas.save()
                canvas.translate(x, y + pad - top)
                canvas.clipRect(0f, top.toFloat(), layout.width.toFloat(), layout.getLineBottom(last).toFloat())
                layout.draw(canvas)
                canvas.restore()
                y += h + 2 * pad
                line = last + 1
            }
        }

        fun gap(pt: Float) { y += pt }

        for ((index, b) in blocks.withIndex()) {
            when (b) {
                is Block.Heading -> {
                    val size = when (b.level) { 1 -> 20f; 2 -> 16.5f; 3 -> 14f; else -> 12f }
                    if (index > 0) gap(if (b.level <= 2) 10f else 6f)
                    // Keep a heading with the start of what follows it.
                    if (y + size * 3.2f > bottom && y > MARGIN) canvas = newPage()
                    flow(layout(spanned(b.runs, bold = true), w, size), MARGIN)
                    gap(3f)
                }
                is Block.Para -> { flow(layout(spanned(b.runs), w), MARGIN); gap(2f) }
                is Block.Bullet -> { flow(layout(prefixed("•  ", b.runs), w), MARGIN + 6f); gap(1.5f) }
                is Block.Numbered -> { flow(layout(prefixed(b.label + "  ", b.runs), w), MARGIN + 6f); gap(1.5f) }
                is Block.Check -> {
                    val runs = if (b.checked) b.runs.map { it.copy(strike = true) } else b.runs
                    flow(layout(prefixed(if (b.checked) "☑  " else "☐  ", runs), w, color = if (b.checked) MUTED else INK), MARGIN + 6f)
                    gap(1.5f)
                }
                is Block.Quote -> {
                    val l = layout(spanned(b.runs), w - 22f)
                    flow(l, MARGIN + 14f, pad = 5f) { c, top, bot ->
                        c.drawRect(MARGIN, top, MARGIN + w, bot, fill(SHADE))
                        c.drawRect(MARGIN, top, MARGIN + 3.5f, bot, fill(RULE))
                    }
                    gap(4f)
                }
                is Block.Code -> {
                    val l = layout(SpannableStringBuilder(b.text.ifEmpty { " " }), w - 16f, 9.5f, mono = true)
                    gap(2f)
                    flow(l, MARGIN + 8f, pad = 6f) { c, top, bot ->
                        c.drawRoundRect(RectF(MARGIN, top, MARGIN + w, bot), 4f, 4f, fill(SHADE))
                    }
                    gap(5f)
                }
                Block.Rule -> {
                    gap(6f)
                    if (y > bottom) canvas = newPage()
                    canvas.drawRect(MARGIN, y, MARGIN + w, y + 0.8f, fill(RULE))
                    gap(7f)
                }
                Block.Blank -> gap(BODY * 0.6f)
                is Block.Image -> {
                    val bmp = NoteExport.resolveImage(baseDir, b.path)?.let { decode(it, (w * 3).toInt()) }
                    if (bmp == null) {
                        flow(layout(SpannableStringBuilder("[Image: ${b.alt.ifBlank { File(b.path).name }}]"), w, color = MUTED, italic = true), MARGIN)
                    } else {
                        val maxH = bottom - MARGIN
                        var dw = minOf(w, bmp.width / 2f)          // ~144 dpi: sharp, and small images stay small
                        var dh = dw * bmp.height / bmp.width
                        if (dh > maxH) { dw = dw * maxH / dh; dh = maxH }
                        if (y + dh > bottom && y > MARGIN) canvas = newPage()
                        canvas.drawBitmap(bmp, null, RectF(MARGIN, y, MARGIN + dw, y + dh), Paint(Paint.FILTER_BITMAP_FLAG))
                        bmp.recycle()
                        y += dh
                    }
                    gap(6f)
                }
                is Block.Table -> {
                    val cols = maxOf(b.header.size, b.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
                    val cw = w / cols
                    val pad = 4f
                    gap(3f)
                    val all = listOf(b.header) + b.rows
                    for ((r, cells) in all.withIndex()) {
                        val layouts = (0 until cols).map { c ->
                            layout(spanned(cells.getOrElse(c) { emptyList() }, bold = r == 0), cw - 2 * pad, 10f)
                        }
                        val h = layouts.maxOf { it.height } + 2 * pad
                        if (y + h > bottom && y > MARGIN) canvas = newPage()
                        if (r == 0) canvas.drawRect(MARGIN, y, MARGIN + w, y + h, fill(SHADE))
                        val border = Paint().apply { color = RULE; style = Paint.Style.STROKE; strokeWidth = 0.7f }
                        for ((c, l) in layouts.withIndex()) {
                            val x = MARGIN + c * cw
                            canvas.drawRect(x, y, x + cw, y + h, border)
                            canvas.save()
                            canvas.translate(x + pad, y + pad)
                            l.draw(canvas)
                            canvas.restore()
                        }
                        y += h
                    }
                    gap(6f)
                }
            }
        }
        page?.let { pdf.finishPage(it) }
        val out = ByteArrayOutputStream()
        pdf.writeTo(out)
        pdf.close()
        return out.toByteArray()
    }

    private fun fill(c: Int) = Paint().apply { color = c; style = Paint.Style.FILL }

    private fun layout(
        text: CharSequence,
        width: Float,
        size: Float = BODY,
        mono: Boolean = false,
        color: Int = INK,
        italic: Boolean = false
    ): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = when {
                mono -> Typeface.MONOSPACE
                italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                else -> Typeface.DEFAULT
            }
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, if (mono) 1.15f else 1.25f)
            .setIncludePad(false)
            .build()
    }

    private fun spanned(runs: List<Run>, bold: Boolean = false): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (r in runs) {
            val start = sb.length
            sb.append(r.text)
            val end = sb.length
            if (start == end) continue
            fun span(what: Any) = sb.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val style = (if (r.bold || bold) Typeface.BOLD else 0) or (if (r.italic) Typeface.ITALIC else 0)
            if (style != 0) span(StyleSpan(style))
            if (r.strike) span(StrikethroughSpan())
            if (r.code) { span(TypefaceSpan("monospace")); span(BackgroundColorSpan(SHADE)) }
            if (r.link != null) { span(ForegroundColorSpan(LINK)); span(UnderlineSpan()) }
        }
        return sb
    }

    /** A list item: its marker, then text that wraps under the text rather than under the marker. */
    private fun prefixed(marker: String, runs: List<Run>): SpannableStringBuilder {
        val sb = SpannableStringBuilder(marker).append(spanned(runs))
        val indent = TextPaint().apply { textSize = BODY }.measureText(marker).toInt()
        sb.setSpan(LeadingMarginSpan.Standard(0, indent), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return sb
    }

    private fun decode(f: File, maxWidth: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
