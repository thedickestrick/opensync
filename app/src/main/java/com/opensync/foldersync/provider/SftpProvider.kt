package com.opensync.foldersync.provider

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.opensync.foldersync.util.PathUtil
import java.io.File
import java.io.IOException
import java.util.Properties

/** SFTP (SSH file transfer) backend built on the maintained JSch fork. */
class SftpProvider(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val rootPath: String
) : StorageProvider {

    private var session: Session? = null
    private var channel: ChannelSftp? = null

    private fun channel() = channel ?: throw IOException("SFTP not connected")
    private fun full(rel: String) = PathUtil.join(rootPath, rel)

    override fun connect() {
        val jsch = JSch()
        val s = jsch.getSession(username, host, if (port > 0) port else 22)
        s.setPassword(password)
        s.setConfig(Properties().apply {
            put("StrictHostKeyChecking", "no")
            put("PreferredAuthentications", "password,keyboard-interactive")
        })
        s.connect(20_000)
        val ch = s.openChannel("sftp") as ChannelSftp
        ch.connect(20_000)
        session = s
        channel = ch
    }

    override fun listDir(relDir: String): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        val entries = channel().ls(full(relDir))
        for (obj in entries) {
            val e = obj as ChannelSftp.LsEntry
            if (e.filename == "." || e.filename == "..") continue
            val attrs = e.attrs
            out += RemoteFile(
                relPath = PathUtil.childRel(relDir, e.filename),
                name = e.filename,
                isDirectory = attrs.isDir,
                size = attrs.size,
                modifiedTime = attrs.mTime.toLong() * 1000L
            )
        }
        return out
    }

    override fun stat(relPath: String): RemoteFile? {
        return try {
            val a = channel().stat(full(relPath))
            RemoteFile(
                relPath = relPath,
                name = PathUtil.name(relPath),
                isDirectory = a.isDir,
                size = a.size,
                modifiedTime = a.mTime.toLong() * 1000L
            )
        } catch (e: SftpException) {
            null
        }
    }

    override fun makeDir(relDir: String) {
        val c = channel()
        var acc = ""
        for (part in full(relDir).trim('/').split('/').filter { it.isNotEmpty() }) {
            acc += "/$part"
            try {
                c.mkdir(acc)
            } catch (e: SftpException) {
                // Directory already exists — continue.
            }
        }
    }

    override fun deleteFile(relPath: String) {
        channel().rm(full(relPath))
    }

    override fun deleteDir(relDir: String) {
        runCatching { channel().rmdir(full(relDir)) }
    }

    override fun rename(fromRel: String, toRel: String) {
        makeDir(parentOf(toRel))
        channel().rename(full(fromRel), full(toRel))
    }

    override fun download(relPath: String, dest: File) {
        channel().get(full(relPath), dest.absolutePath)
    }

    override fun upload(src: File, relPath: String, mtime: Long) {
        makeDir(parentOf(relPath))
        channel().put(src.absolutePath, full(relPath))
        if (mtime > 0) {
            runCatching { channel().setMtime(full(relPath), (mtime / 1000L).toInt()) }
        }
    }

    private fun parentOf(rel: String): String {
        val r = rel.trim('/')
        val i = r.lastIndexOf('/')
        return if (i < 0) "" else r.substring(0, i)
    }

    override fun close() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        channel = null
        session = null
    }
}
