package com.opensync.foldersync.provider

import java.io.Closeable
import java.io.File

/**
 * A file/directory on a storage target, addressed by a path relative to the
 * provider's configured root ('/'-separated, no leading slash).
 */
data class RemoteFile(
    val relPath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedTime: Long
)

/**
 * Uniform blocking interface over every storage backend. Implementations are used from
 * a background dispatcher by the sync engine, so methods may block on network/disk I/O.
 */
interface StorageProvider : Closeable {
    /** Establish the connection / validate the root. */
    fun connect()

    /** Immediate children of [relDir] (relative to root). */
    fun listDir(relDir: String): List<RemoteFile>

    /** Metadata for a single entry, or null if it does not exist. */
    fun stat(relPath: String): RemoteFile?

    /** Create [relDir] and any missing parents. */
    fun makeDir(relDir: String)

    fun deleteFile(relPath: String)

    /** Delete an (expected empty) directory. */
    fun deleteDir(relDir: String)

    /** Move/rename [fromRel] to [toRel] within this same provider. */
    fun rename(fromRel: String, toRel: String)

    /** Download the remote file at [relPath] into local [dest]. */
    fun download(relPath: String, dest: File)

    /** Upload local [src] to [relPath], creating parent dirs; sets mtime when supported. */
    fun upload(src: File, relPath: String, mtime: Long)

    override fun close()
}
