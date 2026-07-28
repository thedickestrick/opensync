package com.opensync.foldersync.ui.pairs

import android.app.TimePickerDialog
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opensync.foldersync.Graph
import com.opensync.foldersync.data.Account
import com.opensync.foldersync.data.ConflictRule
import com.opensync.foldersync.data.FolderPair
import com.opensync.foldersync.data.ScheduleMode
import com.opensync.foldersync.data.SyncDirection
import com.opensync.foldersync.files.ExplorerLocation
import com.opensync.foldersync.files.ExplorerRepository
import com.opensync.foldersync.provider.RemoteFile
import com.opensync.foldersync.ui.AccountPickResult
import com.opensync.foldersync.ui.components.DropdownField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class FolderPairEditViewModel : ViewModel() {
    private val db = Graph.database

    val accounts = db.accountDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _pair = MutableStateFlow(FolderPair(name = "", localFolder = "", remoteFolder = ""))
    val pair = _pair.asStateFlow()
    private var loaded = false

    fun load(id: Long) {
        if (loaded) return
        loaded = true
        if (id > 0) viewModelScope.launch { db.folderPairDao().getById(id)?.let { _pair.value = it } }
    }

    fun update(transform: (FolderPair) -> FolderPair) { _pair.value = transform(_pair.value) }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val p = _pair.value
        val id = if (p.id == 0L) db.folderPairDao().insert(p) else {
            db.folderPairDao().update(p); p.id
        }
        Graph.syncManager.schedulePair(p.copy(id = id))
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val p = _pair.value
        if (p.id != 0L) {
            db.folderPairDao().delete(p)
            db.syncStateDao().deleteForPair(p.id)
            Graph.syncManager.schedulePair(p.copy(enabled = false))
        }
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPairEditScreen(
    pairId: Long,
    onBack: () -> Unit,
    onAddAccount: () -> Unit = {},
    vm: FolderPairEditViewModel = viewModel()
) {
    LaunchedEffect(pairId) { vm.load(pairId) }
    val context = LocalContext.current
    val pair by vm.pair.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val selectedAccount = accounts.firstOrNull { it.id == pair.remoteAccountId }

    var showLocalPicker by remember { mutableStateOf(false) }
    var showRemotePicker by remember { mutableStateOf(false) }
    var showAccountPicker by remember { mutableStateOf(false) }
    // rememberSaveable: the flag must survive while we're away on the account editor.
    var awaitingNewAccount by rememberSaveable { mutableStateOf(false) }

    // Auto-select an account created via "Add account" when we come back to this screen.
    val newAccountId by AccountPickResult.lastCreatedId.collectAsState()
    LaunchedEffect(newAccountId) {
        if (awaitingNewAccount && newAccountId != null) {
            vm.update { it.copy(remoteAccountId = newAccountId) }
            AccountPickResult.lastCreatedId.value = null
            awaitingNewAccount = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pair.id == 0L) "New folder pair" else "Edit folder pair") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { vm.save(onBack) },
                        enabled = pair.name.isNotBlank() && pair.localFolder.isNotBlank()
                    ) { Text("Save") }
                }
            )
        }
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = pair.name,
                onValueChange = { v -> vm.update { it.copy(name = v) } },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = pair.localFolder,
                onValueChange = { v -> vm.update { it.copy(localFolder = v) } },
                label = { Text("Local folder") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { showLocalPicker = true }) {
                        Icon(Icons.Filled.Folder, contentDescription = "Browse")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            val remoteOptions: List<Account?> = listOf<Account?>(null) + accounts
            val selectedRemote = remoteOptions.firstOrNull { it?.id == pair.remoteAccountId }
            DropdownField(
                label = "Remote target",
                options = remoteOptions,
                selected = selectedRemote,
                optionLabel = { acc -> acc?.let { "${it.name} (${it.type.label})" } ?: "Local device folder" },
                onSelected = { acc -> vm.update { it.copy(remoteAccountId = acc?.id) } }
            )
            TextButton(onClick = {
                AccountPickResult.lastCreatedId.value = null
                awaitingNewAccount = true
                onAddAccount()
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Add cloud / remote account")
            }

            val remoteTrailing: (@Composable () -> Unit)? =
                if (pair.remoteAccountId == null) {
                    {
                        IconButton(onClick = { showRemotePicker = true }) {
                            Icon(Icons.Filled.Folder, contentDescription = "Browse")
                        }
                    }
                } else if (selectedRemote != null) {
                    {
                        IconButton(onClick = { showAccountPicker = true }) {
                            Icon(Icons.Filled.Folder, contentDescription = "Browse")
                        }
                    }
                } else null
            OutlinedTextField(
                value = pair.remoteFolder,
                onValueChange = { v -> vm.update { it.copy(remoteFolder = v) } },
                label = {
                    Text(if (pair.remoteAccountId == null) "Second local folder" else "Remote sub-folder")
                },
                singleLine = true,
                trailingIcon = remoteTrailing,
                modifier = Modifier.fillMaxWidth()
            )

            DropdownField(
                label = "Direction",
                options = SyncDirection.entries,
                selected = pair.direction,
                optionLabel = { it.label },
                onSelected = { d -> vm.update { it.copy(direction = d) } }
            )

            DropdownField(
                label = "Conflict resolution",
                options = ConflictRule.entries,
                selected = pair.conflictRule,
                optionLabel = { it.label },
                onSelected = { c -> vm.update { it.copy(conflictRule = c) } }
            )

            SwitchRow("Include subfolders", pair.includeSubfolders) { b ->
                vm.update { it.copy(includeSubfolders = b) }
            }
            SwitchRow("Propagate deletions (delete orphans)", pair.deleteOrphans) { b ->
                vm.update { it.copy(deleteOrphans = b) }
            }
            SwitchRow("Only on Wi-Fi", pair.requireWifi) { b ->
                vm.update { it.copy(requireWifi = b) }
            }
            SwitchRow("Only while charging", pair.requireCharging) { b ->
                vm.update { it.copy(requireCharging = b) }
            }
            SwitchRow("Enabled", pair.enabled) { b ->
                vm.update { it.copy(enabled = b) }
            }

            OutlinedTextField(
                value = pair.includeFilter,
                onValueChange = { v -> vm.update { it.copy(includeFilter = v) } },
                label = { Text("Include patterns (e.g. *.jpg, *.png)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pair.excludeFilter,
                onValueChange = { v -> vm.update { it.copy(excludeFilter = v) } },
                label = { Text("Exclude patterns (e.g. *.tmp, .DS_Store)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            DropdownField(
                label = "Schedule",
                options = ScheduleMode.entries,
                selected = pair.scheduleMode,
                optionLabel = { it.label },
                onSelected = { m -> vm.update { it.copy(scheduleMode = m) } }
            )

            when (pair.scheduleMode) {
                ScheduleMode.MANUAL -> {
                    Text(
                        "Runs only when you tap Sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ScheduleMode.INTERVAL -> {
                    OutlinedTextField(
                        value = if (pair.scheduleMinutes == 0) "" else pair.scheduleMinutes.toString(),
                        onValueChange = { v ->
                            vm.update { it.copy(scheduleMinutes = v.filter(Char::isDigit).toIntOrNull() ?: 0) }
                        },
                        label = { Text("Every N minutes (min 15)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ScheduleMode.DAILY -> {
                    OutlinedButton(
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, h, m -> vm.update { it.copy(dailyHour = h, dailyMinute = m) } },
                                pair.dailyHour, pair.dailyMinute, true
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Time: %02d:%02d".format(pair.dailyHour, pair.dailyMinute))
                    }
                    Text(
                        "On these days:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DayOfWeekRow(pair.daysOfWeek) { mask -> vm.update { it.copy(daysOfWeek = mask) } }
                }
            }

            if (pair.id != 0L) {
                OutlinedButton(
                    onClick = { vm.delete(onBack) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete folder pair") }
            }
        }
    }

    if (showLocalPicker) {
        FolderPickerDialog(
            initial = pair.localFolder,
            onDismiss = { showLocalPicker = false },
            onSelect = { path -> vm.update { it.copy(localFolder = path) }; showLocalPicker = false }
        )
    }
    if (showRemotePicker) {
        FolderPickerDialog(
            initial = pair.remoteFolder,
            onDismiss = { showRemotePicker = false },
            onSelect = { path -> vm.update { it.copy(remoteFolder = path) }; showRemotePicker = false }
        )
    }
    if (showAccountPicker && selectedAccount != null) {
        RemoteFolderPickerDialog(
            account = selectedAccount,
            initialPath = pair.remoteFolder,
            onDismiss = { showAccountPicker = false },
            onSelect = { path -> vm.update { it.copy(remoteFolder = path) }; showAccountPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayOfWeekRow(mask: Int, onChange: (Int) -> Unit) {
    // Bit i = Calendar.DAY_OF_WEEK - 1: 0=Sun … 6=Sat.
    val labels = listOf("S", "M", "T", "W", "T", "F", "S")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { i, lbl ->
            val on = (mask shr i) and 1 == 1
            FilterChip(
                selected = on,
                onClick = { onChange(mask xor (1 shl i)) },
                label = { Text(lbl) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FolderPickerDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val root = remember {
        val f = File(initial)
        when {
            initial.isBlank() -> Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
            f.isDirectory -> f
            f.parentFile != null -> f.parentFile!!
            else -> Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
        }
    }
    var current by remember { mutableStateOf(root) }
    val subDirs = remember(current) {
        current.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(current.absolutePath, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
        },
        text = {
            LazyColumn(Modifier.height(320.dp)) {
                current.parentFile?.let { parent ->
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { current = parent }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null)
                            Text("  ..  (up)", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                items(subDirs, key = { it.absolutePath }) { dir ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { current = dir }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null)
                        Text(dir.name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (subDirs.isEmpty()) {
                    item { Text("(no sub-folders)", Modifier.padding(vertical = 12.dp)) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSelect(current.absolutePath) }) { Text("Select this folder") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Browses folders on a remote account (SMB shows shares first) so the user can pick a path. */
@Composable
private fun RemoteFolderPickerDialog(
    account: Account,
    initialPath: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { ExplorerRepository(context) }
    var relDir by remember { mutableStateOf(initialPath.trim('/')) }
    var folders by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var located by remember { mutableStateOf(false) }

    LaunchedEffect(account.id) {
        located = false
        try {
            repo.setLocation(ExplorerLocation.Remote(account.id))
            located = true
        } catch (e: Exception) {
            error = e.message ?: "Cannot open account"
            loading = false
        }
    }
    LaunchedEffect(relDir, located) {
        if (!located) return@LaunchedEffect
        loading = true
        error = null
        try {
            folders = repo.list(relDir).filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            error = e.message ?: "Cannot list folder"
            folders = emptyList()
        }
        loading = false
    }
    DisposableEffect(Unit) {
        onDispose { Thread { runCatching { repo.release() } }.start() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "${account.name}:/$relDir",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall
            )
        },
        text = {
            LazyColumn(Modifier.height(320.dp)) {
                if (relDir.isNotEmpty()) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { relDir = relDir.substringBeforeLast('/', "") }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null)
                            Text("  ..  (up)", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                items(folders, key = { it.relPath }) { dir ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { relDir = dir.relPath }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null)
                        Text(dir.name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (loading) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) { CircularProgressIndicator() }
                    }
                }
                error?.let { msg ->
                    item {
                        Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                    }
                }
                if (!loading && error == null && folders.isEmpty()) {
                    item {
                        val empty = if (relDir.isEmpty()) "(no shares/folders found)" else "(no sub-folders)"
                        Text(empty, Modifier.padding(vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSelect(relDir) }) { Text("Select this folder") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
