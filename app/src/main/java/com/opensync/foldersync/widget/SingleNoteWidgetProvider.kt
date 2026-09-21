package com.opensync.foldersync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.opensync.foldersync.R
import com.opensync.foldersync.TextEditorActivity
import com.opensync.foldersync.notes.RemoteNotes
import com.opensync.foldersync.update.AppPrefs
import java.io.File

/** Home-screen widget pinned to a single note chosen at placement (SingleNoteWidgetConfigActivity). */
class SingleNoteWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!WidgetNotes.handleRefresh(context, intent)) super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, manager, id)
        NotesSyncWorker.reschedule(context)
    }

    /** Forget the note→widget mapping when a widget is removed. */
    override fun onDeleted(context: Context, ids: IntArray) {
        val prefs = AppPrefs(context)
        for (id in ids) prefs.removeSingleNoteWidget(id)
    }

    override fun onDisabled(context: Context) = NotesSyncWorker.reschedule(context)

    companion object {
        /** Re-render every placed single-note widget (call after notes change). */
        fun notifyChanged(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, SingleNoteWidgetProvider::class.java)
            )
            for (id in ids) render(context, manager, id)
        }

        fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_single_note)
            val path = AppPrefs(context).singleNoteWidgetPath(id)
            val file = path.takeIf { it.isNotBlank() }?.let { File(it) }

            if (file != null && file.isFile) {
                views.setTextViewText(R.id.single_title, file.nameWithoutExtension)
                views.setTextViewText(R.id.single_body, WidgetNotes.body(file))
                val open = PendingIntent.getActivity(
                    context, id,
                    Intent(context, TextEditorActivity::class.java)
                        .putExtra("note_path", path)
                        .putExtra("view_mode", true) // open rendered so checkboxes are tappable
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                views.setOnClickPendingIntent(R.id.single_root, open)
                // A note in an account notes folder can change on the server: offer a sync button.
                val shared = RemoteNotes.folderFor(path) != null
                views.setViewVisibility(R.id.single_refresh, if (shared) View.VISIBLE else View.GONE)
                views.setOnClickPendingIntent(
                    R.id.single_refresh,
                    WidgetNotes.refreshIntent(context, SingleNoteWidgetProvider::class.java, id)
                )
            } else {
                views.setViewVisibility(R.id.single_refresh, View.GONE)
                views.setTextViewText(R.id.single_title, "Tap to choose a note")
                views.setTextViewText(R.id.single_body, "")
                val configure = PendingIntent.getActivity(
                    context, id,
                    Intent(context, SingleNoteWidgetConfigActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                views.setOnClickPendingIntent(R.id.single_root, configure)
            }
            manager.updateAppWidget(id, views)
        }
    }
}
