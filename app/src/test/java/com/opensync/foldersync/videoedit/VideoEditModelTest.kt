package com.opensync.foldersync.videoedit

import com.opensync.foldersync.ColorEdits
import com.opensync.foldersync.NormRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEditModelTest {

    private val landscape = VideoMeta(width = 1920, height = 1080, durationMs = 10_000, hasAudio = true, frameRate = 30f)

    @Test
    fun outputSizeOnlyScalesDownAndKeepsEvenDimensions() {
        assertEquals(1920 to 1080, outputSize(1920 to 1080, RESOLUTIONS[0]))
        assertEquals(1280 to 720, outputSize(1920 to 1080, RESOLUTIONS[2]))
        // Portrait: the short side is the width.
        assertEquals(720 to 1280, outputSize(1080 to 1920, RESOLUTIONS[2]))
        // Never upscales.
        assertEquals(640 to 360, outputSize(640 to 360, RESOLUTIONS[1]))
        // Odd crops come out even.
        val (w, h) = outputSize(1001 to 777, RESOLUTIONS[0])
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test
    fun editedSizeFollowsRotationAndCrop() {
        assertEquals(1920 to 1080, editedSize(landscape, VideoEdits()))
        assertEquals(1080 to 1920, editedSize(landscape, VideoEdits(rotation = 1)))
        assertEquals(960 to 540, editedSize(landscape, VideoEdits(crop = NormRect(0.25f, 0.25f, 0.75f, 0.75f))))
    }

    @Test
    fun historyCollapsesQuickChangesAndSupportsRedo() {
        val h = EditHistory()
        val a = VideoEdits()
        val b = a.copy(speed = 2f)
        val c = b.copy(color = ColorEdits(brightness = 0.1f))
        val d = c.copy(color = ColorEdits(brightness = 0.2f))

        h.record(a, now = 1_000) // a -> b
        h.record(b, now = 5_000) // b -> c
        h.record(c, now = 5_100) // c -> d, part of the same slider drag
        assertTrue(h.canUndo)

        assertEquals(b, h.undo(d)) // the whole drag is one step
        assertEquals(a, h.undo(b))
        assertFalse(h.canUndo)
        assertNull(h.undo(a))

        assertEquals(b, h.redo(a))
        assertEquals(d, h.redo(b))
        assertFalse(h.canRedo)
    }

    @Test
    fun newChangeClearsRedo() {
        val h = EditHistory()
        val a = VideoEdits()
        val b = a.copy(volume = 0f)
        h.record(a, now = 1_000)
        h.undo(b)
        assertTrue(h.canRedo)
        h.record(a, now = 9_000)
        assertFalse(h.canRedo)
    }

    @Test
    fun modifiedCoversEveryKindOfEdit() {
        assertFalse(VideoEdits().modified)
        assertTrue(VideoEdits(speed = 0.5f).modified)
        assertTrue(VideoEdits(volume = 0.3f).modified)
        assertTrue(VideoEdits(music = MusicTrack("content://x", "x")).modified)
        assertTrue(VideoEdits(tone = ToneEdits(vignette = 0.2f)).modified)
        assertTrue(VideoEdits(strokes = listOf(DrawStroke(listOf(0.1f to 0.1f), 0, 0.01f))).modified)
        assertTrue(VideoEdits(trimEndMs = 4_000).modified)
        // A filter at zero strength changes nothing.
        assertFalse(VideoEdits(color = ColorEdits(filter = 3, filterStrength = 0f)).modified)
    }

    @Test
    fun speedLabels() {
        assertEquals("¼×", speedLabel(0.25f))
        assertEquals("2×", speedLabel(2f))
        assertEquals("1.5×", speedLabel(1.5f))
    }
}
