package com.opensync.foldersync.ui.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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

    Column(modifier.verticalScroll(rememberScrollState()).padding(4.dp)) {
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
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
