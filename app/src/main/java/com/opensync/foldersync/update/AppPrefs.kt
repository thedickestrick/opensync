package com.opensync.foldersync.update

import android.content.Context

/** Small SharedPreferences wrapper for app-level settings (currently the update source). */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("opensync_prefs", Context.MODE_PRIVATE)

    var updateOwner: String
        get() = prefs.getString(KEY_OWNER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_OWNER, value).apply() }

    var updateRepo: String
        get() = prefs.getString(KEY_REPO, "") ?: ""
        set(value) { prefs.edit().putString(KEY_REPO, value).apply() }

    /** Folder the user keeps their notes (and Samsung Notes exports) in. */
    var notesDir: String
        get() = prefs.getString(KEY_NOTES_DIR, "") ?: ""
        set(value) { prefs.edit().putString(KEY_NOTES_DIR, value).apply() }

    /** Notes folders that live on an account, as JSON (see [com.opensync.foldersync.notes.RemoteNotes]). */
    var remoteNotesFolders: String
        get() = prefs.getString(KEY_REMOTE_NOTES, "") ?: ""
        set(value) { prefs.edit().putString(KEY_REMOTE_NOTES, value).apply() }

    /** Id of the account notes folder the Notes tab is showing; 0 = the folder on this phone. */
    var activeNotesFolder: Long
        get() = prefs.getLong(KEY_ACTIVE_NOTES, 0L)
        set(value) { prefs.edit().putLong(KEY_ACTIVE_NOTES, value).apply() }

    /** Absolute paths of pinned notes. */
    var pinnedNotes: Set<String>
        get() = prefs.getStringSet(KEY_PINNED, emptySet())?.toSet() ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_PINNED, value).apply() }

    /** Last top-level screen the user was on, restored on next launch. */
    var lastRoute: String
        get() = prefs.getString(KEY_LAST_ROUTE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LAST_ROUTE, value).apply() }

    /** Note path a single-note home-screen widget (keyed by its appWidgetId) is pinned to. */
    fun singleNoteWidgetPath(widgetId: Int): String =
        prefs.getString(KEY_SINGLE_NOTE_WIDGET + widgetId, "") ?: ""

    fun setSingleNoteWidgetPath(widgetId: Int, path: String) {
        prefs.edit().putString(KEY_SINGLE_NOTE_WIDGET + widgetId, path).apply()
    }

    fun removeSingleNoteWidget(widgetId: Int) {
        prefs.edit().remove(KEY_SINGLE_NOTE_WIDGET + widgetId).apply()
    }

    private companion object {
        const val KEY_OWNER = "update_owner"
        const val KEY_REPO = "update_repo"
        const val KEY_NOTES_DIR = "notes_dir"
        const val KEY_REMOTE_NOTES = "remote_notes_folders"
        const val KEY_ACTIVE_NOTES = "active_notes_folder"
        const val KEY_PINNED = "pinned_notes"
        const val KEY_LAST_ROUTE = "last_route"
        const val KEY_SINGLE_NOTE_WIDGET = "single_note_widget_"
    }
}
