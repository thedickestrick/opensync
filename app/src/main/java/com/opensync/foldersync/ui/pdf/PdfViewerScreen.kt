package com.opensync.foldersync.ui.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.opensync.foldersync.ui.common.verticalScrollbar
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.share.ShareUtil
import com.opensync.foldersync.pdf.PdfDoc
import com.opensync.foldersync.pdf.PdfRequest
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    onBack: () -> Unit,
    onEditPages: () -> Unit = {},
    onAnnotate: () -> Unit = {},
    onSign: () -> Unit = {},
    onFillForm: () -> Unit = {}
) {
    val path = remember { PdfRequest.path }
    val context = LocalContext.current
    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
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
                    IconButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(1f) }, enabled = zoom > 1f) {
                        Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out")
                    }
                    IconButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(MAX_ZOOM) }, enabled = zoom < MAX_ZOOM) {
                        Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    menuOpen = false
                                    path?.let { ShareUtil.shareFiles(context, listOf(File(it))) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Annotate") },
                                onClick = { menuOpen = false; onAnnotate() }
                            )
                            DropdownMenuItem(
                                text = { Text("Sign") },
                                onClick = { menuOpen = false; onSign() }
                            )
                            DropdownMenuItem(
                                text = { Text("Fill form") },
                                onClick = { menuOpen = false; onFillForm() }
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
                else -> ZoomablePages(currentDoc, pageCount, zoom, onZoom = { zoom = it })
            }
        }
    }
}

/**
 * The pages, zoomable by pinch, double-tap or the toolbar buttons. While fingers are down the view
 * is only magnified (cheap and smooth); when they lift, the pages are laid out and drawn again at
 * the new size so text is sharp, with the spot under the fingers kept where it was. Zoomed in, one
 * finger pans in any direction.
 */
@Composable
private fun ZoomablePages(doc: PdfDoc, pageCount: Int, zoom: Float, onZoom: (Float) -> Unit) {
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Live magnification during a pinch, and the point it's centred on.
    var gestureScale by remember { mutableFloatStateOf(1f) }
    var focus by remember { mutableStateOf(Offset.Zero) }
    val latestZoom by rememberUpdatedState(zoom)

    /** Settles on [newZoom], keeping the content under [at] (viewport coordinates) in place. */
    fun commit(newZoom: Float, at: Offset) {
        val target = newZoom.coerceIn(1f, MAX_ZOOM)
        val factor = target / latestZoom
        gestureScale = 1f
        if (factor == 1f) return
        val firstIndex = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        val x = hScroll.value
        onZoom(target)
        scope.launch {
            withFrameNanos { }   // let the pages lay out at their new width first
            withFrameNanos { }
            hScroll.scrollTo(((x + at.x) * factor - at.x).roundToInt().coerceIn(0, hScroll.maxValue))
            listState.scrollToItem(firstIndex, ((firstOffset + at.y) * factor - at.y).roundToInt().coerceAtLeast(0))
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .verticalScrollbar(listState)
            .pointerInput(Unit) {
                // Initial pass, so a pinch is seen before the list and never turns into a scroll.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var pinched = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.filter { it.pressed }
                        if (down.isEmpty()) break
                        if (down.size >= 2) {
                            if (!pinched) focus = event.calculateCentroid(useCurrent = true)
                            pinched = true
                            gestureScale = (gestureScale * event.calculateZoom())
                                .coerceIn(1f / latestZoom, MAX_ZOOM / latestZoom)
                            event.changes.forEach { it.consume() }
                        } else if (pinched) {
                            // One finger left over from a pinch shouldn't fling the list.
                            event.changes.forEach { it.consume() }
                        }
                    }
                    if (pinched) commit(latestZoom * gestureScale, focus)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { at ->
                    commit(if (latestZoom > 1.01f) 1f else DOUBLE_TAP_ZOOM, at)
                })
            }
    ) {
        val viewportPx = constraints.maxWidth
        val contentWidth = with(density) { (viewportPx * zoom).toDp() }
        // Sharp up to 2.5× the screen; beyond that a page bitmap costs more memory than it's worth.
        val renderPx = (viewportPx * zoom).toInt().coerceAtMost((viewportPx * 2.5f).toInt().coerceAtMost(4096))
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = gestureScale
                    scaleY = gestureScale
                    transformOrigin = TransformOrigin(
                        if (size.width > 0) focus.x / size.width else 0.5f,
                        if (size.height > 0) focus.y / size.height else 0.5f
                    )
                }
                .horizontalScroll(hScroll, enabled = zoom > 1f)
        ) {
            LazyColumn(
                modifier = Modifier.requiredWidth(contentWidth).fillMaxHeight(),
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(pageCount) { index ->
                    PdfPageView(doc = doc, index = index, width = contentWidth, renderPx = renderPx)
                }
            }
        }
    }
}

@Composable
private fun PdfPageView(doc: PdfDoc, index: Int, width: Dp, renderPx: Int) {
    // Kept across re-renders: the old bitmap stays up, stretched, until the sharper one is ready.
    val bitmap by produceState<Bitmap?>(initialValue = null, index, renderPx) {
        runCatching { doc.renderPage(index, renderPx) }.getOrNull()?.let { value = it }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .width(width)
                .aspectRatio(bmp.width.toFloat() / bmp.height)
        )
    } else {
        Box(Modifier.width(width).height(320.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
