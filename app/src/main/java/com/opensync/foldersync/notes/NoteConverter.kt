package com.opensync.foldersync.notes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Converts a raw Samsung Notes file into an editable Markdown (.md) note next to the original. */
object NoteConverter {

    suspend fun toMarkdown(context: Context, src: File): File? = withContext(Dispatchers.IO) {
        val dir = src.parentFile ?: return@withContext null
        val base = src.nameWithoutExtension
        val parsed = NoteParser.parse(context, src)
        val md = uniqueMd(dir, base)

        val sb = StringBuilder()
        sb.append("# ").append(base).append("\n\n")

        if (parsed.imagePaths.isNotEmpty()) {
            val assets = File(dir, "${md.nameWithoutExtension}_assets").apply { mkdirs() }
            parsed.imagePaths.forEach { p ->
                val img = File(p)
                val dest = File(assets, img.name)
                runCatching { img.copyTo(dest, overwrite = true) }
                sb.append("![](").append(assets.name).append('/').append(img.name).append(")\n\n")
            }
        }

        when {
            parsed.text.isNotBlank() -> sb.append(parsed.text).append('\n')
            parsed.imagePaths.isEmpty() ->
                sb.append("_").append(parsed.note ?: "No extractable text.").append("_\n")
        }

        runCatching { md.writeText(sb.toString()); md }.getOrNull()
    }

    private fun uniqueMd(dir: File, base: String): File {
        var f = File(dir, "$base.md")
        var i = 2
        while (f.exists()) { f = File(dir, "$base ($i).md"); i++ }
        return f
    }
}
