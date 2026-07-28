package com.opensync.foldersync.provider

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OneDrive / Microsoft Graph OAuth2 with PKCE for an installed (public) app — no client secret.
 * Redirect opensync://onedrive is handled by the activity, which calls [complete].
 */
object OneDriveAuth {
    const val REDIRECT = "opensync://onedrive"
    private const val AUTH = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
    private const val TOKEN = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    private const val SCOPE = "Files.ReadWrite offline_access"

    private val client = OkHttpClient()
    private var verifier: String? = null
    private var pendingClientId: String? = null

    val refreshToken = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    fun begin(clientId: String): String {
        val v = randomVerifier()
        verifier = v
        pendingClientId = clientId
        refreshToken.value = null
        error.value = null
        val challenge = base64Url(sha256(v.toByteArray(Charsets.US_ASCII)))
        return "$AUTH?client_id=$clientId" +
            "&response_type=code" +
            "&redirect_uri=$REDIRECT" +
            "&response_mode=query" +
            "&scope=${enc(SCOPE)}" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256"
    }

    suspend fun complete(code: String) = withContext(Dispatchers.IO) {
        val clientId = pendingClientId
        val v = verifier
        if (clientId.isNullOrBlank() || v == null) {
            error.value = "No pending OneDrive sign-in"
            return@withContext
        }
        try {
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT)
                .add("code_verifier", v)
                .add("scope", SCOPE)
                .build()
            val req = Request.Builder().url(TOKEN).post(form).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    error.value = "Token exchange failed (${resp.code})"
                    return@withContext
                }
                val rt = JSONObject(body).optString("refresh_token")
                if (rt.isBlank()) error.value = "OneDrive returned no refresh token"
                else refreshToken.value = rt
            }
        } catch (e: Exception) {
            error.value = e.message ?: "OneDrive sign-in error"
        } finally {
            verifier = null
        }
    }

    fun fail(message: String) { error.value = message }

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
