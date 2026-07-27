package com.opensync.foldersync.ui.gallery

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
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

    BackHandler(enabled = state.canBack) { vm.back() }

    Scaffold(
        topBar = {
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
                    // Sorting applies to the album grid (device source, not inside an album).
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
    ) { inner ->
        // Apply the top inset (below the app bar) but let the grid run to the bottom edge, behind
        // the transparent nav bar; the bar height is handed to the grid as scroll padding.
        Column(
            Modifier.padding(top = inner.calculateTopPadding()).fillMaxSize()
        ) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (state.source is GallerySource.Device && !hasMedia) {
                MediaPermissionPrompt(onGrant = { permLauncher.launch(PermissionUtil.mediaPermissions) })
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp)
                )
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
                        bottomInset = inner.calculateBottomPadding(),
                        onOpenFolder = vm::openFolder,
                        onOpenMedia = vm::openViewer
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

@Composable
private fun MediaGrid(
    folders: List<RemoteFile>,
    media: List<MediaItem>,
    thumbs: Map<String, File>,
    bottomInset: Dp,
    onOpenFolder: (RemoteFile) -> Unit,
    onOpenMedia: (Int) -> Unit
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
            Column(
                Modifier.padding(6.dp).clickable { onOpenFolder(folder) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Folder, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
                }
                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        itemsIndexed(media, key = { _, m -> "m:${m.key}" }) { index, item ->
            Box(Modifier.padding(3.dp).clickable { onOpenMedia(index) }) {
                ThumbBox(model = item.thumbModel ?: thumbs[item.key], isVideo = item.isVideo)
            }
        }
    }
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
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, items.size - 1)) { items.size }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                MediaPage(items[page], materialize)
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
            Text(
                items[pagerState.currentPage].name,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp)
            )
        }
    }
}

@Composable
private fun MediaPage(item: MediaItem, materialize: suspend (MediaItem) -> File) {
    val model by produceState<Any?>(initialValue = item.deviceUri, item.key) {
        value = item.deviceUri ?: runCatching { materialize(item) }.getOrNull()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            model == null -> CircularProgressIndicator(color = Color.White)
            item.isVideo -> VideoPlayer(model!!)
            else -> ZoomableImage(model)
        }
    }
}

@Composable
private fun VideoPlayer(model: Any) {
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
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { player = exo } },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun ZoomableImage(model: Any?) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
    )
}

