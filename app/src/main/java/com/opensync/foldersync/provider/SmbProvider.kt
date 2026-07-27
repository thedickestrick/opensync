package com.opensync.foldersync.provider

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.opensync.foldersync.util.PathUtil
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.IOException
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * SMB2 / SMB3 backend (Windows shares, NAS, domain servers) built on SMBJ.
 *
 * The provider root is `/<ShareName>/<optional/subfolder>`: the first path segment is the SMB
 * share to connect to, the rest is the starting folder inside it. Authentication supports a
 * Windows [domain] (NTLM).
 */
class SmbProvider(
    private val host: String,
    private val port: Int,
    private val domain: String,
    private val username: String,
    private val password: String,
    rootPath: String
) : StorageProvider {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    private val shareName: String
    private val inShareRoot: String  // '/'-separated path within the share (no leading slash)

    init {
        val parts = PathUtil.normalize(rootPath).trim('/').split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty()) {
            "SMB path must start with a share name, e.g. /Backups or /Backups/Phone"
        }
        shareName = parts.first()
        inShareRoot = parts.drop(1).joinToString("/")
    }

    private fun requireShare(): DiskShare = share ?: throw IOException("SMB not connected")

    /** Build the backslash path inside the share for a provider-relative path. */
    private fun full(rel: String): String {
        val combined = listOf(inShareRoot, rel.replace('\\', '/'))
            .flatMap { it.split('/') }
            .filter { it.isNotEmpty() }
        return combined.joinToString("\\")
    }

    private fun parentOf(rel: String): String {
        val r = rel.trim('/')
        val i = r.lastIndexOf('/')
        return if (i < 0) "" else r.substring(0, i)
    }

    override fun connect() {
        ensureFullBouncyCastle()
        val config = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .build()
        val c = SMBClient(config)
        val conn = c.connect(host, if (port > 0) port else 445)
        val auth = if (username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(username, password.toCharArray(), domain)
        }
        val sess = conn.authenticate(auth)
        val sh = sess.connectShare(shareName) as? DiskShare
            ?: throw IOException("'$shareName' is not a disk share")
        client = c
        connection = conn
        session = sess
        share = sh
    }

    override fun listDir(relDir: String): List<RemoteFile> {
        val dir = full(relDir)
        return requireShare().list(dir)
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                RemoteFile(
                    relPath = PathUtil.childRel(relDir, info.fileName),
                    name = info.fileName,
                    isDirectory = isDir,
                    size = if (isDir) 0L else info.endOfFile,
                    modifiedTime = info.lastWriteTime.toEpoch()
                )
            }
    }

    override fun stat(relPath: String): RemoteFile? = try {
        val info = requireShare().getFileInformation(full(relPath))
        val std = info.standardInformation
        RemoteFile(
            relPath = relPath,
            name = PathUtil.name(relPath),
            isDirectory = std.isDirectory,
            size = if (std.isDirectory) 0L else std.endOfFile,
            modifiedTime = info.basicInformation.lastWriteTime.toEpoch()
        )
    } catch (e: SMBApiException) {
        null
    }

    override fun makeDir(relDir: String) {
        val target = full(relDir)
        if (target.isEmpty()) return
        val sh = requireShare()
        var acc = ""
        for (segment in target.split('\\').filter { it.isNotEmpty() }) {
            acc = if (acc.isEmpty()) segment else "$acc\\$segment"
            if (!sh.folderExists(acc)) sh.mkdir(acc)
        }
    }

    override fun deleteFile(relPath: String) {
        requireShare().rm(full(relPath))
    }

    override fun deleteDir(relDir: String) {
        requireShare().rmdir(full(relDir), false)
    }

    override fun rename(fromRel: String, toRel: String) {
        makeDir(parentOf(toRel))
        val entry = requireShare().open(
            full(fromRel),
            setOf(AccessMask.MAXIMUM_ALLOWED),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        try {
            entry.rename(full(toRel))
        } finally {
            entry.close()
        }
    }

    override fun download(relPath: String, dest: File) {
        val f = requireShare().openFile(
            full(relPath),
            setOf(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        try {
            f.inputStream.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
        } finally {
            f.close()
        }
    }

    override fun upload(src: File, relPath: String, mtime: Long) {
        makeDir(parentOf(relPath))
        val f = requireShare().openFile(
            full(relPath),
            setOf(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null
        )
        try {
            f.outputStream.use { out -> src.inputStream().use { input -> input.copyTo(out) } }
        } finally {
            f.close()
        }
    }

    override fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null; session = null; connection = null; client = null
    }

    companion object {
        @Volatile
        private var cryptoReady = false

        /**
         * Android ships a stripped-down "BC" provider that lacks AES-CMAC (needed for SMB 3.x
         * signing). Replace it once with the full BouncyCastle provider so JCE can find it.
         */
        private fun ensureFullBouncyCastle() {
            if (cryptoReady) return
            synchronized(SmbProvider::class.java) {
                if (cryptoReady) return
                runCatching {
                    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                    Security.addProvider(BouncyCastleProvider())
                }
                cryptoReady = true
            }
        }
    }
}
