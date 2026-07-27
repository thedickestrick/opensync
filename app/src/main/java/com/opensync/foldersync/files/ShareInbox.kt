package com.opensync.foldersync.files

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A file handed to us from another app via a share/open intent. */
data class SharedItem(val uri: Uri, val name: String)

sealed interface ShareRequest {
    /** Files shared to us; the user picks a destination folder to save them into. */
    data class Save(val items: List<SharedItem>) : ShareRequest

    /** A folder another app asked us to open. */
    data class Open(val absolutePath: String) : ShareRequest
}

/** Bridges incoming share/open intents (handled in the Activity) to the explorer UI. */
object ShareInbox {
    private val _request = MutableStateFlow<ShareRequest?>(null)
    val request = _request.asStateFlow()

    fun post(request: ShareRequest) { _request.value = request }
    fun clear() { _request.value = null }
}
