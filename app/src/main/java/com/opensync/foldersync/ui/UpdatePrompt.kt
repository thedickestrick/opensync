package com.opensync.foldersync.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.opensync.foldersync.update.AppUpdater
import com.opensync.foldersync.update.UpdateChecker

/** Checks for an update each time the app is opened, and offers to install one when it's out. */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) AppUpdater.checkOnOpen()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val release by AppUpdater.prompt.collectAsState()
    val state by AppUpdater.state.collectAsState()
    // Back from Android's "install unknown apps" screen: carry on if it was allowed.
    val installSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val pending = AppUpdater.prompt.value
        if (pending != null && UpdateChecker.canInstall(context)) AppUpdater.downloadAndInstall(pending)
    }

    val r = release ?: return
    AlertDialog(
        onDismissRequest = { if (!state.downloading) AppUpdater.dismissPrompt() },
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OpenSync ${r.tag.trimStart('v', 'V')} is out. You have ${AppUpdater.currentVersion}.")
                if (state.downloading) {
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Downloading… ${state.progress}%", style = MaterialTheme.typography.labelMedium)
                }
                state.downloadError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.downloading,
                onClick = {
                    if (UpdateChecker.canInstall(context)) AppUpdater.downloadAndInstall(r)
                    else installSettingsLauncher.launch(UpdateChecker.unknownSourcesIntent(context))
                }
            ) { Text(if (state.downloadError != null) "Try again" else "Install") }
        },
        dismissButton = {
            TextButton(enabled = !state.downloading, onClick = { AppUpdater.dismissPrompt() }) { Text("Later") }
        }
    )
}
