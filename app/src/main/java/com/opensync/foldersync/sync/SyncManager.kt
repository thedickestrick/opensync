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
import com.opensync.foldersync.data.ScheduleMode
import com.opensync.foldersync.data.SyncDirection
import com.opensync.foldersync.data.SyncLog
import com.opensync.foldersync.provider.ProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
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

        // Publish live progress for the status UI, forwarding to the caller's notification progress.
        SyncProgressBus.update(ActiveSync(pairId, pair.name, "Starting…", 0, 0))
        val liveProgress = SyncEngine.Progress { message, done, total ->
            SyncProgressBus.update(ActiveSync(pairId, pair.name, message, done, total))
            progress?.update(message, done, total)
        }

        val result = try {
            engine.run(prevState, liveProgress, isCancelled)
        } catch (e: Exception) {
            SyncEngine.Result(
                success = false, filesCopied = 0, filesDeleted = 0, conflicts = 0, bytes = 0,
                message = "Error: ${e.message ?: e.javaClass.simpleName}", newState = prevState
            )
        } finally {
            SyncProgressBus.clear(pairId)
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

    /** Register or cancel the schedule for a single pair, per its [FolderPair.scheduleMode]. */
    fun schedulePair(pair: FolderPair) {
        val wm = WorkManager.getInstance(appContext)
        val name = periodicName(pair.id)
        if (!pair.enabled) {
            wm.cancelUniqueWork(name)
            return
        }
        when (pair.scheduleMode) {
            ScheduleMode.MANUAL -> wm.cancelUniqueWork(name)

            ScheduleMode.INTERVAL -> {
                if (pair.scheduleMinutes <= 0) {
                    wm.cancelUniqueWork(name)
                    return
                }
                val minutes = pair.scheduleMinutes.coerceAtLeast(15).toLong()
                val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
                    .setConstraints(constraintsFor(pair))
                    .setInputData(workDataOf(SyncWorker.KEY_PAIR_ID to pair.id))
                    .build()
                wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
            }

            ScheduleMode.DAILY -> {
                val delay = nextDailyDelayMillis(pair)
                if (delay == null) {
                    wm.cancelUniqueWork(name)
                    return
                }
                val request = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setConstraints(constraintsFor(pair))
                    .setInputData(workDataOf(SyncWorker.KEY_PAIR_ID to pair.id))
                    .build()
                // OneTime work persists across reboots; the worker re-enqueues the next day.
                wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }

    private fun constraintsFor(pair: FolderPair): Constraints {
        val network = when {
            pair.remoteAccountId == null -> NetworkType.NOT_REQUIRED
            pair.requireWifi -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(network)
            .setRequiresCharging(pair.requireCharging)
            .build()
    }

    /** Milliseconds until the next allowed daily run (today at HH:MM if still ahead, else the next allowed weekday). */
    private fun nextDailyDelayMillis(pair: FolderPair): Long? {
        val days = if (pair.daysOfWeek == 0) 0b1111111 else pair.daysOfWeek
        val nowMillis = System.currentTimeMillis()
        val cand = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, pair.dailyHour.coerceIn(0, 23))
            set(Calendar.MINUTE, pair.dailyMinute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (cand.timeInMillis > nowMillis) {
                val bit = cand.get(Calendar.DAY_OF_WEEK) - 1 // 1=Sun … 7=Sat → bit 0…6
                if ((days shr bit) and 1 == 1) return cand.timeInMillis - nowMillis
            }
            cand.add(Calendar.DAY_OF_MONTH, 1)
        }
        return null
    }

    suspend fun rescheduleAll() {
        db.folderPairDao().getAll().forEach { schedulePair(it) }
    }

    /** Stop a running/queued sync for this pair (both scheduled and one-off work). */
    fun cancelSync(pairId: Long) {
        val wm = WorkManager.getInstance(appContext)
        wm.cancelUniqueWork(oneTimeName(pairId))
        wm.cancelUniqueWork(periodicName(pairId))
    }

    private fun periodicName(id: Long) = "sync_$id"
    private fun oneTimeName(id: Long) = "syncnow_$id"
}
