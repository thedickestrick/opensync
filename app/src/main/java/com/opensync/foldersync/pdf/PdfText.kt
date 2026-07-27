package com.opensync.foldersync.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reliable PDF text extraction via PDFBox (documented format — no guessing). */
object PdfText {
    suspend fun extract(file: File): String = withContext(Dispatchers.IO) {
        runCatching {
            PDDocument.load(file).use { doc ->
                PDFTextStripper().apply { sortByPosition = true }.getText(doc).trim()
            }
        }.getOrDefault("")
    }
}
