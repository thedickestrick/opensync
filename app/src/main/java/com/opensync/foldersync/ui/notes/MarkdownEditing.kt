package com.opensync.foldersync.ui.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

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

/** Length of a ``` / ~~~ fence and its info string at [i], or 0. */
private fun fenceLen(text: String, i: Int): Int {
    if (i >= text.length || (text[i] != '`' && text[i] != '~')) return 0
    var n = 0
    while (i + n < text.length && text[i + n] == text[i]) n++
    if (n < 3) return 0
    return text.indexOf('\n', i).let { if (it < 0) text.length else it } - i
}

/**
 * Two tidy-ups a deletion needs that the rendered text can't express on its own: a line pulled up
 * onto the one above leaves its `>`/`-`/`#`/fence stranded mid-line, where it stops being syntax
 * and shows as literal text; and a `****` left lying around draws nothing but still has to go.
 */
private fun tidy(source: String, caret: Int, joinedLines: Boolean): String {
    var text = source
    if (joinedLines) {
        // The line just pulled up no longer starts a line, so its `>`/`-`/`#`/fence would stop
        // being syntax and appear as literal text. Take it with the newline.
        val prefix = fenceLen(text, caret).takeIf { it > 0 } ?: blockPrefixLen(text, caret)
        if (prefix > 0) text = text.removeRange(caret, (caret + prefix).coerceAtMost(text.length))
    }
    // `****` left over from emptying a bold run draws nothing, so dropping it changes no pixels.
    if (caret < text.length) {
        val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        val stray = emptyPairLen(text, caret, lineEnd)
        if (stray > 0) text = text.removeRange(caret, caret + stray)
    }
    return text
}

/**
 * Translates an edit made to the rendered text back onto the Markdown behind it.
 *
 * [old] and [new] are what the text field held before and after; the result is the new Markdown
 * with the caret in source coordinates. Only the characters that actually changed are touched, so
 * syntax the renderer passes through untouched — tables, fenced code, anything unrecognised — comes
 * out of the file exactly as it went in.
 */
fun RenderedMarkdown.applyEdit(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (new.text == old.text) {
        val want = new.selection
        val snapped = if (want.collapsed) {
            TextRange(snapRendered(want.start, backwards = want.start < old.selection.start))
        } else {
            TextRange(snapRendered(want.min, backwards = true), snapRendered(want.max, backwards = false))
        }
        return TextFieldValue(source, srcSelection(snapped))
    }

    var a = 0
    val shared = minOf(old.text.length, new.text.length)
    while (a < shared && old.text[a] == new.text[a]) a++
    var endOld = old.text.length
    var endNew = new.text.length
    while (endOld > a && endNew > a && old.text[endOld - 1] == new.text[endNew - 1]) { endOld--; endNew-- }

    // Where the field says the caret ended up is the truth. Typing a space beside a space, or
    // backspacing inside a run of the same letter, leaves the diff free to guess which one moved,
    // and it guesses the last — which is a different place in the source, and the wrong one.
    val insLen = endNew - a
    val shift = a - (new.selection.start - insLen)
    if (shift in 1..a && (insLen == 0 || endOld == a)) {
        val a2 = a - shift
        val rebuilt = old.text.substring(0, a2) + new.text.substring(a2, endNew - shift) +
            old.text.substring(endOld - shift)
        if (rebuilt == new.text) { a = a2; endOld -= shift; endNew -= shift }
    }

    val deletion = endNew == a
    val spliced = spliceRendered(a, endOld, new.text.substring(a, endNew))
    // Typing a `*` beside another one is you building a marker, not emptying one, so only a
    // deletion gets tidied up after.
    val text = if (deletion) tidy(spliced.source, spliced.caret, spliced.joinedLines)
    else spliced.source
    return TextFieldValue(text, TextRange(spliced.caret.coerceAtMost(text.length)))
}

/**
 * The note edit surface. The field holds the note *as it reads* — no syntax characters in it at
 * all — so the caret, the selection handles and the keyboard's own autocorrect all work on the
 * words you can see. [value] is the Markdown behind it, which is what gets saved.
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
    val styles = rememberMarkdownStyles()
    val doc = remember(value.text, styles) { renderMarkdown(value.text, styles) }
    val shown = TextFieldValue(doc.text.text, doc.renderedSelection(value.selection))
    // The text is already what we want on screen; this only paints the styles over it, character
    // for character, so there is no offset mapping to get wrong.
    val painter = remember(doc) {
        VisualTransformation { input ->
            if (input.text == doc.text.text) TransformedText(doc.text, OffsetMapping.Identity)
            else TransformedText(input, OffsetMapping.Identity)
        }
    }
    Box(modifier) {
        if (value.text.isEmpty()) {
            Text(placeholder, style = body, color = colors.onSurfaceVariant)
        }
        BasicTextField(
            value = shown,
            onValueChange = { onValueChange(doc.applyEdit(shown, it)) },
            textStyle = body.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = painter,
            modifier = Modifier.fillMaxSize()
        )
    }
}
