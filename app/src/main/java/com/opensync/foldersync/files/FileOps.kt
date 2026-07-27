package com.opensync.foldersync.files

import com.opensync.foldersync.provider.LocalProvider
import com.opensync.foldersync.provider.StorageProvider
import com.opensync.foldersync.util.PathUtil
import java.io.File

/** Recursive copy / delete primitives that work across any two [StorageProvider]s. */
object FileOps {

    fun copyFile(
        src: StorageProvider, srcRel: String,
        dst: StorageProvider, dstRel: String,
        tempDir: File
    ) {
        val srcFile = (src as? LocalProvider)?.fileFor(srcRel)
        val dstFile = (dst as? LocalProvider)?.fileFor(dstRel)
        if (srcFile != null && dstFile != null) {
            dstFile.parentFile?.mkdirs()
            srcFile.copyTo(dstFile, overwrite = true)
            return
        }
        val tmp = File.createTempFile("fileop", ".tmp", tempDir)
        try {
            src.download(srcRel, tmp)
            dst.upload(tmp, dstRel, 0L)
        } finally {
            tmp.delete()
        }
    }

    /** Copy a file or a whole directory tree from [srcRel] on [src] to [dstRel] on [dst]. */
    fun copyTree(
        src: StorageProvider, srcRel: String, isDir: Boolean,
        dst: StorageProvider, dstRel: String,
        tempDir: File,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean
    ) {
        if (isCancelled()) throw InterruptedException("Cancelled")
        if (isDir) {
            dst.makeDir(dstRel)
            for (child in src.listDir(srcRel)) {
                copyTree(
                    src, child.relPath, child.isDirectory,
                    dst, PathUtil.childRel(dstRel, child.name),
                    tempDir, onProgress, isCancelled
                )
            }
        } else {
            onProgress(PathUtil.name(srcRel))
            copyFile(src, srcRel, dst, dstRel, tempDir)
        }
    }

    /** Delete a file or a whole directory tree. */
    fun deleteTree(p: StorageProvider, rel: String, isDir: Boolean) {
        if (isDir) {
            for (child in p.listDir(rel)) {
                deleteTree(p, child.relPath, child.isDirectory)
            }
            p.deleteDir(rel)
        } else {
            p.deleteFile(rel)
        }
    }
}
