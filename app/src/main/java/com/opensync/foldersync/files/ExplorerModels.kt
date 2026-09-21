package com.opensync.foldersync.files

import com.opensync.foldersync.provider.RemoteFile

/** A browsable root: the device filesystem, or a configured remote account. */
sealed interface ExplorerLocation {
    data object LocalRoot : ExplorerLocation
    data class Remote(val accountId: Long) : ExplorerLocation
}

enum class SortBy(val label: String) {
    NAME("Name"),
    SIZE("Size"),
    DATE("Date modified"),
    TYPE("Type")
}

/** Files staged for a copy or move, remembering which location they came from. */
data class Clipboard(
    val location: ExplorerLocation,
    val items: List<RemoteFile>,
    val move: Boolean
)

/**
 * A file ready to open. [file] is the real file for local storage, or a downloaded cache copy for a
 * remote account — in which case [accountId]/[relPath] say where an edited copy has to go back to.
 */
data class OpenedFile(val file: java.io.File, val accountId: Long? = null, val relPath: String? = null)

/** Default starting directory (relative to the device root "/"). */
const val DEFAULT_LOCAL_DIR = "storage/emulated/0"
