package com.opensync.foldersync.notes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Renders a note in any [ExportFormat]; the UI decides whether the bytes are shared or saved. */
object NoteExporter {

    /** The countries that print on US Letter; everywhere else uses A4. */
    private val LETTER = setOf("US", "CA", "MX", "PH", "CL", "CO", "VE", "GT", "DO", "CR", "PA", "PR", "SV")

    fun usesLetter(): Boolean = Locale.getDefault().country.uppercase() in LETTER

    suspend fun render(format: ExportFormat, title: String, markdown: String, baseDir: File?): ByteArray =
        withContext(Dispatchers.Default) {
            if (format == ExportFormat.MD) return@withContext markdown.toByteArray()
            val blocks = NoteExport.blocks(markdown, title)
            when (format) {
                ExportFormat.PDF -> NotePdf.render(blocks, baseDir, usesLetter())
                ExportFormat.DOCX -> NoteExport.toDocx(blocks, baseDir, usesLetter())
                ExportFormat.HTML -> NoteExport.toHtml(title, blocks, baseDir).toByteArray()
                ExportFormat.TXT -> NoteExport.toPlainText(blocks).toByteArray()
                ExportFormat.MD -> markdown.toByteArray()
            }
        }

    /**
     * Writes an export into the app's cache for the share sheet. Earlier exports are cleared first,
     * so the folder never holds more than the one being sent.
     */
    suspend fun toCacheFile(context: Context, format: ExportFormat, title: String, markdown: String, baseDir: File?): File {
        val bytes = render(format, title, markdown, baseDir)
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports")
            dir.deleteRecursively()
            dir.mkdirs()
            File(dir, "${NoteExport.safeFileName(title)}.${format.ext}").apply { writeBytes(bytes) }
        }
    }
}
