package com.opensync.foldersync.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.opensync.foldersync.ui.common.verticalScrollbar
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opensync.foldersync.Graph
import com.opensync.foldersync.data.Account
import com.opensync.foldersync.data.AccountType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class AccountsViewModel : ViewModel() {
    private val db = Graph.database
    val accounts = db.accountDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    openDrawer: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    vm: AccountsViewModel = viewModel()
) {
    val accounts by vm.accounts.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add account")
            }
        }
    ) { inner ->
        if (accounts.isEmpty()) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No accounts yet.\nAdd an FTP, SFTP or WebDAV server.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                modifier = Modifier.padding(inner).verticalScrollbar(listState),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(accounts, key = { it.id }) { account ->
                    AccountCard(account, onClick = { onEdit(account.id) })
                }
            }
        }
    }
}

/** A non-sensitive one-line summary for the accounts list (never shows secrets/keys). */
private fun accountSubtitle(account: Account): String = when (account.type) {
    AccountType.LOCAL -> account.basePath.ifBlank { "/" }
    // host holds the client secret for Google Drive — never show it here.
    AccountType.GOOGLE_DRIVE -> "Drive · ${account.basePath.ifBlank { "My Drive" }}"
    AccountType.DROPBOX -> "Dropbox · ${account.basePath.ifBlank { "app folder" }}"
    AccountType.ONEDRIVE -> "OneDrive · ${account.basePath.ifBlank { "root" }}"
    // username is the access key ID (not the secret); show the endpoint + bucket instead.
    AccountType.S3 -> "${account.host.ifBlank { "?" }} · ${account.basePath.ifBlank { "bucket" }}"
    else -> "${account.username.ifBlank { "—" }}@${account.host.ifBlank { "?" }}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountCard(account: Account, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(account.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleMedium)
            Text(account.type.label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(
                accountSubtitle(account),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
