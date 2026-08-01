package com.opensync.foldersync.ui.gallery

import android.media.MediaScannerConnection
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensync.foldersync.Graph
import com.opensync.foldersync.files.Clipboard
import com.opensync.foldersync.files.DEFAULT_LOCAL_DIR
import com.opensync.foldersync.files.ExplorerLocation
import com.opensync.foldersync.gallery.Album
import com.opensync.foldersync.gallery.AlbumSort
import com.opensync.foldersync.gallery.GalleryRepository
import com.opensync.foldersync.gallery.GallerySource
import com.opensync.foldersync.gallery.MediaItem
import com.opensync.foldersync.provider.RemoteFile
import com.opensync.foldersync.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class GalleryUiState(
    val source: GallerySource = GallerySource.Device,
    val sourceLabel: String = "Device",
    val loading: Boolean = false,
    val error: String? = null,
    val albums: List<Album> = emptyList(),
    val albumSort: AlbumSort = AlbumSort.DATE,
    val albumAscending: Boolean = false,
    val inAlbum: Boolean = false,
    val title: String = "",
    val relDir: String = "",
    val folders: List<RemoteFile> = emptyList(),
    val media: List<MediaItem> = emptyList(),
    val viewerIndex: Int? = null,
    val selection: Set<String> = emptySet(),
    val hasClipboard: Boolean = false,
    val busyMessage: String? = null,
    val albumBaseDir: String? = null
) {
    val inSelectionMode: Boolean get() = selection.isNotEmpty()

    /** True when the current view is a provider-backed folder (its media are real files we can manage). */
    val browsingFiles: Boolean
        get() = source is GallerySource.Provider || (inAlbum && albumBaseDir != null)

    val canBack: Boolean
        get() = selection.isNotEmpty() ||
            (source is GallerySource.Device && inAlbum) ||
            (source is GallerySource.Provider && relDir.isNotEmpty())
}

class GalleryViewModel : ViewModel() {

    private val repo = GalleryRepository(Graph.appContext)

    val accounts = Graph.database.accountDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _state = MutableStateFlow(GalleryUiState())
    val state = _state.asStateFlow()

    private val _remoteThumbs = MutableStateFlow<Map<String, File>>(emptyMap())
    val remoteThumbs = _remoteThumbs.asStateFlow()

    private var thumbJob: Job? = null
    private var rawAlbums: List<Album> = emptyList()
    private var currentBucketId: String? = null

    init {
        loadDeviceAlbums()
    }

    fun setSource(source: GallerySource, label: String) {
        viewModelScope.launch {
            thumbJob?.cancel()
            _remoteThumbs.value = emptyMap()
            clipboard = null
            _state.update {
                it.copy(
                    source = source, sourceLabel = label, error = null, inAlbum = false,
                    albums = emptyList(), folders = emptyList(), media = emptyList(),
                    relDir = "", viewerIndex = null, selection = emptySet(),
                    hasClipboard = false, albumBaseDir = null
                )
            }
            when (source) {
                GallerySource.Device -> loadDeviceAlbums()
                is GallerySource.Provider -> {
                    repo.setProviderLocation(source.location)
                    // Local storage: start at the user's storage root, not the filesystem root "/".
                    val start = if (source.location == ExplorerLocation.LocalRoot) DEFAULT_LOCAL_DIR else ""
                    navigateProvider(start)
                }
            }
        }
    }

    fun reloadDevice() {
        if (_state.value.source is GallerySource.Device) loadDeviceAlbums()
    }

    private fun loadDeviceAlbums() {
        currentBucketId = null
        viewModelScope.launch {
            _state.update {
                it.copy(loading = true, error = null, inAlbum = false, media = emptyList(),
                    albumBaseDir = null, selection = emptySet())
            }
            try {
                rawAlbums = repo.deviceAlbums()
                _state.update {
                    it.copy(albums = sortAlbums(rawAlbums, it.albumSort, it.albumAscending), loading = false)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Cannot read media") }
            }
        }
    }

    fun setAlbumSort(sort: AlbumSort) = _state.update {
        val ascending = if (it.albumSort == sort) !it.albumAscending else (sort == AlbumSort.NAME)
        it.copy(
            albumSort = sort,
            albumAscending = ascending,
            albums = sortAlbums(rawAlbums, sort, ascending)
        )
    }

    private fun sortAlbums(albums: List<Album>, sort: AlbumSort, ascending: Boolean): List<Album> {
        val comparator = when (sort) {
            AlbumSort.NAME -> compareBy<Album> { it.name.lowercase() }
            AlbumSort.COUNT -> compareBy<Album> { it.count }
            AlbumSort.DATE -> compareBy<Album> { it.latestDate }
        }
        val sorted = albums.sortedWith(comparator)
        return if (ascending) sorted else sorted.reversed()
    }

    fun openDeviceAlbum(album: Album) {
        // Load via MediaStore (indexed → sub-second) instead of a per-file folder stat, which took
        // ~10s on a large camera roll over the FUSE layer. The MediaStore rows already carry each
        // file's path/size/date, so we get full file controls (copy/cut/delete/rename) with no extra
        // stats. File changes are kept in sync via a quick MediaScanner pass (see refreshAfterOp).
        viewModelScope.launch {
            currentBucketId = album.id
            _state.update { it.copy(loading = true, error = null, selection = emptySet()) }
            try {
                if (album.directory.isNotBlank()) repo.setProviderLocation(ExplorerLocation.LocalRoot)
                val media = repo.deviceMedia(album.id)
                _state.update {
                    it.copy(
                        media = media, folders = emptyList(), inAlbum = true,
                        title = album.name,
                        albumBaseDir = album.directory.ifBlank { null },
                        relDir = album.directory,
                        loading = false
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Cannot open album") }
            }
        }
    }

    fun openFolder(folder: RemoteFile) = navigateProvider(folder.relPath)

    private fun navigateProvider(relDir: String) {
        thumbJob?.cancel()
        _remoteThumbs.value = emptyMap()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, relDir = relDir) }
            try {
                val (folders, media) = repo.listFolder(relDir)
                _state.update { it.copy(folders = folders, media = media, loading = false) }
                startRemoteThumbs(media)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Cannot open folder") }
            }
        }
    }

    /**
     * Refresh after a file operation. In a device album the list comes from MediaStore, so we run a
     * quick MediaScanner pass over the changed paths (keeps the index in sync with the filesystem
     * change) and then re-query MediaStore — both fast. Elsewhere we just re-list the provider folder.
     */
    private fun refreshAfterOp(changedPaths: List<String>) {
        val s = _state.value
        val bucket = currentBucketId
        if (s.source is GallerySource.Device && s.inAlbum && bucket != null) {
            viewModelScope.launch {
                scanPaths(changedPaths)
                val media = withContext(Dispatchers.IO) { repo.deviceMedia(bucket) }
                _state.update { it.copy(media = media) }
            }
        } else {
            navigateProvider(s.relDir)
        }
    }

    private suspend fun scanPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        suspendCancellableCoroutine<Unit> { cont ->
            val remaining = AtomicInteger(paths.size)
            MediaScannerConnection.scanFile(Graph.appContext, paths.toTypedArray(), null) { _, _ ->
                if (remaining.decrementAndGet() == 0 && cont.isActive) cont.resumeWith(Result.success(Unit))
            }
        }
    }

    private fun absPath(rel: String) = "/" + rel.trimStart('/')
    private fun absJoin(dir: String, name: String) =
        if (dir.isBlank()) "/$name" else "/" + dir.trim('/') + "/" + name

    fun back() {
        val s = _state.value
        when {
            s.selection.isNotEmpty() -> clearSelection()
            s.viewerIndex != null -> closeViewer()
            s.source is GallerySource.Device && s.inAlbum -> {
                val base = s.albumBaseDir?.trim('/')
                if (base != null && s.relDir.trim('/') != base && s.relDir.length > base.length) {
                    navigateProvider(s.relDir.trim('/').substringBeforeLast('/', ""))
                } else {
                    loadDeviceAlbums()
                }
            }
            s.source is GallerySource.Provider && s.relDir.isNotEmpty() ->
                navigateProvider(s.relDir.trim('/').substringBeforeLast('/', ""))
        }
    }

    // ---- File controls (folder-based sources) ----

    private var clipboard: Clipboard? = null

    fun toggleSelect(key: String) = _state.update {
        val s = it.selection.toMutableSet()
        if (!s.add(key)) s.remove(key)
        it.copy(selection = s)
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun selectAll() = _state.update {
        val keys = it.folders.map { f -> f.relPath } + it.media.map { m -> m.key }
        it.copy(selection = keys.toSet())
    }

    fun singleSelected(): RemoteFile? = selectedFiles().firstOrNull()

    /** The single selected media item (for opening in the photo editor). */
    fun singleSelectedMediaItem(): MediaItem? {
        val s = _state.value
        return s.media.firstOrNull { it.key in s.selection }
    }

    /** Delete one item straight from the full-screen viewer. */
    fun deleteViewerItem(item: MediaItem) {
        val rf = item.remoteFile ?: return
        viewModelScope.launch {
            _state.update { it.copy(busyMessage = "Deleting…") }
            runCatching { repo.delete(listOf(rf)) }
            _state.update { it.copy(busyMessage = null) }
            refreshAfterOp(listOf(absPath(rf.relPath)))
        }
    }

    /** Move one item into the vault straight from the full-screen viewer. */
    fun vaultViewerItem(item: MediaItem) {
        val rf = item.remoteFile ?: return
        val s = _state.value
        val local = (s.source is GallerySource.Provider && s.source.location == ExplorerLocation.LocalRoot) ||
            (s.source is GallerySource.Device && s.inAlbum)
        viewModelScope.launch {
            when {
                !VaultManager.exists() -> _state.update { it.copy(error = "Create a vault first (Vault tab).") }
                !VaultManager.isUnlocked -> _state.update { it.copy(error = "Unlock the vault first (Vault tab).") }
                else -> {
                    _state.update { it.copy(busyMessage = "Moving to vault…") }
                    val err = withContext(Dispatchers.IO) {
                        runCatching {
                            val f = repo.materialize(item)
                            VaultManager.importFile(Uri.fromFile(f))
                            if (!local) repo.delete(listOf(rf))
                        }.exceptionOrNull()?.message
                    }
                    _state.update { it.copy(busyMessage = null, error = err) }
                    refreshAfterOp(listOf(absPath(rf.relPath)))
                }
            }
        }
    }

    /** Absolute on-device path for [item] when browsing local folders / a device album, else null. */
    fun localAbsolutePath(item: RemoteFile): String? {
        val s = _state.value
        val localProvider = s.source is GallerySource.Provider && s.source.location == ExplorerLocation.LocalRoot
        val deviceAlbum = s.source is GallerySource.Device && s.inAlbum
        return if (localProvider || deviceAlbum) "/" + item.relPath.trimStart('/') else null
    }

    private fun selectedFiles(): List<RemoteFile> {
        val st = _state.value
        val folders = st.folders.filter { it.relPath in st.selection }
        val media = st.media.filter { it.key in st.selection }.mapNotNull { it.remoteFile }
        return folders + media
    }

    fun copySelected() {
        if (!_state.value.browsingFiles) return
        clipboard = Clipboard(repo.providerLocation, selectedFiles(), move = false)
        _state.update { it.copy(hasClipboard = true, selection = emptySet()) }
    }

    fun cutSelected() {
        if (!_state.value.browsingFiles) return
        clipboard = Clipboard(repo.providerLocation, selectedFiles(), move = true)
        _state.update { it.copy(hasClipboard = true, selection = emptySet()) }
    }

    fun clearClipboard() {
        clipboard = null
        _state.update { it.copy(hasClipboard = false) }
    }

    fun paste() {
        val clip = clipboard ?: return
        viewModelScope.launch {
            val dest = _state.value.relDir
            _state.update { it.copy(busyMessage = "Preparing…") }
            try {
                repo.paste(
                    clip, dest,
                    { name -> _state.update { it.copy(busyMessage = "Copying $name") } },
                    { false }
                )
                if (clip.move) clipboard = null
                _state.update { it.copy(busyMessage = null, hasClipboard = clipboard != null) }
                val changed = clip.items.map { absJoin(dest, it.name) } +
                    if (clip.move) clip.items.map { absPath(it.relPath) } else emptyList()
                refreshAfterOp(changed)
            } catch (e: Exception) {
                _state.update { it.copy(busyMessage = null, error = e.message ?: "Paste failed") }
            }
        }
    }

    fun deleteSelected() {
        val items = selectedFiles()
        if (items.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busyMessage = "Deleting…") }
            try {
                val paths = items.map { absPath(it.relPath) }
                repo.delete(items)
                _state.update { it.copy(busyMessage = null, selection = emptySet()) }
                refreshAfterOp(paths)
            } catch (e: Exception) {
                _state.update { it.copy(busyMessage = null, error = e.message ?: "Delete failed") }
            }
        }
    }

    /** Encrypt the selected media into the vault and remove the originals. */
    fun moveSelectedToVault() {
        val s = _state.value
        // Media only (folders are skipped); need a backing file to encrypt.
        val mediaItems = s.media.filter { it.key in s.selection && it.remoteFile != null }
        if (mediaItems.isEmpty()) return
        val local = (s.source is GallerySource.Provider && s.source.location == ExplorerLocation.LocalRoot) ||
            (s.source is GallerySource.Device && s.inAlbum)
        viewModelScope.launch {
            when {
                !VaultManager.exists() ->
                    _state.update { it.copy(error = "Create a vault first (Vault tab).") }
                !VaultManager.isUnlocked ->
                    _state.update { it.copy(error = "Unlock the vault first (Vault tab).") }
                else -> {
                    val paths = mediaItems.mapNotNull { it.remoteFile?.let { rf -> absPath(rf.relPath) } }
                    _state.update { it.copy(busyMessage = "Moving to vault…") }
                    val err = withContext(Dispatchers.IO) {
                        runCatching {
                            for (item in mediaItems) {
                                val file = repo.materialize(item)
                                VaultManager.importFile(Uri.fromFile(file))
                                if (!local) item.remoteFile?.let { repo.delete(listOf(it)) } // drop remote original
                            }
                        }.exceptionOrNull()?.message
                    }
                    _state.update { it.copy(busyMessage = null, selection = emptySet(), error = err) }
                    refreshAfterOp(paths)
                }
            }
        }
    }

    fun renameItem(item: RemoteFile, newName: String) {
        viewModelScope.launch {
            try {
                val oldPath = absPath(item.relPath)
                val newPath = oldPath.substringBeforeLast('/', "") + "/" + newName.trim()
                repo.rename(item, newName)
                _state.update { it.copy(selection = emptySet()) }
                refreshAfterOp(listOf(oldPath, newPath))
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Rename failed") }
            }
        }
    }

    fun newFolder(name: String) {
        if (!_state.value.browsingFiles) return
        viewModelScope.launch {
            try {
                repo.createFolder(_state.value.relDir, name)
                refreshAfterOp(emptyList())
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Cannot create folder") }
            }
        }
    }

    fun openViewer(index: Int) = _state.update { it.copy(viewerIndex = index) }
    fun closeViewer() = _state.update { it.copy(viewerIndex = null) }
    fun dismissError() = _state.update { it.copy(error = null) }

    suspend fun materialize(item: MediaItem): File = repo.materialize(item)

    private fun startRemoteThumbs(media: List<MediaItem>) {
        val source = _state.value.source
        if (source !is GallerySource.Provider || source.location == ExplorerLocation.LocalRoot) return
        thumbJob = viewModelScope.launch(Dispatchers.IO) {
            for (item in media) {
                if (!isActive) break
                if (item.isVideo || item.thumbModel != null) continue
                val size = item.remoteFile?.size ?: continue
                if (size in 1..MAX_THUMB_BYTES) {
                    try {
                        val file = repo.materialize(item)
                        _remoteThumbs.update { it + (item.key to file) }
                    } catch (_: Exception) {
                        // Skip thumbnails that fail to download.
                    }
                }
            }
        }
    }

    override fun onCleared() {
        thumbJob?.cancel()
        repo.release()
    }

    companion object {
        private const val MAX_THUMB_BYTES = 15L * 1024 * 1024
    }
}
