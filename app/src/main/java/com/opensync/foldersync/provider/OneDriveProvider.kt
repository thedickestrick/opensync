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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * OneDrive backend on Microsoft Graph. Uses a stored OAuth refresh token (from [OneDriveAuth]) to
 * mint short-lived access tokens. Path-addressed (…/root:/path:); large files use upload sessions.
 */
class OneDriveProvider(
    private val clientId: String,
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
        getJson("$GRAPH/me/drive")
    }

    override fun listDir(relDir: String): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var url: String? = childrenUrl(relDir)
        while (url != null) {
            val json = JSONObject(getJson(url))
            val arr = json.optJSONArray("value") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val isDir = e.has("folder")
                val name = e.optString("name")
                out.add(
                    RemoteFile(
                        PathUtil.childRel(relDir, name), name, isDir,
                        e.optLong("size", 0), parseIso(e.optString("lastModifiedDateTime"))
                    )
                )
            }
            url = json.optString("@odata.nextLink").ifBlank { null }
        }
        return out
    }

    override fun stat(relPath: String): RemoteFile? {
        val fp = fullPath(relPath)
        if (fp.isEmpty()) return RemoteFile("", "", true, 0, 0)
        return try {
            val e = JSONObject(getJson(itemUrl(relPath)))
            val isDir = e.has("folder")
            RemoteFile(relPath, PathUtil.name(relPath), isDir, e.optLong("size", 0),
                parseIso(e.optString("lastModifiedDateTime")))
        } catch (e: Exception) {
            null
        }
    }

    override fun makeDir(relDir: String) {
        val fp = fullPath(relDir)
        if (fp.isEmpty()) return
        var parent = ""
        for (part in fp.split('/')) {
            val body = JSONObject().put("name", part).put("folder", JSONObject())
                .put("@microsoft.graph.conflictBehavior", "fail").toString()
            runCatching { requestString("POST", childrenUrlForPath(parent), body.toRequestBody(JSON)) }
            parent = if (parent.isEmpty()) part else "$parent/$part"
        }
    }

    override fun deleteFile(relPath: String) = delete(relPath)

    override fun deleteDir(relDir: String) = delete(relDir)

    private fun delete(rel: String) {
        if (fullPath(rel).isEmpty()) return
        runCatching { requestString("DELETE", itemUrl(rel), null) }
            .onFailure { if (it.message?.contains("404") != true) throw it }
    }

    override fun rename(fromRel: String, toRel: String) {
        val newName = PathUtil.name(toRel)
        val newParent = fullPath(toRel).substringBeforeLast('/', "").let {
            if (it == fullPath(toRel)) "" else it
        }
        val parentRef = if (newParent.isEmpty()) "/drive/root:" else "/drive/root:/$newParent"
        val body = JSONObject().put("name", newName)
            .put("parentReference", JSONObject().put("path", parentRef)).toString()
        requestString("PATCH", itemUrl(fromRel), body.toRequestBody(JSON))
    }

    override fun download(relPath: String, dest: File) {
        val url = itemUrl(relPath, "/content")
        for (attempt in 0..1) {
            val req = Request.Builder().url(url).header("Authorization", "Bearer ${token()}").get().build()
            val resp = client.newCall(req).execute()
            try {
                if (resp.code == 401 && attempt == 0) { accessToken = null; continue }
                if (!resp.isSuccessful) throw IOException("OneDrive download ${resp.code} for $relPath")
                dest.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
                return
            } finally { resp.close() }
        }
        throw IOException("OneDrive download failed for $relPath")
    }

    override fun upload(src: File, relPath: String, mtime: Long) {
        val total = src.length()
        if (total <= SIMPLE_MAX) {
            requestString("PUT", itemUrl(relPath, "/content"), src.asRequestBody(OCTET))
            return
        }
        val sessionBody = JSONObject().put(
            "item", JSONObject().put("@microsoft.graph.conflictBehavior", "replace")
        ).toString()
        val uploadUrl = JSONObject(requestString("POST", itemUrl(relPath, "/createUploadSession"), sessionBody.toRequestBody(JSON)))
            .optString("uploadUrl")
        src.inputStream().buffered().use { input ->
            var start = 0L
            while (start < total) {
                val chunk = readChunk(input, CHUNK)
                if (chunk.isEmpty()) break
                val end = start + chunk.size - 1
                putChunk(uploadUrl, chunk, start, end, total)
                start += chunk.size
            }
        }
    }

    override fun close() { /* OkHttp pools idle-collected */ }

    // ---- helpers ----

    private fun putChunk(uploadUrl: String, chunk: ByteArray, start: Long, end: Long, total: Long) {
        val req = Request.Builder().url(uploadUrl)
            .header("Content-Range", "bytes $start-$end/$total")
            .put(chunk.toRequestBody(OCTET))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code != 200 && resp.code != 201 && resp.code != 202) {
                throw IOException("OneDrive chunk ${resp.code}: ${resp.body?.string()?.take(200)}")
            }
        }
    }

    private fun fullPath(rel: String): String {
        val parts = (basePath.trim('/').split('/') + rel.trim('/').split('/')).filter { it.isNotEmpty() }
        return parts.joinToString("/")
    }

    private fun encPath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun itemUrl(rel: String, suffix: String = ""): String {
        val fp = fullPath(rel)
        return if (fp.isEmpty()) "$GRAPH/me/drive/root$suffix"
        else "$GRAPH/me/drive/root:/${encPath(fp)}:$suffix"
    }

    private fun childrenUrl(rel: String): String = childrenUrlForPath(fullPath(rel))

    private fun childrenUrlForPath(fp: String): String =
        if (fp.isEmpty()) "$GRAPH/me/drive/root/children"
        else "$GRAPH/me/drive/root:/${encPath(fp)}:/children"

    private fun getJson(url: String): String = requestString("GET", url, null)

    private fun token(): String {
        accessToken?.let { return it }
        refreshAccess()
        return accessToken ?: throw IOException("OneDrive not authenticated")
    }

    @Synchronized
    private fun refreshAccess() {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("scope", SCOPE)
            .build()
        val req = Request.Builder().url(TOKEN_URL).post(form).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IOException("OneDrive auth failed (${resp.code}). Re-connect the account.")
            accessToken = JSONObject(body).optString("access_token").ifBlank {
                throw IOException("OneDrive returned no access token")
            }
        }
    }

    /** Bearer-authenticated request; refreshes and retries once on 401. */
    private fun requestString(method: String, url: String, body: RequestBody?): String {
        for (attempt in 0..1) {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${token()}")
                .method(method, body)
                .build()
            val resp = client.newCall(req).execute()
            try {
                if (resp.code == 401 && attempt == 0) { accessToken = null; continue }
                if (!resp.isSuccessful) throw IOException("OneDrive ${resp.code}: ${resp.body?.string()?.take(300)}")
                return resp.body?.string() ?: ""
            } finally { resp.close() }
        }
        throw IOException("OneDrive request failed: $url")
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

    private fun parseIso(text: String): Long {
        for (fmt in ISO_FORMATS) {
            try {
                return SimpleDateFormat(fmt, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(text)?.time ?: continue
            } catch (_: Exception) { /* next */ }
        }
        return 0L
    }

    companion object {
        private const val GRAPH = "https://graph.microsoft.com/v1.0"
        private const val TOKEN_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
        private const val SCOPE = "Files.ReadWrite offline_access"
        private const val SIMPLE_MAX = 4L * 1024 * 1024
        private const val CHUNK = 10 * 1024 * 1024 // 10 MiB (multiple of 320 KiB)
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private val ISO_FORMATS = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
    }
}
