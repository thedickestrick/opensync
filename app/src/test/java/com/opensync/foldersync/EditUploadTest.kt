package com.opensync.foldersync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class EditUploadTest {

    @Test
    fun `copies get the first free name, ignoring case`() {
        assertEquals("IMG_1_edited.jpg", uniqueName(listOf("IMG_1.jpg"), "IMG_1_edited", "jpg"))
        assertEquals("IMG_1_edited (1).jpg", uniqueName(listOf("IMG_1.jpg", "img_1_EDITED.JPG"), "IMG_1_edited", "jpg"))
        assertEquals(
            "IMG_1_edited (3).jpg",
            uniqueName(listOf("IMG_1_edited.jpg", "IMG_1_edited (1).jpg", "IMG_1_edited (2).jpg"), "IMG_1_edited", "jpg")
        )
        // Another type with the same name doesn't count.
        assertEquals("clip_edited.mp4", uniqueName(listOf("clip_edited.gif"), "clip_edited", "mp4"))
    }

    @Test
    fun `the origin's folder`() {
        assertEquals("Camera/2024", CloudOrigin(1, "Drive", "Camera/2024/IMG_1.jpg").folder)
        assertEquals("", CloudOrigin(1, "Drive", "IMG_1.jpg").folder)
    }

    @Test
    fun `messages say where the edit went`() {
        val drive = CloudOrigin(7, "Google Drive", "Photos/IMG_1.jpg")
        assertEquals("Photo saved to Google Drive", SavedEdit(cloudName = "IMG_1.jpg", overwrote = true).message("Photo", drive))
        assertEquals(
            "Copy saved to Google Drive as IMG_1_edited.jpg",
            SavedEdit(cloudName = "IMG_1_edited.jpg").message("Copy", drive)
        )
        assertEquals(
            "Couldn't upload to Google Drive (timeout). Saved to Pictures/OpenSync on this phone instead.",
            SavedEdit(file = File("/storage/emulated/0/Pictures/OpenSync/IMG_1_edited.jpg"), uploadError = "timeout").message("Copy", drive)
        )
        assertEquals("Copy saved to Camera", SavedEdit(file = File("/storage/emulated/0/DCIM/Camera/IMG_1_edited.jpg")).message("Copy", null))
        assertEquals("Photo saved", SavedEdit(file = File("/sdcard/DCIM/Camera/IMG_1.jpg"), overwrote = true).message("Photo", null))
        assertEquals("Video saved", SavedEdit(overwrote = true).message("Video", null))
    }
}
