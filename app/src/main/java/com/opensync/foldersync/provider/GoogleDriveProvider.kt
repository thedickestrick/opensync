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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Google Drive backend on Drive API v3. Drive is file-ID based rather than path based, so paths are
 * resolved to folder/file IDs by walking from the (base-path) root; resolved folder IDs are cached.
 * Uses a stored OAuth refresh token (from [GoogleDriveAuth]); large files use resumable uploads.
 */
class GoogleDriveProvider(
    private val clientId: String,
    private val clientSecret: String,
    private val refreshToken: String,
    private val basePath: String,
    /** Called if Google ever hands back a rotated refresh token, so it can be persisted. */
    private val onRefreshTokenRotated: ((String) -> Unit)? = null
) : StorageProvider {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    @Volatile private var accessToken: String? = null
    /** When [accessToken] stops being usable (epoch millis); 0 when there is no token. */
    @Volatile private var accessTokenExpiresAt = 0L
    private var rootId: String = "root"
    private val folderCache = HashMap<String, String>() // rel folder path -> folder id

    // ---- StorageProvider ----

    override fun connect() {
        refreshAccess()
        rootId = resolveBaseRoot()
    }

    override fun listDir(relDir: String): List<RemoteFile> {
        val folderId = resolveFolder(relDir, create = false) ?: return emptyList()
        val out = ArrayList<RemoteFile>()
        var pageToken: String? = null
        do {
            val q = "'$folderId' in parents and trashed = false"
            var url = "$DRIVE/files?q=${enc(q)}" +
                "&fields=${enc("nextPageToken,files(id,name,mimeType,size,modifiedTime)")}" +
                "&pageSize=1000&spaces=drive"
            if (pageToken != null) url += "&pageToken=${enc(pageToken)}"
            val json = JSONObject(getJson(url))
            val arr = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val isDir = e.optString("mimeType") == FOLDER_MIME
                val name = e.optString("name")
                out.add(
                    RemoteFile(
                        PathUtil.childRel(relDir, name), name, isDir,
                        e.optLong("size", 0), parseIso(e.optString("modifiedTime"))
                    )
                )
            }
            pageToken = json.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null)
        return out
    }

    override fun stat(relPath: String): RemoteFile? {
        if (relPath.trim('/').isEmpty()) return RemoteFile("", "", true, 0, 0)
        val e = resolveEntry(relPath) ?: return null
        val isDir = e.optString("mimeType") == FOLDER_MIME
        return RemoteFile(relPath, PathUtil.name(relPath), isDir, e.optLong("size", 0), parseIso(e.optString("modifiedTime")))
    }

    override fun makeDir(relDir: String) {
        resolveFolder(relDir, create = true)
    }

    override fun deleteFile(relPath: String) = deleteEntry(relPath)

    override fun deleteDir(relDir: String) = deleteEntry(relDir)

    private fun deleteEntry(rel: String) {
        val e = resolveEntry(rel) ?: return
        requestString("DELETE", "$DRIVE/files/${e.getString("id")}", null)
        folderCache.clear()
    }

    override fun rename(fromRel: String, toRel: String) {
        val e = resolveEntry(fromRel) ?: throw IOException("Drive: '$fromRel' not found")
        val id = e.getString("id")
        val oldParent = resolveFolder(parentOf(fromRel), create = false)
        val newParent = resolveFolder(parentOf(toRel), create = true) ?: throw IOException("Drive: target folder missing")
        var url = "$DRIVE/files/$id?fields=id&addParents=${enc(newParent)}"
        if (oldParent != null) url += "&removeParents=${enc(oldParent)}"
        requestString("PATCH", url, JSONObject().put("name", PathUtil.name(toRel)).toString().toRequestBody(JSON))
        folderCache.clear()
    }

    override fun download(relPath: String, dest: File) {
        val e = resolveEntry(relPath) ?: throw IOException("Drive: '$relPath' not found")
        val id = e.getString("id")
        for (attempt in 0..1) {
            val req = Request.Builder().url("$DRIVE/files/$id?alt=media")
                .header("Authorization", "Bearer ${token()}").get().build()
            val resp = client.newCall(req).execute()
            try {
                if (resp.code == 401 && attempt == 0) { invalidateToken(); continue }
                if (!resp.isSuccessful) throw IOException("Drive download ${resp.code} for $relPath")
                dest.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
                return
            } finally { resp.close() }
        }
        throw IOException("Drive download failed for $relPath")
    }

    override fun upload(src: File, relPath: String, mtime: Long) {
        val name = PathUtil.name(relPath)
        val parentId = resolveFolder(parentOf(relPath), create = true)
            ?: throw IOException("Drive: parent folder missing for $relPath")
        val existing = childByName(parentId, name)
        if (existing != null && existing.optString("mimeType") != FOLDER_MIME) {
            resumableUpload(src, "PATCH", "$UPLOAD/files/${existing.getString("id")}?uploadType=resumable", JSONObject())
        } else {
            val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parentId))
            resumableUpload(src, "POST", "$UPLOAD/files?uploadType=resumable", meta)
        }
    }

    override fun close() { /* OkHttp pools idle-collected */ }

    // ---- resumable upload ----

    private fun resumableUpload(src: File, method: String, initUrl: String, metadata: JSONObject) {
        val location = run {
            var loc: String? = null
            for (attempt in 0..1) {
                val req = Request.Builder().url(initUrl)
                    .header("Authorization", "Bearer ${token()}")
                    .header("X-Upload-Content-Type", "application/octet-stream")
                    .method(method, metadata.toString().toRequestBody(JSON))
                    .build()
                val resp = client.newCall(req).execute()
                try {
                    if (resp.code == 401 && attempt == 0) { invalidateToken(); continue }
                    if (!resp.isSuccessful) throw IOException("Drive upload init ${resp.code}: ${resp.body?.string()?.take(200)}")
                    loc = resp.header("Location") ?: throw IOException("Drive: no resumable session URL")
                    break
                } finally { resp.close() }
            }
            loc ?: throw IOException("Drive upload init failed")
        }
        // A big upload can outlive the access token it started with, so a 401 here gets one retry
        // with a freshly minted token rather than failing the whole file.
        for (attempt in 0..1) {
            val put = Request.Builder().url(location)
                .header("Authorization", "Bearer ${token()}")
                .put(src.asRequestBody(OCTET))
                .build()
            client.newCall(put).execute().use { resp ->
                if (resp.code == 401 && attempt == 0) { invalidateToken(); return@use }
                if (!resp.isSuccessful) throw IOException("Drive upload ${resp.code}: ${resp.body?.string()?.take(200)}")
                return
            }
        }
        throw IOException("Drive upload failed for ${src.name}")
    }

    // ---- path <-> id resolution ----

    private fun resolveBaseRoot(): String {
        val fp = basePath.trim('/')
        if (fp.isEmpty()) return "root"
        var parent = "root"
        for (part in fp.split('/').filter { it.isNotEmpty() }) {
            val child = childByName(parent, part)
            parent = if (child != null && child.optString("mimeType") == FOLDER_MIME) child.getString("id")
            else createFolder(parent, part)
        }
        return parent
    }

    /** Resolves a folder relative to the base root; creates missing segments when [create]. */
    private fun resolveFolder(rel: String, create: Boolean): String? {
        val key = rel.trim('/')
        if (key.isEmpty()) return rootId
        folderCache[key]?.let { return it }
        var parent = rootId
        for (part in key.split('/').filter { it.isNotEmpty() }) {
            val child = childByName(parent, part)
            parent = when {
                child != null && child.optString("mimeType") == FOLDER_MIME -> child.getString("id")
                create -> createFolder(parent, part)
                else -> return null
            }
        }
        folderCache[key] = parent
        return parent
    }

    /** Resolves a file or folder entry (metadata) at [rel], or null. */
    private fun resolveEntry(rel: String): JSONObject? {
        val parentId = resolveFolder(parentOf(rel), create = false) ?: return null
        return childByName(parentId, PathUtil.name(rel))
    }

    private fun childByName(parentId: String, name: String): JSONObject? {
        val q = "'$parentId' in parents and name = '${escapeQ(name)}' and trashed = false"
        val url = "$DRIVE/files?q=${enc(q)}&fields=${enc("files(id,name,mimeType,size,modifiedTime)")}&pageSize=5&spaces=drive"
        val arr = JSONObject(getJson(url)).optJSONArray("files") ?: return null
        return if (arr.length() > 0) arr.getJSONObject(0) else null
    }

    private fun createFolder(parentId: String, name: String): String {
        val body = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .put("parents", JSONArray().put(parentId))
            .toString()
        val resp = requestString("POST", "$DRIVE/files?fields=id", body.toRequestBody(JSON))
        return JSONObject(resp).getString("id")
    }

    private fun parentOf(rel: String): String = rel.trim('/').substringBeforeLast('/', "")

    // ---- auth / http ----

    private fun getJson(url: String): String = requestString("GET", url, null)

    /**
     * A usable access token. Google's tokens last an hour, so this renews *before* expiry rather
     * than waiting for a 401 — otherwise a long upload started with a nearly-expired token dies
     * partway through.
     */
    private fun token(): String {
        if (!tokenIsFresh()) refreshAccess()
        return accessToken ?: throw IOException("Google Drive not authenticated")
    }

    private fun tokenIsFresh(): Boolean =
        accessToken != null && System.currentTimeMillis() < accessTokenExpiresAt - EXPIRY_SKEW_MS

    /** A refresh that failed for a reason worth retrying (network blip, 5xx, rate limit). */
    private class TransientAuthError(message: String, cause: Exception? = null) : IOException(message, cause)

    @Synchronized
    private fun refreshAccess() {
        // Another thread may have refreshed while this one waited for the lock.
        if (tokenIsFresh()) return
        var last: Exception? = null
        for (attempt in 0 until AUTH_ATTEMPTS) {
            try {
                fetchAccessToken()
                return
            } catch (e: TransientAuthError) {
                last = e
                runCatching { Thread.sleep(1000L * (attempt + 1)) }
            }
        }
        throw IOException(
            "Couldn't reach Google to refresh the sign-in (${last?.message ?: "network error"}). " +
                "The account is still connected — this looks like a network problem, not a sign-in one."
        )
    }

    private fun fetchAccessToken() {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val resp = try {
            client.newCall(Request.Builder().url(TOKEN).post(form).build()).execute()
        } catch (e: IOException) {
            throw TransientAuthError(e.message ?: "network error", e)
        }
        resp.use {
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val error = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                // invalid_grant means the refresh token itself is dead — revoked, password changed,
                // or expired because the OAuth consent screen is still in "Testing" (7-day limit).
                if (error == "invalid_grant") throw IOException(
                    "Google sign-in expired — reconnect the account under Accounts → Google Drive. " +
                        "If this happens about weekly, set the OAuth consent screen in Google Cloud " +
                        "Console to \"In production\": refresh tokens for apps still in \"Testing\" " +
                        "are expired by Google after 7 days."
                )
                if (resp.code == 429 || resp.code in 500..599) {
                    throw TransientAuthError("Google auth ${resp.code}")
                }
                val detail = runCatching {
                    JSONObject(body).optString("error_description").ifBlank { error }
                }.getOrNull().orEmpty()
                throw IOException(
                    "Google auth failed (${resp.code})" + if (detail.isNotBlank()) ": $detail" else ""
                )
            }
            val json = JSONObject(body)
            val fresh = json.optString("access_token").ifBlank { throw IOException("Google returned no access token") }
            // Trust the server's lifetime; fall back to Google's usual hour if it's absent.
            val ttlSeconds = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
            accessToken = fresh
            accessTokenExpiresAt = System.currentTimeMillis() + ttlSeconds * 1000L
            json.optString("refresh_token")
                .takeIf { it.isNotBlank() && it != refreshToken }
                ?.let { rotated -> runCatching { onRefreshTokenRotated?.invoke(rotated) } }
        }
    }

    /** Drops the cached token so the next call mints a fresh one (used after a 401). */
    private fun invalidateToken() {
        accessToken = null
        accessTokenExpiresAt = 0L
    }

    private fun requestString(method: String, url: String, body: RequestBody?): String {
        for (attempt in 0..1) {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${token()}")
                .method(method, body)
                .build()
            val resp = client.newCall(req).execute()
            try {
                if (resp.code == 401 && attempt == 0) { invalidateToken(); continue }
                if (!resp.isSuccessful) throw IOException("Drive ${resp.code}: ${resp.body?.string()?.take(300)}")
                return resp.body?.string() ?: ""
            } finally { resp.close() }
        }
        throw IOException("Drive request failed: $url")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun escapeQ(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

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
        private const val DRIVE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val TOKEN = "https://oauth2.googleapis.com/token"

        /** Renew this far ahead of the stated expiry, so an in-flight request can't age out. */
        private const val EXPIRY_SKEW_MS = 5 * 60 * 1000L
        private const val AUTH_ATTEMPTS = 3
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private val ISO_FORMATS = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
    }
}
