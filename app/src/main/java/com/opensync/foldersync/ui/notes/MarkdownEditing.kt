package com.opensync.foldersync.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/*
 * Hiding the Markdown syntax is only half the job. A VisualTransformation changes what gets *drawn*,
 * not what the caret walks over or what backspace removes — so without this the markers are still
 * there to step into and delete, and knocking one star out of a `**` pair silently unformats the
 * text. Everything here works off the offset map the transformation already builds: original offsets
 * that share a transformed offset are the same place on screen, so a caret move between them is a
 * keypress that appears to do nothing, and a deletion between them removes something invisible.
 */

/** First original offset that draws at the same place as [i]. */
internal fun groupLo(o2t: IntArray, i: Int): Int {
    var j = i.coerceIn(0, o2t.size - 1)
    while (j > 0 && o2t[j - 1] == o2t[j]) j--
    return j
}

/** Last original offset that draws at the same place as [i]. */
internal fun groupHi(o2t: IntArray, i: Int): Int {
    var j = i.coerceIn(0, o2t.size - 1)
    while (j + 1 < o2t.size && o2t[j + 1] == o2t[j]) j++
    return j
}

/** Length of the line-leading syntax on the line starting at [lineStart] — `## `, `- `, `- [ ] `, `> `. */
internal fun blockPrefixLen(text: String, lineStart: Int): Int {
    if (lineStart >= text.length) return 0
    val end = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, end)
    val hashes = line.takeWhile { it == '#' }.length
    return when {
        hashes in 1..6 && line.length > hashes && line[hashes] == ' ' -> hashes + 1
        line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ") -> 6
        line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> 2
        line.startsWith("> ") -> 2
        else -> 0
    }
}

private fun deleting(text: String, from: Int, to: Int): TextFieldValue =
    if (from >= to) TextFieldValue(text, TextRange(from))
    else TextFieldValue(text.removeRange(from, to), TextRange(from))

/** Caret one visible character to the left of [from], having been asked to move to [to]. */
private fun stepBack(o2t: IntArray, from: Int, to: Int): Int {
    if (to == from) return to
    if (o2t[to] != o2t[from]) return groupLo(o2t, to)
    // Same place on screen, so the move would look like nothing happened: go a whole group further.
    val lo = groupLo(o2t, from)
    return if (lo == 0) 0 else groupLo(o2t, lo - 1)
}

/** Caret one visible character to the right of [from], having been asked to move to [to]. */
private fun stepForward(o2t: IntArray, from: Int, to: Int): Int {
    if (to == from) return to
    if (o2t[to] != o2t[from]) return groupHi(o2t, to)
    val hi = groupHi(o2t, from)
    return if (hi >= o2t.size - 1) o2t.size - 1 else groupHi(o2t, hi + 1)
}

/**
 * Rewrites what the text field just did so the hidden Markdown syntax behaves as if it isn't there:
 * arrow keys and taps step over it, backspace and delete take the neighbouring *visible* character
 * instead of quietly breaking a `**` pair, and backspacing into a line's `#`/`-`/`>` drops that
 * marker the way any rich editor does.
 *
 * [o2t] is [MarkdownVisualTransformation.caretStops] for `old.text`.
 */
fun reconcileMarkdownEdit(old: TextFieldValue, new: TextFieldValue, o2t: IntArray): TextFieldValue {
    val len = old.text.length
    if (o2t.size != len + 1) return new     // map is for some other text; don't second-guess it

    if (new.text == old.text) {
        // Caret or selection moved. Mid-word the IME owns the caret, so leave it be.
        if (new.selection == old.selection || new.composition != null) return new
        if (!new.selection.collapsed) {
            // Widen to whole constructs, so deleting a selection can't leave half a marker pair.
            return new.copy(selection = TextRange(groupLo(o2t, new.selection.min), groupHi(o2t, new.selection.max)))
        }
        val anchor = old.selection.start.coerceIn(0, len)
        val target = new.selection.start.coerceIn(0, len)
        val moved = if (target < anchor) stepBack(o2t, anchor, target) else stepForward(o2t, anchor, target)
        return new.copy(selection = TextRange(moved))
    }

    // What changed, in old-text coordinates: old[a, endOld) became new[a, endNew).
    var a = 0
    val shared = minOf(len, new.text.length)
    while (a < shared && old.text[a] == new.text[a]) a++
    var endOld = len
    var endNew = new.text.length
    while (endOld > a && endNew > a && old.text[endOld - 1] == new.text[endNew - 1]) { endOld--; endNew-- }

    // Only deletions can land on syntax you can't see; typing is left exactly as the IME meant it.
    if (endNew != a || !old.selection.collapsed) return new
    val caret = old.selection.start.coerceIn(0, len)

    val lineStart = old.text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val prefix = blockPrefixLen(old.text, lineStart)
    if (prefix > 0 && a < lineStart + prefix && endOld > lineStart) {
        return deleting(old.text, lineStart, lineStart + prefix)   // unformat the line
    }

    if (o2t[a] != o2t[endOld]) return new       // it removed something visible: that's a real edit

    return if (new.selection.start < caret) {   // backspace
        val to = groupLo(o2t, caret)
        if (to == 0) old else deleting(old.text, groupLo(o2t, to - 1), to)
    } else {                                    // forward delete
        val from = groupHi(o2t, caret)
        if (from >= len) old else deleting(old.text, from, groupHi(o2t, from + 1))
    }
}
