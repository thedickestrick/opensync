package com.opensync.foldersync.ui.notes

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.opensync.foldersync.ui.common.verticalScrollbar
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.opensync.foldersync.notes.NoteConverter
import com.opensync.foldersync.notes.NoteParser
import com.opensync.foldersync.notes.NoteRequest
import com.opensync.foldersync.notes.ParsedNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteViewerScreen(onBack: () -> Unit, onEdit: (String) -> Unit = {}) {
    val path = remember { NoteRequest.path }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(path) { path?.let { File(it) } }
    val lower = remember(path) { path?.lowercase() ?: "" }

    val isRaw = lower.endsWith(".spd") || lower.endsWith(".sdoc") || lower.endsWith(".sdocx") ||
        lower.endsWith(".snb") || lower.endsWith(".memo")
    val isImage = listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif").any { lower.endsWith(it) }
    var converting by remember { mutableStateOf(false) }

    // Parse raw Samsung Notes files once so we can preview and convert them.
    val parsed: ParsedNote? = if (isRaw && file != null) {
        val f = file
        val s by produceState<ParsedNote?>(initialValue = null, f.absolutePath) {
            value = NoteParser.parse(context, f)
        }
        s
    } else null

    fun convertAndEdit() {
        val src = file ?: return
        converting = true
        scope.launch {
            val md = withContext(Dispatchers.IO) { NoteConverter.toMarkdown(context, src) }
            converting = false
            if (md != null) {
                Toast.makeText(context, "Converted → ${md.name}", Toast.LENGTH_SHORT).show()
                onEdit(md.absolutePath)
            } else {
                Toast.makeText(context, "Couldn't convert this note", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file?.name ?: "Note", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isRaw) {
                        IconButton(onClick = { convertAndEdit() }, enabled = parsed != null && !converting) {
                            Icon(Icons.Filled.Edit, contentDescription = "Convert to editable note")
                        }
                    }
                }
            )
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when {
                file == null || !file.exists() -> Text(
                    "File not found",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
                isImage -> AsyncImage(
                    model = file,
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp)
                )
                isRaw -> {
                    if (parsed == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        RawNoteContent(parsed)
                    }
                }
                else -> TextNoteContent(file)
            }
        }
    }
}

@Composable
private fun RawNoteContent(p: ParsedNote) {
    val listState = rememberLazyListState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().verticalScrollbar(listState),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        p.note?.let { msg ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        items(p.imagePaths, key = { it }) { imgPath ->
            AsyncImage(
                model = File(imgPath),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (p.text.isNotBlank()) {
            if (p.imagePaths.isNotEmpty()) item { HorizontalDivider() }
            item { Text("Extracted text", style = MaterialTheme.typography.titleSmall) }
            item {
                SelectionContainer {
                    Text(p.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TextNoteContent(file: File) {
    val text by produceState(initialValue = "", file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val raw = file.readText(Charsets.UTF_8).take(500_000)
                if (file.extension.equals("rtf", ignoreCase = true)) stripRtf(raw) else raw
            }.getOrElse { "Could not read this file: ${it.message}" }
        }
    }
    val scrollState = rememberScrollState()
    SelectionContainer {
        Text(
            text.ifBlank { "(empty)" },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxSize().verticalScrollbar(scrollState).verticalScroll(scrollState).padding(16.dp)
        )
    }
}

/** Crude RTF-to-plain-text for the rare .rtf export. */
private fun stripRtf(raw: String): String =
    raw.replace(Regex("\\\\'[0-9a-fA-F]{2}"), "")
        .replace(Regex("\\\\[a-zA-Z]+-?[0-9]* ?"), "")
        .replace("{", "").replace("}", "")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("\n")
