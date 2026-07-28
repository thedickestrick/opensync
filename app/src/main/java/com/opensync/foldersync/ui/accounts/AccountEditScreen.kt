package com.opensync.foldersync.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opensync.foldersync.Graph
import com.opensync.foldersync.crypto.CryptoManager
import com.opensync.foldersync.data.Account
import com.opensync.foldersync.data.AccountType
import com.opensync.foldersync.provider.DropboxAuth
import com.opensync.foldersync.provider.GoogleDriveAuth
import com.opensync.foldersync.provider.OneDriveAuth
import com.opensync.foldersync.provider.ProviderFactory
import com.opensync.foldersync.ui.components.DropdownField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data object Success : TestState
    data class Failure(val message: String) : TestState
}

class AccountEditViewModel : ViewModel() {
    private val db = Graph.database

    private val _account = MutableStateFlow(Account(name = "", type = AccountType.SFTP))
    val account = _account.asStateFlow()

    private val _password = MutableStateFlow("")
    val password = _password.asStateFlow()

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test = _test.asStateFlow()

    private var loaded = false

    fun load(id: Long) {
        if (loaded) return
        loaded = true
        if (id > 0) viewModelScope.launch {
            db.accountDao().getById(id)?.let {
                _account.value = it
                _password.value = CryptoManager.decrypt(it.passwordEnc)
            }
        }
    }

    fun update(transform: (Account) -> Account) {
        _account.value = transform(_account.value)
        _test.value = TestState.Idle
    }

    fun setPassword(p: String) {
        _password.value = p
        _test.value = TestState.Idle
    }

    fun testConnection() = viewModelScope.launch {
        _test.value = TestState.Testing
        val error = withContext(Dispatchers.IO) {
            try {
                val acc = _account.value.copy(passwordEnc = CryptoManager.encrypt(_password.value))
                val provider = ProviderFactory.forAccount(acc, "")
                provider.connect()
                provider.close()
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        }
        _test.value = if (error == null) TestState.Success else TestState.Failure(error)
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val acc = _account.value.copy(passwordEnc = CryptoManager.encrypt(_password.value))
        if (acc.id == 0L) db.accountDao().insert(acc) else db.accountDao().update(acc)
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val acc = _account.value
        if (acc.id != 0L) db.accountDao().delete(acc)
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditScreen(
    accountId: Long,
    onBack: () -> Unit,
    vm: AccountEditViewModel = viewModel()
) {
    LaunchedEffect(accountId) { vm.load(accountId) }
    val account by vm.account.collectAsState()
    val password by vm.password.collectAsState()
    val testState by vm.test.collectAsState()
    var showPassword by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val type = account.type
    val isRemote = type != AccountType.LOCAL

    // Pick up the refresh token once the OAuth redirect completes.
    val dbxToken by DropboxAuth.refreshToken.collectAsState()
    LaunchedEffect(dbxToken) {
        if (type == AccountType.DROPBOX) dbxToken?.let { vm.setPassword(it); DropboxAuth.refreshToken.value = null }
    }
    val odToken by OneDriveAuth.refreshToken.collectAsState()
    LaunchedEffect(odToken) {
        if (type == AccountType.ONEDRIVE) odToken?.let { vm.setPassword(it); OneDriveAuth.refreshToken.value = null }
    }
    val gdToken by GoogleDriveAuth.refreshToken.collectAsState()
    LaunchedEffect(gdToken) {
        if (type == AccountType.GOOGLE_DRIVE) gdToken?.let { vm.setPassword(it); GoogleDriveAuth.refreshToken.value = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (account.id == 0L) "New account" else "Edit account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { vm.save(onBack) },
                        enabled = account.name.isNotBlank()
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
                value = account.name,
                onValueChange = { v -> vm.update { it.copy(name = v) } },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            DropdownField(
                label = "Type",
                options = AccountType.entries,
                selected = type,
                optionLabel = { it.label },
                onSelected = { t -> vm.update { acc -> acc.copy(type = t, useTls = if (t == AccountType.S3) true else acc.useTls) } }
            )

            if (type == AccountType.DROPBOX || type == AccountType.ONEDRIVE) {
                val isDropbox = type == AccountType.DROPBOX
                OAuthSection(
                    instructions = if (isDropbox)
                        "Create an app at dropbox.com/developers (Scoped access → App folder), add redirect " +
                            "URI \"${DropboxAuth.REDIRECT}\", enable files.content read/write, then paste the App key."
                    else
                        "Register an app in the Azure portal (App registrations → any org + personal accounts), " +
                            "add a Mobile/desktop redirect URI \"${OneDriveAuth.REDIRECT}\" and the Files.ReadWrite " +
                            "delegated permission, then paste the Application (client) ID.",
                    keyLabel = if (isDropbox) "Dropbox app key" else "Application (client) ID",
                    providerName = if (isDropbox) "Dropbox" else "OneDrive",
                    appKey = account.username,
                    connected = password.isNotBlank(),
                    onAppKey = { v -> vm.update { it.copy(username = v) } },
                    onConnect = {
                        val url = if (isDropbox) DropboxAuth.begin(account.username.trim())
                        else OneDriveAuth.begin(account.username.trim())
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }
                )
            } else if (type == AccountType.GOOGLE_DRIVE) {
                GoogleDriveSection(
                    clientId = account.username,
                    clientSecret = account.host,
                    connected = password.isNotBlank(),
                    onClientId = { v -> vm.update { it.copy(username = v) } },
                    onClientSecret = { v -> vm.update { it.copy(host = v) } },
                    onConnect = { GoogleDriveAuth.startLogin(context, account.username.trim(), account.host.trim()) }
                )
            } else if (isRemote) {
                OutlinedTextField(
                    value = account.host,
                    onValueChange = { v -> vm.update { it.copy(host = v) } },
                    label = {
                        Text(
                            when (type) {
                                AccountType.WEBDAV -> "Server URL (https://host/path)"
                                AccountType.SMB -> "Server (IP or hostname)"
                                AccountType.S3 -> "Endpoint (e.g. s3.us-west-002.backblazeb2.com)"
                                else -> "Host"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (type == AccountType.SMB || type == AccountType.S3) {
                    OutlinedTextField(
                        value = account.domain,
                        onValueChange = { v -> vm.update { it.copy(domain = v) } },
                        label = {
                            Text(
                                if (type == AccountType.S3) "Region (e.g. us-east-1, us-west-002, auto)"
                                else "Windows domain (blank for workgroup / local)"
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = if (account.port == 0) "" else account.port.toString(),
                    onValueChange = { v -> vm.update { it.copy(port = v.filter(Char::isDigit).toIntOrNull() ?: 0) } },
                    label = { Text("Port (blank = default ${account.defaultPort()})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = account.username,
                    onValueChange = { v -> vm.update { it.copy(username = v) } },
                    label = { Text(if (type == AccountType.S3) "Access key ID" else "Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { vm.setPassword(it) },
                    label = { Text(if (type == AccountType.S3) "Secret access key" else "Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            OutlinedTextField(
                value = account.basePath,
                onValueChange = { v -> vm.update { it.copy(basePath = v) } },
                label = {
                    Text(
                        when (type) {
                            AccountType.LOCAL -> "Base folder (absolute path)"
                            AccountType.SMB -> "Path: blank or / lists all shares, or /Share/Folder"
                            AccountType.S3 -> "Bucket name"
                            AccountType.DROPBOX -> "Base folder in Dropbox (blank = app root)"
                            AccountType.ONEDRIVE -> "Base folder in OneDrive (blank = root)"
                            AccountType.GOOGLE_DRIVE -> "Base folder in Drive (blank = My Drive root)"
                            else -> "Base path on server"
                        }
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (type == AccountType.FTP || type == AccountType.FTPS) {
                SwitchRow("Passive mode", account.passiveMode) { b -> vm.update { it.copy(passiveMode = b) } }
            }
            if (type == AccountType.WEBDAV || type == AccountType.S3) {
                SwitchRow("Use HTTPS", account.useTls) { b -> vm.update { it.copy(useTls = b) } }
            }
            if (type == AccountType.FTPS || type == AccountType.WEBDAV || type == AccountType.S3) {
                SwitchRow("Accept self-signed certificates", account.allowSelfSigned) { b ->
                    vm.update { it.copy(allowSelfSigned = b) }
                }
            }

            if (isRemote) {
                Button(
                    onClick = { vm.testConnection() },
                    enabled = testState != TestState.Testing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testState == TestState.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text("Test connection")
                }
                when (val s = testState) {
                    is TestState.Success -> Text(
                        "✓ Connection successful",
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    is TestState.Failure -> Text(
                        "✗ ${s.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> {}
                }
            }

            if (account.id != 0L) {
                OutlinedButton(
                    onClick = { vm.delete(onBack) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete account") }
            }
        }
    }
}

@Composable
private fun OAuthSection(
    instructions: String,
    keyLabel: String,
    providerName: String,
    appKey: String,
    connected: Boolean,
    onAppKey: (String) -> Unit,
    onConnect: () -> Unit
) {
    Text(
        instructions,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = appKey,
        onValueChange = onAppKey,
        label = { Text(keyLabel) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = onConnect,
        enabled = appKey.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (connected) "Reconnect $providerName" else "Connect $providerName") }
    Text(
        if (connected) "✓ Connected" else "Not connected",
        color = if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun GoogleDriveSection(
    clientId: String,
    clientSecret: String,
    connected: Boolean,
    onClientId: (String) -> Unit,
    onClientSecret: (String) -> Unit,
    onConnect: () -> Unit
) {
    Text(
        "In Google Cloud Console: enable the Google Drive API, create an OAuth client of type " +
            "\"Desktop app\", and add yourself as a test user. Paste the Client ID and Client secret, " +
            "then connect (a browser opens and returns automatically).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = clientId,
        onValueChange = onClientId,
        label = { Text("Client ID") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = clientSecret,
        onValueChange = onClientSecret,
        label = { Text("Client secret") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = onConnect,
        enabled = clientId.isNotBlank() && clientSecret.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (connected) "Reconnect Google Drive" else "Connect Google Drive") }
    Text(
        if (connected) "✓ Connected" else "Not connected",
        color = if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
