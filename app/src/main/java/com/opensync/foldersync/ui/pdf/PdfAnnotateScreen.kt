package com.opensync.foldersync.ui.pdf

import android.graphics.Bitmap
import android.graphics.PointF
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.pdf.AnnotStroke
import com.opensync.foldersync.pdf.AnnotText
import com.opensync.foldersync.pdf.PageAnnotations
import com.opensync.foldersync.pdf.PdfAnnotator
import com.opensync.foldersync.pdf.PdfDoc
import com.opensync.foldersync.pdf.PdfRequest
import kotlinx.coroutines.launch
import java.io.File

private enum class Tool { PEN, HIGHLIGHTER, TEXT }

private val PALETTE = listOf(
    Color.Black, Color(0xFFF44336), Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFFFFEB3B)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfAnnotateScreen(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val path = remember { PdfRequest.path }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    var currentPage by remember { mutableStateOf(0) }
    var tool by remember { mutableStateOf(Tool.PEN) }
    var color by remember { mutableStateOf(Color.Black) }
    var annById by remember { mutableStateOf<Map<Int, PageAnnotations>>(emptyMap()) }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var showTextDialog by remember { mutableStateOf(false) }
    var pendingTextPos by remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(path) {
        if (path == null) error = "No file to annotate"
        else try {
            val opened = PdfDoc.open(File(path))
            doc = opened
            pageCount = opened.pageCount
        } catch (e: Exception) {
            error = e.message ?: "Cannot open this PDF"
        }
        onDispose { doc?.close() }
    }

    fun widthNorm() = if (tool == Tool.HIGHLIGHTER) 0.02f else 0.004f

    fun commitStroke() {
        if (currentStroke.isNotEmpty()) {
            val stroke = AnnotStroke(
                points = currentStroke.map { PointF(it.x, it.y) },
                colorArgb = color.toArgb(),
                normWidth = widthNorm(),
                highlight = tool == Tool.HIGHLIGHTER
            )
            val page = annById[currentPage] ?: PageAnnotations()
            annById = annById + (currentPage to page.copy(strokes = page.strokes + stroke))
        }
        currentStroke = emptyList()
    }

    fun undo() {
        val page = annById[currentPage] ?: return
        annById = annById + (currentPage to when {
            page.strokes.isNotEmpty() -> page.copy(strokes = page.strokes.dropLast(1))
            page.texts.isNotEmpty() -> page.copy(texts = page.texts.dropLast(1))
            else -> page
        })
    }

    fun save() {
        val src = path ?: return
        scope.launch {
            busy = true
            try {
                val dest = annotatedDest(File(src))
                PdfAnnotator.save(File(src), dest, annById)
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
                title = { Text("Annotate") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { undo() }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { save() }, enabled = !busy) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        },
        bottomBar = {
            AnnotateToolbar(
                tool = tool,
                onTool = { tool = it },
                color = color,
                onColor = { color = it },
                page = currentPage,
                pageCount = pageCount,
                onPrev = { if (currentPage > 0) currentPage-- },
                onNext = { if (currentPage < pageCount - 1) currentPage++ }
            )
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            val d = doc
            when {
                error != null -> Text(
                    error!!, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
                d == null || pageCount == 0 -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    val bitmap by produceState<Bitmap?>(initialValue = null, currentPage) {
                        value = runCatching { d.renderPage(currentPage, 1400) }.getOrNull()
                    }
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val bmp = bitmap
                        if (bmp == null) {
                            CircularProgressIndicator(Modifier.padding(32.dp))
                        } else {
                            val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
                            val boxAspect = maxWidth.value / maxHeight.value
                            val targetW = if (aspect >= boxAspect) maxWidth else maxHeight * aspect
                            val targetH = if (aspect >= boxAspect) maxWidth / aspect else maxHeight
                            Box(Modifier.size(width = targetW, height = targetH)) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds
                                )
                                Canvas(
                                    Modifier.fillMaxSize().pointerInput(currentPage, tool) {
                                        if (tool == Tool.TEXT) {
                                            detectTapGestures { off ->
                                                pendingTextPos = Offset(off.x / size.width, off.y / size.height)
                                                showTextDialog = true
                                            }
                                        } else {
                                            detectDragGestures(
                                                onDragStart = { off ->
                                                    currentStroke = listOf(Offset(off.x / size.width, off.y / size.height))
                                                },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    currentStroke = currentStroke +
                                                        Offset(change.position.x / size.width, change.position.y / size.height)
                                                },
                                                onDragEnd = { commitStroke() },
                                                onDragCancel = { currentStroke = emptyList() }
                                            )
                                        }
                                    }
                                ) {
                                    val cw = size.width
                                    val ch = size.height
                                    fun ink(points: List<Offset>, col: Color, widthPx: Float, alpha: Float) {
                                        if (points.isEmpty()) return
                                        val p = Path()
                                        p.moveTo(points[0].x * cw, points[0].y * ch)
                                        for (i in 1 until points.size) p.lineTo(points[i].x * cw, points[i].y * ch)
                                        drawPath(
                                            p, col.copy(alpha = alpha),
                                            style = Stroke(widthPx.coerceAtLeast(1f), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                        )
                                    }
                                    val pageAnn = annById[currentPage]
                                    pageAnn?.strokes?.forEach { s ->
                                        ink(
                                            s.points.map { Offset(it.x, it.y) },
                                            Color(s.colorArgb), s.normWidth * cw,
                                            if (s.highlight) 0.35f else 1f
                                        )
                                    }
                                    if (currentStroke.isNotEmpty()) {
                                        ink(currentStroke, color, widthNorm() * cw, if (tool == Tool.HIGHLIGHTER) 0.35f else 1f)
                                    }
                                    pageAnn?.texts?.forEach { t ->
                                        val paint = android.graphics.Paint().apply {
                                            this.color = t.colorArgb
                                            textSize = t.normSize * cw
                                            isAntiAlias = true
                                        }
                                        drawContext.canvas.nativeCanvas.drawText(t.text, t.x * cw, t.y * ch, paint)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (busy) {
                Surface(Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.4f)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }

    if (showTextDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Add text") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = false,
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            val page = annById[currentPage] ?: PageAnnotations()
                            val note = AnnotText(pendingTextPos.x, pendingTextPos.y, text.trim(), color.toArgb(), 0.03f)
                            annById = annById + (currentPage to page.copy(texts = page.texts + note))
                        }
                        showTextDialog = false
                    }
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showTextDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AnnotateToolbar(
    tool: Tool,
    onTool: (Tool) -> Unit,
    color: Color,
    onColor: (Color) -> Unit,
    page: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.Filled.Edit, "Pen", tool == Tool.PEN) { onTool(Tool.PEN) }
                ToolButton(Icons.Filled.Brush, "Highlighter", tool == Tool.HIGHLIGHTER) { onTool(Tool.HIGHLIGHTER) }
                ToolButton(Icons.Filled.TextFields, "Text", tool == Tool.TEXT) { onTool(Tool.TEXT) }
                Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PALETTE.forEach { swatch ->
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(swatch)
                                .border(2.dp, if (swatch == color) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                .clickable { onColor(swatch) }
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = onPrev, enabled = page > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page")
                }
                Text("Page ${page + 1} / $pageCount", style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = onNext, enabled = page < pageCount - 1) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page")
                }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Box(Modifier.clip(CircleShape).background(bg)) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = desc) }
    }
}

private fun annotatedDest(src: File): File =
    File(src.parentFile, "${src.nameWithoutExtension} (annotated).pdf")
