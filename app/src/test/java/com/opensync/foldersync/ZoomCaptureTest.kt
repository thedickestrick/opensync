package com.opensync.foldersync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoomCaptureTest {

    private fun assertRegion(expected: NormRect, actual: NormRect?) {
        requireNotNull(actual) { "expected $expected, got null" }
        assertEquals(expected.l, actual.l, 1e-4f)
        assertEquals(expected.t, actual.t, 1e-4f)
        assertEquals(expected.r, actual.r, 1e-4f)
        assertEquals(expected.b, actual.b, 1e-4f)
    }

    @Test
    fun `not zoomed, the whole picture is on screen`() {
        assertRegion(NormRect(0f, 0f, 1f, 1f), visibleRegion(1000f, 2000f, 4000f, 3000f, 1f, 0f, 0f))
    }

    @Test
    fun `zoomed into the middle keeps the middle`() {
        // A square picture filling a square screen, at 2x: the middle half each way.
        assertRegion(NormRect(0.25f, 0.25f, 0.75f, 0.75f), visibleRegion(1000f, 1000f, 500f, 500f, 2f, 0f, 0f))
        // At 4x: the middle quarter.
        assertRegion(NormRect(0.375f, 0.375f, 0.625f, 0.625f), visibleRegion(1000f, 1000f, 500f, 500f, 4f, 0f, 0f))
    }

    @Test
    fun `the black bars around a wide picture aren't kept`() {
        // A 2:1 picture on a 1:2 screen sits in a 1000x500 band. At 2x the band is 1000 tall, which
        // still fits on screen: all of its height but only the middle half of its width shows.
        assertRegion(NormRect(0.25f, 0f, 0.75f, 1f), visibleRegion(1000f, 2000f, 2000f, 1000f, 2f, 0f, 0f))
    }

    @Test
    fun `moving the picture right shows its left side`() {
        assertRegion(NormRect(0f, 0.25f, 0.5f, 0.75f), visibleRegion(1000f, 1000f, 100f, 100f, 2f, 500f, 0f))
        // Up shows the bottom.
        assertRegion(NormRect(0.25f, 0.5f, 0.75f, 1f), visibleRegion(1000f, 1000f, 100f, 100f, 2f, 0f, -500f))
    }

    @Test
    fun `nothing to keep when the picture is off screen or unknown`() {
        assertNull(visibleRegion(1000f, 1000f, 100f, 100f, 2f, 3000f, 0f))
        assertNull(visibleRegion(1000f, 1000f, 0f, 0f, 2f, 0f, 0f))
        assertNull(visibleRegion(0f, 0f, 100f, 100f, 2f, 0f, 0f))
    }
}
