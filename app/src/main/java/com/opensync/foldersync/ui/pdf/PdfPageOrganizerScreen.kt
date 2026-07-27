package com.opensync.foldersync.ui.pdf

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.pdf.PageOp
import com.opensync.foldersync.pdf.PdfDoc
import com.opensync.foldersync.pdf.PdfEditor
import com.opensync.foldersync.pdf.PdfRequest
import kotlinx.coroutines.launch
import java.io.File

private data class PageItem(val sourceIndex: Int, val rotation: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPageOrganizerScreen(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val path = remember { PdfRequest.path }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var pages by remember { mutableStateOf<List<PageItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(path) {
        if (path == null) {
            error = "No file to edit"
        } else {
            try {
                val opened = PdfDoc.open(File(path))
                doc = opened
                pages = (0 until opened.pageCount).map { PageItem(it, 0) }
            } catch (e: Exception) {
                error = e.message ?: "Cannot open this PDF"
            }
        }
        onDispose { doc?.close() }
    }

    val mergeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && path != null) {
            scope.launch {
                busy = true
                try {
                    val temp = File(context.cacheDir, "merge_source.pdf")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { input.copyTo(it) }
                    }
                    val dest = editedDest(File(path))
                    PdfEditor.merge(listOf(File(path), temp), dest)
                    temp.delete()
                    busy = false
                    Toast.makeText(context, "Merged → ${dest.name}", Toast.LENGTH_LONG).show()
                    onSaved(dest.absolutePath)
                } catch (e: Exception) {
                    busy = false
                    error = e.message ?: "Merge failed"
                }
            }
        }
    }

    fun save() {
        val src = path ?: return
        if (pages.isEmpty()) {
            Toast.makeText(context, "No pages left to save", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            busy = true
            try {
                val dest = editedDest(File(src))
                PdfEditor.savePages(File(src), pages.map { PageOp(it.sourceIndex, it.rotation) }, dest)
                busy = false
                Toast.makeText(context, "Saved → ${dest.name}", Toast.LENGTH_LONG).show()
                onSaved(dest.absolutePath)
            } catch (e: Exception) {
                busy = false
                error = e.message ?: "Save failed"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Organize pages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { mergeLauncher.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Filled.MergeType, contentDescription = "Merge another PDF")
                    }
                    IconButton(onClick = { save() }, enabled = !busy) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when {
                error != null -> Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
                doc == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(pages, key = { it.sourceIndex }) { item ->
                        val position = pages.indexOf(item)
                        PageRow(
                            doc = doc!!,
                            item = item,
                            position = position,
                            total = pages.size,
                            onRotate = {
                                pages = pages.map {
                                    if (it.sourceIndex == item.sourceIndex)
                                        it.copy(rotation = (it.rotation + 90) % 360) else it
                                }
                            },
                            onDelete = { pages = pages.filter { it.sourceIndex != item.sourceIndex } },
                            onUp = { if (position > 0) pages = pages.swap(position, position - 1) },
                            onDown = { if (position < pages.size - 1) pages = pages.swap(position, position + 1) }
                        )
                    }
                }
            }

            if (busy) {
                Surface(
                    Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.4f)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
private fun PageRow(
    doc: PdfDoc,
    item: PageItem,
    position: Int,
    total: Int,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val bitmap by produceState<Bitmap?>(initialValue = null, item.sourceIndex) {
                value = runCatching { doc.renderPage(item.sourceIndex, 220) }.getOrNull()
            }
            Box(Modifier.width(80.dp).height(110.dp), contentAlignment = Alignment.Center) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().graphicsLayer { rotationZ = item.rotation.toFloat() }
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            Text(
                "Page ${position + 1} of $total",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Column {
                IconButton(onClick = onUp, enabled = position > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = onDown, enabled = position < total - 1) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                }
            }
            IconButton(onClick = onRotate) {
                Icon(Icons.Filled.RotateRight, contentDescription = "Rotate")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete page")
            }
        }
    }
}

private fun <T> List<T>.swap(a: Int, b: Int): List<T> {
    val copy = toMutableList()
    val tmp = copy[a]; copy[a] = copy[b]; copy[b] = tmp
    return copy
}

private fun editedDest(src: File): File {
    val base = src.nameWithoutExtension
    return File(src.parentFile, "$base (edited).pdf")
}
