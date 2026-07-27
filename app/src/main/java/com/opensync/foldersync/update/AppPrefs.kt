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

    private companion object {
        const val KEY_OWNER = "update_owner"
        const val KEY_REPO = "update_repo"
    }
}
