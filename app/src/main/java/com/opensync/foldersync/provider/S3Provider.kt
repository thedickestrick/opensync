package com.opensync.foldersync.provider

import android.util.Xml
import com.opensync.foldersync.util.PathUtil
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * S3-compatible object storage (AWS S3, Backblaze B2, Wasabi, Cloudflare R2, Storj, MinIO, …)
 * implemented directly on OkHttp with AWS Signature V4 (path-style addressing). Object keys are
 * derived from [prefix] + the relative path; "folders" are the usual zero-byte "name/" markers.
 */
class S3Provider(
    endpoint: String,
    private val region: String,
    private val accessKey: String,
    private val secretKey: String,
    private val bucket: String,
    private val prefix: String,
    useTls: Boolean,
    allowSelfSigned: Boolean
) : StorageProvider {

    private val scheme = if (useTls) "https" else "http"
    private val host: String
    private val port: Int

    init {
        // Accept "host", "host:port", or a full URL in endpoint.
        val cleaned = endpoint.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val hp = cleaned.substringBefore('/')
        if (hp.contains(':')) {
            host = hp.substringBefore(':')
            port = hp.substringAfter(':').toIntOrNull() ?: defaultPort()
        } else {
            host = hp
            port = defaultPort()
        }
    }

    private fun defaultPort() = if (scheme == "https") 443 else 80
    private val hostHeader: String =
        if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) host else "$host:$port"

    private val client: OkHttpClient = buildClient(allowSelfSigned)

    // ---- StorageProvider ----

    override fun connect() {
        // Validate credentials + bucket by listing at most one key.
        val signed = sign("GET", "/${enc(bucket)}/", mapOf("list-type" to "2", "max-keys" to "1"), EMPTY_SHA256)
        exec(request(signed).get().build()) { resp ->
            when (resp.code) {
                403 -> throw IOException("S3 access denied — check access key / secret / region")
                404 -> throw IOException("S3 bucket '$bucket' not found at $hostHeader")
                else -> if (!resp.isSuccessful) throw IOException("S3 connect failed: ${resp.code} ${resp.message}")
            }
        }
    }

    override fun listDir(relDir: String): List<RemoteFile> {
        val listPrefix = fullKey(relDir).let { if (it.isEmpty()) "" else "$it/" }
        val out = ArrayList<RemoteFile>()
        var token: String? = null
        do {
            val query = linkedMapOf("list-type" to "2", "delimiter" to "/")
            if (listPrefix.isNotEmpty()) query["prefix"] = listPrefix
            if (token != null) query["continuation-token"] = token
            val signed = sign("GET", "/${enc(bucket)}/", query, EMPTY_SHA256)
            token = exec(request(signed).get().build()) { resp ->
                if (!resp.isSuccessful) throw IOException("S3 list failed: ${resp.code} for '$relDir'")
                parseList(resp.body?.string() ?: "", listPrefix, relDir, out)
            }
        } while (token != null)
        return out
    }

    override fun stat(relPath: String): RemoteFile? {
        val key = fullKey(relPath)
        if (key.isEmpty()) return RemoteFile("", "", true, 0, 0)
        val signed = sign("HEAD", "/${enc(bucket)}/${encKey(key)}", emptyMap(), EMPTY_SHA256)
        val file = exec(request(signed).head().build()) { resp ->
            if (resp.isSuccessful) {
                val size = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                val mod = resp.header("Last-Modified")?.let { parseHttpDate(it) } ?: 0L
                RemoteFile(relPath, PathUtil.name(relPath), false, size, mod)
            } else null
        }
        if (file != null) return file
        // Not an object — maybe a "folder" (prefix with children).
        val q = mapOf("list-type" to "2", "prefix" to "$key/", "max-keys" to "1")
        val ls = sign("GET", "/${enc(bucket)}/", q, EMPTY_SHA256)
        val hasChildren = exec(request(ls).get().build()) { resp ->
            if (!resp.isSuccessful) false
            else resp.body?.string().orEmpty().let { it.contains("<Contents>") || it.contains("<CommonPrefixes>") }
        }
        return if (hasChildren) RemoteFile(relPath, PathUtil.name(relPath), true, 0, 0) else null
    }

    override fun makeDir(relDir: String) {
        val key = fullKey(relDir)
        if (key.isEmpty()) return
        putObject("$key/", EMPTY_BODY)
    }

    override fun deleteFile(relPath: String) {
        deleteKey(fullKey(relPath))
    }

    override fun deleteDir(relDir: String) {
        val base = fullKey(relDir)
        // Delete every object under the prefix (recursive), then the folder marker.
        for (key in listAllKeys(if (base.isEmpty()) "" else "$base/")) deleteKey(key)
        if (base.isNotEmpty()) deleteKey("$base/")
    }

    override fun rename(fromRel: String, toRel: String) {
        val from = fullKey(fromRel)
        val to = fullKey(toRel)
        val asObject = stat(fromRel)
        if (asObject != null && !asObject.isDirectory) {
            copyKey(from, to); deleteKey(from)
            return
        }
        // Directory: copy each descendant, preserving the sub-path, then delete originals.
        val keys = listAllKeys("$from/")
        for (key in keys) {
            val suffix = key.removePrefix("$from/")
            copyKey(key, "$to/$suffix")
        }
        for (key in keys) deleteKey(key)
        deleteKey("$from/")
    }

    override fun download(relPath: String, dest: File) {
        val signed = sign("GET", "/${enc(bucket)}/${encKey(fullKey(relPath))}", emptyMap(), EMPTY_SHA256)
        exec(request(signed).get().build()) { resp ->
            if (!resp.isSuccessful) throw IOException("S3 download failed: ${resp.code} for '$relPath'")
            val body = resp.body ?: throw IOException("Empty S3 response for '$relPath'")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    override fun upload(src: File, relPath: String, mtime: Long) {
        putObject(fullKey(relPath), src.asRequestBody(OCTET))
    }

    override fun close() { /* OkHttp pools are idle-collected */ }

    // ---- helpers ----

    private fun putObject(key: String, body: RequestBody) {
        val signed = sign("PUT", "/${enc(bucket)}/${encKey(key)}", emptyMap(), UNSIGNED_PAYLOAD)
        exec(request(signed).put(body).build()) { resp ->
            if (!resp.isSuccessful) throw IOException("S3 upload failed: ${resp.code} for '$key'")
        }
    }

    private fun deleteKey(key: String) {
        if (key.isEmpty()) return
        val signed = sign("DELETE", "/${enc(bucket)}/${encKey(key)}", emptyMap(), EMPTY_SHA256)
        exec(request(signed).delete().build()) { resp ->
            if (!resp.isSuccessful && resp.code != 404) throw IOException("S3 delete failed: ${resp.code} for '$key'")
        }
    }

    private fun copyKey(from: String, to: String) {
        val copySource = "/${enc(bucket)}/${encKey(from)}"
        val extra = mapOf("x-amz-copy-source" to copySource)
        val signed = sign("PUT", "/${enc(bucket)}/${encKey(to)}", emptyMap(), EMPTY_SHA256, extra)
        exec(request(signed).put(EMPTY_BODY).build()) { resp ->
            if (!resp.isSuccessful) throw IOException("S3 copy failed: ${resp.code} for '$from' -> '$to'")
        }
    }

    private fun listAllKeys(listPrefix: String): List<String> {
        val keys = ArrayList<String>()
        var token: String? = null
        do {
            val q = linkedMapOf("list-type" to "2")
            if (listPrefix.isNotEmpty()) q["prefix"] = listPrefix
            if (token != null) q["continuation-token"] = token
            val signed = sign("GET", "/${enc(bucket)}/", q, EMPTY_SHA256)
            token = exec(request(signed).get().build()) { resp ->
                if (!resp.isSuccessful) throw IOException("S3 list failed: ${resp.code}")
                parseKeys(resp.body?.string() ?: "", keys)
            }
        } while (token != null)
        return keys
    }

    private fun fullKey(rel: String): String {
        val parts = (prefix.trim('/').split('/') + rel.trim('/').split('/')).filter { it.isNotEmpty() }
        return parts.joinToString("/")
    }

    // ---- XML parsing ----

    /** Parses a ListObjectsV2 response into [out]; returns the next continuation token or null. */
    private fun parseList(xml: String, listPrefix: String, relDir: String, out: MutableList<RemoteFile>): String? {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var tag = ""
        var inContents = false
        var inCommon = false
        var key = ""; var size = 0L; var mod = 0L; var cp = ""
        var truncated = false; var next: String? = null
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    when (tag) {
                        "Contents" -> { inContents = true; key = ""; size = 0; mod = 0 }
                        "CommonPrefixes" -> { inCommon = true; cp = "" }
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text ?: ""
                    when {
                        inContents && tag == "Key" -> key = t
                        inContents && tag == "Size" -> size = t.trim().toLongOrNull() ?: 0L
                        inContents && tag == "LastModified" -> mod = parseIso(t.trim())
                        inCommon && tag == "Prefix" -> cp = t
                        !inContents && !inCommon && tag == "IsTruncated" -> truncated = t.trim() == "true"
                        !inContents && !inCommon && tag == "NextContinuationToken" -> next = t.trim()
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "Contents" -> {
                            inContents = false
                            if (key.isNotEmpty() && key != listPrefix && !key.endsWith("/")) {
                                val name = key.removePrefix(listPrefix)
                                if (name.isNotEmpty() && !name.contains('/')) {
                                    out.add(RemoteFile(PathUtil.childRel(relDir, name), name, false, size, mod))
                                }
                            }
                        }
                        "CommonPrefixes" -> {
                            inCommon = false
                            val name = cp.removePrefix(listPrefix).trimEnd('/')
                            if (name.isNotEmpty() && !name.contains('/')) {
                                out.add(RemoteFile(PathUtil.childRel(relDir, name), name, true, 0, 0))
                            }
                        }
                    }
                }
            }
            e = parser.next()
        }
        return if (truncated) next else null
    }

    private fun parseKeys(xml: String, out: MutableList<String>): String? {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var tag = ""; var inContents = false; var key = ""
        var truncated = false; var next: String? = null
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "Contents") { inContents = true; key = "" }
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text ?: ""
                    when {
                        inContents && tag == "Key" -> key = t
                        !inContents && tag == "IsTruncated" -> truncated = t.trim() == "true"
                        !inContents && tag == "NextContinuationToken" -> next = t.trim()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "Contents") {
                    inContents = false
                    if (key.isNotEmpty() && !key.endsWith("/")) out.add(key)
                }
            }
            e = parser.next()
        }
        return if (truncated) next else null
    }

    // ---- signing ----

    private fun request(s: Signed): Request.Builder {
        val b = Request.Builder().url(s.url)
        for ((k, v) in s.headers) b.header(k, v)
        return b
    }

    private data class Signed(val url: String, val headers: Map<String, String>)

    private fun sign(
        method: String,
        canonicalUri: String,
        query: Map<String, String>,
        payloadHash: String,
        extraSigned: Map<String, String> = emptyMap()
    ): Signed {
        val now = Date()
        val amzDate = AMZ_DATE.get().format(now)
        val dateStamp = DATE_STAMP.get().format(now)

        val canonicalQuery = query.entries
            .sortedBy { it.key }
            .joinToString("&") { "${awsUriEncode(it.key, true)}=${awsUriEncode(it.value, true)}" }

        val headers = sortedMapOf<String, String>()
        headers["host"] = hostHeader
        headers["x-amz-content-sha256"] = payloadHash
        headers["x-amz-date"] = amzDate
        for ((k, v) in extraSigned) headers[k.lowercase()] = v

        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" }
        val signedHeaders = headers.keys.joinToString(";")
        val canonicalRequest =
            "$method\n$canonicalUri\n$canonicalQuery\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

        val scope = "$dateStamp/$region/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))}"

        var k = ("AWS4$secretKey").toByteArray(Charsets.UTF_8)
        k = hmac(k, dateStamp); k = hmac(k, region); k = hmac(k, "s3"); k = hmac(k, "aws4_request")
        val signature = hex(hmac(k, stringToSign))

        val authorization =
            "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"

        val url = buildString {
            append(scheme).append("://").append(hostHeader).append(canonicalUri)
            if (canonicalQuery.isNotEmpty()) append("?").append(canonicalQuery)
        }
        val outHeaders = LinkedHashMap<String, String>()
        outHeaders["Authorization"] = authorization
        outHeaders["x-amz-content-sha256"] = payloadHash
        outHeaders["x-amz-date"] = amzDate
        for ((hk, hv) in extraSigned) outHeaders[hk] = hv
        return Signed(url, outHeaders)
    }

    private fun <T> exec(req: Request, block: (okhttp3.Response) -> T): T =
        client.newCall(req).execute().use(block)

    private fun enc(s: String) = awsUriEncode(s, true)
    private fun encKey(key: String) = awsUriEncode(key, false) // keep '/' between segments

    private fun awsUriEncode(input: String, encodeSlash: Boolean): String {
        val sb = StringBuilder()
        for (b in input.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '_' || ch == '-' || ch == '~' || ch == '.' -> sb.append(ch)
                ch == '/' -> sb.append(if (encodeSlash) "%2F" else "/")
                else -> sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(data: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(data))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun parseIso(text: String): Long {
        for (fmt in ISO_FORMATS) {
            try {
                return SimpleDateFormat(fmt, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(text)?.time ?: continue
            } catch (_: Exception) { /* try next */ }
        }
        return 0L
    }

    private fun parseHttpDate(text: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(text)?.time ?: 0L
    } catch (e: Exception) { 0L }

    private fun buildClient(allowSelfSigned: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
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
        private val OCTET = "application/octet-stream".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(OCTET)
        private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
        private const val EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        private val ISO_FORMATS = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        private val AMZ_DATE = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
        private val DATE_STAMP = ThreadLocal.withInitial {
            SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
    }
}
