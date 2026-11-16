package com.opensync.foldersync.provider

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Told how many bytes of an upload have been sent so far. Called on the uploading thread, often (every
 * few KB), so it should be cheap; it may count up from 0 again if the upload is retried.
 */
typealias UploadProgress = (sent: Long) -> Unit

/** An input stream that reports how many bytes have been read through it. */
internal class ProgressInputStream(input: InputStream, private val onProgress: UploadProgress?) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) advance(it) }

    private fun advance(n: Long) {
        count += n
        onProgress?.invoke(count)
    }
}

/** Wraps [input] so reading it reports progress, or returns it unchanged when nobody is listening. */
internal fun InputStream.reportingProgress(onProgress: UploadProgress?): InputStream =
    if (onProgress == null) this else ProgressInputStream(this, onProgress)

/**
 * This body, reporting the bytes OkHttp writes of it. [alreadySent] is added to the count, for a
 * chunk of a larger file sent in several requests.
 */
internal fun RequestBody.reportingProgress(onProgress: UploadProgress?, alreadySent: Long = 0L): RequestBody {
    if (onProgress == null) return this
    val inner = this
    return object : RequestBody() {
        override fun contentType(): MediaType? = inner.contentType()
        override fun contentLength(): Long = inner.contentLength()
        override fun isOneShot(): Boolean = inner.isOneShot()

        override fun writeTo(sink: BufferedSink) {
            var sent = alreadySent
            val counting = object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    sent += byteCount
                    onProgress(sent)
                }
            }.buffer()
            inner.writeTo(counting)
            counting.emit() // hand on the last partial segment; OkHttp closes the sink itself
        }
    }
}
