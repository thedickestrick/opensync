package com.opensync.foldersync.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.opensync.foldersync.R

class NotesWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        NotesRemoteViewsFactory(applicationContext)
}

/** Loads note titles + content snippets from the configured notes folder for the widget list. */
private class NotesRemoteViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<NoteBrief> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        items = WidgetNotes.recent(context, limit = 60)
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val note = items.getOrNull(position)
        val rv = RemoteViews(context.packageName, R.layout.widget_note_item)
        if (note != null) {
            rv.setTextViewText(R.id.item_title, note.title)
            rv.setTextViewText(R.id.item_snippet, note.snippet)
            rv.setOnClickFillInIntent(R.id.item_root, Intent().putExtra("note_path", note.path))
        }
        return rv
    }

    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
    override fun getLoadingView(): RemoteViews? = null
    override fun onDestroy() {}
}
