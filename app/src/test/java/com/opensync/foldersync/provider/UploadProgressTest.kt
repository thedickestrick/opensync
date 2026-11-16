package com.opensync.foldersync.provider

import com.opensync.foldersync.UploadStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class UploadProgressTest {

    private val octet = "application/octet-stream".toMediaType()

    @Test
    fun `a stream counts what is read through it`() {
        val data = ByteArray(100_000) { it.toByte() }
        val seen = ArrayList<Long>()
        val copied = ByteArrayInputStream(data).reportingProgress { seen += it }.use { it.readBytes() }
        assertArrayEquals(data, copied)
        assertEquals(100_000L, seen.last())
        assertTrue("counts only go up", seen.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `nobody listening leaves the stream and body alone`() {
        val stream = ByteArrayInputStream(ByteArray(3))
        assertSame(stream, stream.reportingProgress(null))
        val body = ByteArray(3).toRequestBody(octet)
        assertSame(body, body.reportingProgress(null))
    }

    @Test
    fun `a file body reports every byte and still sends them all`() {
        val file = File.createTempFile("upload", ".bin").apply { deleteOnExit() }
        val data = ByteArray(300_000) { (it * 7).toByte() }
        file.writeBytes(data)
        val seen = ArrayList<Long>()
        val body = file.asRequestBody(octet).reportingProgress({ seen += it })
        assertEquals(300_000L, body.contentLength())
        assertEquals(octet, body.contentType())
        val sink = Buffer()
        body.writeTo(sink)
        assertArrayEquals(data, sink.readByteArray())
        assertEquals(300_000L, seen.last())
        assertTrue("reported as it goes, not just at the end", seen.size > 5)
    }

    @Test
    fun `a chunk adds what was sent before it`() {
        val seen = ArrayList<Long>()
        ByteArray(1000).toRequestBody(octet).reportingProgress({ seen += it }, alreadySent = 5000).writeTo(Buffer())
        assertEquals(6000L, seen.last())
        assertTrue(seen.first() > 5000)
    }

    @Test
    fun `upload status works out the share sent`() {
        assertEquals(0, UploadStatus(0, 0).percent) // an empty file doesn't divide by zero
        assertEquals(50, UploadStatus(512, 1024).percent)
        assertEquals(1f, UploadStatus(2000, 1000).fraction) // never past full
        assertEquals("1.0 MB of 2.0 MB", UploadStatus(1L shl 20, 2L shl 20).sizes.replace(',', '.'))
    }
}
