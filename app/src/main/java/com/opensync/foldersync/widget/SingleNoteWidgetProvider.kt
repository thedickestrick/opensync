package com.opensync.foldersync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.opensync.foldersync.R
import com.opensync.foldersync.TextEditorActivity
import com.opensync.foldersync.update.AppPrefs
import java.io.File

/** Home-screen widget pinned to a single note chosen at placement (SingleNoteWidgetConfigActivity). */
class SingleNoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, manager, id)
    }

    /** Forget the note→widget mapping when a widget is removed. */
    override fun onDeleted(context: Context, ids: IntArray) {
        val prefs = AppPrefs(context)
        for (id in ids) prefs.removeSingleNoteWidget(id)
    }

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
            } else {
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
