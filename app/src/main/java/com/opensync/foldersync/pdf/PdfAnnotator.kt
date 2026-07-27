package com.opensync.foldersync.pdf

import android.graphics.Color
import android.graphics.PointF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A freehand stroke. [points] are normalized (0..1) within the page; [normWidth] is a fraction of page width. */
data class AnnotStroke(
    val points: List<PointF>,
    val colorArgb: Int,
    val normWidth: Float,
    val highlight: Boolean
)

/** A text note at a normalized (0..1) position; [normSize] is a fraction of page width. */
data class AnnotText(
    val x: Float,
    val y: Float,
    val text: String,
    val colorArgb: Int,
    val normSize: Float
)

data class PageAnnotations(
    val strokes: List<AnnotStroke> = emptyList(),
    val texts: List<AnnotText> = emptyList()
) {
    fun isEmpty(): Boolean = strokes.isEmpty() && texts.isEmpty()
}

/** Flattens ink / highlighter / text annotations onto a PDF (burned into the page content). */
object PdfAnnotator {

    suspend fun save(src: File, dest: File, byPage: Map<Int, PageAnnotations>) =
        withContext(Dispatchers.IO) {
            PDDocument.load(src).use { doc ->
                for ((pageIndex, ann) in byPage) {
                    if (ann.isEmpty() || pageIndex !in 0 until doc.numberOfPages) continue
                    val page = doc.getPage(pageIndex)
                    val box = page.mediaBox
                    val w = box.width
                    val h = box.height
                    PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true
                    ).use { cs ->
                        for (s in ann.strokes) drawStroke(cs, s, w, h)
                        for (t in ann.texts) drawText(cs, t, w, h)
                    }
                }
                doc.save(dest)
            }
        }

    private fun drawStroke(cs: PDPageContentStream, s: AnnotStroke, w: Float, h: Float) {
        val c = s.colorArgb
        cs.setStrokingColor(Color.red(c), Color.green(c), Color.blue(c))
        cs.setLineWidth((s.normWidth * w).coerceAtLeast(0.5f))
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)
        val gs = PDExtendedGraphicsState()
        gs.strokingAlphaConstant = if (s.highlight) 0.35f else 1f
        cs.setGraphicsStateParameters(gs)
        val pts = s.points
        when {
            pts.size >= 2 -> {
                cs.moveTo(pts[0].x * w, (1f - pts[0].y) * h)
                for (i in 1 until pts.size) cs.lineTo(pts[i].x * w, (1f - pts[i].y) * h)
                cs.stroke()
            }
            pts.size == 1 -> {
                val x = pts[0].x * w
                val y = (1f - pts[0].y) * h
                cs.moveTo(x, y); cs.lineTo(x + 0.5f, y); cs.stroke()
            }
        }
    }

    private fun drawText(cs: PDPageContentStream, t: AnnotText, w: Float, h: Float) {
        val c = t.colorArgb
        cs.setNonStrokingColor(Color.red(c), Color.green(c), Color.blue(c))
        val fontSize = (t.normSize * w).coerceAtLeast(6f)
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA, fontSize)
        cs.newLineAtOffset(t.x * w, (1f - t.y) * h - fontSize)
        cs.showText(t.text.filter { it.code in 32..126 })
        cs.endText()
    }
}
