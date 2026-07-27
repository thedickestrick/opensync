package com.opensync.foldersync.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Thin wrapper over Android's built-in [PdfRenderer] that renders pages to bitmaps on demand.
 * PdfRenderer allows only one open page at a time and isn't thread-safe, so access is serialized
 * with a mutex; recently rendered pages are cached.
 */
class PdfDoc private constructor(private val pfd: ParcelFileDescriptor) : Closeable {

    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()
    private val cache = LruCache<String, Bitmap>(8)

    val pageCount: Int get() = renderer.pageCount

    suspend fun renderPage(index: Int, targetWidth: Int): Bitmap {
        val key = "$index@$targetWidth"
        cache.get(key)?.let { return it }
        return mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                renderer.openPage(index).use { page ->
                    val width = targetWidth.coerceAtLeast(1)
                    val scale = width.toFloat() / page.width
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bitmap)
                    bitmap
                }
            }
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
        cache.evictAll()
    }

    companion object {
        fun open(file: File): PdfDoc =
            PdfDoc(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
    }
}
