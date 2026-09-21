package com.opensync.foldersync.ui.notes

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.notes.NoteEditRequest
import com.opensync.foldersync.notes.RemoteNotes
import com.opensync.foldersync.share.ShareUtil
import com.opensync.foldersync.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
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
    val note = remember { RichNoteState() }
    var loaded by remember { mutableStateOf(existing == null) }
    var saving by remember { mutableStateOf(false) }
    // Open saved notes in view (rendered) mode; start a brand-new note in edit mode.
    var preview by remember { mutableStateOf(existing != null) }
    var showFind by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showVaultConfirm by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }

    // What the note looked like when it last matched the file; differing from it means unsaved typing.
    var cleanMarkdown by remember { mutableStateOf("") }
    // A sync replaced the file while it was being edited: saving must keep that version too.
    var replacedWhileEditing by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        if (existing != null) {
            val txt = withContext(Dispatchers.IO) { runCatching { existing.readText() }.getOrDefault("") }
            note.load(txt)
            cleanMarkdown = note.markdown
            loaded = true
        }
    }
    // A note in an account notes folder may be stale: sync now, and take what that brings in — unless
    // the user has started typing, in which case their text is never pulled out from under them.
    LaunchedEffect(path) {
        val folder = existing?.let { RemoteNotes.folderFor(it.absolutePath) } ?: return@LaunchedEffect
        RemoteNotes.requestSync(folder)
        RemoteNotes.finished.filter { it.folderId == folder.id }.collect {
            if (!loaded) return@collect
            val fresh = withContext(Dispatchers.IO) { runCatching { existing.readText() }.getOrDefault("") }
            if (fresh.isEmpty() || RichNoteState().apply { load(fresh) }.markdown == cleanMarkdown) return@collect
            if (note.markdown != cleanMarkdown) {
                replacedWhileEditing = true
                return@collect
            }
            note.load(fresh)
            cleanMarkdown = note.markdown
        }
    }

    fun toggleCheckbox(index: Int) {
        note.toggleCheckbox(index)
        // In view mode a checkbox tap should stick without opening the editor — persist it now.
        val target = existing
        if (preview && target != null) {
            val md = note.markdown
            val keepDiskVersion = replacedWhileEditing
            scope.launch(Dispatchers.IO) {
                if (keepDiskVersion) RemoteNotes.keepConflictCopy(target)
                if (runCatching { target.writeText(md) }.isSuccess) {
                    withContext(Dispatchers.Main) { cleanMarkdown = md; replacedWhileEditing = false }
                }
                RemoteNotes.notifyChanged(target.absolutePath)
                withContext(Dispatchers.Main) {
                    com.opensync.foldersync.widget.NotesWidgetProvider.notifyChanged(context)
                    com.opensync.foldersync.widget.SingleNoteWidgetProvider.notifyChanged(context)
                }
            }
        }
    }

    /** Share what's on screen right now — including unsaved edits — as text. */
    fun shareText() {
        ShareUtil.shareText(context, note.markdown, title.trim().ifBlank { "Note" })
    }

    fun deleteNote() {
        val file = existing ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { file.delete() } }
            RemoteNotes.notifyChanged(file.absolutePath)
            com.opensync.foldersync.widget.NotesWidgetProvider.notifyChanged(context)
            com.opensync.foldersync.widget.SingleNoteWidgetProvider.notifyChanged(context)
            onBack()
        }
    }

    /** Encrypt this note into the vault and remove the plaintext file (persists current text first). */
    fun vaultNote() {
        val file = existing ?: return
        val md = note.markdown
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    if (loaded) file.writeText(md) // capture any pending edits
                    VaultManager.importFile(Uri.fromFile(file)) // encrypts, then deletes the original
                }.exceptionOrNull()
            }
            if (err != null) {
                Toast.makeText(context, err.message ?: "Couldn't move to vault", Toast.LENGTH_LONG).show()
            } else {
                RemoteNotes.notifyChanged(file.absolutePath)
                com.opensync.foldersync.widget.NotesWidgetProvider.notifyChanged(context)
                com.opensync.foldersync.widget.SingleNoteWidgetProvider.notifyChanged(context)
                onBack()
            }
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
        val md = note.markdown
        val keepDiskVersion = replacedWhileEditing
        saving = true
        scope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                runCatching {
                    if (keepDiskVersion && existing != null) RemoteNotes.keepConflictCopy(existing)
                    val target = uniqueIfNeeded(File(targetDir, "$safe.$ext"), existing)
                    target.writeText(md)
                    if (existing != null && existing.absolutePath != target.absolutePath) existing.delete()
                    target.absolutePath
                }.getOrNull()
            }
            saving = false
            if (savedPath != null) {
                cleanMarkdown = md
                replacedWhileEditing = false
                RemoteNotes.notifyChanged(savedPath) // a note in an account folder goes up to the server now
                com.opensync.foldersync.widget.NotesWidgetProvider.notifyChanged(context)
                com.opensync.foldersync.widget.SingleNoteWidgetProvider.notifyChanged(context)
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
                    val label = when {
                        existing == null -> "New note"
                        preview -> "Note"
                        else -> "Edit note"
                    }
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { shareText() }, enabled = loaded) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    if (!preview) {
                        IconButton(onClick = { showFind = !showFind }) {
                            Icon(Icons.Filled.FindReplace, contentDescription = "Find & replace")
                        }
                    }
                    IconButton(onClick = { preview = !preview }) {
                        Icon(
                            if (preview) Icons.Filled.Edit else Icons.Filled.Visibility,
                            contentDescription = if (preview) "Edit" else "Preview"
                        )
                    }
                    IconButton(onClick = { save() }, enabled = loaded && !saving) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                    // Vault / delete / "share the file itself" live in the overflow so the bar still
                    // fits once Share is a first-class action.
                    if (existing != null) {
                        Box {
                            IconButton(onClick = { overflow = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                DropdownMenuItem(
                                    text = { Text("Share file (.${existing.extension.lowercase()})") },
                                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                    onClick = { overflow = false; ShareUtil.shareFiles(context, listOf(existing)) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Move to vault") },
                                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                    onClick = {
                                        overflow = false
                                        when {
                                            !VaultManager.exists() -> Toast.makeText(
                                                context, "Create a vault first (Vault tab).", Toast.LENGTH_LONG
                                            ).show()
                                            !VaultManager.isUnlocked -> Toast.makeText(
                                                context, "Unlock the vault first (Vault tab).", Toast.LENGTH_LONG
                                            ).show()
                                            else -> showVaultConfirm = true
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete note") },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    onClick = { overflow = false; showDeleteConfirm = true }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { inner ->
        // Scaffold makes room for the system bars but not the keyboard: without imePadding a long
        // note keeps its full height underneath it and the caret can end up out of sight.
        Column(Modifier.padding(inner).imePadding().fillMaxSize().padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            if (preview) {
                val markdown = remember(note.doc) { note.markdown }
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)
                ) {
                    MarkdownView(
                        text = markdown,
                        baseDir = baseDir,
                        modifier = Modifier.fillMaxSize(),
                        onToggleCheckbox = { toggleCheckbox(it) }
                    )
                }
            } else {
                if (showFind) {
                    FindReplaceBar(
                        find = findText,
                        replace = replaceText,
                        onFind = { findText = it },
                        onReplace = { replaceText = it },
                        onNext = { note.findNext(findText) },
                        onReplaceAll = { note.replaceAll(findText, replaceText) }
                    )
                }
                RichNoteEditor(
                    state = note,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    extraTools = {
                        IconButton(onClick = { note.insertText("1. ") }) {
                            Icon(Icons.Filled.FormatListNumbered, contentDescription = "Numbered list")
                        }
                        IconButton(onClick = {
                            val atLineStart = note.selection.min == note.doc.lineStart(note.selection.min)
                            note.insertText((if (atLineStart) "" else "\n") + "| Column 1 | Column 2 |\n| --- | --- |\n| Cell | Cell |\n")
                        }) {
                            Icon(Icons.Filled.TableChart, contentDescription = "Table")
                        }
                    }
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete note?") },
            text = { Text("This note will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; deleteNote() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
    if (showVaultConfirm) {
        AlertDialog(
            onDismissRequest = { showVaultConfirm = false },
            title = { Text("Move to vault?") },
            text = { Text("This note will be encrypted into the vault and removed from your notes folder.") },
            confirmButton = {
                TextButton(onClick = { showVaultConfirm = false; vaultNote() }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { showVaultConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FindReplaceBar(
    find: String,
    replace: String,
    onFind: (String) -> Unit,
    onReplace: (String) -> Unit,
    onNext: () -> Unit,
    onReplaceAll: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = find,
                onValueChange = onFind,
                label = { Text("Find") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onNext) { Text("Next") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = replace,
                onValueChange = onReplace,
                label = { Text("Replace with") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onReplaceAll) { Text("All") }
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
