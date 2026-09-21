package com.opensync.foldersync.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opensync.foldersync.Notifications
import com.opensync.foldersync.notes.RemoteNotes
import java.util.concurrent.TimeUnit

/**
 * Keeps notes folders that live on an account fresh while the app is closed, so a note pinned to
 * the home screen picks up what someone else wrote. [RemoteNotes] re-renders the widgets itself
 * after every sync, so all this has to do is run one.
 */
class NotesSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching { RemoteNotes.syncAll() }
        return Result.success() // a failed sync just waits for the next round
    }

    /** Only reached on Android 11 and older, where expedited work runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        Notifications.progress(applicationContext, "Syncing notes", "", true, 0, 0)
    )

    companion object {
        private const val PERIODIC = "remote_notes_sync"
        private const val NOW = "remote_notes_sync_now"
        private const val NOTIFICATION_ID = Notifications.PROGRESS_ID_BASE - 1

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * Background syncing costs battery, so it only runs when it has an audience: at least one
         * account notes folder *and* at least one notes widget on the home screen. 15 minutes is the
         * shortest period Android allows.
         */
        fun reschedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (RemoteNotes.folders().isEmpty() || !hasWidgets(context)) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<NotesSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(online)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** The widgets' refresh button: sync as soon as the system lets us. */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<NotesSyncWorker>()
                .setConstraints(online)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }

        private fun hasWidgets(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return listOf(NotesWidgetProvider::class.java, SingleNoteWidgetProvider::class.java)
                .any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
        }
    }
}
