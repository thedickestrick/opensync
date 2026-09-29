package com.opensync.foldersync.videoedit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class GifEncoderTest {

    @Test
    fun framesDecodeBackToTheirPaletteColours() {
        val w = 97
        val h = 61
        val rnd = Random(3)
        // Solid palette colours avoid dithering, so every pixel must come back exactly.
        val paletteColours = IntArray(256) { i ->
            val p = GifEncoder.PALETTE
            0xFF000000.toInt() or ((p[i * 3].toInt() and 0xFF) shl 16) or
                ((p[i * 3 + 1].toInt() and 0xFF) shl 8) or (p[i * 3 + 2].toInt() and 0xFF)
        }
        val frames = listOf(
            IntArray(w * h) { paletteColours[rnd.nextInt(256)] }, // noise: forces LZW table resets
            IntArray(w * h) { paletteColours[200] }, // one colour: long runs
            IntArray(w * h) { i -> paletteColours[(i / 7) % 256] }
        )
        val bytes = ByteArrayOutputStream().also { bos ->
            val enc = GifEncoder(bos, w, h)
            frames.forEach { enc.addFrame(it, 100) }
            enc.finish()
        }.toByteArray()

        val decoded = decodeGif(bytes)
        assertEquals(w, decoded.width)
        assertEquals(h, decoded.height)
        assertEquals(frames.size, decoded.frames.size)
        frames.forEachIndexed { n, f ->
            val want = IntArray(f.size) { i -> indexOf(f[i]) }
            assertArrayEquals("frame $n", want, decoded.frames[n])
        }
        assertEquals(10, decoded.delaysCs.first())
    }

    private fun indexOf(argb: Int) =
        GifEncoder.paletteIndex((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF)

    private class Decoded(val width: Int, val height: Int, val frames: List<IntArray>, val delaysCs: List<Int>)

    /** Just enough of a GIF decoder for what [GifEncoder] writes. */
    private fun decodeGif(b: ByteArray): Decoded {
        var pos = 0
        fun u8() = b[pos++].toInt() and 0xFF
        fun u16() = u8() or (u8() shl 8)
        assertEquals("GIF89a", String(b, 0, 6, Charsets.US_ASCII))
        pos = 6
        val w = u16()
        val h = u16()
        val packed = u8()
        pos += 2
        if (packed and 0x80 != 0) pos += 3 * (1 shl ((packed and 7) + 1))
        val frames = ArrayList<IntArray>()
        val delays = ArrayList<Int>()
        while (true) {
            when (u8()) {
                0x3B -> return Decoded(w, h, frames, delays)
                0x21 -> {
                    val label = u8()
                    if (label == 0xF9) { u8(); u8(); delays += u16(); u8(); u8() } else {
                        while (true) { val n = u8(); if (n == 0) break; pos += n }
                    }
                }
                0x2C -> {
                    pos += 8
                    u8()
                    val minCode = u8()
                    val data = ByteArrayOutputStream()
                    while (true) { val n = u8(); if (n == 0) break; data.write(b, pos, n); pos += n }
                    frames += lzwDecode(data.toByteArray(), minCode, w * h)
                }
                else -> error("Unexpected block at $pos")
            }
        }
    }

    private fun lzwDecode(data: ByteArray, minCode: Int, count: Int): IntArray {
        val clear = 1 shl minCode
        val eoi = clear + 1
        val prefix = IntArray(4096)
        val suffix = IntArray(4096)
        val out = IntArray(count)
        var n = 0
        var size = minCode + 1
        var next = eoi + 1
        var old = -1
        var bit = 0
        fun read(): Int {
            var v = 0
            for (i in 0 until size) {
                val byte = data[(bit + i) shr 3].toInt()
                v = v or (((byte shr ((bit + i) and 7)) and 1) shl i)
            }
            bit += size
            return v
        }
        fun expand(code: Int): IntArray {
            val s = ArrayList<Int>()
            var c = code
            while (c >= clear) { s += suffix[c]; c = prefix[c] }
            s += c
            return s.reversed().toIntArray()
        }
        while (true) {
            val code = read()
            if (code == clear) { size = minCode + 1; next = eoi + 1; old = -1; continue }
            if (code == eoi) break
            val str = when {
                old == -1 -> intArrayOf(code)
                code < next -> expand(code)
                else -> expand(old).let { it + it[0] }
            }
            if (old != -1 && next < 4096) {
                prefix[next] = old
                suffix[next] = str[0]
                next++
                if (next == (1 shl size) && size < 12) size++
            }
            for (v in str) out[n++] = v
            old = code
        }
        assertEquals(count, n)
        return out
    }
}
