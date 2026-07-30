package com.opensync.foldersync.ui.pdf

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.opensync.foldersync.ui.common.verticalScrollbar
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.pdf.FormField
import com.opensync.foldersync.pdf.FormFieldType
import com.opensync.foldersync.pdf.PdfForm
import com.opensync.foldersync.pdf.PdfRequest
import com.opensync.foldersync.ui.components.DropdownField
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfFormScreen(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val path = remember { PdfRequest.path }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Loaded field definitions (null = still loading).
    val fields by produceState<List<FormField>?>(initialValue = null, path) {
        value = if (path == null) {
            error = "No file to open"
            emptyList()
        } else {
            runCatching { PdfForm.readFields(File(path)) }
                .getOrElse { error = it.message ?: "Cannot read form"; emptyList() }
        }
    }

    // Editable values keyed by field name, seeded from the loaded fields.
    val values = remember { mutableStateListOf<Pair<String, String>>() }
    LaunchedEffect(fields) {
        val loaded = fields
        if (loaded != null && values.isEmpty()) {
            loaded.forEach { values.add(it.fullName to it.value) }
        }
    }
    fun valueOf(name: String) = values.firstOrNull { it.first == name }?.second ?: ""
    fun setValue(name: String, v: String) {
        val i = values.indexOfFirst { it.first == name }
        if (i >= 0) values[i] = name to v else values.add(name to v)
    }

    fun save() {
        val src = path ?: return
        scope.launch {
            busy = true
            try {
                val dest = File(File(src).parentFile, "${File(src).nameWithoutExtension} (filled).pdf")
                PdfForm.save(File(src), dest, values.toMap())
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
                title = { Text("Fill form") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { save() }, enabled = !busy && !fields.isNullOrEmpty()) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            val f = fields
            when {
                error != null && f.isNullOrEmpty() -> Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
                f == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                f.isEmpty() -> Text(
                    "This PDF has no fillable form fields.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
                else -> {
                  val listState = rememberLazyListState()
                  LazyColumn(
                    modifier = Modifier.fillMaxSize().verticalScrollbar(listState),
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(f, key = { it.fullName }) { field ->
                        FieldEditor(
                            field = field,
                            value = valueOf(field.fullName),
                            onChange = { setValue(field.fullName, it) }
                        )
                    }
                  }
                }
            }
            if (busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun FieldEditor(field: FormField, value: String, onChange: (String) -> Unit) {
    when (field.type) {
        FormFieldType.TEXT -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(field.label) },
            modifier = Modifier.fillMaxWidth()
        )
        FormFieldType.CHECKBOX -> Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(field.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = value == "true",
                onCheckedChange = { onChange(if (it) "true" else "false") }
            )
        }
        FormFieldType.CHOICE -> {
            if (field.options.isNotEmpty()) {
                val options = (field.options + value).filter { it.isNotBlank() }.distinct()
                DropdownField(
                    label = field.label,
                    options = options,
                    selected = value.ifBlank { options.first() },
                    optionLabel = { it },
                    onSelected = onChange
                )
            } else {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text(field.label) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        FormFieldType.OTHER -> OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text("${field.label} (not editable)") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
