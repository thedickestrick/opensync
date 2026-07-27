package com.opensync.foldersync.provider

import android.util.Xml
import com.opensync.foldersync.util.PathUtil
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** WebDAV backend implemented directly on OkHttp (PROPFIND / GET / PUT / MKCOL / DELETE). */
class WebDavProvider(
    baseUrl: String,
    private val username: String,
    private val password: String,
    allowSelfSigned: Boolean
) : StorageProvider {

    private val base: HttpUrl = baseUrl.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Invalid WebDAV URL: $baseUrl")

    private val auth: String? =
        if (username.isNotEmpty()) Credentials.basic(username, password) else null

    private val client: OkHttpClient = buildClient(allowSelfSigned)

    private fun url(rel: String, dir: Boolean): HttpUrl {
        val b = base.newBuilder()
        for (seg in rel.trim('/').split('/').filter { it.isNotEmpty() }) b.addPathSegment(seg)
        var u = b.build()
        if (dir) u = u.newBuilder().addPathSegment("").build() // trailing slash for collections
        return u
    }

    private fun Request.Builder.authed(): Request.Builder {
        if (auth != null) header("Authorization", auth)
        return this
    }

    override fun connect() {
        val req = Request.Builder()
            .url(url("", dir = true))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", "0")
            .authed()
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw IOException("WebDAV authentication failed (${resp.code})")
            }
            if (!resp.isSuccessful && resp.code != 207) {
                throw IOException("WebDAV connect failed: ${resp.code} ${resp.message}")
            }
        }
    }

    override fun listDir(relDir: String): List<RemoteFile> {
        val reqUrl = url(relDir, dir = true)
        val req = Request.Builder()
            .url(reqUrl)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", "1")
            .authed()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 207) {
                throw IOException("WebDAV list failed: ${resp.code} for $relDir")
            }
            val xml = resp.body?.string() ?: return emptyList()
            return parseMultistatus(xml, reqUrl, relDir)
        }
    }

    override fun stat(relPath: String): RemoteFile? {
        val reqUrl = url(relPath, dir = false)
        val req = Request.Builder()
            .url(reqUrl)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", "0")
            .authed()
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 207) return null
            val xml = resp.body?.string() ?: return null
            parseMultistatus(xml, reqUrl, PathUtil.name(relPath).let { relPath }, self = true).firstOrNull()
        }
    }

    override fun makeDir(relDir: String) {
        val parts = relDir.trim('/').split('/').filter { it.isNotEmpty() }
        var acc = ""
        for (part in parts) {
            acc = if (acc.isEmpty()) part else "$acc/$part"
            val req = Request.Builder().url(url(acc, dir = true)).method("MKCOL", null).authed().build()
            client.newCall(req).execute().use { /* 201 created, 405/301 already exists — ignore */ }
        }
    }

    override fun deleteFile(relPath: String) {
        val req = Request.Builder().url(url(relPath, dir = false)).delete().authed().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) {
                throw IOException("WebDAV delete failed: ${resp.code} for $relPath")
            }
        }
    }

    override fun deleteDir(relDir: String) {
        val req = Request.Builder().url(url(relDir, dir = true)).delete().authed().build()
        client.newCall(req).execute().use { /* ignore */ }
    }

    override fun rename(fromRel: String, toRel: String) {
        val destination = url(toRel, dir = false).toString()
        val req = Request.Builder()
            .url(url(fromRel, dir = false))
            .method("MOVE", null)
            .header("Destination", destination)
            .header("Overwrite", "T")
            .authed()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("WebDAV move failed: ${resp.code} for $fromRel -> $toRel")
            }
        }
    }

    override fun download(relPath: String, dest: File) {
        val req = Request.Builder().url(url(relPath, dir = false)).get().authed().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("WebDAV download failed: ${resp.code} for $relPath")
            val body = resp.body ?: throw IOException("Empty WebDAV response for $relPath")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    override fun upload(src: File, relPath: String, mtime: Long) {
        val parent = PathUtil.name(relPath).let { relPath.trim('/').removeSuffix(it).trim('/') }
        if (parent.isNotEmpty()) makeDir(parent)
        val req = Request.Builder()
            .url(url(relPath, dir = false))
            .put(src.asRequestBody(OCTET))
            .authed()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("WebDAV upload failed: ${resp.code} for $relPath")
        }
    }

    override fun close() { /* OkHttp pools are shared/idle-collected */ }

    // --- XML parsing ---

    private fun parseMultistatus(
        xml: String,
        reqUrl: HttpUrl,
        relDir: String,
        self: Boolean = false
    ): List<RemoteFile> {
        val results = ArrayList<RemoteFile>()
        val reqPath = reqUrl.encodedPath.trimEnd('/')
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var inResponse = false
        var current = ""
        var href: String? = null
        var isCollection = false
        var length = 0L
        var lastMod = 0L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    current = local(parser.name)
                    when (current) {
                        "response" -> {
                            inResponse = true; href = null; isCollection = false; length = 0; lastMod = 0
                        }
                        "collection" -> if (inResponse) isCollection = true
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (inResponse && text.isNotEmpty()) {
                        when (current) {
                            "href" -> href = text
                            "getcontentlength" -> length = text.toLongOrNull() ?: 0L
                            "getlastmodified" -> lastMod = parseHttpDate(text)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (local(parser.name) == "response" && inResponse) {
                        inResponse = false
                        val h = href
                        if (h != null) {
                            val abs = reqUrl.resolve(h)
                            if (abs != null) {
                                val p = abs.encodedPath.trimEnd('/')
                                val isSelf = p == reqPath
                                if (self || !isSelf) {
                                    val name = abs.pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty()
                                    if (name.isNotEmpty()) {
                                        val rel = if (self) relDir else PathUtil.childRel(relDir, name)
                                        results.add(RemoteFile(rel, name, isCollection, length, lastMod))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return results
    }

    private fun local(name: String?): String =
        (name ?: "").substringAfterLast(':').lowercase(Locale.US)

    private fun parseHttpDate(text: String): Long = try {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        fmt.parse(text)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun buildClient(allowSelfSigned: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
        if (allowSelfSigned) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(trustAll), java.security.SecureRandom())
            builder.sslSocketFactory(ctx.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
    }
}
