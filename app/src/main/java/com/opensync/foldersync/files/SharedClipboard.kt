package com.opensync.foldersync.files

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One clipboard shared by the Files explorer and the Gallery, so you can copy/cut in either and paste
 * in the other — including across storage targets (e.g. copy a device photo, paste it onto an SMB share).
 */
object SharedClipboard {
    val flow = MutableStateFlow<Clipboard?>(null)
    var clip: Clipboard?
        get() = flow.value
        set(value) { flow.value = value }
}
