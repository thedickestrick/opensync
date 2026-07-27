package com.opensync.foldersync.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensync.foldersync.Graph
import com.opensync.foldersync.files.ExplorerLocation
import com.opensync.foldersync.gallery.Album
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
    val inAlbum: Boolean = false,
    val title: String = "",
    val relDir: String = "",
    val folders: List<RemoteFile> = emptyList(),
    val media: List<MediaItem> = emptyList(),
    val viewerIndex: Int? = null
) {
    val canBack: Boolean
        get() = (source is GallerySource.Device && inAlbum) ||
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

    init {
        loadDeviceAlbums()
    }

    fun setSource(source: GallerySource, label: String) {
        viewModelScope.launch {
            thumbJob?.cancel()
            _remoteThumbs.value = emptyMap()
            _state.update {
                it.copy(
                    source = source, sourceLabel = label, error = null, inAlbum = false,
                    albums = emptyList(), folders = emptyList(), media = emptyList(),
                    relDir = "", viewerIndex = null
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
            _state.update { it.copy(loading = true, error = null, inAlbum = false, media = emptyList()) }
            try {
                val albums = repo.deviceAlbums()
                _state.update { it.copy(albums = albums, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Cannot read media") }
            }
        }
    }

    fun openDeviceAlbum(album: Album) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val media = repo.deviceMedia(album.id)
                _state.update { it.copy(media = media, inAlbum = true, title = album.name, loading = false) }
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

    fun back() {
        val s = _state.value
        when {
            s.viewerIndex != null -> closeViewer()
            s.source is GallerySource.Device && s.inAlbum -> loadDeviceAlbums()
            s.source is GallerySource.Provider && s.relDir.isNotEmpty() ->
                navigateProvider(s.relDir.trim('/').substringBeforeLast('/', ""))
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
