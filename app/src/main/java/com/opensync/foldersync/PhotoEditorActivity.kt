package com.opensync.foldersync

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opensync.foldersync.ui.theme.OpenSyncTheme
import com.opensync.foldersync.videoedit.DECO_COLORS
import com.opensync.foldersync.videoedit.DecorateMode
import com.opensync.foldersync.videoedit.DecorationLayer
import com.opensync.foldersync.videoedit.DecoratePanel
import com.opensync.foldersync.videoedit.StickerDecoration
import com.opensync.foldersync.videoedit.TextDecoration
import com.opensync.foldersync.videoedit.TextDecorationDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

/**
 * A photo editor modelled on the Samsung Gallery one, sharing its tools with
 * [com.opensync.foldersync.videoedit.VideoEditorActivity] (MediaEditorCommon.kt, Decorations.kt):
 * - Transform: crop (free or a fixed ratio), straighten (zooms to fill), rotate, flip
 * - Filters with intensity; Tone (brightness, exposure, contrast, highlights, shadows, saturation,
 *   warmth, tint, sharpness, vignette, and Auto)
 * - Decorate: text, emoji stickers and freehand drawing
 * - Undo/redo, hold to compare with the original, save over the original or as a copy at a
 *   chosen size, keeping the format and EXIF details (date taken, location, camera).
 * Reachable internally (edit_path extra) and as a system image editor (ACTION_EDIT). A photo the
 * gallery downloaded from one of the user's accounts is saved back to that account (see EditUpload.kt).
 */
class PhotoEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Paths and accounts are only taken from OpenSync itself, never from another app's request.
        val uri = intent?.data
            ?: intent?.getStringExtra("edit_path")?.takeIf { openedByThisApp }?.let { Uri.fromFile(File(it)) }
        if (uri == null) { finish(); return }
        val cloud = cloudOrigin()
        setContent {
            OpenSyncTheme {
                PhotoEditorScreen(uri = uri, cloud = cloud, onSaved = { setResult(Activity.RESULT_OK) }, onClose = { finish() })
            }
        }
    }
}

private enum class PhotoTool(val label: String, val icon: ImageVector) {
    TRANSFORM("Transform", Icons.Filled.Crop),
    FILTERS("Filters", Icons.Filled.PhotoFilter),
    TONE("Tone", Icons.Filled.Tune),
    DECORATE("Decorate", Icons.Filled.EmojiEmotions)
}

/** The preview works on a copy this big (longer side); the save starts again from the full image. */
private const val PREVIEW_MAX_DIM = 1600

/** What the preview image depends on (colour is a live GPU filter on top, decorations a layer). */
private data class PreviewKey(
    val rotation: Int,
    val flipH: Boolean,
    val flipV: Boolean,
    val straighten: Float,
    val crop: NormRect?, // null while cropping: the whole frame is shown under the crop box
    val tone: ToneEdits
)

@Composable
private fun PhotoEditorScreen(uri: Uri, cloud: CloudOrigin?, onSaved: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val loaded by produceState<Result<Pair<Bitmap, PhotoSource>>?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeUpright(context, uri, maxDim = PREVIEW_MAX_DIM) to readPhotoSource(context, uri, cloud) }
        }
    }
    val base = loaded?.getOrNull()?.first
    val source = loaded?.getOrNull()?.second
    val thumb by produceState<Bitmap?>(initialValue = null, base) {
        value = base?.let { withContext(Dispatchers.Default) { Bitmap.createScaledBitmap(it, 140, (140f * it.height / it.width).toInt().coerceAtLeast(1), true) } }
    }

    // Edits + undo history. Every change goes through update() so it can be undone.
    var edits by remember { mutableStateOf(PhotoEdits()) }
    val history = remember { EditHistory<PhotoEdits>() }
    var historyVersion by remember { mutableIntStateOf(0) }
    fun update(new: PhotoEdits, key: Any? = null) {
        if (new == edits) return
        history.record(edits, key)
        edits = new
        historyVersion++
    }

    var tool by remember { mutableStateOf(PhotoTool.TRANSFORM) }
    var aspect by remember { mutableStateOf<Float?>(null) }
    var toneIndex by remember { mutableIntStateOf(0) }
    var decorateMode by remember { mutableStateOf(DecorateMode.TEXT) }
    var selectedDecoration by remember { mutableStateOf<Long?>(null) }
    var drawColor by remember { mutableIntStateOf(DECO_COLORS[2]) }
    var drawWidth by remember { mutableStateOf(0.012f) }
    var textDialog by remember { mutableStateOf<TextDecoration?>(null) }
    var showTextDialog by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var upload by remember { mutableStateOf<UploadStatus?>(null) } // null until the upload connects
    var uploadFailure by remember { mutableStateOf<SavedEdit?>(null) }
    var showSave by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var saveSize by remember { mutableStateOf(PHOTO_SIZES.first()) }
    val cropping = tool == PhotoTool.TRANSFORM

    // The preview pipeline runs off the main thread. Conflated rather than restarted, so a slider
    // drag keeps showing new frames instead of cancelling every one before it finishes.
    var lookInput by remember { mutableStateOf<Bitmap?>(null) } // transformed + cropped, before tone
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(base) {
        val b = base ?: return@LaunchedEffect
        var transformKey: PhotoEdits? = null
        var transformed: Bitmap = b
        var cropKey: Any? = Unit
        var cropped: Bitmap = b
        snapshotFlow {
            PreviewKey(
                edits.rotation, edits.flipH, edits.flipV, edits.straighten,
                if (tool == PhotoTool.TRANSFORM) null else edits.crop,
                edits.tone
            )
        }.conflate().collect { k ->
            val shown = withContext(Dispatchers.Default) {
                val geometry = PhotoEdits(rotation = k.rotation, flipH = k.flipH, flipV = k.flipV, straighten = k.straighten)
                if (geometry != transformKey) {
                    transformed = applyTransform(b, geometry)
                    transformKey = geometry
                    cropKey = Unit
                }
                if (k.crop != cropKey) {
                    cropped = k.crop?.let { cropBitmap(transformed, it) } ?: transformed
                    cropKey = k.crop
                }
                renderLook(cropped, k.tone, null)
            }
            lookInput = cropped
            preview = shown
        }
    }

    val colorFilter = remember(edits.color) {
        ColorFilter.colorMatrix(ComposeColorMatrix(colorMatrixFor(edits.color).array))
    }

    // Keep the screen on while saving, and don't let Back abandon a save or drop unsaved edits.
    val view = LocalView.current
    LaunchedEffect(saving) { view.keepScreenOn = saving }
    BackHandler(enabled = saving) {}
    BackHandler(enabled = !saving && edits.modified) { showDiscard = true }
    fun close() {
        if (edits.modified) showDiscard = true else onClose()
    }

    fun save(overwrite: Boolean, size: PhotoSize) {
        showSave = false
        saving = true
        uploading = false
        upload = null
        val e = edits
        scope.launch {
            val result = try {
                Result.success(saveEditedPhoto(
                    context, uri, e, size, overwrite, cloud,
                    onUploading = { uploading = true },
                    onUploadProgress = { upload = it }
                ))
            } catch (c: CancellationException) {
                throw c // the editor was closed; nothing to report
            } catch (t: Throwable) {
                Result.failure(t)
            }
            saving = false
            result.onSuccess { saved ->
                onSaved()
                if (saved.uploadError != null) {
                    uploadFailure = saved // closes once the user has read it
                } else {
                    Toast.makeText(context, saved.message(if (overwrite) "Photo" else "Copy", cloud), Toast.LENGTH_SHORT).show()
                    onClose()
                }
            }.onFailure {
                val why = if (it is OutOfMemoryError) "not enough memory" else it.message ?: it.javaClass.simpleName
                Toast.makeText(context, "Couldn't save: $why", Toast.LENGTH_LONG).show()
            }
        }
    }

    // background before systemBarsPadding: dark fills behind the bars, content is inset off them.
    Column(Modifier.fillMaxSize().background(EDITOR_BG).systemBarsPadding()) {
        // Top bar
        val canUndo = historyVersion >= 0 && history.canUndo
        val canRedo = historyVersion >= 0 && history.canRedo
        val canCompare = edits.modified && base != null && !saving
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { close() }, enabled = !saving) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { history.undo(edits)?.let { edits = it; historyVersion++ } }, enabled = canUndo && !saving) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (canUndo) Color.White else Color.Gray)
            }
            IconButton(onClick = { history.redo(edits)?.let { edits = it; historyVersion++ } }, enabled = canRedo && !saving) {
                Icon(Icons.AutoMirrored.Filled.Redo, "Redo", tint = if (canRedo) Color.White else Color.Gray)
            }
            // Hold to see the original.
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).then(
                    if (!canCompare) Modifier else Modifier.pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            comparing = true
                            try { awaitRelease() } finally { comparing = false }
                        })
                    }
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Compare, "Hold to compare with the original", tint = if (canCompare) Color.White else Color.Gray)
            }
            IconButton(
                onClick = { update(PhotoEdits()); aspect = null; selectedDecoration = null },
                enabled = edits.modified && !saving
            ) {
                Icon(Icons.Filled.Refresh, "Revert all edits", tint = if (edits.modified) Color.White else Color.Gray)
            }
            TextButton(onClick = { showSave = true }, enabled = edits.modified && !saving && source != null) {
                Icon(Icons.Filled.Save, null, tint = if (edits.modified) Color.White else Color.Gray)
                Spacer(Modifier.width(6.dp))
                Text("Save", color = if (edits.modified) Color.White else Color.Gray)
            }
        }

        // Preview
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            val shown = if (comparing) base else preview
            when {
                loaded == null -> CircularProgressIndicator(color = Color.White)
                base == null -> Text("Couldn't open this photo", color = Color(0xFFBBBBBB))
                shown == null -> CircularProgressIndicator(color = Color.White)
                else -> {
                    val image = remember(shown) { shown.asImageBitmap() }
                    val frameAspect = shown.width.toFloat() / shown.height.toFloat()
                    Box(Modifier.aspectRatio(frameAspect.coerceIn(0.05f, 20f))) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            colorFilter = if (comparing) null else colorFilter,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (!comparing) {
                            if (cropping) {
                                CropOverlay(
                                    imageAspect = frameAspect,
                                    crop = edits.crop,
                                    aspect = aspect,
                                    onCrop = { update(edits.copy(crop = it), "crop") }
                                )
                            } else {
                                // Decorations belong to the finished (cropped) picture, so they're hidden while cropping.
                                DecorationLayer(
                                    decorations = edits.decorations,
                                    strokes = edits.strokes,
                                    mode = if (tool == PhotoTool.DECORATE) decorateMode else null,
                                    selectedId = selectedDecoration,
                                    drawColor = drawColor,
                                    drawWidth = drawWidth,
                                    onSelect = { selectedDecoration = it },
                                    onUpdate = { d -> update(edits.copy(decorations = edits.decorations.map { if (it.id == d.id) d else it }), "deco:${d.id}") },
                                    // One key per stroke: a stroke is one undo step, the next is another.
                                    onStrokes = { update(edits.copy(strokes = it), "stroke:${it.size}") }
                                )
                            }
                        }
                    }
                }
            }
            if (comparing) {
                Text(
                    "Original",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).clip(RoundedCornerShape(12.dp))
                        .background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            if (saving) {
                // Swallows touches so nothing can be edited mid-save.
                Box(
                    Modifier.fillMaxSize().background(Color(0x99000000))
                        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
                    contentAlignment = Alignment.Center
                ) {
                    // Making the photo has no steps to count, so its bar just moves.
                    if (uploading) UploadProgressBar(cloud, upload) else SaveProgress("Saving…", fraction = null)
                }
            }
        }

        // Tool panel
        Column(Modifier.fillMaxWidth().background(EDITOR_PANEL_BG).padding(bottom = 4.dp)) {
            when (tool) {
                PhotoTool.TRANSFORM -> CropPanel(
                    aspect = aspect,
                    straighten = edits.straighten,
                    onAspect = { r ->
                        aspect = r
                        val ia = base?.let {
                            if (edits.rotation % 2 == 1) it.height.toFloat() / it.width else it.width.toFloat() / it.height
                        } ?: 1f
                        update(edits.copy(crop = centeredCrop(ia, r)))
                    },
                    onStraighten = { update(edits.copy(straighten = it), "straighten") },
                    onRotateLeft = { update(edits.rotated(clockwise = false)); aspect = turnedAspect(aspect) },
                    onRotateRight = { update(edits.rotated(clockwise = true)); aspect = turnedAspect(aspect) },
                    onFlipH = { update(edits.mirrored()) },
                    onFlipV = { update(edits.flipped()) }
                )
                PhotoTool.FILTERS -> FilterPanel(
                    thumb = thumb,
                    selected = edits.color.filter,
                    strength = edits.color.filterStrength,
                    onSelect = { update(edits.copy(color = edits.color.copy(filter = it, filterStrength = 1f))) },
                    onStrength = { update(edits.copy(color = edits.color.copy(filterStrength = it)), "filterStrength") }
                )
                PhotoTool.TONE -> TonePanel(
                    color = edits.color,
                    tone = edits.tone,
                    selected = toneIndex,
                    onSelect = { toneIndex = it },
                    onChange = { c, t -> update(edits.copy(color = c, tone = t), "tone:$toneIndex") },
                    onAuto = {
                        (lookInput ?: base)?.let { img ->
                            val (c, t) = autoTone(img, edits.color, edits.tone)
                            update(edits.copy(color = c, tone = t))
                        }
                    }
                )
                PhotoTool.DECORATE -> DecoratePanel(
                    mode = decorateMode,
                    selected = edits.decorations.firstOrNull { it.id == selectedDecoration },
                    drawColor = drawColor,
                    drawWidth = drawWidth,
                    hasStrokes = edits.strokes.isNotEmpty(),
                    onMode = { decorateMode = it; selectedDecoration = null },
                    onAddText = { textDialog = null; showTextDialog = true },
                    onEditText = { textDialog = it; showTextDialog = true },
                    onAddSticker = { e ->
                        val s = StickerDecoration(id = System.nanoTime(), emoji = e)
                        update(edits.copy(decorations = edits.decorations + s))
                        selectedDecoration = s.id
                    },
                    onUpdate = { d -> update(edits.copy(decorations = edits.decorations.map { if (it.id == d.id) d else it }), "deco:${d.id}") },
                    onDelete = { d ->
                        update(edits.copy(decorations = edits.decorations.filterNot { it.id == d.id }))
                        selectedDecoration = null
                    },
                    onDrawColor = { drawColor = it },
                    onDrawWidth = { drawWidth = it },
                    onUndoStroke = { update(edits.copy(strokes = edits.strokes.dropLast(1))) },
                    onClearStrokes = { update(edits.copy(strokes = emptyList())) }
                )
            }
            EditorToolBar(PhotoTool.entries, tool, { it.label }, { it.icon }) {
                tool = it
                selectedDecoration = null
            }
        }
    }

    if (showTextDialog) {
        val editing = textDialog
        TextDecorationDialog(
            initial = editing,
            onDismiss = { showTextDialog = false },
            onConfirm = { text, color, style ->
                showTextDialog = false
                if (editing == null) {
                    val t = TextDecoration(id = System.nanoTime(), text = text, color = color, style = style)
                    update(edits.copy(decorations = edits.decorations + t))
                    selectedDecoration = t.id
                } else {
                    val t = editing.copy(text = text, color = color, style = style)
                    update(edits.copy(decorations = edits.decorations.map { if (it.id == t.id) t else it }))
                }
            }
        )
    }

    if (showSave && source != null) {
        val edited = editedPhotoSize(source.saveWidth, source.saveHeight, edits)
        val options = PHOTO_SIZES.filter { it.longSide == null || it.longSide < max(edited.first, edited.second) }
        val chosen = if (saveSize in options) saveSize else PHOTO_SIZES.first()
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save edited photo") },
            text = {
                Column {
                    Text(
                        when {
                            source.cloud != null && source.canOverwrite ->
                                "Overwrite the original on ${source.cloud.accountName}, or keep it and save a copy next to it?"
                            source.cloud != null ->
                                "This kind of image can't be written back, so the edit is uploaded to ${source.cloud.accountName} as a new JPEG next to the original."
                            source.canOverwrite -> "Overwrite the original, or keep it and save a copy?"
                            source.inAppStorage -> "This photo was downloaded to edit it, so the edit is saved as a new photo in Pictures/OpenSync."
                            else -> "This kind of image can't be written back, so the edit is saved as a new JPEG; the original is kept."
                        }
                    )
                    if (options.size > 1) {
                        Spacer(Modifier.height(12.dp))
                        Text("Size", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.forEach { s ->
                                val sel = s == chosen
                                val (w, h) = photoOutputSize(edited.first, edited.second, s)
                                Column(
                                    Modifier.clip(RoundedCornerShape(8.dp))
                                        .background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { saveSize = s }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(s.label, fontSize = 13.sp)
                                    Text("$w×$h", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { save(false, chosen) }) { Text("Save copy") } },
            dismissButton = {
                if (source.canOverwrite) TextButton(onClick = { save(true, chosen) }) { Text("Overwrite") }
                else TextButton(onClick = { showSave = false }) { Text("Cancel") }
            }
        )
    }

    uploadFailure?.let { saved ->
        UploadFailedDialog(cloud, saved) { uploadFailure = null; onClose() }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard your edits?") },
            text = { Text("The photo stays as it was.") },
            confirmButton = { TextButton(onClick = { showDiscard = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Keep editing") } }
        )
    }
}

/** The crop ratio after a quarter turn: 4:3 becomes 3:4 (picked from [ASPECTS] so its chip stays lit). */
private fun turnedAspect(a: Float?): Float? {
    if (a == null) return null
    val turned = 1f / a
    return ASPECTS.firstNotNullOfOrNull { o -> o.ratio?.takeIf { abs(it - turned) < 0.001f } } ?: turned
}
