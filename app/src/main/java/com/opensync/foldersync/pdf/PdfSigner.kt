package com.opensync.foldersync.pdf

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A signature stamp on a page. [x],[y] = normalized (0..1) top-left; [normWidth] = fraction of page width. */
data class SignPlacement(val pageIndex: Int, val x: Float, val y: Float, val normWidth: Float)

/** Stamps a signature bitmap (transparent PNG) onto a PDF at the given placements. */
object PdfSigner {

    suspend fun save(src: File, dest: File, signature: Bitmap, placements: List<SignPlacement>) =
        withContext(Dispatchers.IO) {
            PDDocument.load(src).use { doc ->
                val image = LosslessFactory.createFromImage(doc, signature)
                val aspect = signature.height.toFloat() / signature.width.coerceAtLeast(1)
                for (p in placements) {
                    if (p.pageIndex !in 0 until doc.numberOfPages) continue
                    val page = doc.getPage(p.pageIndex)
                    val box = page.mediaBox
                    val w = box.width
                    val h = box.height
                    val imgW = p.normWidth * w
                    val imgH = imgW * aspect
                    val x = p.x * w
                    val yBottom = (1f - p.y) * h - imgH
                    PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true
                    ).use { cs ->
                        cs.drawImage(image, x, yBottom, imgW, imgH)
                    }
                }
                doc.save(dest)
            }
        }
}
