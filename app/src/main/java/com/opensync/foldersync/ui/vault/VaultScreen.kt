package com.opensync.foldersync.ui.vault

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.opensync.foldersync.ui.common.verticalScrollbar
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opensync.foldersync.Graph
import com.opensync.foldersync.MediaViewActivity
import com.opensync.foldersync.TextEditorActivity
import com.opensync.foldersync.vault.VaultEntry
import com.opensync.foldersync.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VaultViewModel : ViewModel() {
    data class State(
        val exists: Boolean,
        val unlocked: Boolean,
        val entries: List<VaultEntry> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(State(VaultManager.exists(), VaultManager.isUnlocked))
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val unlocked = VaultManager.isUnlocked
        _state.update {
            it.copy(
                exists = VaultManager.exists(),
                unlocked = unlocked,
                entries = if (unlocked) runCatching { VaultManager.listEntries() }.getOrDefault(emptyList()) else emptyList()
            )
        }
    }

    fun create(password: String) = io {
        VaultManager.create(password.toCharArray())
    }

    fun unlock(password: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        val ok = withContext(Dispatchers.IO) { VaultManager.unlock(password.toCharArray()) }
        _state.update { it.copy(busy = false, error = if (ok) null else "Wrong passphrase") }
        refresh()
    }

    fun lock() { VaultManager.lock(); refresh() }

    fun import(uris: List<Uri>) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        val outcome = withContext(Dispatchers.IO) {
            runCatching { uris.count { !VaultManager.importFile(it).originalRemoved } }
        }
        val err = outcome.exceptionOrNull()?.message
            ?: outcome.getOrNull()?.takeIf { it > 0 }
                ?.let { "Moved to vault, but couldn't delete $it original(s) — remove them manually." }
        _state.update { it.copy(busy = false, error = err) }
        refresh()
    }

    fun delete(entry: VaultEntry) = io { VaultManager.deleteEntry(entry) }

    fun export(entry: VaultEntry, dest: Uri) = io {
        Graph.appContext.contentResolver.openOutputStream(dest)?.use { VaultManager.exportTo(entry, it) }
            ?: error("Could not write to that location")
    }

    private fun io(block: suspend () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        val err = withContext(Dispatchers.IO) { runCatching { block() }.exceptionOrNull() }
        _state.update { it.copy(busy = false, error = err?.message) }
        refresh()
    }

    fun clearError() = _state.update { it.copy(error = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    openDrawer: () -> Unit,
    onOpenPdf: (String) -> Unit,
    vm: VaultViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Auto-lock after the app has been in the background a while (so viewing a file doesn't relock).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> VaultManager.markBackgrounded()
                Lifecycle.Event.ON_START -> { VaultManager.autoLockIfStale(); vm.refresh() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.import(uris) }

    LaunchedEffect(state.error) {
        state.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vault") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                },
                actions = {
                    if (state.unlocked) {
                        IconButton(onClick = { vm.lock() }) { Icon(Icons.Filled.Lock, contentDescription = "Lock") }
                    }
                }
            )
        },
        floatingActionButton = {
            if (state.unlocked) {
                FloatingActionButton(onClick = {
                    importLauncher.launch(arrayOf("*/*"))
                }) { Icon(Icons.Filled.Add, contentDescription = "Add files") }
            }
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when {
                !state.exists -> CreateVault(Modifier.align(Alignment.Center)) { vm.create(it) }
                !state.unlocked -> UnlockVault(Modifier.align(Alignment.Center), state.busy) { vm.unlock(it) }
                state.entries.isEmpty() -> Text(
                    "Vault is empty.\nTap + to move files in — they're encrypted here and " +
                        "removed from their original location.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
                else -> EntryList(
                    entries = state.entries,
                    onOpen = { entry -> openEntry(context, scope, entry, onOpenPdf) },
                    onExport = { entry, uri -> vm.export(entry, uri) },
                    onDelete = { vm.delete(it) }
                )
            }
        }
    }
}

@Composable
private fun CreateVault(modifier: Modifier, onCreate: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pw.length >= 6 && pw == confirm
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Create your vault", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pick a passphrase (6+ characters). It encrypts your files and is never stored — " +
                "if you forget it, the files cannot be recovered.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = pw, onValueChange = { pw = it }, label = { Text("Passphrase") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = confirm, onValueChange = { confirm = it }, label = { Text("Confirm passphrase") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            isError = confirm.isNotEmpty() && confirm != pw,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { onCreate(pw) }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
            Text("Create vault")
        }
    }
}

@Composable
private fun UnlockVault(modifier: Modifier, busy: Boolean, onUnlock: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text("Vault is locked", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = pw, onValueChange = { pw = it }, label = { Text("Passphrase") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { onUnlock(pw) }, enabled = pw.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Unlock")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun EntryList(
    entries: List<VaultEntry>,
    onOpen: (VaultEntry) -> Unit,
    onExport: (VaultEntry, Uri) -> Unit,
    onDelete: (VaultEntry) -> Unit
) {
    var menuFor by remember { mutableStateOf<VaultEntry?>(null) }
    var deleteFor by remember { mutableStateOf<VaultEntry?>(null) }
    var exportFor by remember { mutableStateOf<VaultEntry?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> val e = exportFor; exportFor = null; if (uri != null && e != null) onExport(e, uri) }

    val listState = rememberLazyListState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().verticalScrollbar(listState),
        state = listState,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries, key = { it.id }) { entry ->
            Card(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { onOpen(entry) },
                    onLongClick = { menuFor = entry }
                )
            ) {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(iconFor(entry), contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge)
                        Text(formatSize(entry.size), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { exportFor = entry; exportLauncher.launch(entry.name) }) {
                        Icon(Icons.Filled.Save, contentDescription = "Export")
                    }
                    IconButton(onClick = { deleteFor = entry }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
            }
        }
    }

    deleteFor?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Remove from vault?") },
            text = { Text("“${entry.name}” will be permanently deleted from the vault.") },
            confirmButton = { TextButton(onClick = { onDelete(entry); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } }
        )
    }
    menuFor?.let { entry ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text(formatSize(entry.size)) },
            confirmButton = {
                TextButton(onClick = { menuFor = null; onOpen(entry) }) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = { val e = entry; menuFor = null; exportFor = e; exportLauncher.launch(e.name) }) {
                    Text("Export")
                }
            }
        )
    }
}

/** Decrypt an entry to cache and open it in OpenSync's own viewers (or the system for other types). */
private fun openEntry(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    entry: VaultEntry,
    onOpenPdf: (String) -> Unit
) {
    scope.launch {
        val file = try {
            withContext(Dispatchers.IO) { VaultManager.decryptToCache(entry) }
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't open: ${e.message}", Toast.LENGTH_LONG).show(); return@launch
        }
        val mime = entry.mime
        val name = entry.name.lowercase()
        when {
            mime.startsWith("image/") || mime.startsWith("video/") ->
                context.startActivity(Intent(context, MediaViewActivity::class.java).putExtra("media_path", file.absolutePath))
            mime == "application/pdf" || name.endsWith(".pdf") -> onOpenPdf(file.absolutePath)
            mime.startsWith("text/") || name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown") ->
                context.startActivity(Intent(context, TextEditorActivity::class.java).putExtra("note_path", file.absolutePath))
            else -> openWithSystem(context, file)
        }
    }
}

private fun openWithSystem(context: android.content.Context, file: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    } catch (e: Exception) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun iconFor(entry: VaultEntry) = when {
    entry.mime.startsWith("image/") -> Icons.Filled.Image
    entry.mime.startsWith("video/") -> Icons.Filled.Movie
    entry.mime == "application/pdf" || entry.name.endsWith(".pdf", true) -> Icons.Filled.PictureAsPdf
    else -> Icons.Filled.Description
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
