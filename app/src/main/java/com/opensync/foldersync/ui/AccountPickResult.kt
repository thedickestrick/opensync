package com.opensync.foldersync.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** Carries a just-created account's id back to whatever screen launched "Add account". */
object AccountPickResult {
    val lastCreatedId = MutableStateFlow<Long?>(null)
}
