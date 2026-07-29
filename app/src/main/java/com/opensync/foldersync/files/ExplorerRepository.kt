package com.opensync.foldersync.files

import android.content.Context
import com.opensync.foldersync.Graph
import com.opensync.foldersync.provider.LocalProvider
import com.opensync.foldersync.provider.ProviderFactory
import com.opensync.foldersync.provider.RemoteFile
import com.opensync.foldersync.provider.StorageProvider
import com.opensync.foldersync.util.PathUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Holds the active connection for whichever [ExplorerLocation] is being browsed and exposes
 * file operations on it. Remote connections are kept open across navigation and transparently
 * re-established if they drop.
 */
class ExplorerRepository(private val context: Context) {

    private val db = Graph.database

    var location: ExplorerLocation = ExplorerLocation.LocalRoot
        private set

    private var provider: StorageProvider? = null

    // Serializes all access to the single (possibly network) connection.
    private val mutex = Mutex()

    suspend fun setLocation(newLocation: ExplorerLocation) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { provider?.close() }
            provider = null
            location = newLocation
        }
    }

    private suspend fun buildProvider(loc: ExplorerLocation): StorageProvider = when (loc) {
        ExplorerLocation.LocalRoot -> ProviderFactory.forLocal("/")
        is ExplorerLocation.Remote -> {
            val account = db.accountDao().getById(loc.accountId)
                ?: throw IllegalStateException("Account not found")
            ProviderFactory.forAccount(account, "")
        }
    }

    private suspend fun current(): StorageProvider {
        var p = provider
        if (p == null) {
            p = buildProvider(location)
            p.connect()
            provider = p
        }
        return p
    }

    /** Run [block] on the connected provider, reconnecting once if it fails. */
    private suspend fun <T> op(block: (StorageProvider) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                block(current())
            } catch (e: Exception) {
                runCatching { provider?.close() }
                provider = null
                block(current())
            }
        }
    }

    suspend fun list(relDir: String): List<RemoteFile> = op { it.listDir(relDir) }

    suspend fun createFolder(parentRel: String, name: String) =
        op { it.makeDir(PathUtil.childRel(parentRel, name)) }

    suspend fun rename(item: RemoteFile, newName: String) = op {
        val parent = PathUtil.name(item.relPath).let { n -> item.relPath.removeSuffix(n) }.trim('/')
        it.rename(item.relPath, PathUtil.childRel(parent, newName))
    }

    suspend fun delete(items: List<RemoteFile>) = op { provider ->
        for (item in items) FileOps.deleteTree(provider, item.relPath, item.isDirectory)
    }

    /** Upload a local [src] file into [destDir] on the current location under [name]. */
    suspend fun importFile(destDir: String, name: String, src: File) =
        op { it.upload(src, PathUtil.childRel(destDir, name), src.lastModified()) }

    suspend fun paste(
        clip: Clipboard,
        destDir: String,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
        val dest = current()
        // Fast path: moving within the same location => rename in place.
        if (clip.move && clip.location == location) {
            for (item in clip.items) {
                dest.rename(item.relPath, PathUtil.childRel(destDir, item.name))
            }
            return@withContext
        }
        // When the copy source is the location we're already browsing, reuse that one connection.
        // Only a separate cross-location source gets its own connection (and is closed afterwards);
        // the browsing connection (e.g. an SMB share) is never opened/closed by a paste.
        val sameLocation = clip.location == location
        val source = if (sameLocation) dest else buildProvider(clip.location).also { it.connect() }
        try {
            for (item in clip.items) {
                if (isCancelled()) break
                val target = PathUtil.childRel(destDir, item.name)
                FileOps.copyTree(
                    source, item.relPath, item.isDirectory,
                    dest, target, context.cacheDir, onProgress, isCancelled
                )
                if (clip.move) FileOps.deleteTree(source, item.relPath, item.isDirectory)
            }
        } finally {
            if (!sameLocation) runCatching { source.close() }
        }
        }
    }

    /** Returns a local [File] for [item], downloading it to the cache first if it is remote. */
    suspend fun materialize(item: RemoteFile): File = mutex.withLock {
        withContext(Dispatchers.IO) {
            val p = current()
            if (p is LocalProvider) {
                p.fileFor(item.relPath)
            } else {
                val out = File(File(context.cacheDir, "open"), item.name)
                out.parentFile?.mkdirs()
                p.download(item.relPath, out)
                out
            }
        }
    }

    fun release() {
        runCatching { provider?.close() }
        provider = null
    }
}
