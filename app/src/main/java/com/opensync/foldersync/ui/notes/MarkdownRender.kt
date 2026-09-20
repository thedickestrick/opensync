package com.opensync.foldersync.ui.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/** Result of [RenderedMarkdown.spliceRendered]: new source, caret in it, and whether a line was joined. */
class SplicedSource(val source: String, val caret: Int, val joinedLines: Boolean)

/** Colours and heading sizes the renderer needs, read from the theme once. */
class MarkdownStyles(
    val h1: SpanStyle,
    val h2: SpanStyle,
    val h3: SpanStyle,
    val codeBackground: Color,
    val quoteColor: Color,
    val linkColor: Color,
    val ruleColor: Color
)

@Composable
fun rememberMarkdownStyles(): MarkdownStyles {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    return remember(colors, type) {
        MarkdownStyles(
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
 * A note as it reads, with a two-way map back to the Markdown it came from.
 *
 * This is what the editor puts *in the text field* — no syntax characters at all, so there is
 * nothing hidden for the caret to step into, nothing invisible for backspace to hit, and the
 * keyboard's autocorrect and suggestions work on the same words you can see. Edits arrive in
 * rendered coordinates and [srcRangeOf]/[srcCaretAt] translate them onto the Markdown, touching
 * only the span that changed: anything the renderer doesn't understand (tables, odd syntax) is
 * carried through the file byte for byte.
 */
class RenderedMarkdown internal constructor(
    val source: String,
    val text: AnnotatedString,
    private val srcStart: IntArray,   // rendered char -> first source offset behind it
    private val srcEnd: IntArray,     // rendered char -> source offset just past it
    private val toRendered: IntArray, // source offset -> rendered offset
    // For each marker character: the rendered range it wraps, so we know when it's been emptied.
    private val ownerFrom: IntArray,
    private val ownerTo: IntArray
) {

    val length: Int get() = text.length

    /**
     * Which screen positions the caret can actually occupy, derived from the map rather than
     * declared alongside it, so the two can't disagree. A position is real when putting the caret
     * there and reading it back gives the same place: that rules out the inside of a bullet glyph
     * or a rule, and the gap in front of a line's own `#` or `- `.
     */
    private val reachable = BooleanArray(text.length + 1).also {
        for (r in 0..text.length) it[r] = toRendered[srcCaretAt(r).coerceIn(0, source.length)] == r
    }

    /**
     * The nearest caret position that exists. A bullet's `- ` draws three characters, a rule's
     * `---` draws eight: there is no source position inside those, so a caret asked to sit there
     * would bounce straight back and the arrow key would look dead. Step over the whole glyph.
     */
    fun snapRendered(want: Int, backwards: Boolean): Int {
        var r = want.coerceIn(0, length)
        while (r in 0..length && !reachable[r]) r += if (backwards) -1 else 1
        return r.coerceIn(0, length)
    }

    /** Where in the source text typed at rendered caret [r] should land. */
    fun srcCaretAt(r: Int): Int {
        val at = if (r <= 0) 0 else srcEnd[(r - 1).coerceIn(0, srcEnd.size - 1)]
        // At the start of a line, type *after* that line's `#`, `-` or `>`. In front of it the
        // line stops being a heading or a bullet and the marker appears on screen as text.
        return if (at == 0 || (at in 1..source.length && source[at - 1] == '\n')) {
            at + blockPrefixLen(source, at)
        } else at
    }

    /** The source range behind rendered characters `[from, to)`. */
    fun srcRangeOf(from: Int, to: Int): IntRange {
        if (to <= from) return srcCaretAt(from).let { it until it }
        val a = srcStart[from.coerceIn(0, srcStart.size - 1)]
        val b = srcEnd[(to - 1).coerceIn(0, srcEnd.size - 1)]
        return a until maxOf(a, b)
    }

    /**
     * Replaces rendered characters `[from, to)` with [inserted], and returns the new source with
     * the caret in it.
     *
     * It removes the source characters that *drew* what you deleted — plus any marker whose
     * contents are going with it. Dragging over `bo` in **bold** takes the two letters and leaves
     * the `**` holding what survived; dragging over the whole of it takes the `**` too, rather
     * than leaving a pair around nothing for the reader to trip over. A glyph — a bullet's `- `, a
     * rule, an image — goes whole, because every character it drew points at the same source range.
     */
    fun spliceRendered(from: Int, to: Int, inserted: String): SplicedSource {
        val anchor = srcCaretAt(from)
        val out = StringBuilder(source.length + inserted.length)
        if (to <= from) {
            out.append(source, 0, anchor).append(inserted)
            val caret = out.length
            out.append(source, anchor, source.length)
            return SplicedSource(out.toString(), caret, false)
        }

        val cut = BooleanArray(source.length)
        for (r in from until to.coerceAtMost(length)) {
            for (s in srcStart[r] until srcEnd[r]) if (s < source.length) cut[s] = true
        }
        for (s in 0 until source.length) {
            if (ownerTo[s] in 0..to && ownerFrom[s] >= from) cut[s] = true
        }
        // The anchor is only where the typing goes; cut characters on *either* side of it still
        // go, or selecting a bullet's glyph would leave the `- ` that drew it behind.
        var droppedNewline = false
        for (s in 0 until anchor) {
            if (cut[s]) droppedNewline = droppedNewline || source[s] == '\n' else out.append(source[s])
        }
        out.append(inserted)
        val caret = out.length
        for (s in anchor until source.length) {
            if (cut[s]) droppedNewline = droppedNewline || source[s] == '\n' else out.append(source[s])
        }
        return SplicedSource(out.toString(), caret, droppedNewline)
    }

    fun renderedCaret(src: Int): Int = toRendered[src.coerceIn(0, source.length)]

    /**
     * The same selection on screen. An edit can re-flow the text under the caret — a newline can
     * turn the rest of a line into a bullet — so the position it left behind isn't always one the
     * caret can sit on any more; put it on the nearest one that is.
     */
    fun renderedSelection(sel: TextRange): TextRange {
        val a = snapRendered(renderedCaret(sel.start), backwards = false)
        return if (sel.collapsed) TextRange(a)
        else TextRange(a, snapRendered(renderedCaret(sel.end), backwards = false))
    }

    /** The source selection matching a rendered one, for the formatting toolbar to work on. */
    fun srcSelection(sel: TextRange): TextRange =
        if (sel.collapsed) TextRange(srcCaretAt(sel.start))
        else srcRangeOf(sel.min, sel.max).let { TextRange(it.first, it.last + 1) }
}

/** `----` on a line of its own, and [emptyPairLen] are shared with the read-only [MarkdownView]. */
internal fun emptyPairLen(src: String, i: Int, lineEnd: Int): Int {
    val c = src[i]
    if (c != '*' && c != '_' && c != '~') return 0
    if (i > 0 && src[i - 1] == c) return 0      // not the start of the run
    var n = 0
    while (i + n < lineEnd && src[i + n] == c) n++
    return if (n >= 4 && n % 2 == 0) n else 0
}

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val BOLD_ITALIC = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
private val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)
private const val RULE_GLYPHS = "────────"
private const val IMAGE_GLYPH = "🖼  "

/**
 * Turns Markdown into the text a reader sees — markers gone, content styled — the same subset
 * [MarkdownView] draws, so a note looks identical whether you're editing it or not.
 */
fun renderMarkdown(src: String, s: MarkdownStyles): RenderedMarkdown {
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = s.codeBackground)
    val quote = SpanStyle(fontStyle = FontStyle.Italic, color = s.quoteColor)
    val link = SpanStyle(color = s.linkColor, textDecoration = TextDecoration.Underline)
    val alt = SpanStyle(fontStyle = FontStyle.Italic, color = s.quoteColor)
    val rule = SpanStyle(color = s.ruleColor)

    val b = AnnotatedString.Builder()
    val toRendered = IntArray(src.length + 1)
    val srcStart = ArrayList<Int>(src.length + 8)
    val srcEnd = ArrayList<Int>(src.length + 8)
    val ownerFrom = IntArray(src.length) { -1 }
    val ownerTo = IntArray(src.length) { -1 }
    var oi = 0

    fun keep(count: Int, style: SpanStyle?) {
        var k = 0
        while (k < count && oi < src.length) {
            toRendered[oi] = b.length
            if (style != null) b.pushStyle(style)
            b.append(src[oi])
            if (style != null) b.pop()
            srcStart.add(oi); srcEnd.add(oi + 1)
            oi++; k++
        }
    }

    fun drop(count: Int) {
        var k = 0
        while (k < count && oi < src.length) {
            toRendered[oi] = b.length
            oi++; k++
        }
    }

    /** [count] source characters become [repl] on screen, as one indivisible thing. */
    fun replace(count: Int, repl: String, style: SpanStyle?) {
        if (count <= 0 || oi >= src.length) return
        val from = oi
        val to = (oi + count).coerceAtMost(src.length)
        toRendered[oi] = b.length
        if (style != null) b.pushStyle(style)
        for (c in repl) { b.append(c); srcStart.add(from); srcEnd.add(to) }
        if (style != null) b.pop()
        val end = b.length
        for (j in 1..count) if (oi + j <= src.length) toRendered[oi + j] = end
        oi = to
    }

    /** Remembers that source `[from, to)` is a marker wrapping rendered `[rFrom, rTo)`. */
    fun own(from: Int, to: Int, rFrom: Int, rTo: Int) {
        for (s in from until to.coerceAtMost(src.length)) {
            ownerFrom[s] = rFrom
            ownerTo[s] = rTo
        }
    }

    fun inline(marker: String, style: SpanStyle, lineStyle: SpanStyle?, lineEnd: Int) {
        val close = src.indexOf(marker, oi + marker.length)
        if (close in (oi + marker.length) until lineEnd) {
            val opened = oi
            drop(marker.length)
            val rFrom = b.length
            keep(close - oi, lineStyle?.merge(style) ?: style)
            val rTo = b.length
            val closed = oi
            drop(marker.length)
            own(opened, opened + marker.length, rFrom, rTo)
            own(closed, closed + marker.length, rFrom, rTo)
        } else {
            keep(marker.length, lineStyle)
        }
    }

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
                lineStyle = when (hashes) { 1 -> s.h1; 2 -> s.h2; 3 -> s.h3; else -> BOLD }
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
            val empty = emptyPairLen(src, oi, lineEnd)
            when {
                // An image is one thing: you can put the caret either side of it and delete it
                // whole, but there's no half of `![alt](src)` that makes sense on its own.
                imageAt >= 0 -> replace(
                    imageAt + 1 - oi,
                    IMAGE_GLYPH + src.substring(oi + 2, src.indexOf(']', oi + 2)),
                    lineStyle?.merge(alt) ?: alt
                )
                linkAt >= 0 -> {
                    val close = src.indexOf(']', oi + 1)
                    val opened = oi
                    drop(1)
                    val rFrom = b.length
                    keep(close - oi, lineStyle?.merge(link) ?: link)
                    val rTo = b.length
                    val closed = oi
                    drop(linkAt + 1 - oi)
                    own(opened, opened + 1, rFrom, rTo)
                    own(closed, linkAt + 1, rFrom, rTo)
                }
                // **** — a pair around nothing emphasises nothing, so it shows as nothing.
                empty > 0 -> drop(empty)
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
    toRendered[src.length] = b.length

    return RenderedMarkdown(
        src, b.toAnnotatedString(), srcStart.toIntArray(), srcEnd.toIntArray(),
        toRendered, ownerFrom, ownerTo
    )
}
