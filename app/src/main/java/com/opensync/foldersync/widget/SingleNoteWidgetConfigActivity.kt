package com.opensync.foldersync.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.opensync.foldersync.ui.common.verticalScrollbar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensync.foldersync.ui.theme.OpenSyncTheme
import com.opensync.foldersync.update.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shown when a single-note widget is placed: pick which note it should display. */
class SingleNoteWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default to cancelled so backing out doesn't leave a broken widget on the home screen.
        setResult(RESULT_CANCELED)
        enableEdgeToEdge()

        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        setContent {
            OpenSyncTheme {
                Surface(Modifier.fillMaxSize()) {
                    ChooseNoteScreen(
                        onPick = { path -> confirm(widgetId, path) },
                        onCancel = { finish() }
                    )
                }
            }
        }
    }

    private fun confirm(widgetId: Int, path: String) {
        AppPrefs(this).setSingleNoteWidgetPath(widgetId, path)
        SingleNoteWidgetProvider.render(this, AppWidgetManager.getInstance(this), widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseNoteScreen(onPick: (String) -> Unit, onCancel: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val notes by produceState(initialValue = emptyList<NoteBrief>()) {
        value = withContext(Dispatchers.IO) { WidgetNotes.recent(context) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Choose a note") }) }
    ) { inner ->
        if (notes.isEmpty()) {
            Column(Modifier.padding(inner).fillMaxSize().padding(24.dp)) {
                Text(
                    "No notes found. Open OpenSync → Notes and pick your notes folder first, " +
                        "then add the widget again."
                )
            }
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                Modifier.padding(inner).fillMaxSize().verticalScrollbar(listState),
                state = listState,
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(notes) { note ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(note.path) }
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Text(note.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (note.snippet.isNotBlank()) {
                            Text(note.snippet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
