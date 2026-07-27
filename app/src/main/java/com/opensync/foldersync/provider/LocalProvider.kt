package com.opensync.foldersync.provider

import com.opensync.foldersync.util.PathUtil
import java.io.File
import java.io.IOException

/** Storage on the device's own filesystem (used for local↔local sync and both sides' staging). */
class LocalProvider(rootPath: String) : StorageProvider {

    private val root = File(PathUtil.normalize(rootPath))

    /** Exposes the concrete [File] for a relative path so the engine can copy locally without a temp file. */
    fun fileFor(relPath: String): File =
        if (relPath.isEmpty()) root else File(root, relPath)

    override fun connect() {
        if (!root.exists()) {
            if (!root.mkdirs() && !root.exists()) {
                throw IOException("Cannot access or create folder: ${root.absolutePath}")
            }
        }
        if (!root.isDirectory) throw IOException("Not a folder: ${root.absolutePath}")
    }

    override fun listDir(relDir: String): List<RemoteFile> {
        val dir = fileFor(relDir)
        val children = dir.listFiles() ?: return emptyList()
        return children.map {
            RemoteFile(
                relPath = PathUtil.childRel(relDir, it.name),
                name = it.name,
                isDirectory = it.isDirectory,
                size = if (it.isDirectory) 0 else it.length(),
                modifiedTime = it.lastModified()
            )
        }
    }

    override fun stat(relPath: String): RemoteFile? {
        val f = fileFor(relPath)
        if (!f.exists()) return null
        return RemoteFile(
            relPath = relPath,
            name = f.name,
            isDirectory = f.isDirectory,
            size = if (f.isDirectory) 0 else f.length(),
            modifiedTime = f.lastModified()
        )
    }

    override fun makeDir(relDir: String) {
        val d = fileFor(relDir)
        if (!d.exists() && !d.mkdirs() && !d.exists()) {
            throw IOException("Cannot create directory: ${d.absolutePath}")
        }
    }

    override fun deleteFile(relPath: String) {
        val f = fileFor(relPath)
        if (f.exists() && !f.delete()) throw IOException("Cannot delete file: ${f.absolutePath}")
    }

    override fun deleteDir(relDir: String) {
        val d = fileFor(relDir)
        if (d.exists() && !d.delete()) {
            // Ignore non-empty dirs; the engine deletes children first.
        }
    }

    override fun rename(fromRel: String, toRel: String) {
        val from = fileFor(fromRel)
        val to = fileFor(toRel)
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            // Cross-mount rename can fail; fall back to copy + delete.
            if (from.isDirectory) {
                from.copyRecursively(to, overwrite = true)
                from.deleteRecursively()
            } else {
                from.copyTo(to, overwrite = true)
                from.delete()
            }
        }
    }

    override fun download(relPath: String, dest: File) {
        fileFor(relPath).copyTo(dest, overwrite = true)
    }

    override fun upload(src: File, relPath: String, mtime: Long) {
        val target = fileFor(relPath)
        target.parentFile?.mkdirs()
        src.copyTo(target, overwrite = true)
        if (mtime > 0) target.setLastModified(mtime)
    }

    override fun close() { /* nothing to release */ }
}
