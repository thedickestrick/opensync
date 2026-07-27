package com.opensync.foldersync.ui.pairs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opensync.foldersync.Graph
import com.opensync.foldersync.data.FolderPair
import com.opensync.foldersync.ui.components.StoragePermissionBanner
import com.opensync.foldersync.ui.formatTimestamp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FolderPairsViewModel : ViewModel() {
    private val db = Graph.database

    val pairs = db.folderPairDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun syncNow(id: Long) = Graph.syncManager.enqueueOneTime(id)

    fun setEnabled(pair: FolderPair, enabled: Boolean) = viewModelScope.launch {
        val updated = pair.copy(enabled = enabled)
        db.folderPairDao().update(updated)
        Graph.syncManager.schedulePair(updated)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPairsScreen(
    openDrawer: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    vm: FolderPairsViewModel = viewModel()
) {
    val pairs by vm.pairs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folder pairs") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "Add folder pair")
            }
        }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            StoragePermissionBanner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (pairs.isEmpty()) {
                EmptyMessage(
                    Modifier.weight(1f),
                    "No folder pairs yet.\nTap + to create your first sync."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pairs, key = { it.id }) { pair ->
                        FolderPairCard(
                            pair = pair,
                            onClick = { onEdit(pair.id) },
                            onSync = { vm.syncNow(pair.id) },
                            onToggle = { vm.setEnabled(pair, it) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPairCard(
    pair: FolderPair,
    onClick: () -> Unit,
    onSync: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pair.name.ifBlank { "(unnamed)" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Switch(checked = pair.enabled, onCheckedChange = onToggle)
            }
            Text(
                pair.localFolder,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${directionArrow(pair)} ${remoteLabel(pair)}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatTimestamp(pair.lastSyncTime)}  •  ${pair.lastStatus.ifBlank { "Not synced" }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onSync) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Sync now")
                }
            }
        }
    }
}

private fun directionArrow(pair: FolderPair): String = when (pair.direction.name) {
    "TO_REMOTE" -> "→"
    "FROM_REMOTE" -> "←"
    else -> "↔"
}

private fun remoteLabel(pair: FolderPair): String {
    val target = if (pair.remoteAccountId == null) "Local" else "Remote"
    return "$target: ${pair.remoteFolder.ifBlank { "/" }}"
}

@Composable
private fun EmptyMessage(modifier: Modifier = Modifier, text: String) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
