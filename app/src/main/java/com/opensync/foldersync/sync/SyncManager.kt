package com.opensync.foldersync.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.opensync.foldersync.data.AppDatabase
import com.opensync.foldersync.data.FolderPair
import com.opensync.foldersync.data.SyncDirection
import com.opensync.foldersync.data.SyncLog
import com.opensync.foldersync.provider.ProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Runs syncs on demand and manages their WorkManager schedules. */
class SyncManager(
    private val appContext: Context,
    private val db: AppDatabase
) {
    private val tempDir get() = appContext.cacheDir

    /** Runs a folder pair synchronously (call from a coroutine / worker) and records a log. */
    suspend fun syncNow(
        pairId: Long,
        progress: SyncEngine.Progress? = null,
        isCancelled: () -> Boolean = { false }
    ): SyncLog = withContext(Dispatchers.IO) {
        val pair = db.folderPairDao().getById(pairId)
            ?: throw IllegalArgumentException("Folder pair $pairId not found")

        val local = ProviderFactory.forLocal(pair.localFolder)
        val remote = if (pair.remoteAccountId == null) {
            ProviderFactory.forLocal(pair.remoteFolder)
        } else {
            val account = db.accountDao().getById(pair.remoteAccountId)
                ?: throw IllegalStateException("Remote account not found for '${pair.name}'")
            ProviderFactory.forAccount(account, pair.remoteFolder)
        }

        val prevState = db.syncStateDao().getForPair(pairId)
        val engine = SyncEngine(local, remote, pair, tempDir)
        val start = System.currentTimeMillis()

        val result = try {
            engine.run(prevState, progress, isCancelled)
        } catch (e: Exception) {
            SyncEngine.Result(
                success = false, filesCopied = 0, filesDeleted = 0, conflicts = 0, bytes = 0,
                message = "Error: ${e.message ?: e.javaClass.simpleName}", newState = prevState
            )
        }
        val end = System.currentTimeMillis()

        if (result.success) {
            if (pair.direction == SyncDirection.TWO_WAY) {
                db.syncStateDao().replaceForPair(pairId, result.newState)
            } else {
                db.syncStateDao().deleteForPair(pairId)
            }
        }
        db.folderPairDao().updateStatus(pairId, end, result.message)

        val log = SyncLog(
            pairId = pairId, pairName = pair.name, startTime = start, endTime = end,
            success = result.success, filesCopied = result.filesCopied,
            filesDeleted = result.filesDeleted, conflicts = result.conflicts,
            bytesTransferred = result.bytes, message = result.message
        )
        val id = db.syncLogDao().insert(log)
        log.copy(id = id)
    }

    /** Enqueue an immediate one-off sync via WorkManager (survives app close, shows a notification). */
    fun enqueueOneTime(pairId: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_PAIR_ID to pairId))
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(oneTimeName(pairId), ExistingWorkPolicy.REPLACE, request)
    }

    /** Register or cancel the periodic schedule for a single pair. */
    fun schedulePair(pair: FolderPair) {
        val wm = WorkManager.getInstance(appContext)
        if (!pair.enabled || pair.scheduleMinutes <= 0) {
            wm.cancelUniqueWork(periodicName(pair.id))
            return
        }
        val minutes = pair.scheduleMinutes.coerceAtLeast(15).toLong()
        val network = when {
            pair.remoteAccountId == null -> NetworkType.NOT_REQUIRED
            pair.requireWifi -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(network)
            .setRequiresCharging(pair.requireCharging)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_PAIR_ID to pair.id))
            .build()
        wm.enqueueUniquePeriodicWork(periodicName(pair.id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    suspend fun rescheduleAll() {
        db.folderPairDao().getAll().forEach { schedulePair(it) }
    }

    private fun periodicName(id: Long) = "sync_$id"
    private fun oneTimeName(id: Long) = "syncnow_$id"
}
