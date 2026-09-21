package com.opensync.foldersync.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import com.opensync.foldersync.ui.common.verticalScrollbar
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opensync.foldersync.Graph
import com.opensync.foldersync.backup.SettingsBackup
import com.opensync.foldersync.ui.PermissionUtil
import com.opensync.foldersync.update.AppPrefs
import com.opensync.foldersync.update.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UpdateUiState(
    val owner: String,
    val repo: String,
    val current: String,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val status: String? = null,
    val available: UpdateChecker.Release? = null
)

class SettingsViewModel : ViewModel() {

    private val prefs = AppPrefs(Graph.appContext)

    private val _update = MutableStateFlow(
        UpdateUiState(
            owner = prefs.updateOwner,
            repo = prefs.updateRepo,
            current = UpdateChecker.currentVersion(Graph.appContext)
        )
    )
    val update = _update.asStateFlow()

    fun syncAllNow() = viewModelScope.launch {
        Graph.database.folderPairDao().getAll().forEach { Graph.syncManager.enqueueOneTime(it.id) }
    }

    fun rescheduleAll() = viewModelScope.launch { Graph.syncManager.rescheduleAll() }

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus = _backupStatus.asStateFlow()

    /** Write accounts, folder pairs and app settings to [dest], sealed under [passphrase]. */
    fun exportSettings(dest: Uri, passphrase: String) = viewModelScope.launch {
        _backupStatus.value = "Saving…"
        _backupStatus.value = runCatching {
            val bytes = SettingsBackup.export(passphrase.toCharArray())
            withContext(Dispatchers.IO) {
                (Graph.appContext.contentResolver.openOutputStream(dest, "wt")
                    ?: throw IllegalStateException("Can't write to that location")).use { it.write(bytes) }
            }
            "Settings saved. Keep the file and its passphrase somewhere you can reach from the new phone."
        }.getOrElse { "Couldn't save settings: ${it.message}" }
    }

    fun importSettings(src: Uri, passphrase: String) = viewModelScope.launch {
        _backupStatus.value = "Loading…"
        _backupStatus.value = runCatching {
            val bytes = withContext(Dispatchers.IO) {
                Graph.appContext.contentResolver.openInputStream(src)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Can't read that file")
            }
            val result = SettingsBackup.restore(bytes, passphrase.toCharArray())
            // The update source may have just been filled in.
            _update.update { it.copy(owner = prefs.updateOwner, repo = prefs.updateRepo) }
            result.summary()
        }.getOrElse { it.message ?: "Couldn't load settings" }
    }

    fun setOwner(v: String) {
        prefs.updateOwner = v.trim()
        _update.update { it.copy(owner = v, status = null, available = null) }
    }

    fun setRepo(v: String) {
        prefs.updateRepo = v.trim()
        _update.update { it.copy(repo = v, status = null, available = null) }
    }

    fun checkForUpdates() {
        val s = _update.value
        if (s.owner.isBlank() || s.repo.isBlank()) {
            _update.update { it.copy(status = "Enter a GitHub owner and repository first") }
            return
        }
        viewModelScope.launch {
            _update.update { it.copy(checking = true, status = null, available = null) }
            try {
                val release = UpdateChecker.latestRelease(s.owner.trim(), s.repo.trim())
                if (release == null) {
                    _update.update {
                        it.copy(checking = false, available = null,
                            status = "No releases published in ${s.owner}/${s.repo} yet")
                    }
                    return@launch
                }
                val newer = UpdateChecker.isNewer(release.tag, s.current)
                _update.update {
                    it.copy(
                        checking = false,
                        available = if (newer && release.apkUrl != null) release else null,
                        status = when {
                            release.apkUrl == null -> "Latest release (${release.tag}) has no APK asset"
                            newer -> "Update available: ${release.tag}"
                            else -> "You're up to date (${s.current})"
                        }
                    )
                }
            } catch (e: Exception) {
                _update.update { it.copy(checking = false, status = "Check failed: ${e.message}") }
            }
        }
    }

    fun downloadAndInstall(context: android.content.Context) {
        val release = _update.value.available ?: return
        val url = release.apkUrl ?: return
        viewModelScope.launch {
            _update.update { it.copy(downloading = true, progress = 0, status = "Downloading…") }
            try {
                val apk = File(File(Graph.appContext.cacheDir, "update"), "opensync-${release.tag}.apk")
                UpdateChecker.download(url, apk) { p -> _update.update { it.copy(progress = p) } }
                _update.update { it.copy(downloading = false, status = "Starting installer…") }
                UpdateChecker.installApk(context, apk)
            } catch (e: Exception) {
                _update.update { it.copy(downloading = false, status = "Download failed: ${e.message}") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    openDrawer: () -> Unit,
    vm: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val updateState by vm.update.collectAsState()

    var filesGranted by remember { mutableStateOf(PermissionUtil.hasAllFilesAccess()) }
    val filesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { filesGranted = PermissionUtil.hasAllFilesAccess() }

    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    val installSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (UpdateChecker.canInstall(context)) vm.downloadAndInstall(context)
    }

    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBattery(context)) }
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { batteryUnrestricted = isIgnoringBattery(context) }

    val backupStatus by vm.backupStatus.collectAsState()
    // Saving asks for the passphrase first, then where to put the file; loading is the other way round.
    var askExportPassphrase by remember { mutableStateOf(false) }
    var exportPassphrase by remember { mutableStateOf("") }
    var importSource by remember { mutableStateOf<Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) vm.exportSettings(uri, exportPassphrase)
        exportPassphrase = ""
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> importSource = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                }
            )
        }
    ) { inner ->
        val scrollState = rememberScrollState()
        Column(
            Modifier
                .padding(inner)
                .verticalScrollbar(scrollState)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            SettingRow(
                title = "All files access",
                subtitle = if (filesGranted) "Granted" else "Required to browse and sync any folder",
                actionLabel = if (filesGranted) null else "Grant",
                onAction = { filesLauncher.launch(PermissionUtil.allFilesIntent(context)) }
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SettingRow(
                    title = "Notifications",
                    subtitle = if (notifGranted) "Granted" else "Show sync progress notifications",
                    actionLabel = if (notifGranted) null else "Enable",
                    onAction = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                )
            }

            HorizontalDivider()
            Text("Backup", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { vm.syncAllNow() }, modifier = Modifier.fillMaxWidth()) {
                Text("Sync all pairs now")
            }
            OutlinedButton(onClick = { vm.rescheduleAll() }, modifier = Modifier.fillMaxWidth()) {
                Text("Re-apply background schedules")
            }

            HorizontalDivider()
            Text("Move to a new phone", style = MaterialTheme.typography.titleMedium)
            Text(
                "Save your accounts (with their passwords), folder pairs and app settings to one " +
                    "passphrase-protected file, then load it on the new phone. Loading adds to what's " +
                    "already there — it never deletes anything. The vault and sync history aren't included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { askExportPassphrase = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Save settings to a file")
            }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Load settings from a file")
            }
            backupStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            HorizontalDivider()
            Text("Background reliability", style = MaterialTheme.typography.titleMedium)
            Text(
                "Scheduled backups run through Android's WorkManager, so they keep working when the app " +
                    "is closed and after a reboot. On some phones battery optimization can still pause them — " +
                    "allow unrestricted background use for reliable syncing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingRow(
                title = "Battery optimization",
                subtitle = if (batteryUnrestricted) "Unrestricted — backups can run anytime"
                else "Optimized — background syncs may be delayed or skipped",
                actionLabel = if (batteryUnrestricted) null else "Allow",
                onAction = { runCatching { batteryLauncher.launch(ignoreBatteryIntent(context)) } }
            )
            Text(
                "Samsung: also go to Settings → Battery → Background usage limits, remove OpenSync from " +
                    "\"Sleeping apps\"/\"Deep sleeping apps\", and turn off \"Put unused apps to sleep\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()
            Text("App updates (GitHub)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Point this at the GitHub repo where you publish releases. Each release should attach the new .apk.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = updateState.owner,
                onValueChange = vm::setOwner,
                label = { Text("GitHub owner / username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = updateState.repo,
                onValueChange = vm::setRepo,
                label = { Text("Repository name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Installed version: ${updateState.current}", style = MaterialTheme.typography.bodyMedium)

            Button(
                onClick = { vm.checkForUpdates() },
                enabled = !updateState.checking && !updateState.downloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (updateState.checking) {
                    CircularProgressIndicator(
                        Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Text("Check for updates")
            }

            updateState.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            if (updateState.available != null) {
                if (updateState.downloading) {
                    LinearProgressIndicator(
                        progress = { updateState.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${updateState.progress}%", style = MaterialTheme.typography.labelMedium)
                } else {
                    Button(
                        onClick = {
                            if (UpdateChecker.canInstall(context)) {
                                vm.downloadAndInstall(context)
                            } else {
                                installSettingsLauncher.launch(UpdateChecker.unknownSourcesIntent(context))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Download & install ${updateState.available!!.tag}")
                    }
                }
            }

            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("OpenSync ${updateState.current}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "A free, ad-free file manager, gallery and folder-sync app. " +
                    "Local, SMB (Windows), FTP, SFTP and WebDAV.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (askExportPassphrase) {
        PassphraseDialog(
            title = "Protect the backup",
            message = "The file holds your account passwords, so it's encrypted. You'll need this " +
                "passphrase to load it — it can't be recovered.",
            confirm = true,
            actionLabel = "Next",
            onDismiss = { askExportPassphrase = false },
            onDone = { pass ->
                askExportPassphrase = false
                exportPassphrase = pass
                exportLauncher.launch("opensync-settings.osbackup")
            }
        )
    }
    importSource?.let { src ->
        PassphraseDialog(
            title = "Backup passphrase",
            message = "Enter the passphrase this backup was saved with.",
            confirm = false,
            actionLabel = "Load",
            onDismiss = { importSource = null },
            onDone = { pass ->
                importSource = null
                vm.importSettings(src, pass)
            }
        )
    }
}

@Composable
private fun PassphraseDialog(
    title: String,
    message: String,
    confirm: Boolean,
    actionLabel: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit
) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val mismatch = confirm && again.isNotEmpty() && pass != again
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (confirm) {
                    OutlinedTextField(
                        value = again,
                        onValueChange = { again = it },
                        label = { Text("Repeat passphrase") },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDone(pass) },
                enabled = pass.isNotEmpty() && (!confirm || pass == again)
            ) { Text(actionLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private fun hasNotificationPermission(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else true

private fun isIgnoringBattery(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun ignoreBatteryIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
