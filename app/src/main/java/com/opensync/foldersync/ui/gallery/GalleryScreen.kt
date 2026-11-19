package com.opensync.foldersync.ui.gallery

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import android.view.LayoutInflater
import android.view.View
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.opensync.foldersync.CloudOrigin
import com.opensync.foldersync.NormRect
import com.opensync.foldersync.SaveProgress
import com.opensync.foldersync.SavedEdit
import com.opensync.foldersync.UploadFailedDialog
import com.opensync.foldersync.UploadProgressBar
import com.opensync.foldersync.UploadStatus
import com.opensync.foldersync.ZOOMED_IN
import com.opensync.foldersync.savePhotoArea
import com.opensync.foldersync.saveVideoStill
import com.opensync.foldersync.visibleRegion
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
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import com.opensync.foldersync.share.ShareUtil
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

    // Opened for a result: that tells the editor it was us (so it trusts the path and account it's
    // given), and tells us when something was saved so the folder can show it.
    val editorLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.refreshAfterEdit()
    }

    fun launchEditor(item: MediaItem) {
        editScope.launch {
            val f = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { vm.materialize(item) }.getOrNull() }
            if (f != null) {
                val editor = if (item.isVideo) com.opensync.foldersync.videoedit.VideoEditorActivity::class.java
                    else com.opensync.foldersync.PhotoEditorActivity::class.java
                val intent = android.content.Intent(context, editor).putExtra("edit_path", f.absolutePath)
                vm.cloudOriginFor(item, f)?.putInto(intent)
                editorLauncher.launch(intent)
            } else {
                val what = if (item.isVideo) "video" else "photo"
                android.widget.Toast.makeText(context, "Couldn't open this $what", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.shareRequests.collect { bundle -> ShareUtil.share(context, bundle) }
    }

    BackHandler(enabled = state.canBack) { vm.back() }

    Scaffold(
        topBar = {
            if (state.inSelectionMode) {
                GallerySelectionBar(
                    count = state.selection.size,
                    canRename = state.selection.size == 1,
                    onClose = vm::clearSelection,
                    onShare = vm::shareSelected,
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

            if (state.hasClipboard && state.browsingFiles) {
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
            onShare = { vm.shareViewerItem(it) },
            onVault = { viewerVaultItem = it },
            cloudOf = { item, file -> vm.cloudOriginFor(item, file) },
            onCaptured = { vm.refreshAfterEdit() }
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
    onShare: () -> Unit,
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
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share") }
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy") }
            IconButton(onClick = onCut) { Icon(Icons.Filled.ContentCut, contentDescription = "Cut") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            Box {
                var overflow by remember { mutableStateOf(false) }
                IconButton(onClick = { overflow = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(
                        text = { Text("Move to vault") },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        onClick = { overflow = false; onMoveToVault() }
                    )
                    if (canRename) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { overflow = false; onEdit() })
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

/** Index of the filmstrip tile whose centre is closest to the viewport centre. */
private fun centeredStripIndex(state: LazyListState): Int {
    val info = state.layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return 0
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2) - center) }!!.index
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
    onShare: (MediaItem) -> Unit = {},
    onVault: (MediaItem) -> Unit = {},
    cloudOf: (MediaItem, File) -> CloudOrigin? = { _, _ -> null },
    onCaptured: () -> Unit = {}
) {
    if (items.isEmpty()) { onClose(); return }
    BackHandler(enabled = true) { onClose() }
    var index by remember { mutableStateOf(startIndex.coerceIn(0, items.lastIndex)) }
    var chrome by remember { mutableStateOf(false) }
    // When the list is reloaded (after an edit adds a copy, a delete…), stay on the same photo
    // rather than the same position; if it's gone, stay put (showing its neighbour).
    val shownList = remember { arrayOf(items) }
    if (shownList[0] !== items) {
        val key = shownList[0].getOrNull(index)?.key
        val moved = items.indexOfFirst { it.key == key }
        val target = if (moved >= 0) moved else index.coerceIn(0, items.lastIndex)
        shownList[0] = items
        if (target != index) index = target
    }
    val current = items.getOrNull(index)
    val context = LocalContext.current

    // Saving the zoomed-in part of a photo, or a video's frame, as a new picture beside it.
    val scope = rememberCoroutineScope()
    var capturing by remember { mutableStateOf(false) }
    var captureShown by remember { mutableStateOf(false) } // after a moment, so a quick save doesn't flash
    var captureUploading by remember { mutableStateOf(false) }
    var captureUpload by remember { mutableStateOf<UploadStatus?>(null) }
    var captureCloud by remember { mutableStateOf<CloudOrigin?>(null) }
    var captureFailure by remember { mutableStateOf<SavedEdit?>(null) }
    BackHandler(enabled = capturing) {} // don't abandon a save half-way
    LaunchedEffect(capturing) {
        captureShown = false
        if (capturing) { delay(300); captureShown = true }
    }
    fun capture(item: MediaItem, request: Capture) {
        if (capturing) return
        capturing = true
        captureUploading = false
        captureUpload = null
        captureCloud = null
        scope.launch {
            val result = try {
                val file = materialize(item)
                val cloud = cloudOf(item, file).also { captureCloud = it }
                val uri = Uri.fromFile(file)
                val onUploading = { captureUploading = true }
                val onUploadProgress = { s: UploadStatus -> captureUpload = s }
                Result.success(
                    when (request) {
                        is Capture.PhotoArea -> savePhotoArea(context, uri, cloud, request.region, onUploading, onUploadProgress)
                        is Capture.VideoFrame ->
                            saveVideoStill(context, uri, cloud, request.positionMs, request.region, onUploading, onUploadProgress)
                    }
                )
            } catch (c: CancellationException) {
                throw c // the viewer was closed; nothing to report
            } catch (t: Throwable) {
                Result.failure(t)
            }
            capturing = false
            result.onSuccess { saved ->
                onCaptured()
                if (saved.uploadError != null) captureFailure = saved
                else android.widget.Toast.makeText(context, saved.message("Picture", captureCloud), android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                val why = if (it is OutOfMemoryError) "not enough memory" else it.message ?: it.javaClass.simpleName
                android.widget.Toast.makeText(context, "Couldn't save the picture: $why", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

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
                // Keyed by version too, so a file changed by an edit is fetched and shown afresh.
                key(item.key, item.remoteFile?.modifiedTime, item.remoteFile?.size) {
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
                            onTap = { chrome = !chrome },
                            chrome = chrome,
                            // A video's controls and the bars show and hide together.
                            onControlsChange = { shown -> if (active) chrome = shown },
                            onCapture = if (active && !capturing) { request -> capture(item, request) } else null
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
                        IconButton(onClick = { onShare(c) }) { Icon(Icons.Filled.Share, "Share", tint = Color.White) }
                        IconButton(onClick = { onEdit(c) }) { Icon(Icons.Filled.Edit, "Edit", tint = Color.White) }
                        IconButton(onClick = { onShowDetails(c) }) { Icon(Icons.Filled.Info, "Details", tint = Color.White) }
                        IconButton(onClick = { onVault(c) }) { Icon(Icons.Filled.Lock, "Move to vault", tint = Color.White) }
                        IconButton(onClick = { onDelete(c) }) { Icon(Icons.Filled.Delete, "Delete", tint = Color.White) }
                    }
                }
            }

            // Bottom thumbnail strip of this album: scroll it to select (the centered tile is current),
            // or tap a tile to jump to it.
            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f)
            ) {
                val stripState = rememberLazyListState()
                val dragged by stripState.interactionSource.collectIsDraggedAsState()
                var userScrolling by remember { mutableStateOf(false) }
                LaunchedEffect(dragged, stripState.isScrollInProgress) {
                    if (dragged) userScrolling = true
                    else if (!stripState.isScrollInProgress) userScrolling = false
                }
                // While the user drives the strip, the tile under the centre becomes the current photo.
                LaunchedEffect(stripState) {
                    snapshotFlow { centeredStripIndex(stripState) }.collect { c ->
                        if (userScrolling && c in items.indices && c != index) index = c
                    }
                }
                // When idle (after a swipe or once a scrub settles), snap the current photo to the centre.
                LaunchedEffect(index, userScrolling) { if (!userScrolling) runCatching { stripState.animateScrollToItem(index) } }

                BoxWithConstraints(
                    Modifier.fillMaxWidth().background(Color(0xB3000000)).navigationBarsPadding()
                ) {
                    val tile = STRIP_TILE
                    val sidePad = (maxWidth - tile) / 2 // so the first/last tiles can reach the centre
                    LazyRow(
                        Modifier.fillMaxWidth().padding(vertical = STRIP_PADDING),
                        state = stripState,
                        contentPadding = PaddingValues(horizontal = sidePad),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowItemsIndexed(items, key = { _, m -> "v:${m.key}" }) { i, m ->
                            Box(
                                Modifier.size(tile).clip(RoundedCornerShape(6.dp))
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

            if (capturing) {
                // Swallows touches so nothing changes under a save in progress.
                Box(
                    Modifier.fillMaxSize().zIndex(3f)
                        .background(if (captureShown) Color(0x99000000) else Color.Transparent)
                        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
                    contentAlignment = Alignment.Center
                ) {
                    if (captureShown) {
                        if (captureUploading) UploadProgressBar(captureCloud, captureUpload)
                        else SaveProgress("Saving picture…", fraction = null)
                    }
                }
            }
        }
    }
    captureFailure?.let { saved ->
        UploadFailedDialog(captureCloud, saved) { captureFailure = null }
    }
}

/** What the viewer's capture button asked to save. */
private sealed interface Capture {
    /** This part of the photo (0..1 of its upright size). */
    data class PhotoArea(val region: NormRect) : Capture

    /** The video's frame at [positionMs]; only [region] of it when zoomed in. */
    data class VideoFrame(val positionMs: Long, val region: NormRect?) : Capture
}

/** The viewer's bottom filmstrip: the size of its tiles, and the space above and below them. */
private val STRIP_TILE = 54.dp
private val STRIP_PADDING = 8.dp

/** Up the right edge: clear of the top bar and of the video player's controls across the middle. */
private val CAPTURE_BUTTON_SPOT = BiasAlignment(horizontalBias = 1f, verticalBias = -0.4f)

/** The round button at the right edge of the viewer that saves what's on screen as a picture. */
@Composable
private fun CaptureButton(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.padding(end = 12.dp).size(52.dp).clip(CircleShape).background(Color(0x99000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = Color.White)
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
    onTap: () -> Unit = {},
    chrome: Boolean = false,
    onControlsChange: (shown: Boolean) -> Unit = {},
    onCapture: ((Capture) -> Unit)? = null // null hides the capture button
) {
    val model by produceState<Any?>(initialValue = item.deviceUri, item.key) {
        value = item.deviceUri ?: runCatching { materialize(item) }.getOrNull()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            model == null -> if (active) CircularProgressIndicator(color = Color.White)
            item.isVideo -> VideoPlayer(
                model!!, chrome = chrome, active = active, onPrev = onPrev, onNext = onNext, onLongPress = onLongPress,
                onTap = onTap, onControlsChange = onControlsChange, onCapture = onCapture
            )
            else -> ZoomableImage(
                model, active = active, onPrev = onPrev, onNext = onNext, onLongPress = onLongPress, onTap = onTap,
                onCapture = onCapture
            )
        }
    }
}

/**
 * A video in the viewer. Its player controls and the viewer's bars ([chrome]) show and hide as one:
 * a tap anywhere but on a control toggles the bars ([onTap]) and the controls follow them; when the
 * player shows or hides its controls by itself (paused, finished, a few seconds into playback) it
 * says so through [onControlsChange], and the bars follow that.
 */
@SuppressLint("ClickableViewAccessibility") // taps are read from the touches; the player stays clickable
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoPlayer(
    model: Any,
    chrome: Boolean,
    active: Boolean = true,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {},
    onControlsChange: (shown: Boolean) -> Unit = {},
    onCapture: ((Capture) -> Unit)? = null
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
    // Releasing the player makes it show its controls; that mustn't raise the bars over the next item.
    val alive = remember(uri) { booleanArrayOf(true) }
    DisposableEffect(uri) { onDispose { alive[0] = false; exo.release() } }
    var scale by remember(uri) { mutableStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var controlsShown by remember(uri) { mutableStateOf(false) }
    var playerView by remember(uri) { mutableStateOf<PlayerView?>(null) }
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val controlsChange by rememberUpdatedState(onControlsChange)
    val isActive by rememberUpdatedState(active)

    // The bars lead: the controls show and hide with them. The controls report every change as they
    // animate (even "visible" as a hide starts), so only one that differs from what was asked and
    // wasn't reported while asking — one the player made itself — moves the bars.
    val asked = remember(uri) { ControlsAsk() }
    LaunchedEffect(playerView, chrome) {
        val v = playerView ?: return@LaunchedEffect
        asked.shown = chrome
        asked.asking = true
        try {
            if (chrome) v.showController() else v.hideController()
        } finally {
            asked.asking = false
        }
    }
    // The filmstrip shows with the controls, so their bottom bar (seek bar, times) goes above it.
    val density = LocalDensity.current
    val controlsBottom = with(density) { (STRIP_TILE + STRIP_PADDING * 2).roundToPx() } +
        WindowInsets.navigationBarsIgnoringVisibility.getBottom(density)

    Box(Modifier.fillMaxSize().onSizeChanged { boxSize = it }) {
        Box(
            Modifier.fillMaxSize()
                .then(
                    if (!active) Modifier else Modifier.videoGestures(
                        uri,
                        scale = { scale },
                        onZoom = { zoom, pan ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        },
                        onPrev = onPrev,
                        onNext = onNext
                    )
                )
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(com.opensync.foldersync.R.layout.viewer_player, null, false) as PlayerView).apply {
                        player = exo
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener {
                            val shown = it == View.VISIBLE
                            controlsShown = shown
                            if (alive[0] && isActive && !asked.asking && shown != asked.shown) {
                                asked.shown = shown
                                controlsChange(shown)
                            }
                        })
                        // Touches no control took (play, the seek bar…) arrive here, on the player or on
                        // the picture (which has the player's own tap-to-toggle). Reading taps from them
                        // instead keeps the controls and the bars in step. A swipe or pinch claimed by
                        // videoGestures arrives as a cancel.
                        val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                            override fun onDown(e: MotionEvent) = true
                            override fun onSingleTapUp(e: MotionEvent): Boolean {
                                tap()
                                return true
                            }
                            override fun onLongPress(e: MotionEvent) = longPress()
                        })
                        val touches = View.OnTouchListener { _, e -> detector.onTouchEvent(e); true }
                        setOnTouchListener(touches)
                        videoSurfaceView?.setOnTouchListener(touches)
                        playerView = this
                    }
                },
                update = { v ->
                    // Zoom the picture's frame only, so the player's controls stay put.
                    (v.videoSurfaceView?.parent as? View)?.apply {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    v.findViewById<View>(androidx.media3.ui.R.id.exo_controller)?.let { c ->
                        if (c.paddingBottom != controlsBottom) c.setPadding(0, 0, 0, controlsBottom)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (onCapture != null && (controlsShown || scale > ZOOMED_IN)) {
            CaptureButton(
                Icons.Filled.PhotoCamera,
                if (scale > ZOOMED_IN) "Save the zoomed-in part of this frame as a picture" else "Save this frame as a picture",
                Modifier.align(CAPTURE_BUTTON_SPOT)
            ) {
                exo.pause()
                val region = if (scale <= ZOOMED_IN) null else {
                    val vs = exo.videoSize
                    val sideways = vs.unappliedRotationDegrees == 90 || vs.unappliedRotationDegrees == 270
                    val w = vs.width * vs.pixelWidthHeightRatio
                    val h = vs.height.toFloat()
                    visibleRegion(
                        boxSize.width.toFloat(), boxSize.height.toFloat(),
                        if (sideways) h else w, if (sideways) w else h,
                        scale, offset.x, offset.y
                    ) ?: return@CaptureButton // zoomed into the black bars: nothing of the video to save
                }
                onCapture(Capture.VideoFrame(exo.currentPosition, region))
            }
        }
    }
}

/** What the viewer's bars last asked of a video's controls, and whether that call is under way. */
private class ControlsAsk {
    var shown = false
    var asking = false
}

/**
 * The video's own gestures, on the Initial pointer pass: pinch to zoom, then drag to look around;
 * when it isn't zoomed, a horizontal swipe changes item. Only what it uses is consumed, so taps
 * still reach the player's play/pause controls underneath.
 */
private fun Modifier.videoGestures(
    key: Any,
    scale: () -> Float,
    onZoom: (zoom: Float, pan: Offset) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
): Modifier =
    pointerInput(key) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var dx = 0f
            var dy = 0f
            var swiping = false
            var zooming = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pan = event.calculatePan()
                dx += pan.x; dy += pan.y
                val moved = abs(dx) > slop || abs(dy) > slop
                if (!swiping && !zooming) {
                    if (event.changes.count { it.pressed } > 1 || (scale() > ZOOMED_IN && moved)) zooming = true
                    else if (abs(dx) > slop && abs(dx) > abs(dy)) swiping = true
                }
                if (zooming) onZoom(event.calculateZoom(), pan)
                if (zooming || swiping) event.changes.forEach { if (it.positionChanged()) it.consume() }
                if (event.changes.none { it.pressed }) break
            }
            if (swiping) {
                val threshold = size.width * 0.15f
                if (dx <= -threshold) onNext() else if (dx >= threshold) onPrev()
            }
        }
    }

/**
 * Tap / double-tap / long-press where the tap fires the instant the finger lifts, instead of sitting
 * on it for the double-tap window to see whether a second tap is coming (what `detectTapGestures`
 * does, and why raising the chrome used to lag ~300ms behind the tap).
 *
 * [onTap] must be a *toggle*: if a second tap does arrive, it is called again to undo the toggle the
 * first tap already applied, and then [onDoubleTap] runs. So a double-tap blinks the chrome briefly
 * and ends up zoomed with the chrome as it was — the trade for an instant single tap.
 */
private suspend fun PointerInputScope.detectInstantTaps(
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit
) {
    awaitEachGesture {
        awaitFirstDown()

        // Null from waitForUpOrCancellation means the pan/zoom handler claimed the gesture; null
        // from the timeout means the finger is still down. Only the latter is a long-press.
        var claimed = false
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation().also { if (it == null) claimed = true }
        }
        if (claimed) return@awaitEachGesture
        if (up == null) {
            onLongPress()
            return@awaitEachGesture
        }

        onTap()

        withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
            ?: return@awaitEachGesture
        var secondClaimed = false
        val secondUp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation().also { if (it == null) secondClaimed = true }
        }
        if (!secondClaimed && secondUp != null) {
            onTap()        // revert the toggle the first tap already applied
            onDoubleTap()
        }
    }
}

@Composable
private fun ZoomableImage(
    model: Any?,
    active: Boolean = true,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {},
    onCapture: ((Capture) -> Unit)? = null
) {
    var scale by remember(model) { mutableStateOf(1f) }
    var offset by remember(model) { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember(model) { mutableStateOf(Size.Unspecified) } // only its shape matters
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().onSizeChanged { boxSize = it }) {
        AsyncImage(
            model = remember(model) { ImageRequest.Builder(context).data(model).crossfade(false).build() },
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onSuccess = { imageSize = it.painter.intrinsicSize },
            modifier = Modifier
                .fillMaxSize()
                // Tap toggles the viewer chrome; double-tap zooms; long-press shows details.
                .pointerInput(model, active) {
                    if (!active) return@pointerInput
                    detectInstantTaps(
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
                        val slop = viewConfiguration.touchSlop
                        awaitFirstDown(requireUnconsumed = false)
                        var dx = 0f
                        var dy = 0f
                        var zoomed = false
                        // Nothing is consumed until the finger travels past touch slop. This handler runs
                        // before the tap detector on the Main pass, and a consumed move cancels a tap —
                        // so consuming eagerly would swallow taps that carry a pixel or two of jitter.
                        var dragging = false
                        do {
                            val event = awaitPointerEvent()
                            val multiTouch = event.changes.count { it.pressed } > 1
                            val pan = event.calculatePan()
                            dx += pan.x; dy += pan.y
                            if (!dragging && (multiTouch || abs(dx) > slop || abs(dy) > slop)) dragging = true
                            if (multiTouch || scale > 1f) {
                                if (dragging) {
                                    zoomed = true
                                    scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                                    offset = if (scale > 1f) offset + pan else Offset.Zero
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } else {
                                // Consume so the nav drawer / other ancestors don't treat this as their swipe.
                                if (dragging && abs(dx) > abs(dy)) {
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (!zoomed && scale <= 1f && dragging && abs(dx) > abs(dy)) {
                            val threshold = size.width * 0.15f
                            if (dx <= -threshold) onNext() else if (dx >= threshold) onPrev()
                        }
                    }
                }
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
        )
        // Zoomed in: offer to keep just the part on screen, as Samsung Gallery does.
        if (active && onCapture != null && scale > ZOOMED_IN && imageSize.isSpecified) {
            CaptureButton(Icons.Filled.Crop, "Save the zoomed-in part as a new picture", Modifier.align(CAPTURE_BUTTON_SPOT)) {
                visibleRegion(
                    boxSize.width.toFloat(), boxSize.height.toFloat(), imageSize.width, imageSize.height,
                    scale, offset.x, offset.y
                )?.let { onCapture(Capture.PhotoArea(it)) }
            }
        }
    }
}

