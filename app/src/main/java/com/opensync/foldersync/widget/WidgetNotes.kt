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
            .map { it.trim().trimStart('#', '>', '-', '*', ' ') }
            .filter { it.isNotEmpty() }
            .joinToString("  ")
            .take(max)
    }

    /** Larger body preview for the single-note widget (light markdown-marker cleanup). */
    fun body(file: File, max: Int = 2000): String {
        val raw = runCatching { file.bufferedReader().use { it.readText() } }.getOrDefault("")
        return raw.lineSequence()
            .joinToString("\n") { line ->
                line.replace(Regex("^#{1,6}\\s+"), "")
                    .replace(Regex("^\\s*[-*]\\s+"), "• ")
                    .replace("**", "")
                    .replace("__", "")
            }
            .trim()
            .take(max)
    }
}
