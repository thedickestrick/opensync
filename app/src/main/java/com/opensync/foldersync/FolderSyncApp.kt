package com.opensync.foldersync

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application entry point: sets up the DI graph, notification channel, WorkManager and Coil. */
class FolderSyncApp : Application(), Configuration.Provider, ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifications.createChannels(this)
        PDFBoxResourceLoader.init(this)
        // Refresh WorkManager schedules (recomputes next daily runs after reboot/reinstall).
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { Graph.syncManager.rescheduleAll() }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    // App-wide Coil loader that can also decode video frames for gallery thumbnails.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
}
