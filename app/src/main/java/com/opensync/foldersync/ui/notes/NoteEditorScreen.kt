package com.opensync.foldersync.ui.notes

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
    var title by remember { mutableStateOf(existing?.nameWithoutExtension ?: "") }
    var body by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(existing == null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        if (existing != null) {
            body = withContext(Dispatchers.IO) { runCatching { existing.readText() }.getOrDefault("") }
            loaded = true
        }
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
                    // Renamed an existing note: remove the old file after writing the new one.
                    target.writeText(body)
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
                title = { Text(if (existing == null) "New note" else "Edit note", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { save() }, enabled = loaded && !saving) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().padding(12.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Write your note…") },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)
            )
        }
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
