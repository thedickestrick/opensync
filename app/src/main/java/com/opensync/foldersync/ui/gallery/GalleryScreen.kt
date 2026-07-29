package com.opensync.foldersync.ui.gallery

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
                        onToggleMedia = { item -> item.remoteFile?.let { vm.toggleSelect(it.relPath) } }
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
            materialize = { vm.materialize(it) }
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
    onSelectAll: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
        },
        title = { Text("$count selected") },
        actions = {
            if (canRename) {
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
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp + bottomInset)
    ) {
        items(albums, key = { it.id }) { album ->
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
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 6.dp + bottomInset)
    ) {
        items(folders, key = { "f:${it.relPath}" }) { folder ->
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
        itemsIndexed(media, key = { _, m -> "m:${m.key}" }) { index, item ->
            val selected = item.remoteFile?.let { it.relPath in selection } ?: false
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
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            AsyncImage(model = model, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
    materialize: suspend (MediaItem) -> File
) {
    if (items.isEmpty()) { onClose(); return }
    BackHandler(enabled = true) { onClose() }
    var index by remember { mutableStateOf(startIndex.coerceIn(0, items.size - 1)) }

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

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            // Preload neighbouring device photos at display size (drawn invisibly under the current
            // image) so the first swipe is instant instead of a blank flash.
            for (i in intArrayOf(index - 1, index + 1)) {
                val nb = items.getOrNull(i)
                val uri = nb?.deviceUri
                if (uri != null && !nb.isVideo) {
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().alpha(0f)
                    )
                }
            }
            MediaPage(
                items[index],
                materialize,
                onPrev = { if (index > 0) index-- },
                onNext = { if (index < items.size - 1) index++ }
            )
        }
    }
}

@Composable
private fun MediaPage(
    item: MediaItem,
    materialize: suspend (MediaItem) -> File,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val model by produceState<Any?>(initialValue = item.deviceUri, item.key) {
        value = item.deviceUri ?: runCatching { materialize(item) }.getOrNull()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            model == null -> CircularProgressIndicator(color = Color.White)
            item.isVideo -> VideoPlayer(model!!, onPrev = onPrev, onNext = onNext)
            else -> ZoomableImage(model, onPrev = onPrev, onNext = onNext)
        }
    }
}

@Composable
private fun VideoPlayer(model: Any, onPrev: () -> Unit = {}, onNext: () -> Unit = {}) {
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
    Box(Modifier.fillMaxSize().horizontalSwipe(uri, onPrev, onNext)) {
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

@Composable
private fun ZoomableImage(model: Any?, onPrev: () -> Unit = {}, onNext: () -> Unit = {}) {
    var scale by remember(model) { mutableStateOf(1f) }
    var offset by remember(model) { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            // Double-tap toggles between fit and 2.5× zoom.
            .pointerInput(model) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            // Pinch/pan when zoomed; otherwise a horizontal swipe jumps to the next item instantly.
            .pointerInput(model) {
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

