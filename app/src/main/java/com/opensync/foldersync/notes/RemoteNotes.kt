package com.opensync.foldersync.notes

import com.opensync.foldersync.Graph
import com.opensync.foldersync.data.ConflictRule
import com.opensync.foldersync.data.FolderPair
import com.opensync.foldersync.data.SyncDirection
import com.opensync.foldersync.provider.ProviderFactory
import com.opensync.foldersync.provider.StorageProvider
import com.opensync.foldersync.sync.SyncEngine
import com.opensync.foldersync.update.AppPrefs
import com.opensync.foldersync.widget.NotesSyncWorker
import com.opensync.foldersync.widget.NotesWidgetProvider
import com.opensync.foldersync.widget.SingleNoteWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** A notes folder that lives on an account (server / cloud) rather than on this phone. */
data class RemoteNotesFolder(
    val id: Long,
    val name: String,
    val accountId: Long,
    /** Folder on the account, relative to the account's base path. */
    val remoteFolder: String
)

/**
 * Notes folders on an account.
 *
 * Everything in Notes — the editor, images in a note, PDFs, sharing, the vault — works on real files,
 * so an account folder is kept as a private mirror on the phone and the Notes tab simply browses the
 * mirror. The mirror is kept in step with the account by the same two-way [SyncEngine] folder pairs
 * use: whenever the folder is opened or refreshed, and right after anything in it is changed.
 */
object RemoteNotes {

    /** [conflicts]: notes both sides had changed, whose older version was kept as a "(conflict …)" copy. */
    data class SyncFinished(val folderId: Long, val error: String?, val conflicts: Int = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<Long, Mutex>()
    /** Folders with a sync already waiting its turn — a second request would only repeat it. */
    private val queued: MutableSet<Long> = Collections.synchronizedSet(HashSet())

    private val _syncing = MutableStateFlow<Set<Long>>(emptySet())
    /** Ids of the folders being synced right now. */
    val syncing = _syncing.asStateFlow()

    private val _finished = MutableSharedFlow<SyncFinished>(extraBufferCapacity = 16)
    val finished = _finished.asSharedFlow()

    private val prefs get() = AppPrefs(Graph.appContext)
    private val mirrorRoot: File get() = File(Graph.appContext.filesDir, "remote_notes")

    fun mirrorDir(folder: RemoteNotesFolder): File = File(mirrorRoot, folder.id.toString())

    // --- the list ---

    fun folders(): List<RemoteNotesFolder> = runCatching {
        val arr = JSONArray(prefs.remoteNotesFolders.ifBlank { "[]" })
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteNotesFolder(o.getLong("id"), o.getString("name"), o.getLong("accountId"), o.optString("remoteFolder"))
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<RemoteNotesFolder>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("accountId", it.accountId).put("remoteFolder", it.remoteFolder)
            )
        }
        prefs.remoteNotesFolders = arr.toString()
    }

    /** Adds the folder, or returns the existing entry if this account folder is already a notes folder. */
    @Synchronized
    fun add(name: String, accountId: Long, remoteFolder: String): RemoteNotesFolder {
        val clean = remoteFolder.trim('/')
        val list = folders()
        list.firstOrNull { it.accountId == accountId && it.remoteFolder == clean }?.let { return it }
        // Time-based so an id is never reused: a stale mirror or sync state can't be picked up by a new folder.
        val id = maxOf(System.currentTimeMillis(), (list.maxOfOrNull { it.id } ?: 0L) + 1)
        val folder = RemoteNotesFolder(id, name, accountId, clean)
        save(list + folder)
        NotesSyncWorker.reschedule(Graph.appContext)
        return folder
    }

    /** Forgets the folder on this phone only — the notes on the account are left alone. */
    fun remove(folder: RemoteNotesFolder) {
        synchronized(this) { save(folders().filterNot { it.id == folder.id }) }
        NotesSyncWorker.reschedule(Graph.appContext)
        scope.launch {
            lockFor(folder.id).withLock {
                mirrorDir(folder).deleteRecursively()
                Graph.database.syncStateDao().deleteForPair(pairIdFor(folder))
            }
        }
    }

    /** The account notes folder whose mirror contains [path], if any. */
    fun folderFor(path: String): RemoteNotesFolder? {
        val root = mirrorRoot.absolutePath + File.separator
        val abs = File(path).absolutePath
        if (!abs.startsWith(root)) return null
        val id = abs.removePrefix(root).substringBefore(File.separatorChar).toLongOrNull() ?: return null
        return folders().firstOrNull { it.id == id }
    }

    /**
     * For an editor about to save over a note that a sync replaced while it was being edited: the
     * engine never saw two versions, so keep the one on disk as a conflict copy here instead.
     */
    fun keepConflictCopy(file: File) {
        if (folderFor(file.absolutePath) == null || !file.isFile) return
        runCatching { file.copyTo(File(file.parentFile, SyncEngine.conflictName(file.name)), overwrite = false) }
    }

    // --- syncing ---

    /** Call after writing, moving or deleting anything that may be inside a mirror. */
    fun notifyChanged(path: String) = touched(listOf(path))

    /**
     * Like [notifyChanged] for several paths. [removedDirs] are directories that no longer exist at
     * that path (deleted, renamed or moved away): the engine only ever removes *files*, so their
     * emptied shells are cleared up on both sides afterwards.
     */
    fun touched(paths: List<String>, removedDirs: List<String> = emptyList()) {
        val byFolder = LinkedHashMap<RemoteNotesFolder, MutableList<String>>()
        paths.forEach { p -> folderFor(p)?.let { byFolder.getOrPut(it) { mutableListOf() } } }
        removedDirs.forEach { p ->
            val folder = folderFor(p) ?: return@forEach
            val rel = File(p).relativeTo(mirrorDir(folder)).invariantSeparatorsPath
            if (rel.isNotEmpty() && !rel.startsWith("..")) byFolder.getOrPut(folder) { mutableListOf() } += rel
        }
        byFolder.forEach { (folder, removed) -> requestSync(folder, removed) }
    }

    fun requestSync(folder: RemoteNotesFolder, removedDirs: List<String> = emptyList()) {
        // A sync that hasn't started yet will see this change too; one carrying removed dirs always runs.
        if (removedDirs.isEmpty() && !queued.add(folder.id)) return
        scope.launch {
            lockFor(folder.id).withLock {
                if (removedDirs.isEmpty()) queued.remove(folder.id)
                syncLocked(folder, removedDirs)
            }
        }
    }

    /** Syncs every account notes folder and waits for it — what the background worker runs. */
    suspend fun syncAll() {
        folders().forEach { folder -> lockFor(folder.id).withLock { syncLocked(folder, emptyList()) } }
    }

    private suspend fun syncLocked(folder: RemoteNotesFolder, removedDirs: List<String>) {
        _syncing.update { it + folder.id }
        val outcome = runCatching { sync(folder, removedDirs) }
        _syncing.update { it - folder.id }
        val error = outcome.exceptionOrNull()
            ?.let { "Couldn't sync '${folder.name}': ${it.message ?: it.javaClass.simpleName}" }
        _finished.emit(SyncFinished(folder.id, error, outcome.getOrDefault(0)))
        // A note pinned to the home screen may just have changed underneath its widget.
        NotesWidgetProvider.notifyChanged(Graph.appContext)
        SingleNoteWidgetProvider.notifyChanged(Graph.appContext)
    }

    private fun lockFor(id: Long) = locks.getOrPut(id) { Mutex() }

    /** Negative, so it can never collide with a real folder pair's sync state. */
    private fun pairIdFor(folder: RemoteNotesFolder) = -folder.id

    /** How a mirror is synced: both ways, the newer edit wins, and a deletion on one side carries over. */
    internal fun syncPair(folder: RemoteNotesFolder, mirror: File) = FolderPair(
        id = pairIdFor(folder),
        name = folder.name,
        localFolder = mirror.absolutePath,
        remoteAccountId = folder.accountId,
        remoteFolder = folder.remoteFolder,
        direction = SyncDirection.TWO_WAY,
        conflictRule = ConflictRule.NEWER_WINS,
        deleteOrphans = true
    )

    /** Returns how many notes were in conflict. */
    private suspend fun sync(folder: RemoteNotesFolder, removedDirs: List<String>): Int {
        if (folders().none { it.id == folder.id }) return 0 // removed while this sync was waiting
        val db = Graph.database
        val account = db.accountDao().getById(folder.accountId)
            ?: throw IllegalStateException("its account no longer exists")
        val mirror = mirrorDir(folder).apply { mkdirs() }
        val pair = syncPair(folder, mirror)
        fun remote() = ProviderFactory.forAccount(account, folder.remoteFolder)

        // "Known last time, gone now" is how a deletion is recognised — but a side that is *entirely*
        // empty is far more likely a wiped mirror or an unmounted share than every note being deleted.
        // Forget the history then, so the engine copies what's left across instead of deleting it.
        var prevState = db.syncStateDao().getForPair(pair.id)
        if (prevState.isNotEmpty()) {
            val mirrorEmpty = mirror.list().isNullOrEmpty()
            val remoteEmpty = !mirrorEmpty && remote().use { it.connect(); it.listDir("").isEmpty() }
            if (mirrorEmpty || remoteEmpty) prevState = emptyList()
        }

        fun engine() = SyncEngine(
            ProviderFactory.forLocal(mirror.absolutePath), remote(), pair, Graph.appContext.cacheDir,
            keepConflictCopies = true // a shared folder: never silently drop someone's edit
        )
        val result = engine().run(prevState, null)
        db.syncStateDao().replaceForPair(pair.id, result.newState)
        if (result.conflicts > 0) {
            // The conflict copies were made on one side only; a second pass carries them across now.
            db.syncStateDao().replaceForPair(pair.id, engine().run(result.newState, null).newState)
        }

        if (removedDirs.isNotEmpty()) {
            remote().use { p ->
                p.connect()
                removedDirs.forEach { rel -> runCatching { if (p.stat(rel)?.isDirectory == true) pruneEmpty(p, rel) } }
            }
            ProviderFactory.forLocal(mirror.absolutePath).use { p ->
                removedDirs.forEach { rel -> runCatching { if (p.stat(rel)?.isDirectory == true) pruneEmpty(p, rel) } }
            }
        }
        return result.conflicts
    }

    /** Deletes [rel] if there is no file anywhere beneath it; returns whether it went. */
    private fun pruneEmpty(p: StorageProvider, rel: String): Boolean {
        var empty = true
        for (child in p.listDir(rel)) {
            if (!child.isDirectory || !pruneEmpty(p, child.relPath)) empty = false
        }
        if (empty) p.deleteDir(rel)
        return empty
    }
}
