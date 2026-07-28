package com.opensync.foldersync.provider

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Google OAuth2 for installed apps using the loopback-IP redirect (Google doesn't allow custom
 * scheme redirects). We open the system browser, run a one-shot local HTTP server on 127.0.0.1 to
 * catch the redirect, then exchange the code (PKCE + Desktop client id/secret) for a refresh token.
 */
object GoogleDriveAuth {
    private const val SCOPE = "https://www.googleapis.com/auth/drive"
    private const val TOKEN = "https://oauth2.googleapis.com/token"

    private val client = OkHttpClient()
    val refreshToken = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    fun startLogin(context: Context, clientId: String, clientSecret: String) {
        refreshToken.value = null
        error.value = null
        val verifier = randomVerifier()
        val challenge = base64Url(sha256(verifier.toByteArray(Charsets.US_ASCII)))

        val server = try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            error.value = "Couldn't start local sign-in server: ${e.message}"
            return
        }
        val redirect = "http://127.0.0.1:${server.localPort}"
        val authUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=$clientId" +
            "&response_type=code" +
            "&redirect_uri=${enc(redirect)}" +
            "&scope=${enc(SCOPE)}" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256" +
            "&access_type=offline" +
            "&prompt=consent"

        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!opened) {
            error.value = "Couldn't open a browser for Google sign-in"
            runCatching { server.close() }
            return
        }

        Thread {
            try {
                server.soTimeout = 300_000
                server.accept().use { socket ->
                    val params = readRequest(socket)
                    writeResponse(socket)
                    val code = params["code"]
                    when {
                        code != null -> {
                            val rt = exchange(clientId, clientSecret, code, redirect, verifier)
                            if (rt != null) refreshToken.value = rt
                            else if (error.value == null) error.value = "Token exchange failed"
                        }
                        else -> error.value = params["error"] ?: "Google sign-in cancelled"
                    }
                }
            } catch (e: Exception) {
                error.value = e.message ?: "Google sign-in error"
            } finally {
                runCatching { server.close() }
            }
        }.apply { isDaemon = true }.start()
    }

    fun fail(message: String) { error.value = message }

    private fun exchange(
        clientId: String, clientSecret: String, code: String, redirect: String, verifier: String
    ): String? {
        val form = FormBody.Builder()
            .add("code", code)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("redirect_uri", redirect)
            .add("grant_type", "authorization_code")
            .add("code_verifier", verifier)
            .build()
        client.newCall(Request.Builder().url(TOKEN).post(form).build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                error.value = "Token exchange failed (${resp.code})"
                return null
            }
            return JSONObject(body).optString("refresh_token").ifBlank { null }
        }
    }

    private fun readRequest(socket: Socket): Map<String, String> {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val line = reader.readLine() ?: return emptyMap()
        val path = line.split(" ").getOrNull(1) ?: return emptyMap()
        val query = path.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i < 0) null
            else runCatching {
                URLDecoder.decode(pair.substring(0, i), "UTF-8") to URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }.getOrNull()
        }.toMap()
    }

    private fun writeResponse(socket: Socket) {
        val html = "<html><body style='font-family:sans-serif;text-align:center;padding:48px'>" +
            "<h3>OpenSync</h3><p>Google sign-in complete — you can close this tab and return to the app.</p></body></html>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun randomVerifier(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val sr = SecureRandom()
        return buildString { repeat(64) { append(chars[sr.nextInt(chars.length)]) } }
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun base64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
