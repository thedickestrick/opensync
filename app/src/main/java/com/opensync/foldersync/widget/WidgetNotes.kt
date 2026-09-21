package com.opensync.foldersync.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.opensync.foldersync.notes.RemoteNotes
import com.opensync.foldersync.update.AppPrefs
import java.io.File

/** [source] names the account notes folder the note lives in; blank for the folder on this phone. */
internal data class NoteBrief(val title: String, val snippet: String, val path: String, val source: String = "")

/** Shared helpers for the notes widgets: listing notes and pulling title/snippet/body text. */
internal object WidgetNotes {
    private val EXTS = listOf(".md", ".txt", ".markdown")

    private const val ACTION_REFRESH = "com.opensync.foldersync.widget.REFRESH"

    /** For a widget's ⟳ button: a broadcast back to its own [provider], handled by [handleRefresh]. */
    fun refreshIntent(context: Context, provider: Class<*>, widgetId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, widgetId, Intent(context, provider).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Returns true if [intent] was a ⟳ tap (and a sync of the account notes folders is now queued). */
    fun handleRefresh(context: Context, intent: Intent): Boolean {
        if (intent.action != ACTION_REFRESH) return false
        NotesSyncWorker.syncNow(context)
        Toast.makeText(context, "Syncing notes…", Toast.LENGTH_SHORT).show()
        return true
    }

    /** Recent notes from the phone's notes folder and every account notes folder, newest first. */
    fun recent(context: Context, limit: Int = 200): List<NoteBrief> {
        val roots = buildList {
            AppPrefs(context).notesDir.takeIf { it.isNotBlank() }?.let { add(File(it) to "") }
            RemoteNotes.folders().forEach { add(RemoteNotes.mirrorDir(it) to it.name) }
        }
        return runCatching {
            roots.filter { (root, _) -> root.isDirectory }
                .flatMap { (root, source) ->
                    root.walkTopDown().onFail { _, _ -> }.maxDepth(6)
                        .filter { f -> f.isFile && EXTS.any { f.name.lowercase().endsWith(it) } }
                        .map { it to source }
                        .toList()
                }
                .sortedByDescending { (file, _) -> file.lastModified() }
                .take(limit)
                .map { (file, source) -> NoteBrief(file.nameWithoutExtension, snippet(file), file.absolutePath, source) }
        }.getOrDefault(emptyList())
    }

    // Checklist markers rendered as real checkbox glyphs so the widget shows ☐ / ☑, not "- [ ]".
    private const val UNCHECKED = "☐ " // ☐
    private const val CHECKED = "☑ "    // ☑

    /** Short one-line-ish preview for list rows. */
    fun snippet(file: File, max: Int = 160): String {
        val raw = runCatching {
            file.bufferedReader().use { r ->
                val buf = CharArray(600)
                val n = r.read(buf)
                if (n > 0) String(buf, 0, n) else ""
            }
        }.getOrDefault("")
        return raw.lineSequence()
            .map { cleanLine(it, bullet = "") }
            .filter { it.isNotEmpty() }
            .joinToString("  ")
            .take(max)
    }

    /** Larger body preview for the single-note widget (light markdown-marker cleanup). */
    fun body(file: File, max: Int = 2000): String {
        val raw = runCatching { file.bufferedReader().use { it.readText() } }.getOrDefault("")
        return raw.lineSequence()
            .joinToString("\n") { cleanLine(it, bullet = "• ") } // • bullets
            .trim()
            .take(max)
    }

    /** Strip a line's markdown markers for display; checklist items become ☐ / ☑. */
    private fun cleanLine(line: String, bullet: String): String {
        val t = line.trim()
        return when {
            t.startsWith("- [ ]") -> UNCHECKED + t.removePrefix("- [ ]").trim()
            t.startsWith("- [x]") || t.startsWith("- [X]") -> CHECKED + t.substring(5).trim()
            t.startsWith("- ") || t.startsWith("* ") -> bullet + t.substring(2).trim()
            else -> t.trimStart('#', '>', ' ')
        }.replace("**", "").replace("__", "")
    }
}
