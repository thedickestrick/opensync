package com.opensync.foldersync

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.ui.notes.MarkdownView
import com.opensync.foldersync.ui.theme.OpenSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens .txt / .md files handed to us via ACTION_VIEW in the Markdown editor (view/edit/save). */
class TextEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent?.data
            ?: intent?.getStringExtra("note_path")?.let { Uri.fromFile(java.io.File(it)) }
        if (uri == null) { finish(); return }
        val name = displayName(uri)
        setContent {
            OpenSyncTheme {
                Surface(Modifier.fillMaxSize()) {
                    TextEditorScreen(
                        title = name,
                        read = { readText(uri) },
                        save = { text -> writeText(uri, text) },
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) c.getString(i)?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Text"
    }

    private suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: ""
    }

    private suspend fun writeText(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            (contentResolver.openOutputStream(uri, "wt") ?: return@runCatching false).use {
                it.write(text.toByteArray(Charsets.UTF_8))
            }
            true
        }.getOrDefault(false).also { ok ->
            if (ok) com.opensync.foldersync.widget.NotesWidgetProvider.notifyChanged(applicationContext)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextEditorScreen(
    title: String,
    read: suspend () -> String,
    save: suspend (String) -> Boolean,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var body by remember { mutableStateOf(TextFieldValue("")) }
    var loaded by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        body = TextFieldValue(read())
        loaded = true
    }

    fun wrap(pre: String, post: String) {
        val s = body.text
        val start = minOf(body.selection.start, body.selection.end)
        val end = maxOf(body.selection.start, body.selection.end)
        val sel = s.substring(start, end)
        val nt = s.substring(0, start) + pre + sel + post + s.substring(end)
        val cursor = if (sel.isEmpty()) start + pre.length else start + pre.length + sel.length + post.length
        body = TextFieldValue(nt, TextRange(cursor))
    }

    fun linePrefix(prefix: String) {
        val s = body.text
        val pos = minOf(body.selection.start, body.selection.end)
        val lineStart = s.lastIndexOf('\n', (pos - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        body = TextFieldValue(s.substring(0, lineStart) + prefix + s.substring(lineStart), TextRange(pos + prefix.length))
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    IconButton(
                        onClick = {
                            saving = true
                            scope.launch {
                                val ok = save(body.text)
                                saving = false
                                Toast.makeText(
                                    context,
                                    if (ok) "Saved" else "Couldn't save — the file may be read-only",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        enabled = loaded && !saving
                    ) { Icon(Icons.Filled.Check, contentDescription = "Save") }
                }
            )
        }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().padding(horizontal = 12.dp)) {
            if (!loaded) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            } else if (preview) {
                MarkdownView(
                    text = body.text,
                    baseDir = null,
                    modifier = Modifier.fillMaxSize(),
                    onToggleCheckbox = { toggleCheckbox(it) }
                )
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    IconButton(onClick = { linePrefix("# ") }) { Icon(Icons.Filled.Title, "Heading") }
                    IconButton(onClick = { wrap("**", "**") }) { Icon(Icons.Filled.FormatBold, "Bold") }
                    IconButton(onClick = { wrap("_", "_") }) { Icon(Icons.Filled.FormatItalic, "Italic") }
                    IconButton(onClick = { wrap("`", "`") }) { Icon(Icons.Filled.Code, "Code") }
                    IconButton(onClick = { linePrefix("- ") }) { Icon(Icons.Filled.FormatListBulleted, "Bullet") }
                    IconButton(onClick = { linePrefix("1. ") }) { Icon(Icons.Filled.FormatListNumbered, "Numbered") }
                    IconButton(onClick = { linePrefix("- [ ] ") }) { Icon(Icons.Filled.CheckBox, "Checklist") }
                    IconButton(onClick = { linePrefix("> ") }) { Icon(Icons.Filled.FormatQuote, "Quote") }
                }
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp)
                )
            }
        }
    }
}
