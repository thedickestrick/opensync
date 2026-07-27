package com.opensync.foldersync.ui.pdf

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.pdf.PdfDoc
import com.opensync.foldersync.pdf.PdfRequest
import com.opensync.foldersync.pdf.PdfSigner
import com.opensync.foldersync.pdf.SignPlacement
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfSignScreen(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val path = remember { PdfRequest.path }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(0) }
    var signature by remember { mutableStateOf<Bitmap?>(null) }
    var placements by remember { mutableStateOf<List<SignPlacement>>(emptyList()) }
    var sizePercent by remember { mutableStateOf(0.3f) }
    var showPad by remember { mutableStateOf(false) }

    DisposableEffect(path) {
        if (path == null) error = "No file to sign"
        else try {
            val opened = PdfDoc.open(File(path))
            doc = opened
            pageCount = opened.pageCount
        } catch (e: Exception) {
            error = e.message ?: "Cannot open this PDF"
        }
        onDispose { doc?.close() }
    }

    fun save() {
        val src = path ?: return
        val sig = signature
        if (sig == null || placements.isEmpty()) {
            Toast.makeText(context, "Draw a signature and tap the page to place it", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            busy = true
            try {
                val dest = File(File(src).parentFile, "${File(src).nameWithoutExtension} (signed).pdf")
                PdfSigner.save(File(src), dest, sig, placements)
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
                title = { Text("Sign") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { placements = placements.dropLast(1) }, enabled = placements.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { save() }, enabled = !busy) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { showPad = true }) {
                            Icon(Icons.Filled.Gesture, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(if (signature == null) "  Draw signature" else "  Redraw")
                        }
                        Text(
                            if (signature == null) "  Draw, then tap the page" else "  Tap the page to place",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (signature != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Size", style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = sizePercent,
                                onValueChange = { sizePercent = it },
                                valueRange = 0.1f..0.6f,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        IconButton(onClick = { if (currentPage > 0) currentPage-- }, enabled = currentPage > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page")
                        }
                        Text("Page ${currentPage + 1} / $pageCount", style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { if (currentPage < pageCount - 1) currentPage++ }, enabled = currentPage < pageCount - 1) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page")
                        }
                    }
                }
            }
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            val d = doc
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp))
                d == null || pageCount == 0 -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    val bitmap by produceState<Bitmap?>(initialValue = null, currentPage) {
                        value = runCatching { d.renderPage(currentPage, 1400) }.getOrNull()
                    }
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val bmp = bitmap
                        if (bmp == null) {
                            CircularProgressIndicator()
                        } else {
                            val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
                            val boxAspect = maxWidth.value / maxHeight.value
                            val targetW = if (aspect >= boxAspect) maxWidth else maxHeight * aspect
                            val targetH = if (aspect >= boxAspect) maxWidth / aspect else maxHeight
                            Box(Modifier.size(width = targetW, height = targetH)) {
                                Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                                Box(Modifier.fillMaxSize().pointerInput(currentPage, signature != null, sizePercent) {
                                    detectTapGestures { off ->
                                        if (signature != null) {
                                            placements = placements + SignPlacement(
                                                currentPage, off.x / size.width, off.y / size.height, sizePercent
                                            )
                                        }
                                    }
                                })
                                val sig = signature
                                if (sig != null) {
                                    val sigAspect = sig.height.toFloat() / sig.width.coerceAtLeast(1)
                                    placements.filter { it.pageIndex == currentPage }.forEach { pl ->
                                        Image(
                                            bitmap = sig.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .offset(x = targetW * pl.x, y = targetH * pl.y)
                                                .size(width = targetW * pl.normWidth, height = targetW * pl.normWidth * sigAspect),
                                            contentScale = ContentScale.FillBounds
                                        )
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

    if (showPad) {
        SignaturePadDialog(onDismiss = { showPad = false }, onDone = { bmp -> signature = bmp; showPad = false })
    }
}

@Composable
private fun SignaturePadDialog(onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    var strokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draw your signature") },
        text = {
            Box(
                Modifier.fillMaxWidth().height(200.dp)
                    .border(1.dp, Color.Gray)
                    .background(Color.White)
            ) {
                Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { off -> current = listOf(Offset(off.x / size.width, off.y / size.height)) },
                        onDrag = { c, _ ->
                            c.consume()
                            current = current + Offset(c.position.x / size.width, c.position.y / size.height)
                        },
                        onDragEnd = { strokes = strokes + listOf(current); current = emptyList() },
                        onDragCancel = { current = emptyList() }
                    )
                }) {
                    val cw = size.width
                    val ch = size.height
                    fun draw(s: List<Offset>) {
                        if (s.size < 2) return
                        val p = Path()
                        p.moveTo(s[0].x * cw, s[0].y * ch)
                        for (i in 1 until s.size) p.lineTo(s[i].x * cw, s[i].y * ch)
                        drawPath(p, Color.Black, style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    strokes.forEach { draw(it) }
                    draw(current)
                }
            }
        },
        confirmButton = {
            val all = if (current.isNotEmpty()) strokes + listOf(current) else strokes
            TextButton(onClick = { onDone(renderSignature(all, 800, 300)) }, enabled = all.isNotEmpty()) {
                Text("Done")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes = emptyList(); current = emptyList() }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private fun renderSignature(strokes: List<List<Offset>>, width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        strokeWidth = width * 0.006f
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        isAntiAlias = true
    }
    for (stroke in strokes) {
        if (stroke.size < 2) continue
        val p = android.graphics.Path()
        p.moveTo(stroke[0].x * width, stroke[0].y * height)
        for (i in 1 until stroke.size) p.lineTo(stroke[i].x * width, stroke[i].y * height)
        canvas.drawPath(p, paint)
    }
    return bitmap
}
