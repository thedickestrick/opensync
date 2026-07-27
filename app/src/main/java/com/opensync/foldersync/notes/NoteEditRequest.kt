package com.opensync.foldersync.notes

/**
 * Passes context into the note editor.
 * [path] set = edit that existing note; null = create a new note in [dir].
 */
object NoteEditRequest {
    var path: String? = null
    var dir: String? = null
}
