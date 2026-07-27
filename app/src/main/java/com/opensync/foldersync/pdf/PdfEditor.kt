package com.opensync.foldersync.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A page kept in the output: which source page, plus an added rotation (0/90/180/270). */
data class PageOp(val sourceIndex: Int, val rotation: Int)

/** Page-level PDF editing (reorder / delete / rotate / split / merge) via Apache PDFBox. */
object PdfEditor {

    /**
     * Write [dest] as the pages listed in [order] taken from [src] — in that order, omitting any
     * not listed (delete/split), each with an added [PageOp.rotation].
     */
    suspend fun savePages(src: File, order: List<PageOp>, dest: File) = withContext(Dispatchers.IO) {
        PDDocument.load(src).use { source ->
            PDDocument().use { out ->
                for (op in order) {
                    val page = source.getPage(op.sourceIndex)
                    val imported = out.importPage(page)
                    imported.rotation = (page.rotation + op.rotation) % 360
                }
                out.save(dest)
            }
        }
    }

    /** Merge [files] (in order) into a single [dest] PDF. */
    suspend fun merge(files: List<File>, dest: File) = withContext(Dispatchers.IO) {
        val merger = PDFMergerUtility()
        merger.destinationFileName = dest.absolutePath
        for (f in files) merger.addSource(f)
        merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
    }
}
