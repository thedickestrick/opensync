package com.opensync.foldersync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class PhotoEditModelTest {

    private fun assertRect(expected: NormRect, actual: NormRect, eps: Float = 1e-5f) {
        assertEquals("l", expected.l, actual.l, eps)
        assertEquals("t", expected.t, actual.t, eps)
        assertEquals("r", expected.r, actual.r, eps)
        assertEquals("b", expected.b, actual.b, eps)
    }

    @Test
    fun `quarter turns carry the crop box with the picture`() {
        val e = PhotoEdits(crop = NormRect(0.1f, 0.2f, 0.5f, 0.6f))
        val cw = e.rotated(clockwise = true)
        assertEquals(1, cw.rotation)
        // The box's left edge (x = 0.1) becomes its top edge; its bottom (y = 0.6) its left (1 - 0.6).
        assertRect(NormRect(0.4f, 0.1f, 0.8f, 0.5f), cw.crop)
        assertRect(e.crop, cw.rotated(clockwise = false).crop)
        assertEquals(0, cw.rotated(clockwise = false).rotation)
        var four = e
        repeat(4) { four = four.rotated(clockwise = true) }
        assertEquals(0, four.rotation)
        assertRect(e.crop, four.crop)
    }

    @Test
    fun `mirroring follows the screen on a sideways picture`() {
        val upright = PhotoEdits(straighten = 5f, crop = NormRect(0.1f, 0.2f, 0.5f, 0.6f)).mirrored()
        assertTrue(upright.flipH)
        assertFalse(upright.flipV)
        assertEquals(-5f, upright.straighten)
        assertRect(NormRect(0.5f, 0.2f, 0.9f, 0.6f), upright.crop)

        val sideways = PhotoEdits(rotation = 1).mirrored()
        assertFalse(sideways.flipH)
        assertTrue(sideways.flipV)

        val back = PhotoEdits(rotation = 3, straighten = 7f, crop = NormRect(0.1f, 0.2f, 0.5f, 0.6f)).flipped().flipped()
        assertEquals(PhotoEdits(rotation = 3, straighten = 7f, crop = NormRect(0.1f, 0.2f, 0.5f, 0.6f)).copy(crop = back.crop), back)
        assertRect(NormRect(0.1f, 0.2f, 0.5f, 0.6f), back.crop)
    }

    @Test
    fun `straightening zooms just enough to leave no empty corners`() {
        assertEquals(1f, straightenScale(4f, 3f, 0f))
        for ((w, h) in listOf(4f to 3f, 3f to 4f, 1f to 1f, 16f to 9f)) {
            for (deg in listOf(-45f, -12f, 3f, 30f, 45f)) {
                val k = straightenScale(w, h, deg)
                val rad = Math.toRadians(deg.toDouble())
                // Every corner of the frame, taken back into the turned & scaled picture, must be inside it.
                for ((cx, cy) in listOf(w / 2 to h / 2, -w / 2 to h / 2)) {
                    val x = (cx * cos(rad) + cy * sin(rad)) / k
                    val y = (-cx * sin(rad) + cy * cos(rad)) / k
                    assertTrue("$w x $h at $deg°", abs(x) <= w / 2 + 1e-4 && abs(y) <= h / 2 + 1e-4)
                }
            }
        }
        // ...and no more than that: one corner touches the edge.
        assertEquals(cos(Math.toRadians(10.0)).toFloat() + 4f / 3f * sin(Math.toRadians(10.0)).toFloat(), straightenScale(4f, 3f, 10f), 1e-5f)
    }

    @Test
    fun `free crop edges move and stop at the image`() {
        val c = NormRect(0.2f, 0.2f, 0.8f, 0.8f)
        assertRect(NormRect(0.2f, 0.2f, 0.9f, 0.8f), resizeCrop(c, 0.1f, 0f, false, true, false, false, null))
        assertRect(NormRect(0.2f, 0.2f, 1f, 0.8f), resizeCrop(c, 0.5f, 0f, false, true, false, false, null))
        // Never smaller than the minimum.
        val tiny = resizeCrop(c, -2f, 0f, false, true, false, false, null)
        assertEquals(0.05f, tiny.width, 1e-5f)
        assertRect(NormRect(0f, 0.4f, 0.6f, 1f), moveCrop(c, -0.5f, 0.5f))
    }

    @Test
    fun `a locked ratio survives corner and side drags`() {
        val ratio = 1.5f
        val c = NormRect(0.2f, 0.3f, 0.5f, 0.5f) // 0.3 x 0.2 = 1.5
        // Corner (bottom-right), pulled further sideways: the height follows; the top-left stays put.
        val corner = resizeCrop(c, 0.15f, 0.01f, false, true, false, true, ratio)
        assertEquals(ratio, corner.width / corner.height, 1e-4f)
        assertEquals(0.2f, corner.l, 1e-5f)
        assertEquals(0.3f, corner.t, 1e-5f)
        assertEquals(0.45f, corner.width, 1e-5f)
        // Pulled far past the image: it stops at the first edge it hits, ratio kept.
        val big = resizeCrop(c, 5f, 5f, false, true, false, true, ratio)
        assertEquals(ratio, big.width / big.height, 1e-4f)
        assertTrue(big.r <= 1f && big.b <= 1f)
        assertEquals(1f, maxOf(big.r, big.b), 1e-5f)
        // Side: the height grows evenly about the middle.
        val side = resizeCrop(c, -0.15f, 0f, true, false, false, false, ratio)
        assertEquals(ratio, side.width / side.height, 1e-4f)
        assertEquals(0.4f, (side.t + side.b) / 2f, 1e-5f)
        assertEquals(0.5f, side.r, 1e-5f)
        // Top edge near the image's edge: the width is limited so the box fits.
        val top = resizeCrop(NormRect(0.05f, 0.4f, 0.2f, 0.5f), 0f, -0.3f, false, false, true, false, ratio)
        assertEquals(ratio, top.width / top.height, 1e-4f)
        assertTrue(top.l >= 0f && top.r <= 1f && top.t >= 0f)
        assertEquals(0.5f, top.b, 1e-5f)
    }

    @Test
    fun `sizes and formats`() {
        assertEquals(2560 to 1920, photoOutputSize(4000, 3000, PhotoSize("Large", 2560)))
        assertEquals(4000 to 3000, photoOutputSize(4000, 3000, PHOTO_SIZES.first()))
        assertEquals(1000 to 800, photoOutputSize(1000, 800, PhotoSize("Large", 2560))) // never enlarged
        assertEquals(1500 to 2000, editedPhotoSize(4000, 3000, PhotoEdits(rotation = 1, crop = NormRect(0f, 0f, 0.5f, 0.5f))))

        assertEquals(PhotoFormat.JPEG, writableFormat("IMG_1.JPG", null))
        assertEquals(PhotoFormat.PNG, writableFormat("shot.png", "image/png"))
        assertEquals(PhotoFormat.WEBP, writableFormat(null, "image/webp"))
        assertEquals(PhotoFormat.JPEG, writableFormat("my.holiday", "image/jpeg"))
        assertNull(writableFormat("IMG_2.heic", "image/jpeg"))
        assertNull(writableFormat("anim.gif", null))
        assertNull(writableFormat(null, "image/heic"))
    }
}
