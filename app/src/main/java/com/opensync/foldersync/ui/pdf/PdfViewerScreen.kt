package com.opensync.foldersync.ui.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.pdf.PdfDoc
import com.opensync.foldersync.pdf.PdfRequest
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    onBack: () -> Unit,
    onEditPages: () -> Unit = {},
    onAnnotate: () -> Unit = {},
    onSign: () -> Unit = {}
) {
    val path = remember { PdfRequest.path }
    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    var menuOpen by remember { mutableStateOf(false) }

    DisposableEffect(path) {
        if (path == null) {
            error = "No file to open"
        } else {
            try {
                val opened = PdfDoc.open(File(path))
                doc = opened
                pageCount = opened.pageCount
            } catch (e: Exception) {
                error = e.message ?: "Cannot open this PDF"
            }
        }
        onDispose { doc?.close() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        path?.let { File(it).name } ?: "PDF",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) }) {
                        Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out")
                    }
                    IconButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(4f) }) {
                        Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Annotate") },
                                onClick = { menuOpen = false; onAnnotate() }
                            )
                            DropdownMenuItem(
                                text = { Text("Sign") },
                                onClick = { menuOpen = false; onSign() }
                            )
                            DropdownMenuItem(
                                text = { Text("Organize pages") },
                                onClick = { menuOpen = false; onEditPages() }
                            )
                        }
                    }
                }
            )
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            val currentDoc = doc
            when {
                error != null -> Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
                currentDoc == null || pageCount == 0 ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val baseWidthPx = constraints.maxWidth
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(pageCount) { index ->
                            PdfPageView(
                                doc = currentDoc,
                                index = index,
                                widthPx = (baseWidthPx * zoom).toInt(),
                                allowPan = zoom > 1f
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageView(doc: PdfDoc, index: Int, widthPx: Int, allowPan: Boolean) {
    val bitmap by produceState<Bitmap?>(initialValue = null, index, widthPx) {
        value = runCatching { doc.renderPage(index, widthPx) }.getOrNull()
    }
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            val scroll = rememberScrollState()
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                modifier = if (allowPan) Modifier.horizontalScroll(scroll) else Modifier
            )
        } else {
            Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
