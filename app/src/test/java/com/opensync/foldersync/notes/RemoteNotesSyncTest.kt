package com.opensync.foldersync.notes

import com.opensync.foldersync.data.SyncStateEntry
import com.opensync.foldersync.provider.LocalProvider
import com.opensync.foldersync.sync.SyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** An account notes folder is a mirror synced with [RemoteNotes.syncPair]; a second local folder plays the server. */
class RemoteNotesSyncTest {

    @get:Rule val tmp = TemporaryFolder()

    private val folder = RemoteNotesFolder(id = 42, name = "Work", accountId = 1, remoteFolder = "notes")
    private lateinit var mirror: File
    private lateinit var server: File
    private var state: List<SyncStateEntry> = emptyList()

    private fun sync() {
        if (!::mirror.isInitialized) { mirror = tmp.newFolder("mirror"); server = tmp.newFolder("server") }
        val engine = SyncEngine(
            LocalProvider(mirror.absolutePath), LocalProvider(server.absolutePath),
            RemoteNotes.syncPair(folder, mirror), tmp.root
        )
        state = engine.run(state, null).newState
    }

    @Test
    fun notesOnTheServerComeDownAndNewNotesGoUp() {
        sync()
        File(server, "sub").mkdirs()
        File(server, "sub/from-server.md").writeText("hello")
        File(mirror, "from-phone.md").writeText("hi")
        sync()
        assertEquals("hello", File(mirror, "sub/from-server.md").readText())
        assertEquals("hi", File(server, "from-phone.md").readText())
    }

    @Test
    fun anEditOnThePhoneReplacesTheServerCopy() {
        sync()
        File(mirror, "note.md").writeText("v1")
        sync()
        File(mirror, "note.md").apply { writeText("version two"); setLastModified(System.currentTimeMillis() + 10_000) }
        sync()
        assertEquals("version two", File(server, "note.md").readText())
    }

    @Test
    fun deletingANoteOnEitherSideDeletesItOnTheOther() {
        sync()
        File(mirror, "a.md").writeText("a")
        File(server, "b.md").writeText("b")
        sync()
        File(mirror, "a.md").delete()
        File(server, "b.md").delete()
        sync()
        assertFalse(File(server, "a.md").exists())
        assertFalse(File(mirror, "b.md").exists())
    }

    @Test
    fun withTheHistoryForgottenAnEmptySideIsRefilledNotMirrored() {
        // What RemoteNotes does when the mirror (or the share) turns up completely empty.
        sync()
        File(mirror, "keep.md").writeText("precious")
        sync()
        File(mirror, "keep.md").delete()
        state = emptyList()
        sync()
        assertTrue(File(server, "keep.md").exists())
        assertEquals("precious", File(mirror, "keep.md").readText())
    }

    @Test
    fun syncStateIsFiledUnderANegativePairId() {
        assertEquals(-42L, RemoteNotes.syncPair(folder, File("x")).id)
    }
}
