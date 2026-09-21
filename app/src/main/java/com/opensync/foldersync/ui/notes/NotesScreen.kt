package com.opensync.foldersync.ui.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.opensync.foldersync.ui.common.verticalScrollbar
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opensync.foldersync.Graph
import com.opensync.foldersync.files.SortBy
import com.opensync.foldersync.data.Account
import com.opensync.foldersync.notes.NoteConverter
import com.opensync.foldersync.notes.RemoteNotes
import com.opensync.foldersync.notes.RemoteNotesFolder
import com.opensync.foldersync.ui.pairs.RemoteFolderPickerDialog
import com.opensync.foldersync.share.ShareUtil
import com.opensync.foldersync.ui.formatBytes
import com.opensync.foldersync.ui.formatTimestamp
import com.opensync.foldersync.update.AppPrefs
import com.opensync.foldersync.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class NoteKind { PDF, RAW, TEXT, IMAGE, OTHER }

data class NoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val kind: NoteKind?,
    val subDir: String = "",
    val size: Long = 0,
    val modifiedTime: Long = 0,
    val pinned: Boolean = false
)

data class NotesState(
    val rootDir: String = "",
    val currentDir: String = "",
    val entries: List<NoteEntry> = emptyList(),
    val loading: Boolean = false,
    val includeSub: Boolean = true,
    val sortBy: SortBy = SortBy.NAME,
    val ascending: Boolean = true,
    val query: String = "",
    val searching: Boolean = false,
    val selection: Set<String> = emptySet(),
    val clipboard: List<String> = emptyList(),
    val clipboardCut: Boolean = false,
    val error: String? = null,
    /** The notes folder on this phone ([rootDir] is this, unless an account folder is showing). */
    val localRoot: String = "",
    val remoteFolders: List<RemoteNotesFolder> = emptyList(),
    /** The account notes folder being shown, or null for the one on this phone. */
    val activeRemote: RemoteNotesFolder? = null,
    val syncing: Boolean = false
) {
    val selectionMode get() = selection.isNotEmpty()
    val canGoUp get() = !includeSub && currentDir.isNotBlank() && currentDir != rootDir
}

class NotesViewModel : ViewModel() {
    private val prefs = AppPrefs(Graph.appContext)
    private val _state = MutableStateFlow(NotesState())
    val state = _state.asStateFlow()
    private var rawEntries: List<NoteEntry> = emptyList()
    private var pinned: Set<String> = prefs.pinnedNotes

    /** Accounts an additional notes folder can live on. */
    val accounts = Graph.database.accountDao().observeAll()

    /** Remembered list scroll position, so opening a note and coming back keeps your place. */
    var listIndex = 0
    var listOffset = 0

    init {
        val folders = RemoteNotes.folders()
        val active = folders.firstOrNull { it.id == prefs.activeNotesFolder }
        val root = active?.let { RemoteNotes.mirrorDir(it).absolutePath } ?: prefs.notesDir
        _state.value = NotesState(
            rootDir = root, currentDir = root,
            localRoot = prefs.notesDir, remoteFolders = folders, activeRemote = active
        )
        if (root.isNotBlank()) refresh()

        viewModelScope.launch {
            RemoteNotes.syncing.collect { ids ->
                _state.value = _state.value.let { it.copy(syncing = it.activeRemote?.id in ids) }
            }
        }
        // A finished sync may have brought notes in (or taken some away): show what's there now.
        viewModelScope.launch {
            RemoteNotes.finished.collect { done ->
                if (done.folderId != _state.value.activeRemote?.id) return@collect
                val message = done.error ?: if (done.conflicts > 0) {
                    "${done.conflicts} note(s) were changed on both sides — the older version is kept as a \"(conflict …)\" copy"
                } else null
                if (message != null) _state.value = _state.value.copy(error = message)
                rescan()
            }
        }
    }

    /** Choose the notes folder on this phone, and show it. */
    fun setRoot(path: String) {
        prefs.notesDir = path
        _state.value = _state.value.copy(localRoot = path)
        showLocal()
    }

    fun showLocal() {
        prefs.activeNotesFolder = 0L
        val root = prefs.notesDir
        _state.value = _state.value.copy(
            rootDir = root, currentDir = root, activeRemote = null, syncing = false,
            entries = emptyList(), selection = emptySet()
        )
        rawEntries = emptyList()
        if (root.isNotBlank()) rescan()
    }

    fun showRemote(folder: RemoteNotesFolder) {
        prefs.activeNotesFolder = folder.id
        val root = RemoteNotes.mirrorDir(folder).apply { mkdirs() }.absolutePath
        _state.value = _state.value.copy(
            rootDir = root, currentDir = root, activeRemote = folder,
            entries = emptyList(), selection = emptySet()
        )
        rawEntries = emptyList()
        refresh()
    }

    fun addRemote(name: String, accountId: Long, remoteFolder: String) {
        val folder = RemoteNotes.add(name, accountId, remoteFolder)
        _state.value = _state.value.copy(remoteFolders = RemoteNotes.folders())
        showRemote(folder)
    }

    fun removeRemote(folder: RemoteNotesFolder) {
        RemoteNotes.remove(folder)
        _state.value = _state.value.copy(remoteFolders = RemoteNotes.folders())
        if (_state.value.activeRemote?.id == folder.id) showLocal()
    }

    /** Re-list the folder and, if it lives on an account, sync it with the account first. */
    fun refresh() {
        rescan()
        _state.value.activeRemote?.let { RemoteNotes.requestSync(it) }
    }

    fun openDir(path: String) {
        _state.value = _state.value.copy(currentDir = path, selection = emptySet())
        rescan()
    }

    fun up() {
        val s = _state.value
        if (!s.canGoUp) return
        val parent = File(s.currentDir).parentFile?.absolutePath ?: s.rootDir
        // Never navigate above the chosen notes root.
        val target = if (isWithinRoot(parent, s.rootDir)) parent else s.rootDir
        openDir(target)
    }

    fun rescan() {
        val s = _state.value
        if (s.currentDir.isBlank()) return
        _state.value = s.copy(loading = true)
        viewModelScope.launch {
            val listed = withContext(Dispatchers.IO) { listDir(File(s.currentDir), s.includeSub) }
            // The folder may have been switched while this was listing the old one.
            if (_state.value.currentDir != s.currentDir) return@launch
            rawEntries = listed
            pushSorted()
            com.opensync.foldersync.widget.NotesWidgetProvider.notifyChanged(Graph.appContext)
            com.opensync.foldersync.widget.SingleNoteWidgetProvider.notifyChanged(Graph.appContext)
        }
    }

    fun toggleIncludeSub() {
        _state.value = _state.value.copy(includeSub = !_state.value.includeSub, selection = emptySet())
        rescan()
    }

    /** Toggles direction when the same key is chosen again, matching the Files explorer. */
    fun setSort(sortBy: SortBy) {
        val s = _state.value
        val ascending = if (s.sortBy == sortBy) !s.ascending else true
        _state.value = s.copy(sortBy = sortBy, ascending = ascending)
        pushSorted()
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        pushSorted()
    }

    fun toggleSearch() {
        val s = _state.value
        _state.value = s.copy(searching = !s.searching, query = if (s.searching) "" else s.query)
        pushSorted()
    }

    /** Pin/unpin the selected notes (toggles each individually). */
    fun togglePinSelected() {
        val sel = _state.value.selection
        if (sel.isEmpty()) return
        val next = pinned.toMutableSet()
        sel.forEach { if (it in next) next.remove(it) else next.add(it) }
        pinned = next
        prefs.pinnedNotes = next
        _state.value = _state.value.copy(selection = emptySet())
        pushSorted()
    }

    private fun pushSorted() {
        val s = _state.value
        val withPin = rawEntries.map { it.copy(pinned = it.path in pinned) }
        val q = s.query.trim()
        val filtered = if (q.isBlank()) withPin else withPin.filter { it.name.contains(q, ignoreCase = true) }
        _state.value = s.copy(entries = sortEntries(filtered, s.sortBy, s.ascending), loading = false)
    }

    private fun sortEntries(list: List<NoteEntry>, sortBy: SortBy, ascending: Boolean): List<NoteEntry> {
        val cmp = Comparator<NoteEntry> { a, b ->
            if (a.isDir != b.isDir) return@Comparator if (a.isDir) -1 else 1
            if (!a.isDir && a.pinned != b.pinned) return@Comparator if (a.pinned) -1 else 1
            val k = when (sortBy) {
                SortBy.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                SortBy.SIZE -> a.size.compareTo(b.size)
                SortBy.DATE -> a.modifiedTime.compareTo(b.modifiedTime)
                SortBy.TYPE -> a.name.substringAfterLast('.', "")
                    .compareTo(b.name.substringAfterLast('.', ""), ignoreCase = true)
            }
            if (ascending) k else -k
        }
        return list.sortedWith(cmp)
    }

    private fun listDir(dir: File, recursive: Boolean): List<NoteEntry> {
        if (recursive) {
            return dir.walkTopDown().onFail { _, _ -> }.maxDepth(8)
                .filter { it.isFile }
                .mapNotNull { f ->
                    kindOf(f.name)?.let { k ->
                        val sub = f.parentFile?.relativeToOrNull(dir)?.path
                            ?.takeIf { it.isNotEmpty() && it != "." } ?: ""
                        NoteEntry(f.name, f.absolutePath, false, k, sub, f.length(), f.lastModified())
                    }
                }
                .take(4000)
                .toList()
        }
        val files = dir.listFiles() ?: return emptyList()
        val dirs = files.filter { it.isDirectory }
            .map { NoteEntry(it.name, it.absolutePath, true, null, "", 0L, it.lastModified()) }
        val notes = files.filter { it.isFile }
            .mapNotNull { f ->
                kindOf(f.name)?.let { NoteEntry(f.name, f.absolutePath, false, it, "", f.length(), f.lastModified()) }
            }
        return dirs + notes
    }

    // --- selection ---
    fun toggle(path: String) {
        val sel = _state.value.selection
        _state.value = _state.value.copy(selection = if (path in sel) sel - path else sel + path)
    }

    fun clearSelection() { _state.value = _state.value.copy(selection = emptySet()) }

    fun selectAll() {
        _state.value = _state.value.copy(selection = _state.value.entries.map { it.path }.toSet())
    }

    // --- clipboard ---
    fun copySelected() {
        val s = _state.value
        _state.value = s.copy(clipboard = s.selection.toList(), clipboardCut = false, selection = emptySet())
    }

    fun cutSelected() {
        val s = _state.value
        _state.value = s.copy(clipboard = s.selection.toList(), clipboardCut = true, selection = emptySet())
    }

    fun cancelClipboard() { _state.value = _state.value.copy(clipboard = emptyList(), clipboardCut = false) }

    fun paste() {
        val s = _state.value
        if (s.clipboard.isEmpty()) return
        val dest = File(s.currentDir)
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                val movedDirs = if (s.clipboardCut) s.clipboard.filter { File(it).isDirectory } else emptyList()
                runCatching {
                    s.clipboard.forEach { pasteInto(dest, File(it), s.clipboardCut) }
                }.also {
                    RemoteNotes.touched(s.clipboard + dest.absolutePath, movedDirs.filterNot { File(it).exists() })
                }.exceptionOrNull()?.message
            }
            _state.value = _state.value.copy(clipboard = emptyList(), clipboardCut = false, error = error)
            rescan()
        }
    }

    fun deleteSelected() {
        val paths = _state.value.selection.toList()
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                val dirs = paths.filter { File(it).isDirectory }
                runCatching { paths.forEach { File(it).deleteRecursively() } }
                    .also { RemoteNotes.touched(paths, dirs.filterNot { File(it).exists() }) }
                    .exceptionOrNull()?.message
            }
            _state.value = _state.value.copy(selection = emptySet(), error = error)
            rescan()
        }
    }

    /** Encrypt the selected notes into the vault and remove the originals (folders are skipped). */
    fun moveSelectedToVault() {
        val paths = _state.value.selection.toList().filter { !File(it).isDirectory }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            when {
                !VaultManager.exists() ->
                    _state.value = _state.value.copy(error = "Create a vault first (Vault tab).")
                !VaultManager.isUnlocked ->
                    _state.value = _state.value.copy(error = "Unlock the vault first (Vault tab).")
                else -> {
                    val error = withContext(Dispatchers.IO) {
                        runCatching {
                            paths.forEach { VaultManager.importFile(Uri.fromFile(File(it))) }
                        }.also { RemoteNotes.touched(paths) }.exceptionOrNull()?.message
                    }
                    _state.value = _state.value.copy(selection = emptySet(), error = error)
                    rescan()
                }
            }
        }
    }

    fun rename(path: String, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    val src = File(path)
                    val wasDir = src.isDirectory
                    if (src.renameTo(File(src.parentFile, clean))) {
                        RemoteNotes.touched(listOf(path), if (wasDir) listOf(path) else emptyList())
                    }
                }.exceptionOrNull()?.message
            }
            _state.value = _state.value.copy(selection = emptySet(), error = error)
            rescan()
        }
    }

    fun newFolder(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { File(_state.value.currentDir, clean).mkdirs() } }
            RemoteNotes.notifyChanged(_state.value.currentDir)
            rescan()
        }
    }

    /** Convert every PDF and raw Samsung Notes file currently listed into an editable .md note. */
    fun convertImported() {
        val paths = _state.value.entries
            .filter { it.kind == NoteKind.PDF || it.kind == NoteKind.RAW }
            .map { it.path }
        if (paths.isEmpty()) {
            _state.value = _state.value.copy(error = "No PDF or Samsung Notes files here to convert")
            return
        }
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val made = withContext(Dispatchers.IO) {
                paths.count { NoteConverter.toMarkdown(Graph.appContext, File(it)) != null }
            }
            _state.value = _state.value.copy(error = "Converted $made note(s) to editable .md")
            RemoteNotes.notifyChanged(_state.value.currentDir)
            rescan()
        }
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

    private fun pasteInto(destDir: File, src: File, cut: Boolean) {
        if (!src.exists()) return
        // Guard against copying a directory into itself or a descendant.
        if (src.isDirectory && isWithinRoot(destDir.absolutePath, src.absolutePath)) return
        if (cut) {
            val target = File(destDir, src.name)
            if (target.absolutePath == src.absolutePath) return
            if (!src.renameTo(target)) {
                val fallback = uniqueDest(destDir, src.name)
                src.copyRecursively(fallback, overwrite = true)
                src.deleteRecursively()
            }
        } else {
            src.copyRecursively(uniqueDest(destDir, src.name), overwrite = false)
        }
    }

    private fun uniqueDest(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 2
        while (candidate.exists()) { candidate = File(dir, "$base ($i)$ext"); i++ }
        return candidate
    }

    private fun isWithinRoot(path: String, root: String): Boolean {
        if (root.isBlank()) return false
        val p = File(path).absolutePath
        val r = File(root).absolutePath
        return p == r || p.startsWith(r + File.separator)
    }
}

fun kindOf(name: String): NoteKind? {
    val n = name.lowercase()
    return when {
        n.endsWith(".pdf") -> NoteKind.PDF
        n.endsWith(".spd") || n.endsWith(".sdoc") || n.endsWith(".sdocx") ||
            n.endsWith(".snb") || n.endsWith(".memo") -> NoteKind.RAW
        n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".rtf") -> NoteKind.TEXT
        listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif").any { n.endsWith(it) } -> NoteKind.IMAGE
        n.endsWith(".doc") || n.endsWith(".docx") -> NoteKind.OTHER
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    openDrawer: () -> Unit,
    onOpenPdf: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onNewNote: (String) -> Unit,
    onEditNote: (String) -> Unit,
    vm: NotesViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    // Restore scroll position from the (navigation-surviving) ViewModel; save it on the way out.
    val notesListState = rememberLazyListState(vm.listIndex, vm.listOffset)
    DisposableEffect(Unit) {
        onDispose {
            vm.listIndex = notesListState.firstVisibleItemIndex
            vm.listOffset = notesListState.firstVisibleItemScrollOffset
        }
    }

    var showRootPicker by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var showAddRemote by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showVaultConfirm by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

    BackHandler(enabled = state.selectionMode || state.canGoUp) {
        if (state.selectionMode) vm.clearSelection() else vm.up()
    }

    // Refresh the list when returning to this screen (e.g. after creating/editing a note).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedErrorToast(state.error) { vm.dismissError() }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                NotesSelectionBar(
                    count = state.selection.size,
                    canRename = state.selection.size == 1,
                    onClose = { vm.clearSelection() },
                    onShare = {
                        val files = state.selection.map(::File).filter { it.isFile }
                        if (files.isEmpty()) {
                            Toast.makeText(context, "Only notes can be shared — folders are skipped",
                                Toast.LENGTH_SHORT).show()
                        } else {
                            ShareUtil.shareFiles(context, files)
                            vm.clearSelection()
                        }
                    },
                    onPin = { vm.togglePinSelected() },
                    onCopy = { vm.copySelected() },
                    onCut = { vm.cutSelected() },
                    onDelete = { showDeleteConfirm = true },
                    onRename = { renameTarget = state.selection.first() },
                    onMoveToVault = { showVaultConfirm = true },
                    onSelectAll = { vm.selectAll() }
                )
            } else if (state.searching) {
                NotesSearchBar(
                    query = state.query,
                    onQuery = { vm.setQuery(it) },
                    onClose = { vm.toggleSearch() }
                )
            } else {
                TopAppBar(
                    title = {
                        // The title doubles as the switcher between the phone's and the accounts' notes folders.
                        Row(
                            Modifier.clickable { showFolders = true },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (state.rootDir.isBlank()) "Notes" else titleFor(state),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Notes folders")
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                    },
                    actions = {
                        if (state.rootDir.isNotBlank()) {
                            IconButton(onClick = { vm.toggleSearch() }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = { vm.toggleIncludeSub() }) {
                                Icon(
                                    Icons.Filled.UnfoldMore,
                                    contentDescription = "Include subfolders",
                                    tint = if (state.includeSub) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box {
                                IconButton(onClick = { sortOpen = true }) {
                                    Icon(Icons.Filled.Sort, contentDescription = "Sort")
                                }
                                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                                    SortBy.entries.forEach { s ->
                                        val marker = if (state.sortBy == s) {
                                            if (state.ascending) "  ↑" else "  ↓"
                                        } else ""
                                        DropdownMenuItem(
                                            text = { Text(s.label + marker) },
                                            onClick = { vm.setSort(s); sortOpen = false }
                                        )
                                    }
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("New folder") },
                                    onClick = { menuOpen = false; showNewFolder = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Convert imported notes (PDF/.spd → editable)") },
                                    onClick = { menuOpen = false; vm.convertImported() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Notes folders…") },
                                    onClick = { menuOpen = false; showFolders = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (state.activeRemote != null) "Sync now" else "Rescan") },
                                    onClick = { menuOpen = false; vm.refresh() }
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (state.rootDir.isNotBlank() && !state.selectionMode) {
                ExtendedFloatingActionButton(onClick = { onNewNote(state.currentDir) }) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Text("  New note")
                }
            }
        },
        bottomBar = {
            if (state.clipboard.isNotEmpty()) {
                PasteBar(
                    count = state.clipboard.size,
                    cut = state.clipboardCut,
                    onPaste = { vm.paste() },
                    onCancel = { vm.cancelClipboard() }
                )
            }
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when {
                state.rootDir.isBlank() -> SetupCard(
                    Modifier.align(Alignment.Center),
                    onChoose = { showRootPicker = true },
                    onChooseRemote = { showAddRemote = true }
                )
                // Only take over the screen with a spinner on the first load — a refresh (e.g. on
                // resume) keeps the list composed so your scroll position isn't thrown away.
                // (An account folder opened for the first time is empty until its first sync lands.)
                (state.loading || state.syncing) && state.entries.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                  LazyColumn(
                    modifier = Modifier.fillMaxSize().verticalScrollbar(notesListState),
                    state = notesListState,
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    if (state.canGoUp) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.up() }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.ArrowUpward, contentDescription = "Up")
                                Text("..", Modifier.padding(start = 12.dp),
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    if (state.entries.isEmpty()) {
                        item {
                            Text(
                                "No notes here yet.\nTap “New note” to write one, export from Samsung Notes " +
                                    "(⋮ → Save as file) into this folder, or copy .spd files in.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }
                    items(state.entries, key = { it.path }) { entry ->
                        NoteRow(
                            entry = entry,
                            selected = entry.path in state.selection,
                            selectionMode = state.selectionMode,
                            onClick = {
                                when {
                                    state.selectionMode -> vm.toggle(entry.path)
                                    entry.isDir -> vm.openDir(entry.path)
                                    entry.kind == NoteKind.PDF -> onOpenPdf(entry.path)
                                    entry.kind == NoteKind.TEXT -> onEditNote(entry.path)
                                    entry.kind == NoteKind.OTHER -> openWithSystem(context, File(entry.path))
                                    else -> onOpenNote(entry.path)
                                }
                            },
                            onLongClick = { vm.toggle(entry.path) }
                        )
                    }
                  }
                }
            }
            if (state.syncing && state.entries.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    if (showRootPicker) {
        NotesFolderPickerDialog(
            initial = state.localRoot,
            onDismiss = { showRootPicker = false },
            onSelect = { path -> vm.setRoot(path); showRootPicker = false }
        )
    }
    if (showFolders) {
        NotesFoldersDialog(
            state = state,
            onDismiss = { showFolders = false },
            onShowLocal = {
                showFolders = false
                if (state.localRoot.isBlank()) showRootPicker = true else vm.showLocal()
            },
            onChangeLocal = { showFolders = false; showRootPicker = true },
            onShowRemote = { showFolders = false; vm.showRemote(it) },
            onRemoveRemote = { vm.removeRemote(it) },
            onAddRemote = { showFolders = false; showAddRemote = true }
        )
    }
    if (showAddRemote) {
        val accounts by vm.accounts.collectAsState(initial = null)
        AddRemoteNotesFolder(
            accounts = accounts,
            onDismiss = { showAddRemote = false },
            onAdd = { account, folder ->
                showAddRemote = false
                val leaf = folder.trim('/').substringAfterLast('/')
                vm.addRemote(if (leaf.isBlank()) account.name else "$leaf (${account.name})", account.id, folder)
            }
        )
    }
    if (showNewFolder) {
        NotesTextDialog("New folder", "", "Create", onDismiss = { showNewFolder = false }) { name ->
            vm.newFolder(name); showNewFolder = false
        }
    }
    renameTarget?.let { path ->
        NotesTextDialog("Rename", File(path).name, "Rename", onDismiss = { renameTarget = null }) { name ->
            vm.rename(path, name); renameTarget = null
        }
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${state.selection.size} item(s)?") },
            text = { Text("This permanently deletes the selected files and folders.") },
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
            text = { Text("${state.selection.size} note(s) will be encrypted into the vault and removed from your notes folder. (Folders are skipped.)") },
            confirmButton = {
                TextButton(onClick = { showVaultConfirm = false; vm.moveSelectedToVault() }) { Text("Move") }
            },
            dismissButton = { TextButton(onClick = { showVaultConfirm = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    entry: NoteEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when {
                entry.isDir -> Icons.Filled.Folder
                entry.kind == NoteKind.IMAGE -> Icons.Filled.Image
                else -> Icons.Filled.Description
            },
            contentDescription = null,
            tint = if (entry.isDir) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            val displayName = if (entry.isDir) entry.name else entry.name.substringBeforeLast('.', entry.name)
            Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
            Text(
                noteMeta(entry),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (entry.pinned) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = "Pinned",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        if (selectionMode && selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

private fun noteMeta(entry: NoteEntry): String = buildString {
    if (entry.isDir) {
        append("Folder")
    } else {
        append(formatTimestamp(entry.modifiedTime))
        append("  •  ").append(formatBytes(entry.size))
    }
    if (entry.subDir.isNotEmpty()) append("  •  ").append(entry.subDir)
}

private fun titleFor(state: NotesState): String {
    if (state.currentDir == state.rootDir) return state.activeRemote?.name ?: "Notes"
    return File(state.currentDir).name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesSelectionBar(
    count: Int,
    canRename: Boolean,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onPin: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onMoveToVault: () -> Unit,
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
            IconButton(onClick = onPin) { Icon(Icons.Filled.PushPin, contentDescription = "Pin / unpin") }
            IconButton(onClick = onMoveToVault) { Icon(Icons.Filled.Lock, contentDescription = "Move to vault") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            Box {
                var overflow by remember { mutableStateOf(false) }
                IconButton(onClick = { overflow = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    if (canRename) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { overflow = false; onRename() })
                    }
                    DropdownMenuItem(text = { Text("Copy") }, onClick = { overflow = false; onCopy() })
                    DropdownMenuItem(text = { Text("Cut") }, onClick = { overflow = false; onCut() })
                    DropdownMenuItem(text = { Text("Select all") }, onClick = { overflow = false; onSelectAll() })
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesSearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search notes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
        }
    )
}

@Composable
private fun PasteBar(count: Int, cut: Boolean, onPaste: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.ContentPaste, contentDescription = null)
            Text(
                "${if (cut) "Move" else "Copy"} $count item(s) here",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onPaste) { Text("Paste") }
        }
    }
}

@Composable
private fun SetupCard(modifier: Modifier, onChoose: () -> Unit, onChooseRemote: () -> Unit) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("Your notes", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick a folder to keep your notes in. Then you can write new notes right here, and OpenSync " +
                "will also read PDF/text/image exports and best-effort parse raw Samsung Notes (.spd/.sdoc) " +
                "files — with full copy/move/delete controls.\n\n" +
                "New notes are saved as Markdown (.md) text, so they sync through your backup pairs and open anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onChoose) { Text("Choose notes folder") }
        OutlinedButton(onClick = onChooseRemote) { Text("Use a folder on an account") }
    }
}

/** Lists the notes folder on this phone and the ones on accounts, to switch between or manage them. */
@Composable
private fun NotesFoldersDialog(
    state: NotesState,
    onDismiss: () -> Unit,
    onShowLocal: () -> Unit,
    onChangeLocal: () -> Unit,
    onShowRemote: (RemoteNotesFolder) -> Unit,
    onRemoveRemote: (RemoteNotesFolder) -> Unit,
    onAddRemote: () -> Unit
) {
    var removeTarget by remember { mutableStateOf<RemoteNotesFolder?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notes folders") },
        text = {
            val listState = rememberLazyListState()
            LazyColumn(Modifier.height(320.dp).verticalScrollbar(listState), state = listState) {
                item {
                    NotesFolderRow(
                        icon = Icons.Filled.PhoneAndroid,
                        title = "On this phone",
                        subtitle = state.localRoot.ifBlank { "Not chosen yet" },
                        active = state.activeRemote == null && state.rootDir.isNotBlank(),
                        onClick = onShowLocal
                    ) {
                        if (state.localRoot.isNotBlank()) TextButton(onClick = onChangeLocal) { Text("Change") }
                    }
                }
                items(state.remoteFolders, key = { it.id }) { folder ->
                    NotesFolderRow(
                        icon = Icons.Filled.Cloud,
                        title = folder.name,
                        subtitle = "/" + folder.remoteFolder,
                        active = state.activeRemote?.id == folder.id,
                        onClick = { onShowRemote(folder) }
                    ) {
                        IconButton(onClick = { removeTarget = folder }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove")
                        }
                    }
                }
                item {
                    Text(
                        "A folder on an account is kept in sync with the server: notes are copied to this " +
                            "phone when you open it, and sent back whenever you change one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onAddRemote) { Text("Add account folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
    removeTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove '${folder.name}'?") },
            text = { Text("It's only removed from this phone. The notes on the account are left untouched.") },
            confirmButton = {
                TextButton(onClick = { removeTarget = null; onRemoveRemote(folder) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NotesFolderRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        trailing()
    }
}

/** Two steps: pick the account, then browse to the folder on it. [accounts] is null while loading. */
@Composable
private fun AddRemoteNotesFolder(
    accounts: List<Account>?,
    onDismiss: () -> Unit,
    onAdd: (Account, String) -> Unit
) {
    var account by remember { mutableStateOf<Account?>(null) }
    val picked = account
    if (picked != null) {
        RemoteFolderPickerDialog(
            account = picked,
            initialPath = "",
            onDismiss = { account = null },
            onSelect = { folder -> onAdd(picked, folder) }
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which account?") },
        text = {
            val listState = rememberLazyListState()
            LazyColumn(Modifier.height(280.dp).verticalScrollbar(listState), state = listState) {
                if (accounts != null && accounts.isEmpty()) {
                    item { Text("No accounts yet — add your server or cloud account in the Accounts tab first.") }
                }
                items(accounts.orEmpty(), key = { it.id }) { acc ->
                    Row(
                        Modifier.fillMaxWidth().clickable { account = acc }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Cloud, contentDescription = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(acc.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                acc.type.label, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NotesTextDialog(
    title: String, initial: String, confirmLabel: String,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
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

/** Minimal device-folder browser for choosing the notes root directory. */
@Composable
private fun NotesFolderPickerDialog(initial: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val root = remember {
        val f = File(initial)
        if (initial.isNotBlank() && f.isDirectory) f
        else Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
    }
    var current by remember { mutableStateOf(root) }
    val subDirs = remember(current) {
        current.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(current.absolutePath, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
        },
        text = {
            val pickerState = rememberLazyListState()
            LazyColumn(Modifier.height(340.dp).verticalScrollbar(pickerState), state = pickerState) {
                current.parentFile?.let { parent ->
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable { current = parent }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null)
                            Text("  ..  (up)", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                items(subDirs, key = { it.absolutePath }) { dir ->
                    Row(
                        Modifier.fillMaxWidth().clickable { current = dir }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null)
                        Text(dir.name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (subDirs.isEmpty()) {
                    item { Text("(no sub-folders)", Modifier.padding(vertical = 12.dp)) }
                }
            }
        },
        confirmButton = { Button(onClick = { onSelect(current.absolutePath) }) { Text("Use this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun LaunchedErrorToast(error: String?, onShown: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(error) {
        if (error != null) {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            onShown()
        }
    }
}

private fun openWithSystem(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: Exception) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}
