package com.opensync.foldersync.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensync.foldersync.Graph
import com.opensync.foldersync.files.Clipboard
import com.opensync.foldersync.files.ExplorerLocation
import com.opensync.foldersync.gallery.Album
import com.opensync.foldersync.gallery.AlbumSort
import com.opensync.foldersync.gallery.GalleryRepository
import com.opensync.foldersync.gallery.GallerySource
import com.opensync.foldersync.gallery.MediaItem
import com.opensync.foldersync.provider.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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
                    navigateProvider("")
                }
            }
        }
    }

    fun reloadDevice() {
        if (_state.value.source is GallerySource.Device) loadDeviceAlbums()
    }

    private fun loadDeviceAlbums() {
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
        // Prefer browsing the album's real folder (full file controls); fall back to MediaStore.
        if (album.directory.isNotBlank()) {
            viewModelScope.launch {
                repo.setProviderLocation(ExplorerLocation.LocalRoot)
                _state.update {
                    it.copy(inAlbum = true, title = album.name, albumBaseDir = album.directory, selection = emptySet())
                }
                navigateProvider(album.directory)
            }
        } else {
            viewModelScope.launch {
                _state.update { it.copy(loading = true, error = null) }
                try {
                    val media = repo.deviceMedia(album.id)
                    _state.update {
                        it.copy(media = media, folders = emptyList(), inAlbum = true,
                            title = album.name, albumBaseDir = null, loading = false)
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(loading = false, error = e.message ?: "Cannot open album") }
                }
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
        val keys = it.folders.map { f -> f.relPath } + it.media.mapNotNull { m -> m.remoteFile?.relPath }
        it.copy(selection = keys.toSet())
    }

    fun singleSelected(): RemoteFile? = selectedFiles().firstOrNull()

    private fun selectedFiles(): List<RemoteFile> {
        val st = _state.value
        val folders = st.folders.filter { it.relPath in st.selection }
        val media = st.media.mapNotNull { it.remoteFile }.filter { it.relPath in st.selection }
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
            _state.update { it.copy(busyMessage = "Preparing…") }
            try {
                repo.paste(
                    clip, _state.value.relDir,
                    { name -> _state.update { it.copy(busyMessage = "Copying $name") } },
                    { false }
                )
                if (clip.move) clipboard = null
                _state.update { it.copy(busyMessage = null, hasClipboard = clipboard != null) }
                navigateProvider(_state.value.relDir)
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
                repo.delete(items)
                _state.update { it.copy(busyMessage = null, selection = emptySet()) }
                navigateProvider(_state.value.relDir)
            } catch (e: Exception) {
                _state.update { it.copy(busyMessage = null, error = e.message ?: "Delete failed") }
            }
        }
    }

    fun renameItem(item: RemoteFile, newName: String) {
        viewModelScope.launch {
            try {
                repo.rename(item, newName)
                _state.update { it.copy(selection = emptySet()) }
                navigateProvider(_state.value.relDir)
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
                navigateProvider(_state.value.relDir)
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
