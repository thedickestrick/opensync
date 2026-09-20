package com.opensync.foldersync.notes

/*
 * A note the way a rich-text editor holds it: plain text plus a list of styled ranges.
 *
 * The text is exactly what the editor's text field shows and what the keyboard edits — there are
 * no syntax characters in it, so nothing is ever hidden from the caret or from autocorrect. Bullets
 * and checkboxes are real characters (• ☐ ☑) you can select and delete like any other. Markdown
 * exists only at the edges: [NoteDocument.parse] on the way in, [toMarkdown] on the way out.
 *
 * Everything in this file is plain Kotlin with no UI dependencies, so it is unit-tested directly.
 */

enum class Style(val inline: Boolean) {
    BOLD(true), ITALIC(true), STRIKE(true), CODE(true), LINK(true),
    H1(false), H2(false), H3(false), H4(false), H5(false), H6(false), QUOTE(false);

    val headingLevel: Int get() = if (ordinal in H1.ordinal..H6.ordinal) ordinal - H1.ordinal + 1 else 0

    companion object {
        fun heading(level: Int): Style = entries[H1.ordinal + level.coerceIn(1, 6) - 1]
    }
}

/** A styled range `[start, end)` of the text. Line styles always cover exactly one whole line. */
data class Span(val start: Int, val end: Int, val style: Style, val url: String? = null)

/** What the user asked to replace: text `[from, to)` becomes [insert]. */
data class Edit(val from: Int, val to: Int, val insert: String)

const val BULLET = "• "
const val TODO = "☐ "
const val DONE = "☑ "

class NoteDocument(val text: String, spans: List<Span> = emptyList()) {
    val spans: List<Span> = normalize(text, spans)

    override fun equals(other: Any?) = other is NoteDocument && other.text == text && other.spans == spans
    override fun hashCode() = text.hashCode() * 31 + spans.hashCode()
    override fun toString() = "NoteDocument(${text.length} chars, ${spans.size} spans)"

    // ---------------------------------------------------------------- lines

    fun lineStart(offset: Int): Int = text.lastIndexOf('\n', offset.coerceIn(0, text.length) - 1) + 1
    fun lineEnd(offset: Int): Int = text.indexOf('\n', offset.coerceIn(0, text.length)).let { if (it < 0) text.length else it }

    /** Starts of every line touched by `[from, to]`. */
    fun lineStarts(from: Int, to: Int): List<Int> {
        val out = ArrayList<Int>()
        var s = lineStart(minOf(from, to))
        val last = lineStart(maxOf(from, to))
        while (true) {
            out.add(s)
            if (s >= last) break
            s = lineEnd(s) + 1
            if (s > text.length) break
        }
        return out
    }

    fun lineStyle(lineStart: Int): Span? = spans.firstOrNull { !it.style.inline && it.start == lineStart }

    /** The list glyph (with its space) a line starts with, if any. A bare glyph is just a character. */
    fun glyphAt(lineStart: Int): String? = when {
        text.startsWith(BULLET, lineStart) -> BULLET
        text.startsWith(TODO, lineStart) -> TODO
        text.startsWith(DONE, lineStart) -> DONE
        else -> null
    }

    /** Inline styles in force at [offset], for the toolbar to show and for typing to continue. */
    fun stylesAt(offset: Int): Set<Style> =
        spans.filter { it.style.inline && offset > it.start && offset <= it.end }.map { it.style }.toSet()

    // ---------------------------------------------------------------- editing

    /**
     * Applies a text change and keeps every span pointing at the same characters. Typing at the end
     * of a bold run continues it, unless [pending] says otherwise; [pending] can also start a style
     * for text typed with nothing selected, which is what the toolbar does with an empty selection.
     */
    fun replaced(from: Int, to: Int, insert: String, pending: Map<Style, Boolean> = emptyMap()): NoteDocument {
        val a = from.coerceIn(0, text.length)
        val b = to.coerceIn(a, text.length)
        val delta = insert.length - (b - a)
        val newText = text.substring(0, a) + insert + text.substring(b)
        val out = ArrayList<Span>(spans.size + 2)
        for (s in spans) {
            // A span starting exactly where text is inserted moves with the text after it, so
            // typing in front of a bold word stays plain and Enter in front of a heading pushes it down.
            val ns = when {
                s.start < a -> s.start
                s.start >= b -> s.start + delta
                else -> a
            }
            val ne = when {
                s.end < a -> s.end
                s.end == a -> {
                    // Typing right after a run: continue it (sticky), unless told not to.
                    val sticky = a == b && insert.isNotEmpty() && s.style.inline && s.style != Style.LINK
                    if (sticky && pending[s.style] != false) s.end + insert.length else s.end
                }
                s.end >= b -> s.end + delta
                else -> a + insert.length
            }
            out.add(s.copy(start = ns, end = ne))
        }
        if (insert.isNotEmpty()) {
            for ((style, on) in pending) if (on && style.inline) out.add(Span(a, a + insert.length, style))
        }
        return NoteDocument(newText, out)
    }

    /**
     * What a keystroke should do, beyond the literal edit: Enter on a list item starts a new item,
     * Enter on an empty item ends the list, and backspacing into a list glyph removes the whole
     * glyph rather than half of it. Returns the new document and where the caret belongs.
     */
    fun typed(edit: Edit, pending: Map<Style, Boolean> = emptyMap()): Pair<NoteDocument, Int> {
        val (from, to, insert) = edit
        if (insert == "\n" && from == to) {
            val ls = lineStart(from)
            val glyph = glyphAt(ls)
            if (glyph != null) {
                if (from == ls + glyph.length && lineEnd(from) == from) {
                    return replaced(ls, from, "") to ls                       // empty item: end the list
                }
                if (from >= ls + glyph.length) {
                    val next = "\n" + (if (glyph.startsWith(DONE.take(1))) TODO else glyph)
                    return replaced(from, to, next, pending) to from + next.length
                }
            }
        }
        if (insert.isEmpty() && to - from == 1) {
            val ls = lineStart(from)
            val glyph = glyphAt(ls)
            if (glyph != null && from > ls && to == ls + glyph.length) {
                return replaced(ls, to, "") to ls                             // take the glyph whole
            }
        }
        return replaced(from, to, insert, pending) to from + insert.length
    }

    /** Adds [style] over `[from, to)`, or removes it if the whole range already has it. */
    fun toggleInline(from: Int, to: Int, style: Style, url: String? = null): NoteDocument {
        val a = minOf(from, to).coerceIn(0, text.length)
        val b = maxOf(from, to).coerceIn(0, text.length)
        if (a == b) return this
        val covering = spans.filter { it.style == style && it.start < b && it.end > a }
        val covered = (a until b).all { i -> covering.any { i >= it.start && i < it.end } }
        return if (covered && (style != Style.LINK || url == null)) {
            NoteDocument(text, spans.flatMap { s ->
                if (s.style != style || s.end <= a || s.start >= b) listOf(s)
                else listOfNotNull(
                    if (s.start < a) s.copy(end = a) else null,
                    if (s.end > b) s.copy(start = b) else null
                )
            })
        } else {
            NoteDocument(text, spans.filter { !(it.style == style && it.start < b && it.end > a) } +
                Span(a, b, style, url))
        }
    }

    /** Sets (or with null, clears) the line style of every line in `[from, to]`. */
    fun setLineStyle(from: Int, to: Int, style: Style?): NoteDocument {
        val starts = lineStarts(from, to).toSet()
        val kept = spans.filter { it.style.inline || it.start !in starts }
        val added = if (style == null) emptyList() else starts.map { Span(it, lineEnd(it), style) }
        return NoteDocument(text, kept + added)
    }

    /**
     * Puts [glyph] at the start of every line in `[from, to]`, or removes it if the first line
     * already has it. Returns the document and the caret, moved with the text it was on.
     */
    fun toggleGlyph(from: Int, to: Int, glyph: String, caret: Int): Pair<NoteDocument, Int> {
        var doc = this
        var c = caret
        val remove = glyphAt(lineStart(minOf(from, to))) == glyph
        for (ls in lineStarts(from, to).sortedDescending()) {   // back to front: offsets stay valid
            val have = doc.glyphAt(ls)
            if (remove && have != null) {
                doc = doc.replaced(ls, ls + have.length, "")
                if (c > ls) c = maxOf(ls, c - have.length)
            } else if (!remove && have == null) {
                doc = doc.replaced(ls, ls, glyph)
                if (c >= ls) c += glyph.length
            } else if (!remove && have != null && have != glyph) {
                doc = doc.replaced(ls, ls + have.length, glyph)
                if (c > ls) c = maxOf(ls, c - have.length + glyph.length)
            }
        }
        return doc to c
    }

    /** Ticks or unticks the checkbox on the line at [lineIndex] (0-based, as the viewer counts them). */
    fun toggleCheckbox(lineIndex: Int): NoteDocument {
        var ls = 0
        repeat(lineIndex) { ls = lineEnd(ls) + 1; if (ls > text.length) return this }
        return when (glyphAt(ls)) {
            TODO -> replaced(ls, ls + TODO.length, DONE)
            DONE -> replaced(ls, ls + DONE.length, TODO)
            else -> this
        }
    }

    // ---------------------------------------------------------------- markdown out

    fun toMarkdown(): String {
        val sb = StringBuilder(text.length + spans.size * 4)
        var inFence = false
        var ls = 0
        var first = true
        while (true) {
            val le = lineEnd(ls)
            if (!first) sb.append('\n')
            first = false
            val line = text.substring(ls, le)
            val trimmed = line.trim()
            when {
                isFenceLine(trimmed) -> { inFence = !inFence; sb.append(line) }
                inFence -> sb.append(line)
                else -> serializeLine(ls, le, sb)
            }
            if (le >= text.length) break
            ls = le + 1
        }
        return sb.toString()
    }

    private fun serializeLine(ls: Int, le: Int, sb: StringBuilder) {
        val style = lineStyle(ls)?.style
        if (style != null) sb.append(if (style == Style.QUOTE) "> " else "#".repeat(style.headingLevel) + " ")
        var from = ls
        val glyph = glyphAt(ls)
        if (glyph != null) {
            sb.append(when (glyph[0]) { '☐' -> "- [ ] "; '☑' -> "- [x] "; else -> "- " })
            from += glyph.length
        }
        val body = StringBuilder(le - from + 8)
        serializeInline(from, le, body)
        // Written out, the line must not merely *look* like block syntax — checked on the result,
        // since an italic run at the start puts its own `*` first and that is not a bullet.
        val hashes = body.takeWhile { it == '#' }.length
        if (glyph == null && (
                (hashes in 1..6 && body.length > hashes && body[hashes] == ' ') ||
                    body.startsWith("- ") || body.startsWith("* ") || body.startsWith("+ ") ||
                    body.startsWith("> ") || body.contentEquals(">")
                )
        ) sb.append('\\')
        sb.append(body)
    }

    private fun serializeInline(from: Int, to: Int, sb: StringBuilder) {
        if (from >= to) return
        // Each character's styles, with emphasis pulled in off surrounding whitespace (a marker
        // next to a space doesn't parse) and nothing allowed inside code.
        val sets = Array(to - from) { LinkedHashSet<Span>() }
        for (s in spans) {
            if (!s.style.inline || s.end <= from || s.start >= to) continue
            val a = maxOf(s.start, from)
            val b = minOf(s.end, to)
            for (i in a until b) sets[i - from].add(s.copy(start = a, end = b))
        }
        for (set in sets) if (set.any { it.style == Style.CODE }) set.removeAll { it.style != Style.CODE }
        // Emphasis markers can't touch whitespace, so each stretch of bold/italic/strike as it will
        // actually be written — which a code span may have cut in two — is trimmed at both ends.
        for (style in listOf(Style.BOLD, Style.ITALIC, Style.STRIKE)) {
            var i = 0
            while (i < sets.size) {
                if (sets[i].none { it.style == style }) { i++; continue }
                var a = i
                var b = i
                while (b < sets.size && sets[b].any { it.style == style }) b++
                while (a < b && text[from + a].isWhitespace()) sets[a++].removeAll { it.style == style }
                while (b > a && text[from + b - 1].isWhitespace()) sets[--b].removeAll { it.style == style }
                i = maxOf(b, i + 1)
            }
        }
        // A bare `[` with a link still to come on this line would swallow it; note where links end.
        val lastLink = sets.indexOfLast { set -> set.any { it.style == Style.LINK } }

        val open = ArrayList<Span>()
        val fences = HashMap<Span, String>()   // a code span's fence, long enough to contain it
        for (i in from until to) {
            val c = text[i]
            val want = sets[i - from]
            // Close whatever no longer applies (and whatever sits on top of it).
            val reopen = ArrayList<Span>()
            while (open.any { it !in want }) {
                val top = open.removeAt(open.size - 1)
                sb.append(closeMarker(top, fences))
                if (top in want) reopen.add(0, top)
            }
            // A marker can't open in front of whitespace, so hold it for the next real character.
            // The longest-lasting style goes outermost: below the others, it isn't closed and
            // reopened every time one of them ends.
            var opened = false
            if (!c.isWhitespace()) {
                val adds = want.filter { it !in open && it !in reopen }
                val order = (reopen + adds)
                    .sortedWith(compareByDescending<Span> { it.end }.thenBy { ORDER.indexOf(it.style) })
                    .toMutableList()
                // Bold and italic opening together write `***`, which a reader takes as `**` then
                // `*` — so bold must be the outer one, whatever their lengths.
                val b = order.indexOfFirst { it.style == Style.BOLD }
                val it_ = order.indexOfFirst { it.style == Style.ITALIC }
                if (b >= 0 && it_ >= 0 && it_ < b) { val t = order[b]; order[b] = order[it_]; order[it_] = t }
                for (s in order) {
                    open.add(s)
                    sb.append(openMarker(s, i, fences))
                    opened = true
                }
            }
            val inCode = open.any { it.style == Style.CODE }
            val inLink = open.any { it.style == Style.LINK }
            // A literal marker character touching a marker we're writing would merge into one run,
            // and a backslash there would escape the marker instead of standing for itself.
            val atBoundary = opened || i + 1 == to || sets[i - from] != sets[i - from + 1]
            val linkAhead = i - from < lastLink
            // A `!` right before a link's `[` would turn it into an image.
            val bangBeforeLink = c == '!' && !inLink && i + 1 < to && sets[i - from + 1].any { it.style == Style.LINK }
            if (!inCode && (needsEscape(i, inLink, linkAhead) || (atBoundary && c in "*_~\\") || bangBeforeLink)) sb.append('\\')
            sb.append(c)
        }
        while (open.isNotEmpty()) sb.append(closeMarker(open.removeAt(open.size - 1), fences))
    }

    /** Would this literal character be read as syntax if written bare? */
    private fun needsEscape(i: Int, inLink: Boolean, linkAhead: Boolean): Boolean {
        val c = text[i]
        val le = lineEnd(i)
        val prev = if (i > lineStart(i)) text[i - 1] else null
        val next = if (i + 1 < le) text[i + 1] else null
        fun nonSpace(ch: Char?) = ch != null && !ch.isWhitespace()
        return when (c) {
            '\\' -> next != null && isPunct(next)
            '`' -> true
            '*' -> nonSpace(prev) || nonSpace(next)
            '_' -> (prev?.isLetterOrDigit() != true && nonSpace(next)) || (nonSpace(prev) && next?.isLetterOrDigit() != true)
            '~' -> prev == '~' || next == '~'
            // Inside a label either bracket would end it early. Outside, `[` only matters if a
            // `](` follows — and `![` is an image written out as-is, which reads back as-is.
            '[' -> inLink || linkAhead || (prev != '!' && text.indexOf("](", i).let { it in (i + 1) until le })
            ']' -> inLink
            else -> false
        }
    }

    /** The marker that opens [s] at [at]. A code span gets a fence one backtick longer than any run inside it. */
    private fun openMarker(s: Span, at: Int, fences: MutableMap<Span, String>): String = when (s.style) {
        Style.BOLD -> "**"; Style.ITALIC -> "*"; Style.STRIKE -> "~~"; Style.LINK -> "["
        Style.CODE -> {
            val body = text.substring(at, s.end)
            var longest = 0; var run = 0
            for (ch in body) { run = if (ch == '`') run + 1 else 0; longest = maxOf(longest, run) }
            // A backtick at either edge, or a space at both, needs a space of padding the reader strips.
            val pad = body.startsWith('`') || body.endsWith('`') ||
                (body.startsWith(' ') && body.endsWith(' ') && body.isNotBlank())
            val fence = "`".repeat(longest + 1)
            fences[s] = fence + (if (pad) " " else "")
            fence + (if (pad) " " else "")
        }
        else -> ""
    }

    private fun closeMarker(s: Span, fences: Map<Span, String>): String = when (s.style) {
        Style.LINK -> "](${s.url.orEmpty()})"
        Style.CODE -> fences[s]?.reversed() ?: "`"
        else -> openMarker(s, 0, HashMap())
    }

    companion object {
        private val ORDER = listOf(Style.LINK, Style.CODE, Style.BOLD, Style.ITALIC, Style.STRIKE)
        private val MARKERS = listOf("**", "__", "~~", "*", "_")

        internal fun isPunct(c: Char): Boolean = c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

        /**
         * A code-block fence line. As in CommonMark, a backtick fence's info string may not hold a
         * backtick — which keeps an inline span written with a long fence, like ```` ```a``b``` ````,
         * from being mistaken for the start of a block.
         */
        internal fun isFenceLine(trimmed: String): Boolean = when {
            trimmed.startsWith("```") -> trimmed.indexOf('`', trimmed.length - trimmed.trimStart('`').length) < 0
            trimmed.startsWith("~~~") -> true
            else -> false
        }

        /** Keeps spans well-formed: line styles cover their whole line, inline ones are merged and non-empty. */
        private fun normalize(text: String, raw: List<Span>): List<Span> {
            val len = text.length
            val lines = LinkedHashMap<Int, Span>()
            val inline = ArrayList<Span>()
            for (s in raw) {
                val a = s.start.coerceIn(0, len)
                val b = s.end.coerceIn(a, len)
                if (s.style.inline) {
                    if (b > a) inline.add(s.copy(start = a, end = b))
                } else {
                    val ls = text.lastIndexOf('\n', a - 1) + 1
                    val le = text.indexOf('\n', ls).let { if (it < 0) len else it }
                    lines.putIfAbsent(ls, Span(ls, le, s.style))
                }
            }
            inline.sortWith(compareBy({ it.start }, { it.style.ordinal }, { it.end }))
            val merged = ArrayList<Span>(inline.size)
            for (s in inline) {
                val i = merged.indexOfLast { it.style == s.style && it.url == s.url && it.end >= s.start }
                if (i >= 0 && merged[i].start <= s.start) {
                    merged[i] = merged[i].copy(end = maxOf(merged[i].end, s.end))
                } else merged.add(s)
            }
            return lines.values.sortedBy { it.start } + merged
        }

        // ------------------------------------------------------------ markdown in

        fun parse(markdown: String): NoteDocument {
            val out = StringBuilder(markdown.length)
            val spans = ArrayList<Span>()
            var inFence = false
            val lines = markdown.split('\n')
            for ((i, raw) in lines.withIndex()) {
                if (i > 0) out.append('\n')
                val trimmed = raw.trim()
                if (isFenceLine(trimmed)) {
                    inFence = !inFence; out.append(raw); continue
                }
                if (inFence) { out.append(raw); continue }

                var line = raw
                var lineStyle: Style? = null
                val hashes = line.takeWhile { it == '#' }.length
                when {
                    hashes in 1..6 && line.length > hashes && line[hashes] == ' ' -> {
                        lineStyle = Style.heading(hashes); line = line.substring(hashes + 1)
                    }
                    line.startsWith("> ") -> { lineStyle = Style.QUOTE; line = line.substring(2) }
                    line == ">" -> { lineStyle = Style.QUOTE; line = "" }
                }
                when {
                    line.startsWith("- [ ] ") -> line = TODO + line.substring(6)
                    line.startsWith("- [x] ") || line.startsWith("- [X] ") -> line = DONE + line.substring(6)
                    line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> line = BULLET + line.substring(2)
                }
                val ls = out.length
                val (plain, inline) = parseInline(line)
                out.append(plain)
                for (s in inline) spans.add(s.copy(start = s.start + ls, end = s.end + ls))
                if (lineStyle != null) spans.add(Span(ls, out.length, lineStyle))
            }
            return NoteDocument(out.toString(), spans)
        }

        private fun styleOf(marker: String) = when (marker) {
            "**", "__" -> Style.BOLD
            "~~" -> Style.STRIKE
            else -> Style.ITALIC
        }

        /** An emphasis opener we've stepped over and are waiting to match. */
        private class Opener(val marker: String, val textStart: Int, val runEnd: Int, val runLen: Int)

        /** One line's inline Markdown → plain text and line-relative spans. */
        private fun parseInline(s: String): Pair<String, List<Span>> {
            val out = StringBuilder(s.length)
            val spans = ArrayList<Span>()
            val open = ArrayList<Opener>()

            fun runEnd(at: Int): Int { var j = at; while (j < s.length && s[j] == s[at]) j++; return j }
            /**
             * Can the run at [at] close an opener whose run was [openerRun] long? CommonMark's
             * "rule of 3": if this run could open as well, the two run lengths must not add up to
             * a multiple of three — which is what stops `*i**a***` closing the italic at `**`.
             */
            fun canClose(m: String, at: Int, openerRun: Int): Boolean {
                if (at == 0 || s[at - 1].isWhitespace()) return false
                val end = runEnd(at)
                val run = end - at
                if (m[0] == '_' && end < s.length && s[end].isLetterOrDigit()) return false
                val couldOpen = end < s.length && !s[end].isWhitespace()
                if (couldOpen && (openerRun + run) % 3 == 0 && !(openerRun % 3 == 0 && run % 3 == 0)) return false
                return true
            }
            // The partner has to sit beyond this run of the same character, with something between:
            // `****` is four stars, not a star wrapped in emphasis.
            fun canOpen(m: String, at: Int): Boolean {
                val after = at + m.length
                if (after >= s.length || s[after].isWhitespace()) return false
                if (m[0] == '_' && at > 0 && s[at - 1].isLetterOrDigit()) return false
                val end = runEnd(at)
                var j = s.indexOf(m, end)
                while (j >= 0) { if (canClose(m, j, end - at)) return true; j = s.indexOf(m, j + 1) }
                return false
            }

            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length && isPunct(s[i + 1])) { out.append(s[i + 1]); i += 2; continue }
                if (c == '`') {
                    // A fence of n backticks closes at the next run of exactly n, so a span can
                    // hold backticks shorter than its fence. One space of padding at both ends is
                    // the writer's, not the content's.
                    val n = runEnd(i) - i
                    var j = i + n
                    var close = -1
                    while (j < s.length) {
                        if (s[j] != '`') { j++; continue }
                        val k = runEnd(j) - j
                        if (k == n) { close = j; break }
                        j += k
                    }
                    if (close > 0) {
                        var body = s.substring(i + n, close)
                        if (body.length >= 2 && body.startsWith(' ') && body.endsWith(' ') && body.isNotBlank()) {
                            body = body.substring(1, body.length - 1)
                        }
                        val st = out.length
                        out.append(body)
                        spans.add(Span(st, out.length, Style.CODE))
                        i = close + n; continue
                    }
                    out.append(s, i, i + n); i += n; continue
                }
                if (c == '!' && s.startsWith("![", i)) {
                    val end = linkEnd(s, i + 1)
                    if (end > 0) { out.append(s, i, end); i = end; continue }   // images stay as written
                }
                if (c == '[') {
                    val end = linkEnd(s, i)
                    if (end > 0) {
                        val close = indexOfUnescaped(s, ']', i + 1)
                        val (label, inner) = parseInline(s.substring(i + 1, close))
                        val st = out.length
                        out.append(label)
                        for (sp in inner) spans.add(sp.copy(start = sp.start + st, end = sp.end + st))
                        spans.add(Span(st, out.length, Style.LINK, s.substring(close + 2, end - 1)))
                        i = end; continue
                    }
                }
                val top = open.lastOrNull()
                if (top != null && s.startsWith(top.marker, i) && i >= top.runEnd &&
                    canClose(top.marker, i, top.runLen) && out.length > top.textStart
                ) {
                    spans.add(Span(top.textStart, out.length, styleOf(top.marker)))
                    open.removeAt(open.size - 1)
                    i += top.marker.length; continue
                }
                val m = MARKERS.firstOrNull { mk -> s.startsWith(mk, i) && open.none { it.marker == mk } && canOpen(mk, i) }
                if (m != null) {
                    val end = runEnd(i)
                    open.add(Opener(m, out.length, end, end - i)); i += m.length; continue
                }
                out.append(c); i++
            }
            // An opener that never found its partner was just text after all; put it back.
            for (o in open.asReversed()) {
                val at = o.textStart
                out.insert(at, o.marker)
                for (k in spans.indices) {
                    val sp = spans[k]
                    if (sp.start >= at) spans[k] = sp.copy(start = sp.start + o.marker.length, end = sp.end + o.marker.length)
                    else if (sp.end > at) spans[k] = sp.copy(end = sp.end + o.marker.length)
                }
            }
            return out.toString() to spans
        }

        /** First [ch] at or after [from] that isn't escaped, or -1. */
        private fun indexOfUnescaped(s: String, ch: Char, from: Int): Int {
            var i = from
            while (i < s.length) {
                when {
                    s[i] == '\\' -> i += 2
                    s[i] == ch -> return i
                    else -> i++
                }
            }
            return -1
        }

        /** Index just past a `[label](url)` starting at [i], or -1. */
        private fun linkEnd(s: String, i: Int): Int {
            if (i >= s.length || s[i] != '[') return -1
            val close = indexOfUnescaped(s, ']', i + 1)
            if (close < 0 || close + 1 >= s.length || s[close + 1] != '(') return -1
            val paren = indexOfUnescaped(s, ')', close + 2)
            return if (paren < 0) -1 else paren + 1
        }
    }
}
