package com.opensync.foldersync

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.notes.RemoteNotes
import com.opensync.foldersync.provider.ProviderFactory
import com.opensync.foldersync.share.ShareUtil
import com.opensync.foldersync.ui.notes.MarkdownView
import com.opensync.foldersync.ui.notes.RichNoteEditor
import com.opensync.foldersync.ui.notes.RichNoteState
import com.opensync.foldersync.ui.theme.OpenSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens .txt / .md files handed to us via ACTION_VIEW in the note editor (view/edit/save). */
class TextEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = intent?.data
            ?: intent?.getStringExtra("note_path")?.let { Uri.fromFile(java.io.File(it)) }
        if (uri == null) { finish(); return }
        val name = displayName(uri)
        // The widgets explicitly request view mode; otherwise notes (.md/.txt) open rendered and plain
        // data files (.json/.csv/…) open in edit.
        val startInPreview = intent?.getBooleanExtra("view_mode", false) == true ||
            name.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") || it.endsWith(".txt") }
        // A note in an account notes folder (typically opened from a home-screen widget) may be stale:
        // sync now, and let the screen pick up whatever that brings in.
        val sharedFolder = uri.takeIf { it.scheme == "file" }?.path?.let { RemoteNotes.folderFor(it) }
        sharedFolder?.let { RemoteNotes.requestSync(it) }
        val synced = sharedFolder?.let { f -> RemoteNotes.finished.filter { it.folderId == f.id }.map { } }
        setContent {
            OpenSyncTheme {
                Surface(Modifier.fillMaxSize()) {
                    TextEditorScreen(
                        title = name,
                        initialPreview = startInPreview,
                        synced = synced,
                        read = { readText(uri) },
                        save = { text, keepDiskVersion -> writeText(uri, text, keepDiskVersion) },
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

    /** Returns null on success, or a message saying why the save failed. */
    private suspend fun writeText(uri: Uri, text: String, keepDiskVersion: Boolean): String? = withContext(Dispatchers.IO) {
        if (keepDiskVersion && uri.scheme == "file") uri.path?.let { RemoteNotes.keepConflictCopy(java.io.File(it)) }
        val wrote = runCatching {
            (contentResolver.openOutputStream(uri, "wt") ?: return@runCatching false).use {
                it.write(text.toByteArray(Charsets.UTF_8))
            }
            true
        }.getOrDefault(false)
        if (!wrote) return@withContext "Couldn't save — the file may be read-only"
        // A file opened from a remote account (SMB, FTP, …) is only a cache copy: push it back.
        uploadToRemote(uri)?.let { return@withContext it }
        // A note inside an account notes folder (opened from a widget, say) syncs like any other edit.
        if (uri.scheme == "file") uri.path?.let { com.opensync.foldersync.notes.RemoteNotes.notifyChanged(it) }
        com.opensync.foldersync.widget.NotesWidgetProvider.notifyChanged(applicationContext)
        com.opensync.foldersync.widget.SingleNoteWidgetProvider.notifyChanged(applicationContext)
        null
    }

    private suspend fun uploadToRemote(uri: Uri): String? {
        val accountId = intent?.getLongExtra("remote_account_id", -1L) ?: -1L
        val relPath = intent?.getStringExtra("remote_rel_path")
        val local = uri.path?.let { java.io.File(it) }
        if (accountId < 0 || relPath == null || local == null) return null
        return runCatching {
            val account = Graph.database.accountDao().getById(accountId)
                ?: throw IllegalStateException("Account not found")
            ProviderFactory.forAccount(account, "").use { provider ->
                provider.connect()
                provider.upload(local, relPath, System.currentTimeMillis())
            }
        }.exceptionOrNull()?.let { "Couldn't save to the server: ${it.message ?: it.javaClass.simpleName}" }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextEditorScreen(
    title: String,
    read: suspend () -> String,
    /** (text, keepDiskVersion) — the flag asks for the file's current content to be kept as a conflict copy. */
    save: suspend (String, Boolean) -> String?,
    onBack: () -> Unit,
    initialPreview: Boolean = false,
    /** Fires whenever the file may have been replaced underneath us by a sync. */
    synced: Flow<Unit>? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val note = remember { RichNoteState() }
    var loaded by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(initialPreview) }
    var saving by remember { mutableStateOf(false) }
    // What the note looked like when it last matched the file; differing from it means unsaved typing.
    var cleanMarkdown by remember { mutableStateOf("") }
    var replacedWhileEditing by remember { mutableStateOf(false) }

    /** Returns the error, or null once [md] is safely in the file. */
    suspend fun saveNow(md: String): String? {
        val error = save(md, replacedWhileEditing)
        if (error == null) { cleanMarkdown = md; replacedWhileEditing = false }
        return error
    }

    LaunchedEffect(Unit) {
        note.load(read())
        cleanMarkdown = note.markdown
        loaded = true
    }
    LaunchedEffect(synced) {
        synced?.collect {
            // Never pull the text out from under someone who is typing; their save will sort it out.
            if (!loaded) return@collect
            val fresh = read()
            if (fresh.isEmpty() || RichNoteState().apply { load(fresh) }.markdown == cleanMarkdown) return@collect
            if (note.markdown != cleanMarkdown) {
                // …but remember the file is no longer what they started from, so saving keeps both.
                replacedWhileEditing = true
                return@collect
            }
            note.load(fresh)
            cleanMarkdown = note.markdown
        }
    }

    fun toggleCheckbox(index: Int) {
        note.toggleCheckbox(index)
        // Checking a box in view mode should stick immediately.
        if (preview) {
            val md = note.markdown
            scope.launch { saveNow(md) }
        }
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
                    // Shares the text itself: this file may have arrived as another app's
                    // content:// Uri, which we can't legally re-grant to a third app.
                    IconButton(onClick = { ShareUtil.shareText(context, note.markdown, title) }, enabled = loaded) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { preview = !preview }) {
                        Icon(
                            if (preview) Icons.Filled.Edit else Icons.Filled.Visibility,
                            contentDescription = if (preview) "Edit" else "Preview"
                        )
                    }
                    IconButton(
                        onClick = {
                            saving = true
                            val md = note.markdown
                            scope.launch {
                                val error = saveNow(md)
                                saving = false
                                Toast.makeText(context, error ?: "Saved", Toast.LENGTH_LONG).show()
                            }
                        },
                        enabled = loaded && !saving
                    ) { Icon(Icons.Filled.Check, contentDescription = "Save") }
                }
            )
        }
    ) { inner ->
        // Make room for the keyboard too, so a long note scrolls above it instead of under it.
        Column(Modifier.padding(inner).imePadding().fillMaxSize().padding(horizontal = 12.dp)) {
            if (!loaded) {
                CircularProgressIndicator(Modifier.padding(24.dp))
            } else if (preview) {
                val markdown = remember(note.doc) { note.markdown }
                MarkdownView(
                    text = markdown,
                    baseDir = null,
                    modifier = Modifier.fillMaxSize(),
                    onToggleCheckbox = { toggleCheckbox(it) }
                )
            } else {
                RichNoteEditor(state = note, modifier = Modifier.fillMaxWidth().weight(1f), placeholder = "Write…")
            }
        }
    }
}
