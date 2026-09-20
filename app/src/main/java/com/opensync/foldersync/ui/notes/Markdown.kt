package com.opensync.foldersync.ui.notes

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import com.opensync.foldersync.ui.common.verticalScrollbar
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/**
 * Renders a lightweight Markdown subset (headings, bold/italic/strike/code, lists, checklists,
 * quotes, links, rules, fenced code, tables, images). [MarkdownVisualTransformation] hides the same
 * syntax while editing, so the two stay in step — change one and change the other.
 */
@Composable
fun MarkdownView(
    text: String,
    baseDir: File?,
    modifier: Modifier = Modifier,
    onToggleCheckbox: (Int) -> Unit = {}
) {
    val lines = remember(text) { text.split("\n") }
    val imageRegex = remember { Regex("^!\\[.*?]\\((.+)\\)\\s*$") }
    val numberedRegex = remember { Regex("^\\d{1,3}\\. .*") }

    val scrollState = rememberScrollState()
    Column(modifier.verticalScrollbar(scrollState).verticalScroll(scrollState).padding(4.dp)) {
        var i = 0
        while (i < lines.size) {
            val index = i
            val line = lines[index]
            val trimmed = line.trim()

            // Fenced code: the fence lines are syntax, the body is shown verbatim.
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val fence = trimmed.take(3)
                val body = StringBuilder()
                var j = index + 1
                while (j < lines.size && !lines[j].trim().startsWith(fence)) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[j])
                    j++
                }
                CodeBlock(body.toString())
                i = if (j < lines.size) j + 1 else j
                continue
            }

            if (isTableRow(line) && index + 1 < lines.size && isTableSeparator(lines[index + 1])) {
                val header = parseRow(line)
                val rows = ArrayList<List<String>>()
                var j = index + 2
                while (j < lines.size && isTableRow(lines[j])) { rows.add(parseRow(lines[j])); j++ }
                MarkdownTable(header, rows)
                i = j
                continue
            }

            val heading = headingLevel(line)
            when {
                line.isBlank() -> Spacer(Modifier.height(8.dp))

                imageRegex.matches(trimmed) -> {
                    val path = imageRegex.find(trimmed)!!.groupValues[1]
                    AsyncImage(
                        model = resolveImage(baseDir, path),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }

                isHorizontalRule(trimmed) -> HorizontalDivider(Modifier.padding(vertical = 8.dp))

                heading > 0 -> Text(
                    md(line.substring(heading + 1)),
                    style = when (heading) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                    },
                    modifier = Modifier.padding(vertical = if (heading == 1) 4.dp else 2.dp)
                )

                line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ") -> {
                    val checked = !line.startsWith("- [ ] ")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { onToggleCheckbox(index) })
                        Text(md(line.substring(6)), style = MaterialTheme.typography.bodyLarge)
                    }
                }

                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text("•  ", style = MaterialTheme.typography.bodyLarge)
                        Text(md(line.substring(2)), style = MaterialTheme.typography.bodyLarge)
                    }

                numberedRegex.matches(line) -> {
                    val dot = line.indexOf(". ")
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(line.substring(0, dot + 1) + "  ", style = MaterialTheme.typography.bodyLarge)
                        Text(md(line.substring(dot + 2)), style = MaterialTheme.typography.bodyLarge)
                    }
                }

                line.startsWith("> ") -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Text(
                        md(line.removePrefix("> ")),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                else -> Text(md(line), style = MaterialTheme.typography.bodyLarge)
            }
            i++
        }
    }
}

/** `# ` … `###### ` — the level, or 0 if this line isn't a heading. */
private fun headingLevel(line: String): Int {
    val n = line.takeWhile { it == '#' }.length
    return if (n in 1..6 && line.length > n && line[n] == ' ') n else 0
}

/** `---`, `***` or `___` on a line of its own. */
internal fun isHorizontalRule(trimmed: String): Boolean {
    if (trimmed.length < 3) return false
    val c = trimmed[0]
    return (c == '-' || c == '*' || c == '_') && trimmed.all { it == c }
}

private fun isTableRow(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.count { it == '|' } >= 2
}

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!t.startsWith("|")) return false
    return t.trim('|').split("|").all { cell ->
        val c = cell.trim()
        c.isNotEmpty() && c.contains('-') && c.all { it == '-' || it == ':' || it == ' ' }
    }
}

private fun parseRow(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.substring(1)
    if (t.endsWith("|")) t = t.substring(0, t.length - 1)
    return t.split("|").map { it.trim() }
}

@Composable
private fun CodeBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(8.dp)
        )
    }
}

@Composable
private fun MarkdownTable(header: List<String>, rows: List<List<String>>) {
    val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val border = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        TableRowView(header, cols, border, isHeader = true)
        rows.forEach { TableRowView(it, cols, border, isHeader = false) }
    }
}

@Composable
private fun TableRowView(cells: List<String>, cols: Int, border: Color, isHeader: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        for (c in 0 until cols) {
            Text(
                md(cells.getOrElse(c) { "" }),
                style = if (isHeader) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).border(0.5.dp, border).padding(6.dp)
            )
        }
    }
}

private fun resolveImage(baseDir: File?, path: String): Any {
    val f = File(path)
    if (f.isAbsolute && f.exists()) return f
    if (baseDir != null) {
        val rel = File(baseDir, path)
        if (rel.exists()) return rel
    }
    return path
}

/** [inlineMarkdown] with this theme's link and code colours. */
@Composable
private fun md(s: String): AnnotatedString =
    inlineMarkdown(s, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceVariant)

private val LINK_RE = Regex("(!?)\\[([^\\]\\n]*)]\\(([^)\\n]*)\\)")
private val MD_BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val MD_ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val MD_BOLD_ITALIC = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
private val MD_STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)

/**
 * Parses inline `**bold**`, `*italic*` / `_italic_`, `~~strike~~`, `` `code` ``, `[links](url)` and
 * inline `![images](src)` into an AnnotatedString. Mirrors the inline pass in
 * [MarkdownVisualTransformation] so a note reads the same whether you're editing it or not.
 */
fun inlineMarkdown(
    s: String,
    linkColor: Color = Color.Unspecified,
    codeBackground: Color = Color.Unspecified
): AnnotatedString = buildAnnotatedString {
    var i = 0

    /** Emits the text between a matching pair of markers; false if the closing one is missing. */
    fun paired(marker: String, style: SpanStyle): Boolean {
        val end = s.indexOf(marker, i + marker.length)
        if (end < 0) return false
        withStyle(style) { append(s.substring(i + marker.length, end)) }
        i = end + marker.length
        return true
    }

    /** Emits a link's text (or an image's alt text); false if this isn't actually a link. */
    fun linked(): Boolean {
        val m = LINK_RE.matchAt(s, i) ?: return false
        val label = m.groupValues[2]
        if (m.groupValues[1].isEmpty()) {
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(label) }
        } else {
            withStyle(MD_ITALIC) { append("🖼  $label") }
        }
        i = m.range.last + 1
        return true
    }

    while (i < s.length) {
        val c = s[i]
        val handled = when {
            c == '[' || (c == '!' && s.startsWith("![", i)) -> linked()
            s.startsWith("***", i) -> paired("***", MD_BOLD_ITALIC)
            s.startsWith("___", i) -> paired("___", MD_BOLD_ITALIC)
            s.startsWith("**", i) -> paired("**", MD_BOLD)
            s.startsWith("__", i) -> paired("__", MD_BOLD)
            s.startsWith("~~", i) -> paired("~~", MD_STRIKE)
            c == '`' -> paired("`", SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground))
            c == '*' -> paired("*", MD_ITALIC)
            c == '_' -> paired("_", MD_ITALIC)
            else -> false
        }
        if (!handled) { append(c); i++ }
    }
}
