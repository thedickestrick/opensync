package com.opensync.foldersync.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** True while a full-screen media viewer is open, so the nav drawer's edge-swipe can be disabled. */
object FullscreenState {
    val active = MutableStateFlow(false)
}
