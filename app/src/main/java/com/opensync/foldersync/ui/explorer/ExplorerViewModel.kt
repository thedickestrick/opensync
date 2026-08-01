package com.opensync.foldersync.ui.explorer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensync.foldersync.Graph
import com.opensync.foldersync.files.Clipboard
import com.opensync.foldersync.files.DEFAULT_LOCAL_DIR
import com.opensync.foldersync.files.ExplorerLocation
import com.opensync.foldersync.files.ExplorerRepository
import com.opensync.foldersync.files.ShareInbox
import com.opensync.foldersync.files.SharedClipboard
import com.opensync.foldersync.files.ShareRequest
import com.opensync.foldersync.files.SharedItem
import com.opensync.foldersync.files.SortBy
import com.opensync.foldersync.provider.RemoteFile
import com.opensync.foldersync.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ExplorerUiState(
    val location: ExplorerLocation = ExplorerLocation.LocalRoot,
    val locationLabel: String = "Internal storage",
    val relDir: String = DEFAULT_LOCAL_DIR,
    val entries: List<RemoteFile> = emptyList(),
    val selection: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: String? = null,
    val sortBy: SortBy = SortBy.NAME,
    val ascending: Boolean = true,
    val showHidden: Boolean = false,
    val hasClipboard: Boolean = false,
    val pendingShareCount: Int = 0,
    val busyMessage: String? = null
) {
    val inSelectionMode: Boolean get() = selection.isNotEmpty()
    val canGoUp: Boolean get() = relDir.isNotEmpty()
}

class ExplorerViewModel : ViewModel() {

    private val repo = ExplorerRepository(Graph.appContext)
    private val db = Graph.database

    val accounts = db.accountDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _state = MutableStateFlow(ExplorerUiState())
    val state = _state.asStateFlow()

    private var raw: List<RemoteFile> = emptyList()
    private var pendingShares: List<SharedItem> = emptyList()

    /** Remembered list scroll position, so opening a file and coming back keeps your place. */
    var listIndex = 0
    var listOffset = 0

    private val _openFile = Channel<File>(Channel.BUFFERED)
    val openFile = _openFile.receiveAsFlow()

    init {
        navigate(DEFAULT_LOCAL_DIR)
        // Reflect the shared clipboard (copies made here or in the Gallery) in the paste bar.
        viewModelScope.launch {
            SharedClipboard.flow.collect { c -> _state.update { it.copy(hasClipboard = c != null) } }
        }
        viewModelScope.launch {
            ShareInbox.request.collect { req ->
                when (req) {
                    is ShareRequest.Open -> {
                        ShareInbox.clear()
                        openLocalPath(req.absolutePath)
                    }
                    is ShareRequest.Save -> {
                        pendingShares = req.items
                        _state.update { it.copy(pendingShareCount = req.items.size) }
                    }
                    null -> {}
                }
            }
        }
    }

    private fun openLocalPath(absolutePath: String) {
        viewModelScope.launch {
            repo.setLocation(ExplorerLocation.LocalRoot)
            _state.update {
                it.copy(location = ExplorerLocation.LocalRoot, locationLabel = "Internal storage", selection = emptySet())
            }
            navigate(absolutePath.trim('/'))
        }
    }

    fun saveShared() {
        val items = pendingShares
        if (items.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busyMessage = "Saving ${items.size} file(s)…") }
            try {
                val resolver = Graph.appContext.contentResolver
                for (item in items) {
                    val tmp = File.createTempFile("share", ".bin", Graph.appContext.cacheDir)
                    resolver.openInputStream(item.uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    repo.importFile(_state.value.relDir, item.name, tmp)
                    tmp.delete()
                }
                pendingShares = emptyList()
                ShareInbox.clear()
                _state.update { it.copy(busyMessage = null, pendingShareCount = 0) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(busyMessage = null, error = e.message ?: "Save failed") }
            }
        }
    }

    fun cancelShare() {
        pendingShares = emptyList()
        ShareInbox.clear()
        _state.update { it.copy(pendingShareCount = 0) }
    }

    fun openLocation(location: ExplorerLocation, label: String) {
        viewModelScope.launch {
            repo.setLocation(location)
            val startDir = if (location == ExplorerLocation.LocalRoot) DEFAULT_LOCAL_DIR else ""
            _state.update { it.copy(location = location, locationLabel = label, selection = emptySet()) }
            navigate(startDir)
        }
    }

    fun navigate(relDir: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, relDir = relDir, selection = emptySet()) }
            try {
                raw = repo.list(relDir)
                _state.update { it.copy(entries = display(it), loading = false) }
            } catch (e: Exception) {
                raw = emptyList()
                _state.update {
                    it.copy(entries = emptyList(), loading = false, error = e.message ?: "Cannot open folder")
                }
            }
        }
    }

    fun refresh() = navigate(_state.value.relDir)

    /** Absolute on-device path for [item], or null when browsing a remote account. */
    fun localAbsolutePath(item: RemoteFile): String? =
        if (_state.value.location == ExplorerLocation.LocalRoot) "/" + item.relPath else null

    fun up() {
        val cur = _state.value.relDir
        if (cur.isEmpty()) return
        navigate(cur.trim('/').substringBeforeLast('/', ""))
    }

    fun open(item: RemoteFile) {
        if (item.isDirectory) {
            navigate(item.relPath)
        } else {
            viewModelScope.launch {
                try {
                    _openFile.send(repo.materialize(item))
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "Cannot open file") }
                }
            }
        }
    }

    fun toggleSelect(item: RemoteFile) = _state.update {
        val s = it.selection.toMutableSet()
        if (!s.add(item.relPath)) s.remove(item.relPath)
        it.copy(selection = s)
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun selectAll() = _state.update { it.copy(selection = it.entries.map { e -> e.relPath }.toSet()) }

    fun setSort(sortBy: SortBy) = _state.update {
        val ascending = if (it.sortBy == sortBy) !it.ascending else true
        val next = it.copy(sortBy = sortBy, ascending = ascending)
        next.copy(entries = display(next))
    }

    fun toggleHidden() = _state.update {
        val next = it.copy(showHidden = !it.showHidden)
        next.copy(entries = display(next))
    }

    fun newFolder(name: String) = viewModelScope.launch {
        try {
            repo.createFolder(_state.value.relDir, name)
            refresh()
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Cannot create folder") }
        }
    }

    fun rename(item: RemoteFile, newName: String) = viewModelScope.launch {
        try {
            repo.rename(item, newName)
            clearSelection()
            refresh()
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "Cannot rename") }
        }
    }

    fun deleteSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busyMessage = "Deleting…") }
            try {
                repo.delete(items)
                clearSelection()
                _state.update { it.copy(busyMessage = null) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(busyMessage = null, error = e.message ?: "Cannot delete") }
            }
        }
    }

    /** Encrypt the selected files into the vault and remove the originals from here. */
    fun moveSelectedToVault() {
        val items = selectedItems().filter { !it.isDirectory }
        if (items.isEmpty()) return
        val local = _state.value.location == ExplorerLocation.LocalRoot
        viewModelScope.launch {
            when {
                !VaultManager.exists() ->
                    _state.update { it.copy(error = "Create a vault first (Vault tab).") }
                !VaultManager.isUnlocked ->
                    _state.update { it.copy(error = "Unlock the vault first (Vault tab).") }
                else -> {
                    _state.update { it.copy(busyMessage = "Moving to vault…") }
                    val err = withContext(Dispatchers.IO) {
                        runCatching {
                            for (item in items) {
                                // materialize() returns the real local file, or a downloaded temp for
                                // remote — importFile encrypts it and deletes that source.
                                val file = repo.materialize(item)
                                VaultManager.importFile(Uri.fromFile(file))
                                if (!local) repo.delete(listOf(item)) // also drop the remote original
                            }
                        }.exceptionOrNull()?.message
                    }
                    clearSelection()
                    _state.update { it.copy(busyMessage = null, error = err) }
                    refresh()
                }
            }
        }
    }

    fun copySelected() {
        SharedClipboard.clip = Clipboard(_state.value.location, selectedItems(), move = false)
        _state.update { it.copy(selection = emptySet()) }
    }

    fun cutSelected() {
        SharedClipboard.clip = Clipboard(_state.value.location, selectedItems(), move = true)
        _state.update { it.copy(selection = emptySet()) }
    }

    fun clearClipboard() {
        SharedClipboard.clip = null
    }

    fun paste() {
        val clip = SharedClipboard.clip ?: return
        viewModelScope.launch {
            _state.update { it.copy(busyMessage = "Preparing…") }
            try {
                repo.paste(
                    clip = clip,
                    destDir = _state.value.relDir,
                    onProgress = { name -> _state.update { it.copy(busyMessage = "Copying $name") } },
                    isCancelled = { false }
                )
                if (clip.move) SharedClipboard.clip = null
                _state.update { it.copy(busyMessage = null) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(busyMessage = null, error = e.message ?: "Paste failed") }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun selectedItems(): List<RemoteFile> =
        _state.value.entries.filter { it.relPath in _state.value.selection }

    private fun display(st: ExplorerUiState): List<RemoteFile> {
        val filtered = if (st.showHidden) raw else raw.filter { !it.name.startsWith(".") }
        val comparator = Comparator<RemoteFile> { a, b ->
            if (a.isDirectory != b.isDirectory) return@Comparator if (a.isDirectory) -1 else 1
            val k = when (st.sortBy) {
                SortBy.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                SortBy.SIZE -> a.size.compareTo(b.size)
                SortBy.DATE -> a.modifiedTime.compareTo(b.modifiedTime)
                SortBy.TYPE -> ext(a.name).compareTo(ext(b.name), ignoreCase = true)
            }
            if (st.ascending) k else -k
        }
        return filtered.sortedWith(comparator)
    }

    private fun ext(name: String) = name.substringAfterLast('.', "")

    override fun onCleared() {
        repo.release()
    }
}
