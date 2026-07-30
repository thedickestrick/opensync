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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Renders a lightweight Markdown subset (headings, bold/italic/code, lists, checklists, quotes, images). */
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
            if (isTableRow(line) && index + 1 < lines.size && isTableSeparator(lines[index + 1])) {
                val header = parseRow(line)
                val rows = ArrayList<List<String>>()
                var j = index + 2
                while (j < lines.size && isTableRow(lines[j])) { rows.add(parseRow(lines[j])); j++ }
                MarkdownTable(header, rows)
                i = j
                continue
            }
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

                line.startsWith("### ") -> Text(
                    inlineMarkdown(line.removePrefix("### ")),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                line.startsWith("## ") -> Text(
                    inlineMarkdown(line.removePrefix("## ")),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                line.startsWith("# ") -> Text(
                    inlineMarkdown(line.removePrefix("# ")),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ") -> {
                    val checked = !line.startsWith("- [ ] ")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { onToggleCheckbox(index) })
                        Text(inlineMarkdown(line.substring(6)), style = MaterialTheme.typography.bodyLarge)
                    }
                }

                line.startsWith("- ") || line.startsWith("* ") -> Row(Modifier.padding(vertical = 1.dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodyLarge)
                    Text(inlineMarkdown(line.substring(2)), style = MaterialTheme.typography.bodyLarge)
                }

                numberedRegex.matches(line) -> {
                    val dot = line.indexOf(". ")
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(line.substring(0, dot + 1) + "  ", style = MaterialTheme.typography.bodyLarge)
                        Text(inlineMarkdown(line.substring(dot + 2)), style = MaterialTheme.typography.bodyLarge)
                    }
                }

                line.startsWith("> ") -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Text(
                        inlineMarkdown(line.removePrefix("> ")),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                else -> Text(inlineMarkdown(line), style = MaterialTheme.typography.bodyLarge)
            }
            i++
        }
    }
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
private fun MarkdownTable(header: List<String>, rows: List<List<String>>) {
    val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
    val border = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        TableRowView(header, cols, border, isHeader = true)
        rows.forEach { TableRowView(it, cols, border, isHeader = false) }
    }
}

@Composable
private fun TableRowView(cells: List<String>, cols: Int, border: androidx.compose.ui.graphics.Color, isHeader: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        for (c in 0 until cols) {
            Text(
                inlineMarkdown(cells.getOrElse(c) { "" }),
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

/** Parses inline **bold**, *italic* / _italic_, and `code` into an AnnotatedString. */
fun inlineMarkdown(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                    i = end + 2
                } else { append(s[i]); i++ }
            }
            s.startsWith("~~", i) -> {
                val end = s.indexOf("~~", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(s.substring(i + 2, end)) }
                    i = end + 2
                } else { append(s[i]); i++ }
            }
            s.startsWith("`", i) -> {
                val end = s.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s[i] == '*' -> {
                val end = s.indexOf('*', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s[i] == '_' -> {
                val end = s.indexOf('_', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}
