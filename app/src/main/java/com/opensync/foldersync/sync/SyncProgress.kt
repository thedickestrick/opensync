package com.opensync.foldersync.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Snapshot of one in-flight backup for the live status UI. */
data class ActiveSync(
    val pairId: Long,
    val pairName: String,
    val message: String,
    val done: Int,
    val total: Int
) {
    val indeterminate: Boolean get() = total <= 0
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Process-global registry of currently running syncs. Workers push updates here as files
 * transfer; the UI observes [active] to show progress and the current file.
 */
object SyncProgressBus {
    private val _active = MutableStateFlow<Map<Long, ActiveSync>>(emptyMap())
    val active: StateFlow<Map<Long, ActiveSync>> = _active.asStateFlow()

    fun update(state: ActiveSync) {
        _active.value = _active.value + (state.pairId to state)
    }

    fun clear(pairId: Long) {
        _active.value = _active.value - pairId
    }
}
