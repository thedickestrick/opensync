package com.opensync.foldersync.ui.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration

/**
 * Renders Markdown as rich text *while editing*: the syntax itself (`#`, `**`, `_`, `~~`, backticks,
 * `- [ ]`, `>`, fences, `[links]`, `![images]`, rules) is hidden and the content is styled the way
 * [MarkdownView] draws it, so the edit surface reads like the finished note. The value behind the
 * field stays plain Markdown; a per-character offset map keeps the cursor and selection honest
 * across the hidden bits.
 */
class MarkdownVisualTransformation(
    private val h1: SpanStyle,
    private val h2: SpanStyle,
    private val h3: SpanStyle,
    codeBackground: Color,
    quoteColor: Color,
    linkColor: Color,
    ruleColor: Color
) : VisualTransformation {

    private val code = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
    private val quote = SpanStyle(fontStyle = FontStyle.Italic, color = quoteColor)
    private val link = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    private val alt = SpanStyle(fontStyle = FontStyle.Italic, color = quoteColor)
    private val rule = SpanStyle(color = ruleColor)

    private class Parsed(val text: AnnotatedString, val o2t: IntArray, val t2o: IntArray)

    // filter() runs on every recomposition and caretStops() wants the same walk, so keep the last one.
    private var cacheKey: String? = null
    private var cacheValue: Parsed? = null

    private fun parsed(src: String): Parsed {
        cacheValue?.let { if (cacheKey == src) return it }
        return parse(src).also { cacheKey = src; cacheValue = it }
    }

    override fun filter(text: AnnotatedString): TransformedText {
        val p = parsed(text.text)
        val srcLen = text.text.length
        val tLen = p.text.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                p.o2t[offset.coerceIn(0, srcLen)].coerceIn(0, tLen)

            override fun transformedToOriginal(offset: Int): Int {
                val o = offset.coerceIn(0, tLen)
                return (if (o >= p.t2o.size) srcLen else p.t2o[o]).coerceIn(0, srcLen)
            }
        }
        return TransformedText(p.text, mapping)
    }

    /**
     * Original offset -> transformed offset, for every offset in [text]. Offsets that share a value
     * are the same place on screen, which is how [reconcileMarkdownEdit] keeps the caret and the
     * backspace key out of syntax nobody can see.
     */
    fun caretStops(text: String): IntArray = parsed(text).o2t

    private fun parse(src: String): Parsed {
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
            if (count <= 0 || oi >= src.length) return
            o2t[oi] = b.length
            if (style != null) b.pushStyle(style)
            for (c in repl) { b.append(c); t2o.add(oi) }
            if (style != null) b.pop()
            val end = b.length
            for (j in 1..count) if (oi + j <= src.length) o2t[oi + j] = end
            oi = (oi + count).coerceAtMost(src.length)
        }

        /** `marker … marker` on one line: hide both markers, style what sits between them. */
        fun inline(marker: String, style: SpanStyle, lineStyle: SpanStyle?, lineEnd: Int) {
            val close = src.indexOf(marker, oi + marker.length)
            if (close in (oi + marker.length) until lineEnd) {
                drop(marker.length)
                keep(close - oi, lineStyle?.merge(style) ?: style)
                drop(marker.length)
            } else {
                keep(marker.length, lineStyle)
            }
        }

        /** End offset of a `[text](url)` / `![alt](src)` run starting at [i], or -1 if there isn't one. */
        fun linkEnd(i: Int, image: Boolean, lineEnd: Int): Int {
            val close = src.indexOf(']', i + if (image) 2 else 1)
            if (close < 0 || close >= lineEnd) return -1
            if (close + 1 >= lineEnd || src[close + 1] != '(') return -1
            val paren = src.indexOf(')', close + 2)
            return if (paren < 0 || paren >= lineEnd) -1 else paren
        }

        var inFence = false
        while (oi < src.length) {
            val lineStart = oi
            val lineEnd = src.indexOf('\n', lineStart).let { if (it < 0) src.length else it }
            val line = src.substring(lineStart, lineEnd)
            val trimmed = line.trim()

            // A fence line is pure syntax, so all of it goes; its newline stays, leaving a gap.
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                drop(lineEnd - lineStart)
                inFence = !inFence
                if (oi < src.length && src[oi] == '\n') keep(1, null)
                continue
            }
            if (inFence) {
                keep(lineEnd - lineStart, code)
                if (oi < src.length && src[oi] == '\n') keep(1, null)
                continue
            }

            var lineStyle: SpanStyle? = null
            val hashes = line.takeWhile { it == '#' }.length
            when {
                hashes in 1..6 && line.length > hashes && line[hashes] == ' ' -> {
                    drop(hashes + 1)
                    lineStyle = when (hashes) { 1 -> h1; 2 -> h2; 3 -> h3; else -> BOLD }
                }
                isHorizontalRule(trimmed) -> replace(lineEnd - lineStart, RULE_GLYPHS, rule)
                line.startsWith("- [ ] ") -> replace(6, "☐  ", null)
                line.startsWith("- [x] ") || line.startsWith("- [X] ") -> replace(6, "☑  ", null)
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") ->
                    replace(2, "•  ", null)
                line.startsWith("> ") -> { drop(2); lineStyle = quote }
                line == ">" -> { drop(1); lineStyle = quote }
            }

            while (oi < src.length && src[oi] != '\n') {
                val c = src[oi]
                val imageAt = if (c == '!' && src.startsWith("![", oi)) linkEnd(oi, true, lineEnd) else -1
                val linkAt = if (c == '[') linkEnd(oi, false, lineEnd) else -1
                when {
                    // **** — a pair with nothing in it, which is what the Bold button leaves behind
                    // and what's left when you delete the last character out of **bold**.
                    emptyPairLen(src, oi, lineEnd) > 0 -> drop(emptyPairLen(src, oi, lineEnd))
                    imageAt >= 0 -> {
                        val close = src.indexOf(']', oi + 2)
                        replace(2, IMAGE_GLYPH, null)
                        keep(close - oi, lineStyle?.merge(alt) ?: alt)
                        drop(imageAt + 1 - oi)
                    }
                    linkAt >= 0 -> {
                        val close = src.indexOf(']', oi + 1)
                        drop(1)
                        keep(close - oi, lineStyle?.merge(link) ?: link)
                        drop(linkAt + 1 - oi)
                    }
                    src.startsWith("***", oi) -> inline("***", BOLD_ITALIC, lineStyle, lineEnd)
                    src.startsWith("___", oi) -> inline("___", BOLD_ITALIC, lineStyle, lineEnd)
                    src.startsWith("**", oi) -> inline("**", BOLD, lineStyle, lineEnd)
                    src.startsWith("__", oi) -> inline("__", BOLD, lineStyle, lineEnd)
                    src.startsWith("~~", oi) -> inline("~~", STRIKE, lineStyle, lineEnd)
                    c == '`' -> inline("`", code, lineStyle, lineEnd)
                    c == '*' -> inline("*", ITALIC, lineStyle, lineEnd)
                    c == '_' -> inline("_", ITALIC, lineStyle, lineEnd)
                    else -> keep(1, lineStyle)
                }
            }
            if (oi < src.length && src[oi] == '\n') keep(1, null)
        }
        o2t[src.length] = b.length
        return Parsed(b.toAnnotatedString(), o2t, t2o.toIntArray())
    }

    private companion object {
        val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
        val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
        val BOLD_ITALIC = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
        val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)
        const val RULE_GLYPHS = "────────"
        const val IMAGE_GLYPH = "🖼  "
    }
}

/**
 * Length of an emphasis pair with nothing between its markers — `****`, `____`, `~~~~` — starting at
 * [i]. Such a pair emphasises nothing, so it's drawn as nothing; an odd run isn't a pair, and a run
 * of two is the opener you're part-way through typing, which should stay visible.
 */
internal fun emptyPairLen(src: String, i: Int, lineEnd: Int): Int {
    val c = src[i]
    if (c != '*' && c != '_' && c != '~') return 0
    if (i > 0 && src[i - 1] == c) return 0      // not the start of the run
    var n = 0
    while (i + n < lineEnd && src[i + n] == c) n++
    return if (n >= 4 && n % 2 == 0) n else 0
}

/** A transformation wired to the current theme, so editing and reading agree on how a note looks. */
@Composable
fun rememberMarkdownTransformation(): MarkdownVisualTransformation {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    return remember(colors, type) {
        MarkdownVisualTransformation(
            h1 = type.headlineSmall.toSpanStyle(),
            h2 = type.titleLarge.toSpanStyle(),
            h3 = type.titleMedium.toSpanStyle(),
            codeBackground = colors.surfaceVariant,
            quoteColor = colors.onSurfaceVariant,
            linkColor = colors.primary,
            ruleColor = colors.outline
        )
    }
}

/**
 * The note edit surface: deliberately chrome-free — no box, no label — so the only difference from
 * reading the note is that you can type in it.
 */
@Composable
fun MarkdownEditField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Write your note…"
) {
    val colors = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyLarge
    val transformation = rememberMarkdownTransformation()
    Box(modifier) {
        if (value.text.isEmpty()) {
            Text(placeholder, style = body, color = colors.onSurfaceVariant)
        }
        BasicTextField(
            value = value,
            // The markers aren't drawn, so they shouldn't be steppable or deletable either.
            onValueChange = { onValueChange(reconcileMarkdownEdit(value, it, transformation.caretStops(value.text))) },
            textStyle = body.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = transformation,
            modifier = Modifier.fillMaxSize()
        )
    }
}
