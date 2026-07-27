package com.opensync.foldersync.provider

import com.opensync.foldersync.util.PathUtil
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** FTP / FTPS backend built on Apache Commons Net. */
class FtpProvider(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val rootPath: String,
    private val useTls: Boolean,
    private val passiveMode: Boolean,
    private val allowSelfSigned: Boolean
) : StorageProvider {

    private var client: FTPClient? = null

    private fun client() = client ?: throw IOException("FTP not connected")
    private fun full(rel: String) = PathUtil.join(rootPath, rel)

    override fun connect() {
        val c = if (useTls) FTPSClient("TLS", false) else FTPClient()
        if (useTls && allowSelfSigned) {
            (c as FTPSClient).trustManager = TrustManagerUtils.getAcceptAllTrustManager()
        }
        c.connectTimeout = 20_000
        c.connect(host, if (port > 0) port else 21)
        if (!FTPReply.isPositiveCompletion(c.replyCode)) {
            c.disconnect()
            throw IOException("FTP connect failed: ${c.replyString}")
        }
        if (!c.login(username, password)) {
            c.disconnect()
            throw IOException("FTP login failed for user '$username'")
        }
        if (useTls) {
            val fs = c as FTPSClient
            fs.execPBSZ(0L)
            fs.execPROT("P")
        }
        c.setFileType(FTP.BINARY_FILE_TYPE)
        c.controlEncoding = "UTF-8"
        if (passiveMode) c.enterLocalPassiveMode() else c.enterLocalActiveMode()
        client = c
    }

    override fun listDir(relDir: String): List<RemoteFile> {
        val files = client().listFiles(full(relDir)) ?: emptyArray()
        return files
            .filter { it != null && it.name != "." && it.name != ".." }
            .map {
                RemoteFile(
                    relPath = PathUtil.childRel(relDir, it.name),
                    name = it.name,
                    isDirectory = it.isDirectory,
                    size = it.size,
                    modifiedTime = it.timestamp?.timeInMillis ?: 0L
                )
            }
    }

    override fun stat(relPath: String): RemoteFile? {
        val parent = PathUtil.name(relPath).let { name ->
            listDir(parentOf(relPath)).firstOrNull { it.name == name }
        }
        return parent
    }

    private fun parentOf(rel: String): String {
        val r = rel.trim('/')
        val i = r.lastIndexOf('/')
        return if (i < 0) "" else r.substring(0, i)
    }

    override fun makeDir(relDir: String) {
        val c = client()
        var acc = ""
        for (part in full(relDir).trim('/').split('/').filter { it.isNotEmpty() }) {
            acc += "/$part"
            // Ignore result: directory may already exist.
            c.makeDirectory(acc)
        }
    }

    override fun deleteFile(relPath: String) {
        client().deleteFile(full(relPath))
    }

    override fun deleteDir(relDir: String) {
        client().removeDirectory(full(relDir))
    }

    override fun rename(fromRel: String, toRel: String) {
        makeDir(parentOf(toRel))
        if (!client().rename(full(fromRel), full(toRel))) {
            throw IOException("FTP rename failed: $fromRel -> $toRel (${client().replyString})")
        }
    }

    override fun download(relPath: String, dest: File) {
        FileOutputStream(dest).use { out ->
            if (!client().retrieveFile(full(relPath), out)) {
                throw IOException("FTP download failed: $relPath (${client().replyString})")
            }
        }
    }

    override fun upload(src: File, relPath: String, mtime: Long) {
        makeDir(parentOf(relPath))
        FileInputStream(src).use { input ->
            if (!client().storeFile(full(relPath), input)) {
                throw IOException("FTP upload failed: $relPath (${client().replyString})")
            }
        }
        if (mtime > 0) {
            runCatching { client().setModificationTime(full(relPath), gmt(mtime)) }
        }
    }

    override fun close() {
        client?.let {
            runCatching { if (it.isConnected) it.logout() }
            runCatching { if (it.isConnected) it.disconnect() }
        }
        client = null
    }

    private fun gmt(millis: Long): String {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date(millis))
    }
}
