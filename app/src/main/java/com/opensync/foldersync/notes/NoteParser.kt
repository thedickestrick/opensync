package com.opensync.foldersync.notes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** Result of a best-effort parse of a raw Samsung Notes (.spd/.sdoc) file. */
data class ParsedNote(
    val title: String,
    val imagePaths: List<String>,
    val text: String,
    val note: String? = null
)

/**
 * Samsung Notes .spd and older .sdoc files are ZIP containers holding proprietary content.
 * Handwriting/S-Pen strokes are undocumented vector data we can't faithfully re-render, but we
 * can reliably pull out embedded/preview images and best-effort typed text.
 */
object NoteParser {
    private val IMAGE_EXTS = listOf(".png", ".jpg", ".jpeg", ".webp", ".bmp")
    private const val MIN_IMAGE_BYTES = 2_048          // skip tiny UI/icon assets
    private const val MAX_TEXT_ENTRY_BYTES = 4_000_000

    suspend fun parse(context: Context, src: File): ParsedNote = withContext(Dispatchers.IO) {
        val title = src.nameWithoutExtension
        val outDir = File(context.cacheDir, "notes/${src.absolutePath.hashCode()}").apply {
            deleteRecursively(); mkdirs()
        }
        val images = ArrayList<String>()
        val textBuf = StringBuilder()
        var sawZip = false

        try {
            ZipInputStream(src.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                var idx = 0
                while (entry != null) {
                    if (!entry.isDirectory) {
                        sawZip = true
                        val lower = entry.name.lowercase()
                        val bytes = zis.readBytes()
                        when {
                            IMAGE_EXTS.any { lower.endsWith(it) } -> {
                                if (bytes.size >= MIN_IMAGE_BYTES) {
                                    val ext = lower.substringAfterLast('.', "png")
                                    val f = File(outDir, "img_%03d.%s".format(idx++, ext))
                                    f.writeBytes(bytes)
                                    images.add(f.absolutePath)
                                }
                            }
                            lower.endsWith(".json") ->
                                extractJsonText(String(bytes, Charsets.UTF_8), textBuf)
                            lower.endsWith(".txt") || lower.endsWith(".xml") || lower.endsWith(".html") ->
                                extractLooseText(String(bytes, Charsets.UTF_8), textBuf)
                            bytes.size in 1..MAX_TEXT_ENTRY_BYTES && looksTextual(bytes) ->
                                extractLooseText(String(bytes, Charsets.UTF_8), textBuf)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return@withContext ParsedNote(
                title, images.sorted(), cleanText(textBuf.toString()),
                note = "Partial parse (${e.message ?: e.javaClass.simpleName}). " +
                    "For full fidelity, export this note from Samsung Notes as PDF or text."
            )
        }

        if (!sawZip) {
            return@withContext ParsedNote(
                title, emptyList(), "",
                note = "This file isn't a readable Samsung Notes archive (it may be encrypted or a " +
                    "cloud backup). Export it from Samsung Notes as PDF, Text, or Image instead."
            )
        }

        val text = cleanText(textBuf.toString())
        val note = if (images.isEmpty() && text.isBlank())
            "No typed text or images could be extracted — this note is likely handwriting only. " +
                "Export it from Samsung Notes as PDF or Image to view it here."
        else null
        ParsedNote(title, images.sorted(), text, note)
    }

    // Typed text in Samsung Notes JSON lives in string values; collect the prose-looking ones.
    private fun extractJsonText(json: String, out: StringBuilder) {
        try {
            val t = json.trim()
            when {
                t.startsWith("{") -> walkJson(JSONObject(t), out)
                t.startsWith("[") -> walkJson(JSONArray(t), out)
                else -> extractLooseText(json, out)
            }
        } catch (_: Exception) {
            extractLooseText(json, out)
        }
    }

    private fun walkJson(node: Any?, out: StringBuilder) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) walkJson(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) walkJson(node.opt(i), out)
            is String -> if (looksLikeProse(node)) out.append(node.trim()).append('\n')
        }
    }

    private fun looksLikeProse(s: String): Boolean {
        val t = s.trim()
        if (t.length < 2) return false
        if (t.count { it.isLetter() } < 2) return false
        if (t.length > 40 && !t.contains(' ')) return false          // long unbroken token = id/blob
        if (t.matches(Regex("^[0-9a-fA-F-]{8,}$"))) return false       // uuid/hex
        if (t.matches(Regex("^[A-Za-z0-9+/=]{40,}$"))) return false    // base64 blob
        return true
    }

    private fun extractLooseText(raw: String, out: StringBuilder) {
        val noTags = raw.replace(Regex("<[^>]+>"), " ")
        for (line in noTags.split('\n')) {
            val cleaned = line.filter { it == ' ' || it == '\t' || !it.isISOControl() }.trim()
            if (cleaned.length >= 2 && cleaned.count { it.isLetter() } >= 2) {
                out.append(cleaned).append('\n')
            }
        }
    }

    private fun looksTextual(bytes: ByteArray): Boolean {
        val sample = bytes.take(1024)
        if (sample.take(256).any { it.toInt() == 0 }) return false     // NUL early = binary
        var printable = 0
        for (b in sample) {
            val c = b.toInt() and 0xFF
            if (c == 9 || c == 10 || c == 13 || c in 32..126 || c >= 128) printable++
        }
        return printable.toDouble() / sample.size > 0.9
    }

    private fun cleanText(s: String): String =
        s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
            .take(200_000)
}
