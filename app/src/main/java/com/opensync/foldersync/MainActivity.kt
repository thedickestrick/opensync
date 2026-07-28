package com.opensync.foldersync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.opensync.foldersync.files.ShareInbox
import com.opensync.foldersync.files.ShareRequest
import com.opensync.foldersync.files.SharedItem
import com.opensync.foldersync.provider.DropboxAuth
import kotlinx.coroutines.launch
import com.opensync.foldersync.ui.AppRoot
import com.opensync.foldersync.ui.theme.OpenSyncTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // draw behind transparent system bars (nav bar shows content through it)
        handleIntent(intent)
        setContent {
            OpenSyncTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let {
                    ShareInbox.post(ShareRequest.Save(listOf(resolve(it))))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) ShareInbox.post(ShareRequest.Save(uris.map { resolve(it) }))
            }
            Intent.ACTION_VIEW -> {
                val data = intent.data
                when {
                    data?.scheme == "opensync" && data.host == "dropbox" -> {
                        val code = data.getQueryParameter("code")
                        if (code != null) lifecycleScope.launch { DropboxAuth.complete(code) }
                        else DropboxAuth.fail(data.getQueryParameter("error_description") ?: "Dropbox sign-in cancelled")
                    }
                    data?.scheme == "file" -> {
                        val f = File(data.path.orEmpty())
                        if (f.isDirectory) ShareInbox.post(ShareRequest.Open(f.absolutePath))
                    }
                }
            }
        }
    }

    private fun resolve(uri: Uri): SharedItem {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { name = it }
                }
            }
        }
        return SharedItem(uri, name)
    }
}
