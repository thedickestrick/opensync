package com.opensync.foldersync.ui.notes

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.notes.NoteEditRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val path = remember { NoteEditRequest.path }
    val dir = remember { NoteEditRequest.dir }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val existing = remember(path) { path?.let { File(it) } }
    val baseDir = remember(path, dir) { existing?.parentFile ?: dir?.let { File(it) } }
    var title by remember { mutableStateOf(existing?.nameWithoutExtension ?: "") }
    var body by remember { mutableStateOf(TextFieldValue("")) }
    var loaded by remember { mutableStateOf(existing == null) }
    var saving by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        if (existing != null) {
            val txt = withContext(Dispatchers.IO) { runCatching { existing.readText() }.getOrDefault("") }
            body = TextFieldValue(txt)
            loaded = true
        }
    }

    fun wrap(pre: String, post: String) {
        val s = body.text
        val start = minOf(body.selection.start, body.selection.end)
        val end = maxOf(body.selection.start, body.selection.end)
        val selected = s.substring(start, end)
        val nt = s.substring(0, start) + pre + selected + post + s.substring(end)
        val cursor = if (selected.isEmpty()) start + pre.length else start + pre.length + selected.length + post.length
        body = TextFieldValue(nt, TextRange(cursor))
    }

    fun linePrefix(prefix: String) {
        val s = body.text
        val pos = minOf(body.selection.start, body.selection.end)
        val lineStart = s.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val nt = s.substring(0, lineStart) + prefix + s.substring(lineStart)
        body = TextFieldValue(nt, TextRange(pos + prefix.length))
    }

    fun toggleCheckbox(index: Int) {
        val lines = body.text.split("\n").toMutableList()
        if (index !in lines.indices) return
        val l = lines[index]
        lines[index] = when {
            l.startsWith("- [ ] ") -> l.replaceFirst("- [ ] ", "- [x] ")
            l.startsWith("- [x] ") -> l.replaceFirst("- [x] ", "- [ ] ")
            l.startsWith("- [X] ") -> l.replaceFirst("- [X] ", "- [ ] ")
            else -> l
        }
        body = body.copy(text = lines.joinToString("\n"))
    }

    fun save() {
        val targetDir = existing?.parentFile ?: dir?.let { File(it) }
        if (targetDir == null) {
            Toast.makeText(context, "No folder to save into", Toast.LENGTH_SHORT).show()
            return
        }
        val safe = title.trim().ifBlank { "Note" }.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val ext = existing?.extension?.takeIf { it.isNotBlank() } ?: "md"
        saving = true
        scope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                runCatching {
                    val target = uniqueIfNeeded(File(targetDir, "$safe.$ext"), existing)
                    target.writeText(body.text)
                    if (existing != null && existing.absolutePath != target.absolutePath) existing.delete()
                    target.absolutePath
                }.getOrNull()
            }
            saving = false
            if (savedPath != null) {
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                onSaved(savedPath)
            } else {
                Toast.makeText(context, "Couldn't save note here", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (existing == null) "New note" else "Edit note",
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { preview = !preview }) {
                        Icon(
                            if (preview) Icons.Filled.Edit else Icons.Filled.Visibility,
                            contentDescription = if (preview) "Edit" else "Preview"
                        )
                    }
                    IconButton(onClick = { save() }, enabled = loaded && !saving) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            if (preview) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)
                ) {
                    MarkdownView(
                        text = body.text,
                        baseDir = baseDir,
                        modifier = Modifier.fillMaxSize(),
                        onToggleCheckbox = { toggleCheckbox(it) }
                    )
                }
            } else {
                FormattingToolbar(
                    onHeading = { linePrefix("# ") },
                    onBold = { wrap("**", "**") },
                    onItalic = { wrap("_", "_") },
                    onStrike = { wrap("~~", "~~") },
                    onCode = { wrap("`", "`") },
                    onBullet = { linePrefix("- ") },
                    onNumbered = { linePrefix("1. ") },
                    onChecklist = { linePrefix("- [ ] ") },
                    onQuote = { linePrefix("> ") }
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Write your note…  (Markdown supported)") },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun FormattingToolbar(
    onHeading: () -> Unit,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onStrike: () -> Unit,
    onCode: () -> Unit,
    onBullet: () -> Unit,
    onNumbered: () -> Unit,
    onChecklist: () -> Unit,
    onQuote: () -> Unit
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        IconButton(onClick = onHeading) { Icon(Icons.Filled.Title, contentDescription = "Heading") }
        IconButton(onClick = onBold) { Icon(Icons.Filled.FormatBold, contentDescription = "Bold") }
        IconButton(onClick = onItalic) { Icon(Icons.Filled.FormatItalic, contentDescription = "Italic") }
        IconButton(onClick = onStrike) { Icon(Icons.Filled.FormatStrikethrough, contentDescription = "Strikethrough") }
        IconButton(onClick = onCode) { Icon(Icons.Filled.Code, contentDescription = "Code") }
        IconButton(onClick = onBullet) { Icon(Icons.Filled.FormatListBulleted, contentDescription = "Bullet list") }
        IconButton(onClick = onNumbered) { Icon(Icons.Filled.FormatListNumbered, contentDescription = "Numbered list") }
        IconButton(onClick = onChecklist) { Icon(Icons.Filled.CheckBox, contentDescription = "Checklist") }
        IconButton(onClick = onQuote) { Icon(Icons.Filled.FormatQuote, contentDescription = "Quote") }
    }
}

/** Keep the target path stable when saving over the same note; otherwise avoid clobbering another file. */
private fun uniqueIfNeeded(target: File, existing: File?): File {
    if (existing != null && existing.absolutePath == target.absolutePath) return target
    if (!target.exists()) return target
    val base = target.nameWithoutExtension
    val ext = target.extension.let { if (it.isEmpty()) "" else ".$it" }
    var i = 2
    var candidate = target
    while (candidate.exists()) { candidate = File(target.parentFile, "$base ($i)$ext"); i++ }
    return candidate
}
