package com.opensync.foldersync.ui.notes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.notes.ExportFormat
import com.opensync.foldersync.notes.NoteExport
import com.opensync.foldersync.notes.NoteExporter
import com.opensync.foldersync.share.ShareUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What to export: read at the moment the user acts, so unsaved edits go out too. */
class ExportSource(val title: String, val markdown: String, val baseDir: File?)

/** "Save to…" with the MIME type and file name chosen per export, which the stock contract can't do. */
private object CreateTypedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.first)
            .putExtra(Intent.EXTRA_TITLE, input.second)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * Asks which format, then shares the exported file or saves it wherever the user picks. The
 * system file picker covers this phone, SD cards and any cloud app that offers a folder.
 */
@Composable
fun NoteExportDialog(source: () -> ExportSource, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(ExportFormat.PDF) }
    var busy by remember { mutableStateOf(false) }
    // Captured when "Save to…" is pressed, so what's written is what was on screen then.
    var pending by remember { mutableStateOf<ExportSource?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(CreateTypedDocument) { uri ->
        val src = pending
        pending = null
        if (uri == null || src == null) { busy = false; return@rememberLauncherForActivityResult }
        scope.launch {
            val ok = runCatching {
                val bytes = NoteExporter.render(format, src.title, src.markdown, src.baseDir)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error("Couldn't open the file")
                }
            }.isSuccess
            busy = false
            Toast.makeText(context, if (ok) "Exported as ${format.label}" else "Couldn't export this note", Toast.LENGTH_LONG).show()
            if (ok) onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Export note") },
        text = {
            Column(Modifier.selectableGroup()) {
                ExportFormat.entries.forEach { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = format == f, enabled = !busy, role = Role.RadioButton) { format = f }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = format == f, onClick = null, enabled = !busy)
                        Text("${f.label}  ", modifier = Modifier.padding(start = 12.dp))
                        Text(".${f.ext}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (busy) Text("Exporting…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val src = source()
                    busy = true
                    scope.launch {
                        val file = runCatching {
                            NoteExporter.toCacheFile(context, format, src.title, src.markdown, src.baseDir)
                        }.getOrNull()
                        busy = false
                        if (file == null) {
                            Toast.makeText(context, "Couldn't export this note", Toast.LENGTH_LONG).show()
                        } else {
                            ShareUtil.shareFiles(context, listOf(file))
                            onDismiss()
                        }
                    }
                }
            ) { Text("Share") }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val src = source()
                    pending = src
                    busy = true
                    runCatching { saveLauncher.launch(format.mime to "${NoteExport.safeFileName(src.title)}.${format.ext}") }
                        .onFailure {
                            pending = null
                            busy = false
                            Toast.makeText(context, "No app here can save files", Toast.LENGTH_LONG).show()
                        }
                }
            ) { Text("Save to…") }
        }
    )
}
