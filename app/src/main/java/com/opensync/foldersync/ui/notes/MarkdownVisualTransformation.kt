package com.opensync.foldersync.ui.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * Renders Markdown as rich text *while editing*: markers (**, *, _, ~~, `, #, - [ ]) are hidden and
 * the content is styled, so the edit surface looks like the rendered view. The underlying value stays
 * plain Markdown; a per-character offset map keeps the cursor/selection correct across the hidden bits.
 */
class MarkdownVisualTransformation(
    private val codeBackground: Color,
    private val quoteColor: Color
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val src = text.text
        val b = AnnotatedString.Builder()
        val o2t = IntArray(src.length + 1)          // original offset -> transformed offset
        val t2o = ArrayList<Int>(src.length + 8)    // transformed char index -> original offset
        var oi = 0

        fun keep(count: Int, style: SpanStyle?) {
            var k = 0
            while (k < count && oi < src.length) {
                o2t[oi] = b.length
                if (style != null) b.pushStyle(style)
                b.append(src[oi])
                if (style != null) b.pop()
                t2o.add(oi)
                oi++; k++
            }
        }
        fun drop(count: Int) {
            var k = 0
            while (k < count && oi < src.length) {
                o2t[oi] = b.length
                oi++; k++
            }
        }
        fun replace(count: Int, repl: String, style: SpanStyle?) {
            if (oi >= src.length) return
            o2t[oi] = b.length
            if (style != null) b.pushStyle(style)
            for (c in repl) { b.append(c); t2o.add(oi) }
            if (style != null) b.pop()
            val end = b.length
            for (j in 1..count) if (oi + j <= src.length) o2t[oi + j] = end
            oi += count
        }

        fun lineEndFrom(i: Int): Int = src.indexOf('\n', i).let { if (it < 0) src.length else it }

        fun inline(marker: String, style: SpanStyle, lineStyle: SpanStyle?) {
            val lineEnd = lineEndFrom(oi)
            val close = src.indexOf(marker, oi + marker.length)
            if (close in (oi + marker.length) until lineEnd) {
                drop(marker.length)
                keep(close - oi, lineStyle?.merge(style) ?: style)
                drop(marker.length)
            } else {
                keep(marker.length, lineStyle)
            }
        }

        while (oi < src.length) {
            val atLineStart = oi == 0 || src[oi - 1] == '\n'
            var lineStyle: SpanStyle? = null
            if (atLineStart) {
                when {
                    src.startsWith("### ", oi) -> { drop(4); lineStyle = H3 }
                    src.startsWith("## ", oi) -> { drop(3); lineStyle = H2 }
                    src.startsWith("# ", oi) -> { drop(2); lineStyle = H1 }
                    src.startsWith("- [ ] ", oi) -> replace(6, "☐ ", null)
                    src.startsWith("- [x] ", oi) || src.startsWith("- [X] ", oi) -> {
                        replace(6, "☑ ", null); lineStyle = STRIKE
                    }
                    src.startsWith("> ", oi) -> { drop(2); lineStyle = SpanStyle(fontStyle = FontStyle.Italic, color = quoteColor) }
                    src.startsWith("- ", oi) || src.startsWith("* ", oi) -> replace(2, "•  ", null)
                }
            }
            while (oi < src.length && src[oi] != '\n') {
                when {
                    src.startsWith("**", oi) -> inline("**", BOLD, lineStyle)
                    src.startsWith("~~", oi) -> inline("~~", STRIKE, lineStyle)
                    src.startsWith("`", oi) -> inline("`", SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground), lineStyle)
                    src.startsWith("*", oi) -> inline("*", ITALIC, lineStyle)
                    src.startsWith("_", oi) -> inline("_", ITALIC, lineStyle)
                    else -> keep(1, lineStyle)
                }
            }
            if (oi < src.length && src[oi] == '\n') keep(1, null)
        }
        o2t[src.length] = b.length

        val transformed = b.toAnnotatedString()
        val tLen = transformed.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                o2t[offset.coerceIn(0, src.length)].coerceIn(0, tLen)

            override fun transformedToOriginal(offset: Int): Int {
                val o = offset.coerceIn(0, tLen)
                return (if (o >= t2o.size) src.length else t2o[o]).coerceIn(0, src.length)
            }
        }
        return TransformedText(transformed, mapping)
    }

    private companion object {
        val H1 = SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val H2 = SpanStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold)
        val H3 = SpanStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)
        val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
        val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
        val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)
    }
}
