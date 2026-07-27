package com.opensync.foldersync

import android.content.Context
import com.opensync.foldersync.data.AppDatabase
import com.opensync.foldersync.sync.SyncManager

/** Tiny manual dependency container, initialized from [FolderSyncApp]. */
object Graph {
    lateinit var database: AppDatabase
        private set
    lateinit var syncManager: SyncManager
        private set
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        database = AppDatabase.build(appContext)
        syncManager = SyncManager(appContext, database)
    }
}
