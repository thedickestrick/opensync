package com.opensync.foldersync.videoedit

import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * A small animated-GIF (GIF89a) writer. Frames are mapped onto one fixed 256-colour palette
 * (3 bits red, 3 green, 2 blue) with ordered dithering, which is fast and needs no per-frame
 * analysis; the animation loops forever. Pure Kotlin so it can be unit tested on the JVM.
 */
internal class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int) {
    private var started = false

    init {
        require(width in 1..0xFFFF && height in 1..0xFFFF) { "Bad GIF size ${width}x$height" }
    }

    private fun start() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical screen descriptor, with a 256-entry global colour table.
        writeShort(width)
        writeShort(height)
        out.write(0xF7) // global table present, 8-bit colour resolution, 2^(7+1) entries
        out.write(0) // background colour index
        out.write(0) // pixel aspect ratio
        out.write(PALETTE)
        // NETSCAPE2.0 application extension: loop forever.
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
        started = true
    }

    /** Appends one frame of ARGB pixels ([width] x [height], row-major) shown for [delayMs]. */
    fun addFrame(argb: IntArray, delayMs: Int) {
        require(argb.size == width * height) { "Frame has ${argb.size} pixels, expected ${width * height}" }
        if (!started) start()
        // Graphic control extension: frame delay in 1/100 s, no transparency.
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00))
        writeShort(((delayMs + 5) / 10).coerceIn(2, 0xFFFF))
        out.write(byteArrayOf(0x00, 0x00))
        // Image descriptor: full frame, no local colour table.
        out.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        out.write(0)
        out.write(8) // LZW minimum code size
        lzwEncode(quantize(argb))
    }

    /** Writes the trailer. The stream is left open. */
    fun finish() {
        if (!started) start()
        out.write(0x3B)
        out.flush()
    }

    private fun writeShort(v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun quantize(argb: IntArray): ByteArray {
        val idx = ByteArray(argb.size)
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = argb[i]
                // Ordered dither of under half a palette step, so exact palette colours stay put.
                val d = BAYER[(y and 3) * 4 + (x and 3)] // -7.5..7.5, in 1/16ths of a palette step
                val r = level(((p ushr 16) and 0xFF) + d * STEP_RG / 16f, STEP_RG, 7)
                val g = level(((p ushr 8) and 0xFF) + d * STEP_RG / 16f, STEP_RG, 7)
                val b = level((p and 0xFF) + d * STEP_B / 16f, STEP_B, 3)
                idx[i] = ((r shl 5) or (g shl 2) or b).toByte()
                i++
            }
        }
        return idx
    }

    // ---- LZW (variable code width, 9..12 bits, GIF flavour) ----

    private val block = ByteArray(255)
    private var blockLen = 0
    private var bitBuf = 0
    private var bitCount = 0

    private fun lzwEncode(pixels: ByteArray) {
        val clear = 256
        val eoi = 257
        val keys = IntArray(TABLE_SIZE)
        val codes = IntArray(TABLE_SIZE)
        fun reset() = keys.fill(-1)
        reset()
        var codeSize = 9
        var next = 258
        blockLen = 0; bitBuf = 0; bitCount = 0

        emit(clear, codeSize)
        var prefix = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val k = pixels[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            var slot = (key * 0x9E3779B1.toInt() ushr 18) and (TABLE_SIZE - 1)
            while (keys[slot] != -1 && keys[slot] != key) slot = (slot + 1) and (TABLE_SIZE - 1)
            if (keys[slot] == key) {
                prefix = codes[slot]
                continue
            }
            emit(prefix, codeSize)
            if (next < MAX_CODES) {
                keys[slot] = key
                codes[slot] = next++
                // The decoder widens its codes once its table reaches 2^codeSize entries; it is
                // always one entry behind the encoder, hence ">" rather than ">=".
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                emit(clear, codeSize)
                reset()
                codeSize = 9
                next = 258
            }
            prefix = k
        }
        emit(prefix, codeSize)
        emit(eoi, codeSize)
        if (bitCount > 0) pushByte(bitBuf and 0xFF)
        flushBlock()
        out.write(0) // block terminator
    }

    private fun emit(code: Int, size: Int) {
        bitBuf = bitBuf or (code shl bitCount)
        bitCount += size
        while (bitCount >= 8) {
            pushByte(bitBuf and 0xFF)
            bitBuf = bitBuf ushr 8
            bitCount -= 8
        }
    }

    private fun pushByte(b: Int) {
        block[blockLen++] = b.toByte()
        if (blockLen == 255) flushBlock()
    }

    private fun flushBlock() {
        if (blockLen == 0) return
        out.write(blockLen)
        out.write(block, 0, blockLen)
        blockLen = 0
    }

    companion object {
        private const val MAX_CODES = 4096
        private const val TABLE_SIZE = 1 shl 14 // > MAX_CODES, power of two for cheap wrapping
        private const val STEP_RG = 255f / 7f
        private const val STEP_B = 255f / 3f

        private val BAYER = floatArrayOf(0f, 8f, 2f, 10f, 12f, 4f, 14f, 6f, 3f, 11f, 1f, 9f, 15f, 7f, 13f, 5f)
            .map { it - 7.5f }.toFloatArray()

        private fun level(v: Float, step: Float, max: Int): Int = (v / step).roundToInt().coerceIn(0, max)

        /** Nearest palette entry (levels 0..7 for red/green, 0..3 for blue). */
        fun paletteIndex(r: Int, g: Int, b: Int): Int =
            (level(r.toFloat(), STEP_RG, 7) shl 5) or (level(g.toFloat(), STEP_RG, 7) shl 2) or level(b.toFloat(), STEP_B, 3)

        /** RGB triplets for all 256 indices of [paletteIndex]. */
        val PALETTE: ByteArray = ByteArray(256 * 3).also { p ->
            for (i in 0 until 256) {
                p[i * 3] = ((i ushr 5) * STEP_RG).roundToInt().toByte()
                p[i * 3 + 1] = (((i ushr 2) and 7) * STEP_RG).roundToInt().toByte()
                p[i * 3 + 2] = ((i and 3) * STEP_B).roundToInt().toByte()
            }
        }
    }
}
