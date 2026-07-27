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
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.IOException
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * SMB2 / SMB3 backend (Windows shares, NAS, domain servers) built on SMBJ.
 *
 * The provider connects at the **server** level, then resolves each provider-relative path so
 * its first segment is the SMB share and the rest is the path inside it. A base/root path of
 * `/<Share>/…` pins that share; a **blank / `/`** root lists **all shares** on the server
 * (enumerated over SRVSVC), so you can browse a server without knowing its share names.
 * Authentication supports a Windows [domain] (NTLM).
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
    private val shareCache = HashMap<String, DiskShare>()

    /** Fixed prefix (may be empty): '/'-separated, first segment is a pinned share if present. */
    private val root: String = PathUtil.normalize(rootPath).trim('/')

    private fun requireSession(): Session = session ?: throw IOException("SMB not connected")

    /** Split a provider-relative path into (share or null for server-root, backslash in-share path). */
    private fun resolve(rel: String): Pair<String?, String> {
        val combined = if (root.isEmpty()) rel else "$root/$rel"
        val segments = combined.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null to ""
        return segments.first() to segments.drop(1).joinToString("\\")
    }

    private fun diskShare(name: String): DiskShare = shareCache.getOrPut(name) {
        (requireSession().connectShare(name) as? DiskShare)
            ?: throw IOException("'$name' is not a disk share")
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

        // Accept "server", "server.domain", or accidental UNC input like "\\server\share".
        val cleanHost = host.trim().trim('\\', '/').substringBefore('\\').substringBefore('/')
        val candidates = LinkedHashSet<String>().apply {
            if (cleanHost.isNotEmpty()) add(cleanHost)
            // Windows resolves short names via the domain suffix; Android doesn't, so try the FQDN.
            if (!cleanHost.contains('.') && domain.isNotBlank()) add("$cleanHost.$domain")
        }
        val effectivePort = if (port > 0) port else 445

        var established: Connection? = null
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                established = c.connect(candidate, effectivePort)
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (established == null) {
            runCatching { c.close() }
            throw IOException(
                "Can't reach SMB server '$cleanHost'. Use its full DNS name " +
                    "(e.g. $cleanHost.your-domain) or its IP address. (${lastError?.message})"
            )
        }

        val auth = if (username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(username, password.toCharArray(), domain)
        }
        session = established.authenticate(auth)
        client = c
        connection = established
    }

    /** Disk share names available on the server (excludes IPC$ / printer shares). */
    private fun listShares(): List<String> {
        val transport = SMBTransportFactories.SRVSVC.getTransport(requireSession())
        val service = ServerService(transport)
        return service.shares1
            .filter { (it.type and 0xFF) == 0 }      // STYPE_DISKTREE
            .mapNotNull { it.netName }
            .filter { it.isNotEmpty() }
    }

    override fun listDir(relDir: String): List<RemoteFile> {
        val (share, inPath) = resolve(relDir)
        if (share == null) {
            return listShares().map { name ->
                RemoteFile(PathUtil.childRel(relDir, name), name, isDirectory = true, size = 0L, modifiedTime = 0L)
            }
        }
        return diskShare(share).list(inPath)
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                RemoteFile(
                    relPath = PathUtil.childRel(relDir, info.fileName),
                    name = info.fileName,
                    isDirectory = isDir,
                    size = if (isDir) 0L else info.endOfFile,
                    modifiedTime = info.lastWriteTime.toEpoch(TimeUnit.MILLISECONDS)
                )
            }
    }

    override fun stat(relPath: String): RemoteFile? {
        val (share, inPath) = resolve(relPath)
        if (share == null) return RemoteFile(relPath, "", isDirectory = true, size = 0L, modifiedTime = 0L)
        if (inPath.isEmpty()) return RemoteFile(relPath, share, isDirectory = true, size = 0L, modifiedTime = 0L)
        return try {
            val info = diskShare(share).getFileInformation(inPath)
            val std = info.standardInformation
            RemoteFile(
                relPath = relPath,
                name = PathUtil.name(relPath),
                isDirectory = std.isDirectory,
                size = if (std.isDirectory) 0L else std.endOfFile,
                modifiedTime = info.basicInformation.lastWriteTime.toEpoch(TimeUnit.MILLISECONDS)
            )
        } catch (e: SMBApiException) {
            null
        }
    }

    override fun makeDir(relDir: String) {
        val (share, inPath) = resolve(relDir)
        if (share == null || inPath.isEmpty()) return  // can't create shares
        val sh = diskShare(share)
        var acc = ""
        for (segment in inPath.split('\\').filter { it.isNotEmpty() }) {
            acc = if (acc.isEmpty()) segment else "$acc\\$segment"
            if (!sh.folderExists(acc)) sh.mkdir(acc)
        }
    }

    override fun deleteFile(relPath: String) {
        val (share, inPath) = resolve(relPath)
        if (share == null) throw IOException("Cannot delete at the server root")
        diskShare(share).rm(inPath)
    }

    override fun deleteDir(relDir: String) {
        val (share, inPath) = resolve(relDir)
        if (share == null || inPath.isEmpty()) return
        diskShare(share).rmdir(inPath, false)
    }

    override fun rename(fromRel: String, toRel: String) {
        val (fromShare, inFrom) = resolve(fromRel)
        val (toShare, inTo) = resolve(toRel)
        if (fromShare == null || toShare == null) throw IOException("Cannot move items at the server root")
        if (fromShare != toShare) throw IOException("Moving between shares isn't supported; copy instead")
        makeDir(parentOf(toRel))
        val entry = diskShare(fromShare).open(
            inFrom,
            setOf(AccessMask.MAXIMUM_ALLOWED),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        try {
            entry.rename(inTo)
        } finally {
            entry.close()
        }
    }

    override fun download(relPath: String, dest: File) {
        val (share, inPath) = resolve(relPath)
        if (share == null) throw IOException("Not a file: $relPath")
        val f = diskShare(share).openFile(
            inPath,
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
        val (share, inPath) = resolve(relPath)
        if (share == null) throw IOException("Cannot write at the server root")
        makeDir(parentOf(relPath))
        val f = diskShare(share).openFile(
            inPath,
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
        shareCache.values.forEach { runCatching { it.close() } }
        shareCache.clear()
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        session = null; connection = null; client = null
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
