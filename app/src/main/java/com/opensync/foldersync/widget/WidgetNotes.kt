package com.opensync.foldersync.widget

import android.content.Context
import com.opensync.foldersync.update.AppPrefs
import java.io.File

internal data class NoteBrief(val title: String, val snippet: String, val path: String)

/** Shared helpers for the notes widgets: listing notes and pulling title/snippet/body text. */
internal object WidgetNotes {
    private val EXTS = listOf(".md", ".txt", ".markdown")

    /** Recent notes from the configured notes folder, newest first. */
    fun recent(context: Context, limit: Int = 200): List<NoteBrief> {
        val dir = AppPrefs(context).notesDir
        if (dir.isBlank()) return emptyList()
        val root = File(dir)
        if (!root.isDirectory) return emptyList()
        return runCatching {
            root.walkTopDown().onFail { _, _ -> }.maxDepth(6)
                .filter { f -> f.isFile && EXTS.any { f.name.lowercase().endsWith(it) } }
                .sortedByDescending { it.lastModified() }
                .take(limit)
                .map { NoteBrief(it.nameWithoutExtension, snippet(it), it.absolutePath) }
                .toList()
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
