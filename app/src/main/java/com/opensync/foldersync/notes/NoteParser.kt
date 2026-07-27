package com.opensync.foldersync.notes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Result of a best-effort parse of a raw Samsung Notes (.spd/.sdoc/.snb) file. */
data class ParsedNote(
    val title: String,
    val imagePaths: List<String>,
    val text: String,
    val note: String? = null
)

/**
 * Samsung Notes .spd/.sdoc/.snb files are proprietary containers (usually ZIP). Handwriting/S-Pen
 * strokes are undocumented vector data we can't re-render, but we can extract embedded/preview
 * images and typed text. Strategy: read as a ZIP first (central-directory based, tolerant); if that
 * fails or yields nothing, carve images and text runs (UTF-8 + UTF-16) straight from the raw bytes.
 */
object NoteParser {
    private val IMAGE_EXTS = listOf(".png", ".jpg", ".jpeg", ".webp", ".bmp")
    private const val MIN_IMAGE_BYTES = 2_048
    private const val MAX_TEXT_ENTRY_BYTES = 6_000_000

    suspend fun parse(context: Context, src: File): ParsedNote = withContext(Dispatchers.IO) {
        val title = src.nameWithoutExtension
        val outDir = File(context.cacheDir, "notes/${src.absolutePath.hashCode()}").apply {
            deleteRecursively(); mkdirs()
        }
        val images = ArrayList<String>()
        val text = StringBuilder()

        val zipOk = runCatching { readZip(src, outDir, images, text) }.getOrDefault(false)
        if (!zipOk || (images.isEmpty() && text.isBlank())) {
            // Not a (readable) zip, or the zip gave us nothing usable — carve the raw bytes.
            runCatching { carve(src.readBytes(), outDir, images, text) }
        }

        val cleaned = cleanText(text.toString())
        val note = when {
            images.isEmpty() && cleaned.isBlank() ->
                "No typed text or images could be extracted. This note may be handwriting only, " +
                    "encrypted, or a cloud backup. Export it from Samsung Notes as PDF, Text, or Image."
            else -> null
        }
        ParsedNote(title, images.sorted(), cleaned, note)
    }

    /** Returns true if [src] opened as a ZIP (whether or not content was found). */
    private fun readZip(src: File, outDir: File, images: MutableList<String>, text: StringBuilder): Boolean {
        val zf = try { ZipFile(src) } catch (_: Exception) { return false }
        zf.use { zip ->
            var idx = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val lower = e.name.lowercase()
                val bytes = runCatching { zip.getInputStream(e).use { it.readBytes() } }.getOrNull() ?: continue
                when {
                    IMAGE_EXTS.any { lower.endsWith(it) } -> addImage(bytes, outDir, idx++, images)
                    bytes.size <= MAX_TEXT_ENTRY_BYTES -> {
                        val decoded = decodeText(bytes)
                        if (looksLikeJson(decoded)) extractJsonText(decoded, text)
                        else extractLooseText(decoded, text)
                    }
                }
            }
        }
        return true
    }

    /** Carve images (by magic bytes) and text runs (UTF-8 + UTF-16LE) from arbitrary container bytes. */
    private fun carve(data: ByteArray, outDir: File, images: MutableList<String>, text: StringBuilder) {
        carveImages(data, outDir, images)
        carveText(data, text)
    }

    private fun carveImages(data: ByteArray, outDir: File, images: MutableList<String>) {
        val png = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val iend = intArrayOf(0x49, 0x45, 0x4E, 0x44)
        val jpg = intArrayOf(0xFF, 0xD8, 0xFF)
        val eoi = intArrayOf(0xFF, 0xD9)
        var idx = images.size
        var i = 0
        while (i < data.size) {
            when {
                matchAt(data, i, png) -> {
                    val end = indexOf(data, iend, i + 8)
                    if (end > 0) {
                        addImage(data.copyOfRange(i, minOf(end + 8, data.size)), outDir, idx++, images, "png")
                        i = end + 8; continue
                    }
                }
                matchAt(data, i, jpg) -> {
                    val end = indexOf(data, eoi, i + 3)
                    if (end > 0) {
                        addImage(data.copyOfRange(i, minOf(end + 2, data.size)), outDir, idx++, images, "jpg")
                        i = end + 2; continue
                    }
                }
            }
            i++
        }
    }

    private fun carveText(data: ByteArray, out: StringBuilder) {
        // UTF-8 / ASCII runs.
        val run = StringBuilder()
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c == 0x09 || c in 0x20..0x7E) run.append(c.toChar()) else flushRun(run, out)
        }
        flushRun(run, out)
        // UTF-16LE runs (ASCII char followed by a zero high byte).
        var i = 0
        while (i + 1 < data.size) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt() and 0xFF
            if (hi == 0 && (lo == 0x09 || lo in 0x20..0x7E)) run.append(lo.toChar()) else flushRun(run, out)
            i += 2
        }
        flushRun(run, out)
    }

    private fun flushRun(run: StringBuilder, out: StringBuilder) {
        val s = run.toString().trim()
        run.setLength(0)
        if (s.length >= 4 && looksLikeProse(s)) out.append(s).append('\n')
    }

    private fun addImage(
        bytes: ByteArray, outDir: File, idx: Int, images: MutableList<String>, forceExt: String? = null
    ) {
        if (bytes.size < MIN_IMAGE_BYTES) return
        val ext = forceExt ?: "png"
        val f = File(outDir, "img_%03d.%s".format(idx, ext))
        runCatching { f.writeBytes(bytes); images.add(f.absolutePath) }
    }

    private fun matchAt(data: ByteArray, pos: Int, sig: IntArray): Boolean {
        if (pos + sig.size > data.size) return false
        for (j in sig.indices) if ((data[pos + j].toInt() and 0xFF) != sig[j]) return false
        return true
    }

    private fun indexOf(data: ByteArray, sig: IntArray, from: Int): Int {
        var i = maxOf(from, 0)
        while (i + sig.size <= data.size) {
            if (matchAt(data, i, sig)) return i
            i++
        }
        return -1
    }

    /** Decode as UTF-16LE when the bytes carry many NULs (Samsung's older UTF-16 content), else UTF-8. */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sampleLen = minOf(bytes.size, 4096)
        var zeros = 0
        for (i in 0 until sampleLen) if (bytes[i].toInt() == 0) zeros++
        return if (zeros.toDouble() / sampleLen > 0.2) String(bytes, Charsets.UTF_16LE)
        else String(bytes, Charsets.UTF_8)
    }

    private fun looksLikeJson(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("{") || t.startsWith("[")
    }

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
        if (t.length > 40 && !t.contains(' ')) return false           // long unbroken token = id/blob
        if (t.matches(Regex("^[0-9a-fA-F-]{8,}$"))) return false        // uuid/hex
        if (t.matches(Regex("^[A-Za-z0-9+/=]{40,}$"))) return false     // base64 blob
        return true
    }

    private fun extractLooseText(raw: String, out: StringBuilder) {
        val noTags = raw.replace(Regex("<[^>]+>"), " ")
        for (line in noTags.split('\n')) {
            val cleaned = line.filter { it == ' ' || it == '\t' || !it.isISOControl() }.trim()
            if (cleaned.length >= 2 && cleaned.count { it.isLetter() } >= 2) out.append(cleaned).append('\n')
        }
    }

    private fun cleanText(s: String): String =
        s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
            .take(200_000)
}
