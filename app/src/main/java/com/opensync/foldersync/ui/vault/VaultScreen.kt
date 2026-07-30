package com.opensync.foldersync.ui.vault

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
        val currentDir: String = "",
        val folders: List<String> = emptyList(),
        val entries: List<VaultEntry> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null
    ) {
        val atRoot: Boolean get() = currentDir.isEmpty()
        val isEmpty: Boolean get() = folders.isEmpty() && entries.isEmpty()
    }

    private val _state = MutableStateFlow(State(VaultManager.exists(), VaultManager.isUnlocked))
    val state = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val unlocked = VaultManager.isUnlocked
        val dir = _state.value.currentDir
        _state.update {
            if (!unlocked) {
                it.copy(exists = VaultManager.exists(), unlocked = false,
                    folders = emptyList(), entries = emptyList(), currentDir = "")
            } else {
                it.copy(
                    exists = VaultManager.exists(),
                    unlocked = true,
                    folders = runCatching { VaultManager.subFolders(dir) }.getOrDefault(emptyList()),
                    entries = runCatching { VaultManager.entriesIn(dir) }.getOrDefault(emptyList())
                )
            }
        }
    }

    fun openFolder(name: String) {
        val dir = _state.value.currentDir
        _state.update { it.copy(currentDir = if (dir.isEmpty()) name else "$dir/$name") }
        refresh()
    }

    fun up() {
        val dir = _state.value.currentDir
        if (dir.isEmpty()) return
        _state.update { it.copy(currentDir = dir.substringBeforeLast('/', "")) }
        refresh()
    }

    fun create(password: String) = io { VaultManager.create(password.toCharArray()) }

    fun unlock(password: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        val ok = withContext(Dispatchers.IO) { VaultManager.unlock(password.toCharArray()) }
        _state.update { it.copy(busy = false, error = if (ok) null else "Wrong passphrase") }
        refresh()
    }

    fun lock() {
        VaultManager.lock()
        _state.update { it.copy(currentDir = "") }
        refresh()
    }

    fun import(uris: List<Uri>) = viewModelScope.launch {
        val dir = _state.value.currentDir
        _state.update { it.copy(busy = true, error = null) }
        val outcome = withContext(Dispatchers.IO) {
            runCatching { uris.count { !VaultManager.importFile(it, dir).originalRemoved } }
        }
        val err = outcome.exceptionOrNull()?.message
            ?: outcome.getOrNull()?.takeIf { it > 0 }
                ?.let { "Moved to vault, but couldn't delete $it original(s) — remove them manually." }
        _state.update { it.copy(busy = false, error = err) }
        refresh()
    }

    fun newFolder(name: String) = io { VaultManager.createFolder(_state.value.currentDir, name) }

    fun deleteFolder(name: String) = io {
        val dir = _state.value.currentDir
        VaultManager.deleteFolder(if (dir.isEmpty()) name else "$dir/$name")
    }

    fun delete(entry: VaultEntry) = io { VaultManager.deleteEntry(entry) }

    fun export(entry: VaultEntry, dest: Uri) = io {
        Graph.appContext.contentResolver.openOutputStream(dest)?.use { VaultManager.exportTo(entry, it) }
            ?: error("Could not write to that location")
    }

    fun changePassword(current: String, new: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true, error = null) }
        val ok = withContext(Dispatchers.IO) {
            runCatching { VaultManager.changePassword(current.toCharArray(), new.toCharArray()) }.getOrDefault(false)
        }
        _state.update { it.copy(busy = false, error = if (ok) "Passphrase changed" else "Current passphrase is wrong") }
        refresh()
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

    var showNewFolder by remember { mutableStateOf(false) }
    var showChangePw by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = state.unlocked && !state.atRoot) { vm.up() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.unlocked && !state.atRoot) state.currentDir else "Vault",
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (state.unlocked && !state.atRoot) {
                        IconButton(onClick = { vm.up() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                        }
                    } else {
                        IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                    }
                },
                actions = {
                    if (state.unlocked) {
                        IconButton(onClick = { vm.lock() }) { Icon(Icons.Filled.Lock, contentDescription = "Lock") }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("New folder") },
                                    onClick = { overflowOpen = false; showNewFolder = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Change passphrase") },
                                    onClick = { overflowOpen = false; showChangePw = true }
                                )
                            }
                        }
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
                state.isEmpty -> Text(
                    if (state.atRoot)
                        "Vault is empty.\nTap + to move files in — they're encrypted here and " +
                            "removed from their original location.\nUse the ⋮ menu to make folders."
                    else "This folder is empty.\nTap + to move files in.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
                else -> VaultBrowser(
                    folders = state.folders,
                    entries = state.entries,
                    onOpenFolder = { vm.openFolder(it) },
                    onDeleteFolder = { vm.deleteFolder(it) },
                    onOpen = { entry -> openEntry(context, scope, entry, onOpenPdf) },
                    onExport = { entry, uri -> vm.export(entry, uri) },
                    onDelete = { vm.delete(it) }
                )
            }
        }
    }

    if (showNewFolder) {
        VaultTextDialog(
            title = "New folder",
            confirmLabel = "Create",
            onDismiss = { showNewFolder = false },
            onConfirm = { name -> vm.newFolder(name); showNewFolder = false }
        )
    }
    if (showChangePw) {
        ChangePasswordDialog(
            onDismiss = { showChangePw = false },
            onSubmit = { cur, new -> vm.changePassword(cur, new); showChangePw = false }
        )
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
private fun VaultBrowser(
    folders: List<String>,
    entries: List<VaultEntry>,
    onOpenFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onOpen: (VaultEntry) -> Unit,
    onExport: (VaultEntry, Uri) -> Unit,
    onDelete: (VaultEntry) -> Unit
) {
    var menuFor by remember { mutableStateOf<VaultEntry?>(null) }
    var deleteFor by remember { mutableStateOf<VaultEntry?>(null) }
    var exportFor by remember { mutableStateOf<VaultEntry?>(null) }
    var deleteFolderFor by remember { mutableStateOf<String?>(null) }

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
        items(folders, key = { "d:$it" }) { folder ->
            Card(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { onOpenFolder(folder) },
                    onLongClick = { deleteFolderFor = folder }
                )
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(folder, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { deleteFolderFor = folder }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete folder")
                    }
                }
            }
        }
        items(entries, key = { it.id }) { entry ->
            Card(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { onOpen(entry) },
                    onLongClick = { menuFor = entry }
                )
            ) {
                Row(
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
    deleteFolderFor?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteFolderFor = null },
            title = { Text("Delete folder?") },
            text = { Text("“$folder” and everything inside it will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { onDeleteFolder(folder); deleteFolderFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFolderFor = null }) { Text("Cancel") } }
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

@Composable
private fun VaultTextDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var current by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = current.isNotEmpty() && newPw.length >= 6 && newPw == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = current, onValueChange = { current = it }, label = { Text("Current passphrase") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPw, onValueChange = { newPw = it }, label = { Text("New passphrase (6+)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it }, label = { Text("Confirm new passphrase") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    isError = confirm.isNotEmpty() && confirm != newPw,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(current, newPw) }, enabled = valid) { Text("Change") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
