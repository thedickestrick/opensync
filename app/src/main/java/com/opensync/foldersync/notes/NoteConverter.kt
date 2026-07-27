package com.opensync.foldersync.notes

import android.content.Context
import android.graphics.Bitmap
import com.opensync.foldersync.pdf.PdfDoc
import com.opensync.foldersync.pdf.PdfText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Converts a note file into an editable Markdown (.md) note next to the original. */
object NoteConverter {

    suspend fun toMarkdown(context: Context, src: File): File? =
        if (src.extension.equals("pdf", ignoreCase = true)) pdfToMarkdown(src)
        else rawToMarkdown(context, src)

    /** PDF → editable note. Extracts text reliably via PDFBox; renders page images when text is sparse. */
    private suspend fun pdfToMarkdown(src: File): File? = withContext(Dispatchers.IO) {
        val dir = src.parentFile ?: return@withContext null
        val base = src.nameWithoutExtension
        val md = uniqueMd(dir, base)
        val text = PdfText.extract(src)

        val sb = StringBuilder()
        sb.append("# ").append(base).append("\n\n")

        if (text.length >= 20) {
            sb.append(text).append('\n')
        } else {
            // Little/no selectable text (likely handwritten) — render the pages as images.
            val assets = File(dir, "${md.nameWithoutExtension}_assets").apply { mkdirs() }
            runCatching {
                PdfDoc.open(src).use { doc ->
                    val pages = minOf(doc.pageCount, 40)
                    for (i in 0 until pages) {
                        val bmp = doc.renderPage(i, 1500)
                        val f = File(assets, "page_%03d.png".format(i))
                        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                        sb.append("![](").append(assets.name).append('/').append(f.name).append(")\n\n")
                    }
                }
            }
            if (text.isNotBlank()) sb.append(text).append('\n')
        }

        runCatching { md.writeText(sb.toString()); md }.getOrNull()
    }

    /** Raw Samsung Notes (.spd/.sdoc/.sdocx) → best-effort editable note. */
    private suspend fun rawToMarkdown(context: Context, src: File): File? = withContext(Dispatchers.IO) {
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
