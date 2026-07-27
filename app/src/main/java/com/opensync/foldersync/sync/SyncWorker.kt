package com.opensync.foldersync.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.opensync.foldersync.Graph
import com.opensync.foldersync.Notifications
import com.opensync.foldersync.data.ScheduleMode
import kotlinx.coroutines.CancellationException

/** Background worker that runs a single folder pair's sync with a progress notification. */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pairId = inputData.getLong(KEY_PAIR_ID, -1L)
        if (pairId < 0) return Result.failure()

        val notifId = Notifications.PROGRESS_ID_BASE + pairId.toInt()
        runCatching { setForeground(foregroundInfo(notifId, "Preparing…", 0, 0, true)) }

        val progress = SyncEngine.Progress { message, done, total ->
            val notif = Notifications.progress(
                applicationContext, "Syncing", message, total == 0, total, done
            )
            runCatching { NotificationManagerCompat.from(applicationContext).notify(notifId, notif) }
        }

        return try {
            Graph.syncManager.syncNow(pairId, progress) { isStopped }
            rescheduleDaily(pairId)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The failure is already recorded in the sync log; don't spin on retries.
            rescheduleDaily(pairId)
            Result.success()
        }
    }

    /** For daily-scheduled pairs, queue the next occurrence (one-time work does not auto-repeat). */
    private suspend fun rescheduleDaily(pairId: Long) {
        runCatching {
            val pair = Graph.database.folderPairDao().getById(pairId)
            if (pair != null && pair.enabled && pair.scheduleMode == ScheduleMode.DAILY) {
                Graph.syncManager.schedulePair(pair)
            }
        }
    }

    private fun foregroundInfo(
        notifId: Int, text: String, done: Int, total: Int, indeterminate: Boolean
    ): ForegroundInfo {
        val notif = Notifications.progress(applicationContext, "Syncing", text, indeterminate, total, done)
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notif)
        }
    }

    companion object {
        const val KEY_PAIR_ID = "pair_id"
    }
}
