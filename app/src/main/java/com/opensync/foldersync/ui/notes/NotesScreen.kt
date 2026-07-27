package com.opensync.foldersync.ui.notes

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opensync.foldersync.Graph
import com.opensync.foldersync.update.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class NoteKind { PDF, RAW, TEXT, IMAGE, OTHER }

data class NoteEntry(val name: String, val path: String, val subDir: String, val kind: NoteKind)

data class NotesState(
    val dir: String = "",
    val entries: List<NoteEntry> = emptyList(),
    val loading: Boolean = false
)

class NotesViewModel : ViewModel() {
    private val prefs = AppPrefs(Graph.appContext)
    private val _state = MutableStateFlow(NotesState(dir = prefs.notesDir))
    val state = _state.asStateFlow()

    init { if (prefs.notesDir.isNotBlank()) rescan() }

    fun setDir(path: String) {
        prefs.notesDir = path
        _state.value = _state.value.copy(dir = path)
        rescan()
    }

    fun rescan() {
        val dir = _state.value.dir
        if (dir.isBlank()) return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { scan(File(dir)) }
            _state.value = _state.value.copy(entries = entries, loading = false)
        }
    }

    private fun scan(root: File): List<NoteEntry> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().maxDepth(6)
            .filter { it.isFile }
            .mapNotNull { f ->
                val kind = kindOf(f.name) ?: return@mapNotNull null
                val sub = f.parentFile?.relativeToOrNull(root)?.path?.takeIf { it.isNotEmpty() && it != "." } ?: ""
                NoteEntry(f.name, f.absolutePath, sub, kind)
            }
            .take(3000)
            .sortedWith(compareBy({ it.subDir.lowercase() }, { it.name.lowercase() }))
            .toList()
    }
}

fun kindOf(name: String): NoteKind? {
    val n = name.lowercase()
    return when {
        n.endsWith(".pdf") -> NoteKind.PDF
        n.endsWith(".spd") || n.endsWith(".sdoc") || n.endsWith(".snb") -> NoteKind.RAW
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
    vm: NotesViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
                },
                actions = {
                    if (state.dir.isNotBlank()) {
                        IconButton(onClick = { vm.rescan() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                        }
                    }
                    IconButton(onClick = { showPicker = true }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "Choose folder")
                    }
                }
            )
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when {
                state.dir.isBlank() -> SetupCard(Modifier.align(Alignment.Center)) { showPicker = true }
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.entries.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "No notes found in:\n${state.dir}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "\nExport notes from Samsung Notes (⋮ → Save as file → PDF / Text / Image) " +
                            "into this folder, or copy the raw .spd files here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    item {
                        Text(
                            state.dir,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    items(state.entries, key = { it.path }) { entry ->
                        NoteRow(entry) {
                            when (entry.kind) {
                                NoteKind.PDF -> onOpenPdf(entry.path)
                                NoteKind.RAW, NoteKind.TEXT, NoteKind.IMAGE -> onOpenNote(entry.path)
                                NoteKind.OTHER -> openWithSystem(context, File(entry.path))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        NotesFolderPickerDialog(
            initial = state.dir,
            onDismiss = { showPicker = false },
            onSelect = { path -> vm.setDir(path); showPicker = false }
        )
    }
}

@Composable
private fun NoteRow(entry: NoteEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when (entry.kind) {
                NoteKind.IMAGE -> Icons.Filled.Image
                else -> Icons.Filled.Description
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
            val label = buildString {
                append(kindLabel(entry.kind))
                if (entry.subDir.isNotEmpty()) append("  •  ${entry.subDir}")
            }
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun kindLabel(kind: NoteKind) = when (kind) {
    NoteKind.PDF -> "PDF"
    NoteKind.RAW -> "Samsung Notes"
    NoteKind.TEXT -> "Text"
    NoteKind.IMAGE -> "Image"
    NoteKind.OTHER -> "Document"
}

@Composable
private fun SetupCard(modifier: Modifier, onChoose: () -> Unit) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Filled.Description, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary)
        Text("Import your notes", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pick the folder where your notes live. OpenSync reads PDF, text and image exports, " +
                "and best-effort parses raw Samsung Notes (.spd/.sdoc) files.\n\n" +
                "Tip: in Samsung Notes, ⋮ → Save as file → PDF or Text, and save into this folder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onChoose) { Text("Choose notes folder") }
    }
}

/** Minimal device-folder browser for choosing the notes directory. */
@Composable
private fun NotesFolderPickerDialog(initial: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val root = remember {
        val f = File(initial)
        when {
            initial.isNotBlank() && f.isDirectory -> f
            else -> Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
        }
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
            LazyColumn(Modifier.height(340.dp)) {
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
