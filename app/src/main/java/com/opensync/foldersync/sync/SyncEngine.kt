package com.opensync.foldersync.sync

import com.opensync.foldersync.data.ConflictRule
import com.opensync.foldersync.data.FolderPair
import com.opensync.foldersync.data.SyncDirection
import com.opensync.foldersync.data.SyncStateEntry
import com.opensync.foldersync.provider.LocalProvider
import com.opensync.foldersync.provider.RemoteFile
import com.opensync.foldersync.provider.StorageProvider
import com.opensync.foldersync.util.PathUtil
import java.io.File
import kotlin.math.abs

/**
 * Compares the local and remote trees and performs the copies/deletions required by the
 * folder pair's direction, conflict rule and filters. Two-way mode uses the previous
 * [SyncStateEntry] snapshot to detect which side actually changed since the last run.
 */
class SyncEngine(
    private val local: StorageProvider,
    private val remote: StorageProvider,
    private val pair: FolderPair,
    private val tempDir: File
) {

    fun interface Progress {
        fun update(message: String, done: Int, total: Int)
    }

    data class Result(
        val success: Boolean,
        val filesCopied: Int,
        val filesDeleted: Int,
        val conflicts: Int,
        val bytes: Long,
        val message: String,
        val newState: List<SyncStateEntry>
    )

    private var copied = 0
    private var deleted = 0
    private var conflicts = 0
    private var bytes = 0L

    private class Scan {
        val files = LinkedHashMap<String, RemoteFile>()
        val dirs = LinkedHashMap<String, RemoteFile>()
    }

    fun run(
        prevState: List<SyncStateEntry>,
        progress: Progress?,
        isCancelled: () -> Boolean = { false }
    ): Result {
        local.connect()
        try {
            remote.connect()
        } catch (e: Exception) {
            runCatching { local.close() }
            throw e
        }
        try {
            val filter = SyncFilter(pair.includeFilter, pair.excludeFilter)
            val localScan = scan(local, filter)
            val remoteScan = scan(remote, filter)

            val newState = when (pair.direction) {
                SyncDirection.TO_REMOTE -> {
                    mirror(localScan, remoteScan, local, remote, progress, isCancelled, "↑")
                    emptyList()
                }
                SyncDirection.FROM_REMOTE -> {
                    mirror(remoteScan, localScan, remote, local, progress, isCancelled, "↓")
                    emptyList()
                }
                SyncDirection.TWO_WAY ->
                    twoWay(localScan, remoteScan, prevState, progress, isCancelled)
            }

            val summary = buildString {
                append("Copied $copied, deleted $deleted")
                if (conflicts > 0) append(", $conflicts conflict(s)")
            }
            return Result(true, copied, deleted, conflicts, bytes, summary, newState)
        } finally {
            runCatching { remote.close() }
            runCatching { local.close() }
        }
    }

    // --- Scanning ---

    private fun scan(p: StorageProvider, filter: SyncFilter): Scan {
        val out = Scan()
        val stack = ArrayDeque<String>()
        stack.addLast("")
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = try {
                p.listDir(dir)
            } catch (e: Exception) {
                if (dir.isEmpty()) throw e else continue
            }
            for (f in children) {
                if (f.isDirectory) {
                    if (filter.excludesDir(f.name)) continue
                    out.dirs[f.relPath] = f
                    if (pair.includeSubfolders) stack.addLast(f.relPath)
                } else {
                    if (!filter.acceptsFile(f.name)) continue
                    out.files[f.relPath] = f
                }
            }
        }
        return out
    }

    // --- One-way mirror (src -> dst) ---

    private fun mirror(
        src: Scan,
        dst: Scan,
        srcP: StorageProvider,
        dstP: StorageProvider,
        progress: Progress?,
        isCancelled: () -> Boolean,
        arrow: String
    ) {
        for (rel in src.dirs.keys.sortedBy { depth(it) }) {
            if (!dst.dirs.containsKey(rel)) dstP.makeDir(rel)
        }
        val total = src.files.size
        var idx = 0
        for ((rel, sf) in src.files) {
            if (isCancelled()) throw InterruptedException("Sync cancelled")
            idx++
            val df = dst.files[rel]
            if (df == null || differs(sf, df)) {
                progress?.update("$arrow ${PathUtil.name(rel)}", idx, total)
                copy(srcP, dstP, rel, sf.modifiedTime)
                copied++
                bytes += sf.size
            }
        }
        if (pair.deleteOrphans) {
            for (rel in dst.files.keys) {
                if (!src.files.containsKey(rel)) {
                    dstP.deleteFile(rel)
                    deleted++
                }
            }
            for (rel in dst.dirs.keys.sortedByDescending { depth(it) }) {
                if (!src.dirs.containsKey(rel)) dstP.deleteDir(rel)
            }
        }
    }

    // --- Two-way ---

    private fun twoWay(
        localScan: Scan,
        remoteScan: Scan,
        prevState: List<SyncStateEntry>,
        progress: Progress?,
        isCancelled: () -> Boolean
    ): List<SyncStateEntry> {
        val state = prevState.associateBy { it.relPath }
        val newState = LinkedHashMap<String, SyncStateEntry>()

        for (rel in localScan.dirs.keys.sortedBy { depth(it) }) {
            if (!remoteScan.dirs.containsKey(rel)) remote.makeDir(rel)
        }
        for (rel in remoteScan.dirs.keys.sortedBy { depth(it) }) {
            if (!localScan.dirs.containsKey(rel)) local.makeDir(rel)
        }

        val allPaths = LinkedHashSet<String>().apply {
            addAll(localScan.files.keys)
            addAll(remoteScan.files.keys)
            addAll(state.keys)
        }
        val total = allPaths.size
        var idx = 0

        for (rel in allPaths) {
            if (isCancelled()) throw InterruptedException("Sync cancelled")
            idx++
            val l = localScan.files[rel]
            val r = remoteScan.files[rel]
            val st = state[rel]
            val localChanged = l != null && (st == null || !sigEq(l, st.localSize, st.localMtime))
            val remoteChanged = r != null && (st == null || !sigEq(r, st.remoteSize, st.remoteMtime))

            when {
                l != null && r != null -> {
                    val bothEqual = l.size == r.size && abs(l.modifiedTime - r.modifiedTime) <= TOL
                    when {
                        bothEqual -> record(newState, rel, l.size, l.modifiedTime, r.size, r.modifiedTime)
                        localChanged && !remoteChanged -> {
                            uploadLocalToRemote(rel, l, progress, idx, total, newState)
                        }
                        remoteChanged && !localChanged -> {
                            downloadRemoteToLocal(rel, r, progress, idx, total, newState)
                        }
                        !localChanged && !remoteChanged ->
                            record(newState, rel, l.size, l.modifiedTime, r.size, r.modifiedTime)
                        else -> resolveConflict(rel, l, r, progress, idx, total, newState)
                    }
                }

                l != null && r == null -> {
                    // Present locally, absent remotely.
                    if (st != null && !localChanged && pair.deleteOrphans) {
                        local.deleteFile(rel); deleted++ // remote deletion propagated
                    } else {
                        uploadLocalToRemote(rel, l, progress, idx, total, newState)
                    }
                }

                r != null && l == null -> {
                    if (st != null && !remoteChanged && pair.deleteOrphans) {
                        remote.deleteFile(rel); deleted++
                    } else {
                        downloadRemoteToLocal(rel, r, progress, idx, total, newState)
                    }
                }

                else -> { /* both deleted: drop from state */ }
            }
        }
        return newState.values.toList()
    }

    private fun resolveConflict(
        rel: String, l: RemoteFile, r: RemoteFile,
        progress: Progress?, idx: Int, total: Int,
        newState: MutableMap<String, SyncStateEntry>
    ) {
        conflicts++
        when (pair.conflictRule) {
            ConflictRule.NEWER_WINS ->
                if (l.modifiedTime >= r.modifiedTime) uploadLocalToRemote(rel, l, progress, idx, total, newState)
                else downloadRemoteToLocal(rel, r, progress, idx, total, newState)
            ConflictRule.LOCAL_WINS -> uploadLocalToRemote(rel, l, progress, idx, total, newState)
            ConflictRule.REMOTE_WINS -> downloadRemoteToLocal(rel, r, progress, idx, total, newState)
            ConflictRule.SKIP -> { /* leave both; no state so it is re-checked next run */ }
        }
    }

    private fun uploadLocalToRemote(
        rel: String, l: RemoteFile, progress: Progress?, idx: Int, total: Int,
        newState: MutableMap<String, SyncStateEntry>
    ) {
        progress?.update("↑ ${PathUtil.name(rel)}", idx, total)
        copy(local, remote, rel, l.modifiedTime)
        copied++; bytes += l.size
        val rf = remote.stat(rel)
        record(newState, rel, l.size, l.modifiedTime, rf?.size ?: l.size, rf?.modifiedTime ?: l.modifiedTime)
    }

    private fun downloadRemoteToLocal(
        rel: String, r: RemoteFile, progress: Progress?, idx: Int, total: Int,
        newState: MutableMap<String, SyncStateEntry>
    ) {
        progress?.update("↓ ${PathUtil.name(rel)}", idx, total)
        copy(remote, local, rel, r.modifiedTime)
        copied++; bytes += r.size
        val lf = local.stat(rel)
        record(newState, rel, lf?.size ?: r.size, lf?.modifiedTime ?: r.modifiedTime, r.size, r.modifiedTime)
    }

    private fun record(
        map: MutableMap<String, SyncStateEntry>, rel: String,
        localSize: Long, localMtime: Long, remoteSize: Long, remoteMtime: Long
    ) {
        map[rel] = SyncStateEntry(pair.id, rel, localSize, localMtime, remoteSize, remoteMtime)
    }

    // --- Copy primitive ---

    private fun copy(from: StorageProvider, to: StorageProvider, rel: String, mtime: Long) {
        val srcFile = (from as? LocalProvider)?.fileFor(rel)
        val dstFile = (to as? LocalProvider)?.fileFor(rel)
        if (srcFile != null && dstFile != null) {
            dstFile.parentFile?.mkdirs()
            srcFile.copyTo(dstFile, overwrite = true)
            if (mtime > 0) dstFile.setLastModified(mtime)
            return
        }
        val tmp = File.createTempFile("osync", ".tmp", tempDir)
        try {
            from.download(rel, tmp)
            to.upload(tmp, rel, mtime)
        } finally {
            tmp.delete()
        }
    }

    private fun differs(src: RemoteFile, dst: RemoteFile): Boolean =
        src.size != dst.size || src.modifiedTime > dst.modifiedTime + TOL

    private fun sigEq(f: RemoteFile, size: Long, mtime: Long): Boolean =
        f.size == size && abs(f.modifiedTime - mtime) <= TOL

    private fun depth(rel: String): Int = rel.count { it == '/' }

    companion object {
        /** Filesystem/mtime granularity tolerance in milliseconds. */
        private const val TOL = 2000L
    }
}
