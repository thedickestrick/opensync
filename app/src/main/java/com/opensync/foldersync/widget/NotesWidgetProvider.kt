package com.opensync.foldersync.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.opensync.foldersync.MainActivity
import com.opensync.foldersync.R
import com.opensync.foldersync.TextEditorActivity

/** Home-screen widget listing recent notes; resizable (see res/xml/notes_widget_info.xml). */
class NotesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(context, manager, id)
    }

    companion object {
        /** Rebuild all placed widgets (refreshes their list + tap targets) after notes change. */
        fun notifyChanged(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, NotesWidgetProvider::class.java)
            )
            for (id in ids) updateWidget(context, manager, id)
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_notes)

            // List backed by NotesWidgetService; unique data URI per widget id.
            val serviceIntent = Intent(context, NotesWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list, serviceIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)

            val immutable = PendingIntent.FLAG_IMMUTABLE

            // Header / "+" opens the app.
            val openApp = PendingIntent.getActivity(
                context, id, Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), immutable
            )
            views.setOnClickPendingIntent(R.id.widget_header, openApp)
            views.setOnClickPendingIntent(R.id.widget_empty, openApp)

            // Tapping a note opens it in the text editor (the item supplies note_path via fill-in intent).
            val itemTemplate = PendingIntent.getActivity(
                context, 0,
                Intent(context, TextEditorActivity::class.java)
                    .putExtra("view_mode", true) // open rendered so checkboxes are tappable
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setPendingIntentTemplate(R.id.widget_list, itemTemplate)

            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        }
    }
}
