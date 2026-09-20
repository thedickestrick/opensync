package com.opensync.foldersync.ui.notes

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.notes.BULLET
import com.opensync.foldersync.notes.DONE
import com.opensync.foldersync.notes.Edit
import com.opensync.foldersync.notes.NoteDocument
import com.opensync.foldersync.notes.Style
import com.opensync.foldersync.notes.TODO

/**
 * Everything the editor needs to know about the note being edited: the document, where the caret
 * is, styles armed for the next thing typed, and undo history. Screens hold one of these; the
 * text field and toolbar both talk to it.
 */
class RichNoteState(initialMarkdown: String = "") {
    var doc by mutableStateOf(NoteDocument.parse(initialMarkdown))
        private set
    var selection by mutableStateOf(TextRange(0))
        private set
    private var composition by mutableStateOf<TextRange?>(null)

    /** Styles switched on or off for text typed next with nothing selected — what Bold does at a caret. */
    var pending by mutableStateOf<Map<Style, Boolean>>(emptyMap())
        private set

    private class Snapshot(val doc: NoteDocument, val selection: TextRange)
    private val undoStack = mutableStateListOf<Snapshot>()
    private val redoStack = mutableStateListOf<Snapshot>()
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val markdown: String get() = doc.toMarkdown()
    val text: String get() = doc.text

    /** What the text field shows: the plain text, the caret, and the keyboard's composing region. */
    val fieldValue: TextFieldValue get() = TextFieldValue(doc.text, selection, composition)

    fun load(markdown: String) {
        doc = NoteDocument.parse(markdown)
        selection = TextRange(0)
        composition = null
        pending = emptyMap()
        undoStack.clear()
        redoStack.clear()
    }

    private fun remember() {
        undoStack.add(Snapshot(doc, selection))
        if (undoStack.size > 200) undoStack.removeAt(0)
        redoStack.clear()
    }

    private fun apply(next: NoteDocument, caret: TextRange) {
        remember()
        doc = next
        selection = TextRange(caret.start.coerceIn(0, next.text.length), caret.end.coerceIn(0, next.text.length))
        composition = null
        pending = emptyMap()
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.add(Snapshot(doc, selection))
        doc = s.doc; selection = s.selection; composition = null; pending = emptyMap()
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.add(Snapshot(doc, selection))
        doc = s.doc; selection = s.selection; composition = null; pending = emptyMap()
    }

    /** The text field reporting what the user did. */
    fun onFieldChange(v: TextFieldValue) {
        if (v.text == doc.text) {
            if (v.selection != selection) pending = emptyMap()   // moving the caret disarms Bold-at-caret
            selection = v.selection
            composition = v.composition
            return
        }
        remember()
        val (next, caret) = doc.typed(editFor(doc.text, v.text, v.selection.start), pending)
        doc = next
        selection = TextRange(caret)
        // Keep the keyboard's composing region only if we took its text exactly as offered.
        composition = if (next.text == v.text) v.composition else null
        pending = emptyMap()
    }

    // ------------------------------------------------------------ toolbar

    private fun covered(style: Style): Boolean =
        if (selection.collapsed) style in doc.stylesAt(selection.start)
        else (selection.min until selection.max).all { i -> doc.spans.any { it.style == style && i >= it.start && i < it.end } }

    /** Whether [style] shows as switched on right now. */
    fun isActive(style: Style): Boolean = pending[style] ?: covered(style)

    fun toggle(style: Style) {
        if (selection.collapsed) {
            pending = pending + (style to !isActive(style))
        } else {
            apply(doc.toggleInline(selection.min, selection.max, style), selection)
        }
    }

    val headingLevel: Int get() = doc.lineStyle(doc.lineStart(selection.min))?.style?.headingLevel ?: 0
    val isQuote: Boolean get() = doc.lineStyle(doc.lineStart(selection.min))?.style == Style.QUOTE

    /** Plain → H1 → H2 → H3 → plain. */
    fun cycleHeading() {
        val next = if (headingLevel >= 3) null else Style.heading(headingLevel + 1)
        apply(doc.setLineStyle(selection.min, selection.max, next), selection)
    }

    fun toggleQuote() = apply(doc.setLineStyle(selection.min, selection.max, if (isQuote) null else Style.QUOTE), selection)

    fun hasGlyph(glyph: String): Boolean = doc.glyphAt(doc.lineStart(selection.min)) == glyph

    fun toggleGlyph(glyph: String) {
        val (next, caret) = doc.toggleGlyph(selection.min, selection.max, glyph, selection.start)
        apply(next, TextRange(caret))
    }

    /** The link under the caret, if any. */
    fun linkAt(): com.opensync.foldersync.notes.Span? =
        doc.spans.firstOrNull { it.style == Style.LINK && selection.min >= it.start && selection.max <= it.end }

    /** Links the selection (or the link under the caret) to [url]; null removes the link. */
    fun setLink(url: String?) {
        val existing = linkAt()
        val from = existing?.start ?: selection.min
        val to = existing?.end ?: selection.max
        if (from == to) return
        apply(doc.toggleInline(from, to, Style.LINK, url), TextRange(from, to))
    }

    /** Puts [text] at the caret, replacing any selection. */
    fun insertText(text: String) {
        val (next, caret) = doc.typed(Edit(selection.min, selection.max, text), pending)
        apply(next, TextRange(caret))
    }

    fun toggleCheckbox(lineIndex: Int) = apply(doc.toggleCheckbox(lineIndex), selection)

    // ------------------------------------------------------------ find & replace

    fun findNext(query: String) {
        if (query.isEmpty()) return
        var i = doc.text.indexOf(query, selection.max, ignoreCase = true)
        if (i < 0) i = doc.text.indexOf(query, 0, ignoreCase = true)
        if (i >= 0) { selection = TextRange(i, i + query.length); composition = null }
    }

    fun replaceAll(query: String, replacement: String) {
        if (query.isEmpty()) return
        remember()
        var d = doc
        var from = 0
        while (true) {
            val i = d.text.indexOf(query, from, ignoreCase = true)
            if (i < 0) break
            d = d.replaced(i, i + query.length, replacement)
            from = i + replacement.length
        }
        doc = d
        selection = TextRange(selection.start.coerceAtMost(d.text.length))
        composition = null
    }

    companion object {
        /**
         * What changed between two versions of the text. The field's caret settles an ambiguous
         * edit: typing a space beside a space, the diff can't tell which is new, and it guesses
         * the last — which may be a different place in a styled run.
         */
        internal fun editFor(old: String, new: String, caret: Int): Edit {
            var a = 0
            val shared = minOf(old.length, new.length)
            while (a < shared && old[a] == new[a]) a++
            var endOld = old.length
            var endNew = new.length
            while (endOld > a && endNew > a && old[endOld - 1] == new[endNew - 1]) { endOld--; endNew-- }
            val insLen = endNew - a
            val shift = a - (caret - insLen)
            if (shift in 1..a && (insLen == 0 || endOld == a)) {
                val a2 = a - shift
                val rebuilt = old.substring(0, a2) + new.substring(a2, endNew - shift) + old.substring(endOld - shift)
                if (rebuilt == new) { a = a2; endOld -= shift; endNew -= shift }
            }
            return Edit(a, endOld, new.substring(a, endNew))
        }
    }
}

/**
 * The editor: a formatting toolbar over a text field that holds the note's plain text and paints
 * the formatting on top. Nothing in the field is syntax, so the caret, the selection handles and
 * the keyboard's autocorrect all work on exactly the words on screen.
 */
@Composable
fun RichNoteEditor(
    state: RichNoteState,
    modifier: Modifier = Modifier,
    placeholder: String = "Write your note…",
    extraTools: @Composable RowScope.() -> Unit = {}
) {
    val context = LocalContext.current
    var linkDialog by remember { mutableStateOf<String?>(null) }   // the URL being edited, or null

    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Tool(Icons.AutoMirrored.Filled.Undo, "Undo", enabled = state.canUndo) { state.undo() }
            Tool(Icons.AutoMirrored.Filled.Redo, "Redo", enabled = state.canRedo) { state.redo() }
            Tool(Icons.Filled.Title, "Heading", active = state.headingLevel > 0) { state.cycleHeading() }
            Tool(Icons.Filled.FormatBold, "Bold", active = state.isActive(Style.BOLD)) { state.toggle(Style.BOLD) }
            Tool(Icons.Filled.FormatItalic, "Italic", active = state.isActive(Style.ITALIC)) { state.toggle(Style.ITALIC) }
            Tool(Icons.Filled.FormatStrikethrough, "Strikethrough", active = state.isActive(Style.STRIKE)) { state.toggle(Style.STRIKE) }
            Tool(Icons.Filled.Code, "Code", active = state.isActive(Style.CODE)) { state.toggle(Style.CODE) }
            Tool(Icons.Filled.FormatListBulleted, "Bullet list", active = state.hasGlyph(BULLET)) { state.toggleGlyph(BULLET) }
            Tool(Icons.Filled.CheckBox, "Checklist", active = state.hasGlyph(TODO) || state.hasGlyph(DONE)) { state.toggleGlyph(TODO) }
            Tool(Icons.Filled.FormatQuote, "Quote", active = state.isQuote) { state.toggleQuote() }
            Tool(Icons.Filled.Link, "Link", active = state.linkAt() != null) {
                val link = state.linkAt()
                if (link == null && state.selection.collapsed) {
                    Toast.makeText(context, "Select the text to link first", Toast.LENGTH_SHORT).show()
                } else linkDialog = link?.url ?: ""
            }
            extraTools()
        }
        RichNoteField(state, placeholder, Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp, bottom = 8.dp))
    }

    linkDialog?.let { initial ->
        var url by remember(initial) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { linkDialog = null },
            title = { Text(if (initial.isEmpty()) "Add link" else "Edit link") },
            text = {
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { linkDialog = null; if (url.isNotBlank()) state.setLink(url.trim()) }) { Text("Save") }
            },
            dismissButton = {
                if (initial.isNotEmpty()) {
                    TextButton(onClick = { linkDialog = null; state.setLink(null) }) { Text("Remove") }
                } else {
                    TextButton(onClick = { linkDialog = null }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = label, tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
}

/** The text field itself, with the note's formatting painted over its plain text. */
@Composable
private fun RichNoteField(state: RichNoteState, placeholder: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val body = type.bodyLarge
    val doc = state.doc
    val styled = remember(doc, colors, type) {
        styledText(
            doc,
            heading = listOf(type.headlineSmall, type.titleLarge, type.titleMedium).map { it.toSpanStyle() },
            codeBackground = colors.surfaceVariant,
            quoteColor = colors.onSurfaceVariant,
            accent = colors.primary
        )
    }
    // Same characters in, same characters out: this only adds colour and weight, so there is no
    // offset mapping — and nothing for one to get wrong.
    val painter = remember(styled) {
        VisualTransformation { input ->
            if (input.text == styled.text) TransformedText(styled, OffsetMapping.Identity)
            else TransformedText(input, OffsetMapping.Identity)
        }
    }
    Box(modifier) {
        if (doc.text.isEmpty()) Text(placeholder, style = body, color = colors.onSurfaceVariant)
        BasicTextField(
            value = state.fieldValue,
            onValueChange = state::onFieldChange,
            textStyle = body.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = painter,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)

/** The document's plain text with its formatting applied — the same subset [MarkdownView] draws. */
internal fun styledText(
    doc: NoteDocument,
    heading: List<SpanStyle>,
    codeBackground: Color,
    quoteColor: Color,
    accent: Color
): AnnotatedString {
    val text = doc.text
    val b = AnnotatedString.Builder(text)
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
    for (s in doc.spans) {
        if (s.end <= s.start && s.style.inline) continue
        val style = when (s.style) {
            Style.BOLD -> BOLD
            Style.ITALIC -> ITALIC
            Style.STRIKE -> STRIKE
            Style.CODE -> code
            Style.LINK -> SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
            Style.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic, color = quoteColor)
            else -> heading.getOrElse(s.style.headingLevel - 1) { BOLD }
        }
        if (s.end > s.start) b.addStyle(style, s.start, s.end)
    }
    // Fenced code blocks and list glyphs aren't spans, but they should still look like themselves.
    var inFence = false
    var ls = 0
    while (ls <= text.length) {
        val le = text.indexOf('\n', ls).let { if (it < 0) text.length else it }
        val line = text.substring(ls, le)
        if (NoteDocument.isFenceLine(line.trim())) inFence = !inFence
        else if (inFence && le > ls) b.addStyle(code, ls, le)
        doc.glyphAt(ls)?.let { b.addStyle(SpanStyle(color = accent), ls, ls + 1) }
        if (le >= text.length) break
        ls = le + 1
    }
    return b.toAnnotatedString()
}
