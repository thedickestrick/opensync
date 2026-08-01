package com.opensync.foldersync.ui.gallery

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.opensync.foldersync.gallery.isImageName
import com.opensync.foldersync.gallery.isVideoName
import com.opensync.foldersync.ui.common.DetailsInfo
import com.opensync.foldersync.ui.common.FileDetailsDialog
import com.opensync.foldersync.ui.common.fileTypeLabel
import com.opensync.foldersync.ui.common.verticalScrollbar
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.opensync.foldersync.ui.FullscreenState
import kotlin.math.abs
import com.opensync.foldersync.data.Account
import com.opensync.foldersync.files.ExplorerLocation
import com.opensync.foldersync.gallery.Album
import com.opensync.foldersync.gallery.AlbumSort
import com.opensync.foldersync.gallery.GallerySource
import com.opensync.foldersync.gallery.MediaItem
import com.opensync.foldersync.provider.RemoteFile
import com.opensync.foldersync.ui.PermissionUtil
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    openDrawer: () -> Unit,
    vm: GalleryViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val thumbs by vm.remoteThumbs.collectAsState()
    val context = LocalContext.current
    val editScope = rememberCoroutineScope()

    var hasMedia by remember { mutableStateOf(PermissionUtil.hasMediaAccess(context)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasMedia = PermissionUtil.hasMediaAccess(context)
        if (hasMedia) vm.reloadDevice()
    }

    var sortMenuOpen by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var detailsTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var viewerDetails by remember { mutableStateOf<MediaItem?>(null) }
    var viewerDeleteItem by remember { mutableStateOf<MediaItem?>(null) }
    var viewerVaultItem by remember { mutableStateOf<MediaItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showVaultConfirm by remember { mutableStateOf(false) }

    fun launchEditor(item: MediaItem) {
        if (item.isVideo) {
            android.widget.Toast.makeText(context, "Only photos can be edited", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        editScope.launch {
            val f = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { vm.materialize(item) }.getOrNull() }
            if (f != null) {
                context.startActivity(
                    android.content.Intent(context, com.opensync.foldersync.PhotoEditorActivity::class.java)
                        .putExtra("edit_path", f.absolutePath)
                )
            } else {
                android.widget.Toast.makeText(context, "Couldn't open this photo", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = state.canBack) { vm.back() }

    Scaffold(
        topBar = {
            if (state.inSelectionMode) {
                GallerySelectionBar(
                    count = state.selection.size,
                    canRename = state.selection.size == 1,
                    onClose = vm::clearSelection,
                    onCopy = vm::copySelected,
                    onCut = vm::cutSelected,
                    onDelete = { showDeleteConfirm = true },
                    onRename = { renameTarget = vm.singleSelected() },
                    onDetails = { detailsTarget = vm.singleSelected() },
                    onMoveToVault = { showVaultConfirm = true },
                    onEdit = { vm.singleSelectedMediaItem()?.let { launchEditor(it) } },
                    onSelectAll = vm::selectAll
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        if (state.canBack) {
                            IconButton(onClick = { vm.back() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        } else {
                            IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                        }
                    },
                    title = {
                        GallerySourceSelector(
                            label = if (state.inAlbum) state.title else state.sourceLabel,
                            accounts = accounts,
                            onPick = vm::setSource
                        )
                    },
                    actions = {
                        if (state.source is GallerySource.Device && !state.inAlbum) {
                            Box {
                                IconButton(onClick = { sortMenuOpen = true }) {
                                    Icon(Icons.Filled.Sort, contentDescription = "Sort albums")
                                }
                                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                    AlbumSort.entries.forEach { option ->
                                        val marker = if (state.albumSort == option) {
                                            if (state.albumAscending) " ↑" else " ↓"
                                        } else ""
                                        DropdownMenuItem(
                                            text = { Text(option.label + marker) },
                                            onClick = { vm.setAlbumSort(option); sortMenuOpen = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (state.browsingFiles && !state.inSelectionMode && state.viewerIndex == null) {
                FloatingActionButton(onClick = { showNewFolder = true }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
                }
            }
        }
    ) { inner ->
        Column(
            Modifier.padding(top = inner.calculateTopPadding()).fillMaxSize()
        ) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (state.source is GallerySource.Device && !hasMedia) {
                MediaPermissionPrompt(onGrant = { permLauncher.launch(PermissionUtil.mediaPermissions) })
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }

            if (state.hasClipboard) {
                GalleryActionBar(
                    text = "Clipboard ready",
                    confirmLabel = "Paste here",
                    onConfirm = vm::paste,
                    onCancel = vm::clearClipboard
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

            val showAlbums = state.source is GallerySource.Device && !state.inAlbum
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (showAlbums) {
                    AlbumGrid(
                        albums = state.albums,
                        bottomInset = inner.calculateBottomPadding(),
                        onOpen = vm::openDeviceAlbum
                    )
                } else {
                    MediaGrid(
                        folders = state.folders,
                        media = state.media,
                        thumbs = thumbs,
                        selection = state.selection,
                        selectionMode = state.inSelectionMode,
                        bottomInset = inner.calculateBottomPadding(),
                        onOpenFolder = vm::openFolder,
                        onOpenMedia = vm::openViewer,
                        onToggleFolder = { folder -> vm.toggleSelect(folder.relPath) },
                        onToggleMedia = { item -> vm.toggleSelect(item.key) }
                    )
                }
            }
        }
    }

    state.viewerIndex?.let { index ->
        MediaViewer(
            items = state.media,
            startIndex = index,
            onClose = vm::closeViewer,
            materialize = { vm.materialize(it) },
            onShowDetails = { viewerDetails = it },
            onDelete = { viewerDeleteItem = it },
            onEdit = { launchEditor(it) },
            onVault = { viewerVaultItem = it }
        )
    }

    if (showNewFolder) {
        GalleryTextDialog("New folder", "", "Create", onDismiss = { showNewFolder = false }) { name ->
            vm.newFolder(name); showNewFolder = false
        }
    }
    renameTarget?.let { target ->
        GalleryTextDialog("Rename", target.name, "Rename", onDismiss = { renameTarget = null }) { name ->
            vm.renameItem(target, name); renameTarget = null
        }
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
    if (showVaultConfirm) {
        AlertDialog(
            onDismissRequest = { showVaultConfirm = false },
            title = { Text("Move to vault?") },
            text = { Text("${state.selection.size} item(s) will be encrypted into the vault and removed from here. (Folders are skipped.)") },
            confirmButton = {
                TextButton(onClick = { showVaultConfirm = false; vm.moveSelectedToVault() }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { showVaultConfirm = false }) { Text("Cancel") } }
        )
    }
    detailsTarget?.let { target ->
        FileDetailsDialog(
            info = DetailsInfo(
                name = target.name,
                isDirectory = target.isDirectory,
                typeLabel = fileTypeLabel(target.name, target.isDirectory),
                size = target.size,
                modified = target.modifiedTime,
                localPath = vm.localAbsolutePath(target),
                isImage = !target.isDirectory && isImageName(target.name),
                isVideo = !target.isDirectory && isVideoName(target.name)
            ),
            onDismiss = { detailsTarget = null }
        )
    }
    viewerDetails?.let { item ->
        FileDetailsDialog(
            info = DetailsInfo(
                name = item.name,
                isDirectory = false,
                typeLabel = fileTypeLabel(item.name, false),
                uri = item.deviceUri,
                size = item.remoteFile?.size,
                modified = item.remoteFile?.modifiedTime,
                localPath = item.remoteFile?.let { vm.localAbsolutePath(it) },
                isImage = !item.isVideo,
                isVideo = item.isVideo
            ),
            onDismiss = { viewerDetails = null }
        )
    }
    viewerDeleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { viewerDeleteItem = null },
            title = { Text("Delete this ${if (item.isVideo) "video" else "photo"}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteViewerItem(item); viewerDeleteItem = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { viewerDeleteItem = null }) { Text("Cancel") } }
        )
    }
    viewerVaultItem?.let { item ->
        AlertDialog(
            onDismissRequest = { viewerVaultItem = null },
            title = { Text("Move to vault?") },
            text = { Text("This ${if (item.isVideo) "video" else "photo"} will be encrypted into the vault and removed from here.") },
            confirmButton = {
                TextButton(onClick = { vm.vaultViewerItem(item); viewerVaultItem = null }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { viewerVaultItem = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GallerySelectionBar(
    count: Int,
    canRename: Boolean,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onDetails: () -> Unit,
    onMoveToVault: () -> Unit,
    onEdit: () -> Unit,
    onSelectAll: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
        },
        title = { Text("$count selected") },
        actions = {
            // Primary actions as icons; overflow the rest so nothing gets clipped off-screen.
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy") }
            IconButton(onClick = onCut) { Icon(Icons.Filled.ContentCut, contentDescription = "Cut") }
            IconButton(onClick = onMoveToVault) { Icon(Icons.Filled.Lock, contentDescription = "Move to vault") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            Box {
                var overflow by remember { mutableStateOf(false) }
                IconButton(onClick = { overflow = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    if (canRename) {
                        DropdownMenuItem(text = { Text("Edit photo") }, onClick = { overflow = false; onEdit() })
                        DropdownMenuItem(text = { Text("Details") }, onClick = { overflow = false; onDetails() })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { overflow = false; onRename() })
                    }
                    DropdownMenuItem(text = { Text("Select all") }, onClick = { overflow = false; onSelectAll() })
                }
            }
        }
    )
}

@Composable
private fun GalleryActionBar(text: String, confirmLabel: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
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

@Composable
private fun GalleryTextDialog(
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
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
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
private fun GallerySourceSelector(
    label: String,
    accounts: List<Account>,
    onPick: (GallerySource, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.clickable { expanded = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change source")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Device") },
                onClick = { onPick(GallerySource.Device, "Device"); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("Internal storage (folders)") },
                onClick = {
                    onPick(GallerySource.Provider(ExplorerLocation.LocalRoot), "Internal storage"); expanded = false
                }
            )
            accounts.forEach { acc ->
                DropdownMenuItem(
                    text = { Text("${acc.name} (${acc.type.label})") },
                    onClick = {
                        onPick(GallerySource.Provider(ExplorerLocation.Remote(acc.id)), acc.name); expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MediaPermissionPrompt(onGrant: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Allow access to your photos and videos to show the gallery.")
        Button(onClick = onGrant) { Text("Allow access") }
    }
}

@Composable
private fun AlbumGrid(albums: List<Album>, bottomInset: Dp, onOpen: (Album) -> Unit) {
    if (albums.isEmpty()) {
        EmptyBox("No albums")
        return
    }
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize().verticalScrollbar(gridState),
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp + bottomInset)
    ) {
        items(albums, key = { it.id }, contentType = { "album" }) { album ->
            Column(Modifier.padding(6.dp).clickable { onOpen(album) }) {
                ThumbBox(model = album.coverModel, isVideo = false)
                Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                Text("${album.count}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    folders: List<RemoteFile>,
    media: List<MediaItem>,
    thumbs: Map<String, File>,
    selection: Set<String>,
    selectionMode: Boolean,
    bottomInset: Dp,
    onOpenFolder: (RemoteFile) -> Unit,
    onOpenMedia: (Int) -> Unit,
    onToggleFolder: (RemoteFile) -> Unit,
    onToggleMedia: (MediaItem) -> Unit
) {
    if (folders.isEmpty() && media.isEmpty()) {
        EmptyBox("No photos or videos here")
        return
    }
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize().verticalScrollbar(gridState),
        contentPadding = PaddingValues(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 6.dp + bottomInset)
    ) {
        items(folders, key = { "f:${it.relPath}" }, contentType = { "folder" }) { folder ->
            val selected = folder.relPath in selection
            Column(
                Modifier.padding(6.dp).combinedClickable(
                    onClick = { if (selectionMode) onToggleFolder(folder) else onOpenFolder(folder) },
                    onLongClick = { onToggleFolder(folder) }
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
                    if (selected) SelectionCheck()
                }
                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        itemsIndexed(media, key = { _, m -> "m:${m.key}" }, contentType = { _, _ -> "media" }) { index, item ->
            val selected = item.key in selection
            Box(
                Modifier.padding(3.dp).combinedClickable(
                    onClick = { if (selectionMode) onToggleMedia(item) else onOpenMedia(index) },
                    onLongClick = { onToggleMedia(item) }
                )
            ) {
                ThumbBox(model = item.thumbModel ?: thumbs[item.key], isVideo = item.isVideo)
                if (selected) {
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
                    SelectionCheck()
                }
            }
        }
    }
}

@Composable
private fun BoxScope.SelectionCheck() {
    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
    )
}

@Composable
private fun ThumbBox(model: Any?, isVideo: Boolean) {
    val context = LocalContext.current
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            // Decode to a small thumbnail (not the full 12MP image) so the grid loads and scrolls fast.
            AsyncImage(
                model = remember(model) {
                    ImageRequest.Builder(context).data(model).size(384).crossfade(false).build()
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Filled.Image, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isVideo) {
            Icon(Icons.Filled.PlayCircle, contentDescription = null, tint = Color.White,
                modifier = Modifier.padding(4.dp).align(Alignment.Center))
        }
    }
}

@Composable
private fun EmptyBox(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MediaViewer(
    items: List<MediaItem>,
    startIndex: Int,
    onClose: () -> Unit,
    materialize: suspend (MediaItem) -> File,
    onShowDetails: (MediaItem) -> Unit = {},
    onDelete: (MediaItem) -> Unit = {},
    onEdit: (MediaItem) -> Unit = {},
    onVault: (MediaItem) -> Unit = {}
) {
    if (items.isEmpty()) { onClose(); return }
    BackHandler(enabled = true) { onClose() }
    var index by remember { mutableStateOf(startIndex.coerceIn(0, items.lastIndex)) }
    var chrome by remember { mutableStateOf(false) }
    LaunchedEffect(items.size) { if (index > items.lastIndex) index = items.lastIndex.coerceAtLeast(0) }
    val current = items.getOrNull(index)
    val context = LocalContext.current

    // Immersive + suppress the nav-drawer edge swipe while the viewer is open.
    val view = LocalView.current
    DisposableEffect(Unit) {
        FullscreenState.active.value = true
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            FullscreenState.active.value = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // Show the system bars while the chrome is up so the action row clears the status bar.
    LaunchedEffect(chrome) {
        val controller = (view.context as? Activity)?.window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (chrome) controller?.show(WindowInsetsCompat.Type.systemBars())
        else controller?.hide(WindowInsetsCompat.Type.systemBars())
    }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            // Filmstrip: keep the previous / current / next pages composed and keyed by item, so the
            // page preloaded off-screen (decoded, invisible) is the *same* composable shown after a
            // swipe — no reload, no blank flash. Only the current page is visible + handles gestures.
            for (i in intArrayOf(index - 1, index, index + 1)) {
                val item = items.getOrNull(i) ?: continue
                if (item.isVideo && i != index) continue  // don't preload heavy video neighbours
                key(item.key) {
                    val active = i == index
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(if (active) 1f else 0f)
                            .alpha(if (active) 1f else 0f)
                    ) {
                        MediaPage(
                            item = item,
                            materialize = materialize,
                            active = active,
                            onPrev = { if (index > 0) index-- },
                            onNext = { if (index < items.lastIndex) index++ },
                            onLongPress = { onShowDetails(item) },
                            onTap = { chrome = !chrome }
                        )
                    }
                }
            }

            // Top action bar (tap the photo to toggle).
            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).zIndex(2f)
            ) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xB3000000)).statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    current?.let { c ->
                        if (!c.isVideo) IconButton(onClick = { onEdit(c) }) { Icon(Icons.Filled.Edit, "Edit", tint = Color.White) }
                        IconButton(onClick = { onShowDetails(c) }) { Icon(Icons.Filled.Info, "Details", tint = Color.White) }
                        IconButton(onClick = { onVault(c) }) { Icon(Icons.Filled.Lock, "Move to vault", tint = Color.White) }
                        IconButton(onClick = { onDelete(c) }) { Icon(Icons.Filled.Delete, "Delete", tint = Color.White) }
                    }
                }
            }

            // Bottom thumbnail strip of this album; tap a tile to jump to it.
            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f)
            ) {
                val stripState = rememberLazyListState()
                LaunchedEffect(index, chrome) { if (chrome) runCatching { stripState.animateScrollToItem(index) } }
                LazyRow(
                    Modifier.fillMaxWidth().background(Color(0xB3000000)).navigationBarsPadding().padding(vertical = 8.dp),
                    state = stripState,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowItemsIndexed(items, key = { _, m -> "v:${m.key}" }) { i, m ->
                        Box(
                            Modifier.size(54.dp).clip(RoundedCornerShape(6.dp))
                                .border(
                                    if (i == index) 2.dp else 0.dp,
                                    if (i == index) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .background(Color(0xFF2A2A30))
                                .clickable { index = i }
                        ) {
                            AsyncImage(
                                model = remember(m.key) {
                                    ImageRequest.Builder(context).data(m.thumbModel ?: m.deviceUri).size(160).crossfade(false).build()
                                },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (m.isVideo) Icon(
                                Icons.Filled.PlayCircle, null, tint = Color.White,
                                modifier = Modifier.align(Alignment.Center).size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaPage(
    item: MediaItem,
    materialize: suspend (MediaItem) -> File,
    active: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {}
) {
    val model by produceState<Any?>(initialValue = item.deviceUri, item.key) {
        value = item.deviceUri ?: runCatching { materialize(item) }.getOrNull()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            model == null -> if (active) CircularProgressIndicator(color = Color.White)
            item.isVideo -> VideoPlayer(model!!, active = active, onPrev = onPrev, onNext = onNext, onLongPress = onLongPress)
            else -> ZoomableImage(model, active = active, onPrev = onPrev, onNext = onNext, onLongPress = onLongPress, onTap = onTap)
        }
    }
}

@Composable
private fun VideoPlayer(
    model: Any,
    active: Boolean = true,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val context = LocalContext.current
    val uri: Uri = when (model) {
        is Uri -> model
        is File -> Uri.fromFile(model)
        else -> return
    }
    val exo = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(uri) { onDispose { exo.release() } }
    // Long-press for details is handled without swallowing the player's tap-to-toggle controls.
    Box(
        Modifier.fillMaxSize()
            .then(if (active) Modifier.horizontalSwipe(uri, onPrev, onNext) else Modifier)
            .then(if (active) Modifier.longPressPassthrough(uri, onLongPress) else Modifier)
    ) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = exo } },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Detects a horizontal swipe on the Initial pointer pass and consumes it, so a swipe changes item
 * while taps still reach the video's play/pause controls underneath.
 */
private fun Modifier.horizontalSwipe(key: Any, onPrev: () -> Unit, onNext: () -> Unit): Modifier =
    pointerInput(key) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var dx = 0f
            var dy = 0f
            var claimed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pan = event.calculatePan()
                dx += pan.x; dy += pan.y
                if (!claimed && abs(dx) > slop && abs(dx) > abs(dy)) claimed = true
                if (claimed) event.changes.forEach { if (it.positionChanged()) it.consume() }
                if (event.changes.none { it.pressed }) break
            }
            if (claimed) {
                val threshold = size.width * 0.15f
                if (dx <= -threshold) onNext() else if (dx >= threshold) onPrev()
            }
        }
    }

/**
 * Fires [onLongPress] when a finger is held still past the long-press timeout, without consuming any
 * pointer events — so a normal tap still passes through to the video player's own controls underneath.
 */
private fun Modifier.longPressPassthrough(key: Any, onLongPress: () -> Unit): Modifier =
    pointerInput(key) {
        val timeout = viewConfiguration.longPressTimeoutMillis
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var moved = 0f
            var fired = false
            try {
                withTimeout(timeout) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) return@withTimeout // lifted → tap
                        moved += change.positionChange().getDistance()
                        if (moved > slop) return@withTimeout // dragging → not a long-press
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                fired = true
            }
            if (fired) onLongPress()
        }
    }

@Composable
private fun ZoomableImage(
    model: Any?,
    active: Boolean = true,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {}
) {
    var scale by remember(model) { mutableStateOf(1f) }
    var offset by remember(model) { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    AsyncImage(
        model = remember(model) { ImageRequest.Builder(context).data(model).crossfade(false).build() },
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            // Tap toggles the viewer chrome; double-tap zooms; long-press shows details.
            .pointerInput(model, active) {
                if (!active) return@pointerInput
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    }
                )
            }
            // Pinch/pan when zoomed; otherwise a horizontal swipe jumps to the next item instantly.
            .pointerInput(model, active) {
                if (!active) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var dy = 0f
                    var zoomed = false
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        if (multiTouch || scale > 1f) {
                            zoomed = true
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else {
                            val pan = event.calculatePan()
                            dx += pan.x; dy += pan.y
                            // Consume so the nav drawer / other ancestors don't treat this as their swipe.
                            if (abs(dx) > abs(dy)) event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!zoomed && scale <= 1f && abs(dx) > abs(dy)) {
                        val threshold = size.width * 0.15f
                        if (dx <= -threshold) onNext() else if (dx >= threshold) onPrev()
                    }
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
    )
}

