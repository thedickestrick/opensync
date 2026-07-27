package com.opensync.foldersync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/** Notification channel + sync-progress notification builder. */
object Notifications {
    const val CHANNEL_SYNC = "sync"
    const val PROGRESS_ID_BASE = 1000

    fun createChannels(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_SYNC,
            context.getString(R.string.sync_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.sync_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    fun progress(
        context: Context,
        title: String,
        text: String,
        indeterminate: Boolean,
        max: Int,
        current: Int
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(if (indeterminate) 0 else max, if (indeterminate) 0 else current, indeterminate)
            .build()
    }
}
