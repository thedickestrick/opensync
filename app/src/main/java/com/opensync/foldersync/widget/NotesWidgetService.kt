package com.opensync.foldersync.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.opensync.foldersync.R
import com.opensync.foldersync.update.AppPrefs
import java.io.File

class NotesWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        NotesRemoteViewsFactory(applicationContext)
}

private data class NoteBrief(val title: String, val snippet: String, val path: String)

/** Loads note titles + content snippets from the configured notes folder for the widget list. */
private class NotesRemoteViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<NoteBrief> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        items = loadNotes()
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

    private fun loadNotes(): List<NoteBrief> {
        val dir = AppPrefs(context).notesDir
        if (dir.isBlank()) return emptyList()
        val root = File(dir)
        if (!root.isDirectory) return emptyList()
        return runCatching {
            root.walkTopDown().onFail { _, _ -> }.maxDepth(6)
                .filter { f ->
                    f.isFile && f.name.lowercase().let { it.endsWith(".md") || it.endsWith(".txt") || it.endsWith(".markdown") }
                }
                .sortedByDescending { it.lastModified() }
                .take(60)
                .map { NoteBrief(it.nameWithoutExtension, snippetOf(it), it.absolutePath) }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun snippetOf(file: File): String {
        val raw = runCatching {
            file.bufferedReader().use { r ->
                val buf = CharArray(600)
                val n = r.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }
        }.getOrDefault("")
        return raw.lineSequence()
            .map { it.trim().trimStart('#', '>', '-', '*', ' ') }
            .filter { it.isNotEmpty() }
            .joinToString("  ")
            .take(160)
    }
}
