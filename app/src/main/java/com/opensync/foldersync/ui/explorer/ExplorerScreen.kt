package com.opensync.foldersync.ui.explorer

import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.opensync.foldersync.gallery.isImageName
import com.opensync.foldersync.gallery.isVideoName
import com.opensync.foldersync.ui.common.DetailsInfo
import com.opensync.foldersync.ui.common.FileDetailsDialog
import com.opensync.foldersync.ui.common.fileTypeLabel
import com.opensync.foldersync.ui.common.verticalScrollbar
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.opensync.foldersync.data.Account
import com.opensync.foldersync.files.ExplorerLocation
import com.opensync.foldersync.files.SortBy
import com.opensync.foldersync.provider.RemoteFile
import com.opensync.foldersync.ui.components.StoragePermissionBanner
import com.opensync.foldersync.ui.formatBytes
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    openDrawer: () -> Unit,
    onOpenPdf: (String) -> Unit = {},
    vm: ExplorerViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var detailsTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.openFile.collect { file -> openFile(context, file, onOpenPdf) }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    BackHandler(enabled = state.inSelectionMode || state.canGoUp) {
        if (state.inSelectionMode) vm.clearSelection() else vm.up()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.inSelectionMode) {
                SelectionBar(
                    count = state.selection.size,
                    onClose = vm::clearSelection,
                    onCopy = vm::copySelected,
                    onCut = vm::cutSelected,
                    onDelete = { showDeleteConfirm = true },
                    onRename = { renameTarget = vm.singleSelected(state) },
                    onDetails = { detailsTarget = vm.singleSelected(state) },
                    onSelectAll = vm::selectAll,
                    canRename = state.selection.size == 1
                )
            } else {
                ExplorerTopBar(
                    state = state,
                    accounts = accounts,
                    openDrawer = openDrawer,
                    onPickLocation = vm::openLocation,
                    onSort = vm::setSort,
                    onToggleHidden = vm::toggleHidden,
                    onSelectAll = vm::selectAll,
                    onRefresh = vm::refresh
                )
            }
        },
        floatingActionButton = {
            if (!state.inSelectionMode) {
                FloatingActionButton(onClick = { showNewFolder = true }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                }
            }
        }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            StoragePermissionBanner(Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            Breadcrumb(relDir = state.relDir, rootLabel = state.locationLabel, onNavigate = vm::navigate)

            if (state.hasClipboard) {
                ActionBar(
                    text = "Clipboard ready",
                    confirmLabel = "Paste here",
                    onConfirm = vm::paste,
                    onCancel = vm::clearClipboard
                )
            }
            if (state.pendingShareCount > 0) {
                ActionBar(
                    text = "Save ${state.pendingShareCount} shared file(s) here",
                    confirmLabel = "Save",
                    onConfirm = vm::saveShared,
                    onCancel = vm::cancelShare
                )
            }

            state.busyMessage?.let { msg ->
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (state.entries.isEmpty() && !state.loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val listState = rememberLazyListState()
                LazyColumn(Modifier.weight(1f).verticalScrollbar(listState), state = listState) {
                    items(state.entries, key = { it.relPath }) { item ->
                        ExplorerRow(
                            item = item,
                            selected = item.relPath in state.selection,
                            isLocal = state.location == ExplorerLocation.LocalRoot,
                            selectionMode = state.inSelectionMode,
                            onClick = { if (state.inSelectionMode) vm.toggleSelect(item) else vm.open(item) },
                            onLongClick = { vm.toggleSelect(item) }
                        )
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        TextInputDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { showNewFolder = false },
            onConfirm = { name -> vm.newFolder(name); showNewFolder = false }
        )
    }
    renameTarget?.let { target ->
        TextInputDialog(
            title = "Rename",
            initial = target.name,
            confirmLabel = "Rename",
            onDismiss = { renameTarget = null },
            onConfirm = { name -> vm.rename(target, name); renameTarget = null }
        )
    }
    detailsTarget?.let { target ->
        val localPath = vm.localAbsolutePath(target)
        FileDetailsDialog(
            info = DetailsInfo(
                name = target.name,
                isDirectory = target.isDirectory,
                typeLabel = fileTypeLabel(target.name, target.isDirectory),
                location = "${state.locationLabel}/${target.relPath.substringBeforeLast('/', "")}".trimEnd('/'),
                size = target.size,
                modified = target.modifiedTime,
                localPath = localPath,
                isImage = !target.isDirectory && isImageName(target.name),
                isVideo = !target.isDirectory && isVideoName(target.name)
            ),
            onDismiss = { detailsTarget = null }
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${state.selection.size} item(s)?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; vm.deleteSelected() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

private fun ExplorerViewModel.singleSelected(state: ExplorerUiState): RemoteFile? =
    state.entries.firstOrNull { it.relPath in state.selection }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExplorerTopBar(
    state: ExplorerUiState,
    accounts: List<Account>,
    openDrawer: () -> Unit,
    onPickLocation: (ExplorerLocation, String) -> Unit,
    onSort: (SortBy) -> Unit,
    onToggleHidden: () -> Unit,
    onSelectAll: () -> Unit,
    onRefresh: () -> Unit
) {
    var sortOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
        },
        title = {
            LocationSelector(
                label = state.locationLabel,
                accounts = accounts,
                onPick = onPickLocation
            )
        },
        actions = {
            Box {
                IconButton(onClick = { sortOpen = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sort")
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortBy.entries.forEach { s ->
                        val marker = if (state.sortBy == s) (if (state.ascending) " ↑" else " ↓") else ""
                        DropdownMenuItem(text = { Text(s.label + marker) }, onClick = { onSort(s); sortOpen = false })
                    }
                }
            }
            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(Icons.Filled.SelectAll, contentDescription = "More")
                }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    DropdownMenuItem(text = { Text("Select all") }, onClick = { onSelectAll(); overflowOpen = false })
                    DropdownMenuItem(
                        text = { Text(if (state.showHidden) "Hide hidden files" else "Show hidden files") },
                        onClick = { onToggleHidden(); overflowOpen = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Refresh") },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        onClick = { onRefresh(); overflowOpen = false }
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
    onSelectAll: () -> Unit,
    canRename: Boolean
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
        },
        title = { Text("$count selected") },
        actions = {
            if (canRename) {
                IconButton(onClick = onDetails) {
                    Icon(Icons.Filled.Info, contentDescription = "Details")
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Rename")
                }
            }
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy") }
            IconButton(onClick = onCut) { Icon(Icons.Filled.ContentCut, contentDescription = "Cut") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            IconButton(onClick = onSelectAll) { Icon(Icons.Filled.SelectAll, contentDescription = "Select all") }
        }
    )
}

@Composable
private fun LocationSelector(
    label: String,
    accounts: List<Account>,
    onPick: (ExplorerLocation, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change location")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Internal storage") },
                onClick = { onPick(ExplorerLocation.LocalRoot, "Internal storage"); expanded = false }
            )
            accounts.forEach { acc ->
                DropdownMenuItem(
                    text = { Text("${acc.name} (${acc.type.label})") },
                    onClick = { onPick(ExplorerLocation.Remote(acc.id), acc.name); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun Breadcrumb(relDir: String, rootLabel: String, onNavigate: (String) -> Unit) {
    val segments = relDir.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { onNavigate("") }) { Text(rootLabel) }
        segments.forEachIndexed { index, seg ->
            Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val path = segments.take(index + 1).joinToString("/")
            TextButton(onClick = { onNavigate(path) }) { Text(seg) }
        }
    }
}

@Composable
private fun ActionBar(text: String, confirmLabel: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerRow(
    item: RemoteFile,
    selected: Boolean,
    isLocal: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Surface(color = bg) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val model = if (isLocal && !item.isDirectory && isImage(item.name)) File("/", item.relPath) else null
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
                )
            } else {
                Icon(
                    imageVector = iconFor(item),
                    contentDescription = null,
                    tint = if (item.isDirectory) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp).padding(4.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge)
                if (!item.isDirectory) {
                    Text(formatBytes(item.size), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
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

// --- helpers ---

private fun isImage(name: String): Boolean {
    val n = name.lowercase()
    return listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif").any { n.endsWith(it) }
}

private fun isVideo(name: String): Boolean {
    val n = name.lowercase()
    return listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".3gp", ".m4v").any { n.endsWith(it) }
}

private fun isAudio(name: String): Boolean {
    val n = name.lowercase()
    return listOf(".mp3", ".flac", ".wav", ".ogg", ".m4a", ".aac").any { n.endsWith(it) }
}

private fun iconFor(item: RemoteFile) = when {
    item.isDirectory -> Icons.Filled.Folder
    isImage(item.name) -> Icons.Filled.Image
    isVideo(item.name) -> Icons.Filled.Movie
    isAudio(item.name) -> Icons.Filled.MusicNote
    else -> Icons.Filled.Description
}

private val OPEN_IMAGE_EXTS =
    setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "dng")
private val OPEN_VIDEO_EXTS =
    setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts", "flv", "wmv", "mpeg", "mpg")
private val OPEN_TEXT_EXTS =
    setOf("txt", "md", "markdown", "log", "json", "xml", "csv", "yml", "yaml", "ini", "conf", "properties")

/**
 * Opens a file tapped in OpenSync's own explorer. Types OpenSync handles (images, video, PDF, text)
 * go straight to the built-in viewers — no "Open with" chooser, ever. Anything else is handed to the
 * system with a plain ACTION_VIEW so Android's normal default applies (and remembers an "Always" pick).
 */
private fun openFile(context: android.content.Context, file: File, onOpenPdf: (String) -> Unit) {
    val ext = file.extension.lowercase()
    when {
        ext == "pdf" -> onOpenPdf(file.absolutePath)
        ext in OPEN_IMAGE_EXTS || ext in OPEN_VIDEO_EXTS ->
            launchInternal(context, file, com.opensync.foldersync.MediaViewActivity::class.java, "media_path")
        ext in OPEN_TEXT_EXTS ->
            launchInternal(context, file, com.opensync.foldersync.TextEditorActivity::class.java, "note_path")
        else -> openWithSystem(context, file)
    }
}

/** Launch one of OpenSync's own activities by file path (no chooser, no file:// exposure). */
private fun launchInternal(
    context: android.content.Context, file: File, cls: Class<*>, pathExtra: String
) {
    try {
        context.startActivity(Intent(context, cls).putExtra(pathExtra, file.absolutePath))
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun openWithSystem(context: android.content.Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // No createChooser(): a plain ACTION_VIEW lets Android use (and remember) the user's default.
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}
