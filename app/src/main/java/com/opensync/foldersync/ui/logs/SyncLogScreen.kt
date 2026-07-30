package com.opensync.foldersync.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.opensync.foldersync.ui.common.verticalScrollbar
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.opensync.foldersync.data.SyncLog
import com.opensync.foldersync.ui.formatBytes
import com.opensync.foldersync.ui.formatTimestamp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SyncLogViewModel : ViewModel() {
    private val db = Graph.database
    val logs = db.syncLogDao().observeRecent(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clear() = viewModelScope.launch { db.syncLogDao().clear() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncLogScreen(
    openDrawer: () -> Unit,
    vm: SyncLogViewModel = viewModel()
) {
    val logs by vm.logs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync log") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                },
                actions = {
                    IconButton(onClick = { vm.clear() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear log")
                    }
                }
            )
        }
    ) { inner ->
        if (logs.isEmpty()) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No sync runs yet.",
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(logs, key = { it.id }) { log -> LogCard(log) }
            }
        }
    }
}

@Composable
private fun LogCard(log: SyncLog) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Icon(
                imageVector = if (log.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = if (log.success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 12.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(log.pairName.ifBlank { "(pair)" }, style = MaterialTheme.typography.titleSmall)
                Text(formatTimestamp(log.startTime), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(log.message, style = MaterialTheme.typography.bodyMedium)
                if (log.success) {
                    Text(
                        "${log.filesCopied} copied • ${log.filesDeleted} deleted • ${formatBytes(log.bytesTransferred)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
