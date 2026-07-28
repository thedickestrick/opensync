package com.opensync.foldersync.provider

import com.opensync.foldersync.util.PathUtil
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Dropbox backend on the HTTP API v2. Uses a stored OAuth refresh token (obtained via
 * [DropboxAuth]) to mint short-lived access tokens. Paths are relative to [basePath];
 * large files upload via chunked upload sessions.
 */
class DropboxProvider(
    private val appKey: String,
    private val refreshToken: String,
    private val basePath: String
) : StorageProvider {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile private var accessToken: String? = null

    // ---- StorageProvider ----

    override fun connect() {
        refreshAccess()
        // Validate the token/scopes cheaply.
        postJson("$API/2/users/get_current_account", "null")
    }

    override fun listDir(relDir: String): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var body = postJson(
            "$API/2/files/list_folder",
            JSONObject().put("path", dbxPath(relDir)).put("recursive", false).put("limit", 2000).toString()
        )
        while (true) {
            val json = JSONObject(body)
            val entries = json.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until entries.length()) {
                val e = entries.getJSONObject(i)
                val isDir = e.optString(".tag") == "folder"
                val name = e.optString("name")
                val mod = if (isDir) 0L else parseIso(e.optString("server_modified"))
                out.add(RemoteFile(PathUtil.childRel(relDir, name), name, isDir, e.optLong("size", 0), mod))
            }
            if (json.optBoolean("has_more", false)) {
                body = postJson(
                    "$API/2/files/list_folder/continue",
                    JSONObject().put("cursor", json.optString("cursor")).toString()
                )
            } else break
        }
        return out
    }

    override fun stat(relPath: String): RemoteFile? {
        val path = dbxPath(relPath)
        if (path.isEmpty()) return RemoteFile("", "", true, 0, 0)
        return try {
            val e = JSONObject(postJson("$API/2/files/get_metadata", JSONObject().put("path", path).toString()))
            val isDir = e.optString(".tag") == "folder"
            RemoteFile(
                relPath, PathUtil.name(relPath), isDir, e.optLong("size", 0),
                if (isDir) 0L else parseIso(e.optString("server_modified"))
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun makeDir(relDir: String) {
        val path = dbxPath(relDir)
        if (path.isEmpty()) return
        val parts = path.trim('/').split('/')
        var acc = ""
        for (part in parts) {
            acc = "$acc/$part"
            runCatching {
                postJson(
                    "$API/2/files/create_folder_v2",
                    JSONObject().put("path", acc).put("autorename", false).toString()
                )
            } // 409 conflict (already exists) is fine
        }
    }

    override fun deleteFile(relPath: String) = delete(relPath)

    override fun deleteDir(relDir: String) = delete(relDir)

    private fun delete(rel: String) {
        val path = dbxPath(rel)
        if (path.isEmpty()) return
        runCatching { postJson("$API/2/files/delete_v2", JSONObject().put("path", path).toString()) }
            .onFailure { if (it.message?.contains("not_found") != true) throw it }
    }

    override fun rename(fromRel: String, toRel: String) {
        postJson(
            "$API/2/files/move_v2",
            JSONObject().put("from_path", dbxPath(fromRel)).put("to_path", dbxPath(toRel))
                .put("autorename", false).toString()
        )
    }

    override fun download(relPath: String, dest: File) {
        val arg = asciiEscape(JSONObject().put("path", dbxPath(relPath)).toString())
        for (attempt in 0..1) {
            val req = Request.Builder()
                .url("$CONTENT/2/files/download")
                .header("Authorization", "Bearer ${token()}")
                .header("Dropbox-API-Arg", arg)
                .post(EMPTY_BODY)
                .build()
            val resp = client.newCall(req).execute()
            try {
                if (resp.code == 401 && attempt == 0) { accessToken = null; continue }
                if (!resp.isSuccessful) throw IOException("Dropbox download ${resp.code}: ${resp.body?.string()?.take(200)}")
                dest.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
                return
            } finally { resp.close() }
        }
        throw IOException("Dropbox download failed for $relPath")
    }

    override fun upload(src: File, relPath: String, mtime: Long) {
        val path = dbxPath(relPath)
        if (src.length() <= SINGLE_MAX) {
            val arg = uploadArg(path)
            contentPost("$CONTENT/2/files/upload", arg, src.asRequestBody(OCTET))
            return
        }
        // Large file: chunked upload session.
        src.inputStream().buffered().use { input ->
            val first = readChunk(input, CHUNK)
            val startArg = JSONObject().put("close", false).toString()
            val sessionId = JSONObject(contentPost("$CONTENT/2/files/upload_session/start", startArg, first.toRequestBody(OCTET)))
                .optString("session_id")
            var offset = first.size.toLong()
            while (true) {
                val chunk = readChunk(input, CHUNK)
                if (chunk.isEmpty()) break
                val appendArg = JSONObject()
                    .put("cursor", JSONObject().put("session_id", sessionId).put("offset", offset))
                    .put("close", false).toString()
                contentPost("$CONTENT/2/files/upload_session/append_v2", appendArg, chunk.toRequestBody(OCTET))
                offset += chunk.size
            }
            val finishArg = JSONObject()
                .put("cursor", JSONObject().put("session_id", sessionId).put("offset", offset))
                .put("commit", JSONObject().put("path", path).put("mode", "overwrite").put("mute", true))
                .toString()
            contentPost("$CONTENT/2/files/upload_session/finish", finishArg, EMPTY_BODY)
        }
    }

    override fun close() { /* OkHttp pools idle-collected */ }

    // ---- helpers ----

    private fun uploadArg(path: String) =
        JSONObject().put("path", path).put("mode", "overwrite").put("mute", true).toString()

    private fun dbxPath(rel: String): String {
        val parts = (basePath.trim('/').split('/') + rel.trim('/').split('/')).filter { it.isNotEmpty() }
        return if (parts.isEmpty()) "" else "/" + parts.joinToString("/")
    }

    private fun token(): String {
        accessToken?.let { return it }
        refreshAccess()
        return accessToken ?: throw IOException("Dropbox not authenticated")
    }

    @Synchronized
    private fun refreshAccess() {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", appKey)
            .build()
        val req = Request.Builder().url("$API/oauth2/token").post(form).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IOException("Dropbox auth failed (${resp.code}). Re-connect the account.")
            accessToken = JSONObject(body).optString("access_token").ifBlank {
                throw IOException("Dropbox returned no access token")
            }
        }
    }

    /** RPC endpoint POST with a JSON body; refreshes and retries once on 401. */
    private fun postJson(url: String, json: String): String {
        for (attempt in 0..1) {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${token()}")
                .post(json.toRequestBody(JSON))
                .build()
            val resp = client.newCall(req).execute()
            try {
                if (resp.code == 401 && attempt == 0) { accessToken = null; continue }
                if (!resp.isSuccessful) throw IOException("Dropbox ${resp.code}: ${resp.body?.string()?.take(300)}")
                return resp.body?.string() ?: ""
            } finally { resp.close() }
        }
        throw IOException("Dropbox request failed: $url")
    }

    /** Content endpoint POST (arg in header, bytes in body); refreshes and retries once on 401. */
    private fun contentPost(url: String, arg: String, body: RequestBody): String {
        for (attempt in 0..1) {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${token()}")
                .header("Dropbox-API-Arg", asciiEscape(arg))
                .post(body)
                .build()
            val resp = client.newCall(req).execute()
            try {
                if (resp.code == 401 && attempt == 0) { accessToken = null; continue }
                if (!resp.isSuccessful) throw IOException("Dropbox ${resp.code}: ${resp.body?.string()?.take(300)}")
                return resp.body?.string() ?: ""
            } finally { resp.close() }
        }
        throw IOException("Dropbox content request failed: $url")
    }

    private fun readChunk(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    /** Dropbox-API-Arg must be HTTP-header safe: escape non-ASCII as \uXXXX. */
    private fun asciiEscape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c.code in 0x20..0x7E) sb.append(c) else sb.append("\\u").append("%04x".format(c.code))
        }
        return sb.toString()
    }

    private fun parseIso(text: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(text)?.time ?: 0L
    } catch (e: Exception) { 0L }

    companion object {
        private const val API = "https://api.dropboxapi.com"
        private const val CONTENT = "https://content.dropboxapi.com"
        private const val SINGLE_MAX = 140L * 1024 * 1024
        private const val CHUNK = 8 * 1024 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(OCTET)
    }
}
