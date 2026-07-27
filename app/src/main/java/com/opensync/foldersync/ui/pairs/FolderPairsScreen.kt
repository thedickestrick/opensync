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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.opensync.foldersync.data.ScheduleMode
import com.opensync.foldersync.sync.ActiveSync
import com.opensync.foldersync.sync.SyncProgressBus
import com.opensync.foldersync.ui.components.StoragePermissionBanner
import com.opensync.foldersync.ui.formatTimestamp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FolderPairsViewModel : ViewModel() {
    private val db = Graph.database

    val pairs = db.folderPairDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Live progress for currently running backups, keyed by pair id. */
    val activeSyncs = SyncProgressBus.active
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun syncNow(id: Long) = Graph.syncManager.enqueueOneTime(id)

    fun cancelSync(pairId: Long) = viewModelScope.launch {
        Graph.syncManager.cancelSync(pairId)
        // Cancelling clears any recurring schedule too; restore it for scheduled pairs.
        db.folderPairDao().getById(pairId)?.let { Graph.syncManager.schedulePair(it) }
    }

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
    val activeSyncs by vm.activeSyncs.collectAsState()

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
                            active = activeSyncs[pair.id],
                            onClick = { onEdit(pair.id) },
                            onSync = { vm.syncNow(pair.id) },
                            onCancel = { vm.cancelSync(pair.id) },
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
    active: ActiveSync?,
    onClick: () -> Unit,
    onSync: () -> Unit,
    onCancel: () -> Unit,
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
            Text(
                "⏱  ${scheduleLabel(pair)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (active != null) {
                ActiveSyncSection(active = active, onCancel = onCancel)
            } else {
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
}

/** Live progress row shown on a pair card while it is syncing: bar, current file, and cancel. */
@Composable
private fun ActiveSyncSection(active: ActiveSync, onCancel: () -> Unit) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (active.indeterminate) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { active.fraction }, modifier = Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    active.message.ifBlank { "Syncing…" },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val counts = if (active.total > 0) "${active.done} / ${active.total} files" else "Scanning…"
                Text(
                    counts,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
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

private fun scheduleLabel(pair: FolderPair): String = when (pair.scheduleMode) {
    ScheduleMode.MANUAL -> "Manual"
    ScheduleMode.INTERVAL ->
        if (pair.scheduleMinutes > 0) "Every ${pair.scheduleMinutes} min" else "Manual"
    ScheduleMode.DAILY -> "Daily at %02d:%02d%s".format(
        pair.dailyHour, pair.dailyMinute, daysSuffix(pair.daysOfWeek)
    )
}

private fun daysSuffix(mask: Int): String {
    if (mask == 0 || mask == 0b1111111) return ""
    val letters = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
    val on = letters.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }
    return " (${on.joinToString(",")})"
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
