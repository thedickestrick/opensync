package com.opensync.foldersync.provider

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Dropbox OAuth2 with PKCE for an installed (public) app — no client secret. The account editor
 * calls [begin] to launch the browser; the redirect (opensync://dropbox?code=…) is handled by the
 * activity, which calls [complete] to exchange the code for a long-lived refresh token.
 */
object DropboxAuth {
    const val REDIRECT = "opensync://dropbox"

    private val client = OkHttpClient()
    private var verifier: String? = null
    private var pendingAppKey: String? = null

    /** Set to the refresh token after a successful exchange; observed by the account editor. */
    val refreshToken = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    /** Builds the authorize URL and stores the PKCE state for the pending flow. */
    fun begin(appKey: String): String {
        val v = randomVerifier()
        verifier = v
        pendingAppKey = appKey
        refreshToken.value = null
        error.value = null
        val challenge = base64Url(sha256(v.toByteArray(Charsets.US_ASCII)))
        return "https://www.dropbox.com/oauth2/authorize" +
            "?client_id=$appKey" +
            "&response_type=code" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256" +
            "&token_access_type=offline" +
            "&redirect_uri=$REDIRECT"
    }

    suspend fun complete(code: String) = withContext(Dispatchers.IO) {
        val appKey = pendingAppKey
        val v = verifier
        if (appKey.isNullOrBlank() || v == null) {
            error.value = "No pending Dropbox sign-in"
            return@withContext
        }
        try {
            val form = FormBody.Builder()
                .add("code", code)
                .add("grant_type", "authorization_code")
                .add("client_id", appKey)
                .add("redirect_uri", REDIRECT)
                .add("code_verifier", v)
                .build()
            val req = Request.Builder().url("https://api.dropboxapi.com/oauth2/token").post(form).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    error.value = "Token exchange failed (${resp.code})"
                    return@withContext
                }
                val rt = JSONObject(body).optString("refresh_token")
                if (rt.isBlank()) error.value = "Dropbox returned no refresh token"
                else refreshToken.value = rt
            }
        } catch (e: Exception) {
            error.value = e.message ?: "Dropbox sign-in error"
        } finally {
            verifier = null
        }
    }

    fun fail(message: String) { error.value = message }

    private fun randomVerifier(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val sr = SecureRandom()
        return buildString { repeat(64) { append(chars[sr.nextInt(chars.length)]) } }
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun base64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
