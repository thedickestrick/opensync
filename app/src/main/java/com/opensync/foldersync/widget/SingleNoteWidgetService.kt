package com.opensync.foldersync.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.opensync.foldersync.R
import com.opensync.foldersync.update.AppPrefs
import java.io.File

class SingleNoteWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = SingleNoteLinesFactory(
        applicationContext,
        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    )
}

/** Feeds the single-note widget's scrolling body: one row per line of the pinned note. */
private class SingleNoteLinesFactory(
    private val context: Context,
    private val widgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private var path: String = ""
    private var lines: List<String> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        path = AppPrefs(context).singleNoteWidgetPath(widgetId)
        val file = path.takeIf { it.isNotBlank() }?.let { File(it) }
        lines = if (file != null && file.isFile) WidgetNotes.body(file, max = 20_000).lines() else emptyList()
    }

    override fun getCount(): Int = lines.size

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_single_note_line)
        rv.setTextViewText(R.id.single_line, lines.getOrElse(position) { "" })
        // Tapping any line opens the note, like tapping the rest of the widget.
        rv.setOnClickFillInIntent(R.id.single_line, Intent().putExtra("note_path", path))
        return rv
    }

    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
    override fun getLoadingView(): RemoteViews? = null
    override fun onDestroy() {}
}
