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
            RemoteNotes.syncPair(folder, mirror), tmp.root, keepConflictCopies = true
        )
        state = engine.run(state, null).newState
    }

    private fun conflictCopies(dir: File) = dir.listFiles()!!.filter { "(conflict " in it.name }

    @Test
    fun whenBothSidesEditANoteTheOlderVersionIsKeptAsAConflictCopyOnBothSides() {
        sync()
        File(mirror, "list.md").writeText("milk")
        sync()
        val now = System.currentTimeMillis()
        File(mirror, "list.md").apply { writeText("milk, eggs (me)"); setLastModified(now + 10_000) }
        File(server, "list.md").apply { writeText("milk, bread (wife, later)"); setLastModified(now + 20_000) }
        sync() // resolves: the later edit wins, the other is renamed aside
        sync() // carries the conflict copy across
        for (side in listOf(mirror, server)) {
            assertEquals("milk, bread (wife, later)", File(side, "list.md").readText())
            val copies = conflictCopies(side)
            assertEquals(1, copies.size)
            assertEquals("milk, eggs (me)", copies.single().readText())
            assertTrue(copies.single().name.endsWith(".md"))
        }
    }

    @Test
    fun identicalFilesThatWereNeverSyncedAreNotAConflict() {
        sync()
        File(mirror, "same.md").apply { writeText("same text"); setLastModified(1_700_000_000_000) }
        File(server, "same.md").apply { writeText("same text"); setLastModified(1_700_000_900_000) }
        sync()
        assertTrue(conflictCopies(mirror).isEmpty() && conflictCopies(server).isEmpty())
        assertEquals(1, state.size)
    }

    @Test
    fun anEditOnOneSideOnlyIsNeverAConflict() {
        sync()
        File(mirror, "note.md").writeText("v1")
        sync()
        File(server, "note.md").apply { writeText("v2 from the other phone"); setLastModified(System.currentTimeMillis() + 10_000) }
        sync()
        assertEquals("v2 from the other phone", File(mirror, "note.md").readText())
        assertTrue(conflictCopies(mirror).isEmpty() && conflictCopies(server).isEmpty())
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
