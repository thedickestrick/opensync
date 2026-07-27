package com.opensync.foldersync.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Checks a GitHub repo's latest release for a newer APK and installs it. */
object UpdateChecker {

    data class Release(val tag: String, val notes: String, val apkUrl: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun latestRelease(owner: String, repo: String): Release = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "OpenSync")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("GitHub responded ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val tag = json.optString("tag_name")
            val notes = json.optString("body")
            var apk: String? = null
            json.optJSONArray("assets")?.let { assets ->
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apk = asset.optString("browser_download_url")
                        break
                    }
                }
            }
            Release(tag, notes, apk)
        }
    }

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** True if [latestTag] represents a higher version than [current] (both like "1.2.3" or "v1.2"). */
    fun isNewer(latestTag: String, current: String): Boolean {
        val a = parse(latestTag)
        val b = parse(current)
        for (i in 0 until max(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(v: String): List<Int> =
        v.trimStart('v', 'V').split('.', '-', '_')
            .mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
            .ifEmpty { listOf(0) }

    suspend fun download(url: String, dest: File, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "OpenSync").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed: ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response")
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var readTotal = 0L
                    var n: Int
                    while (input.read(buffer).also { n = it } >= 0) {
                        out.write(buffer, 0, n)
                        readTotal += n
                        if (total > 0) onProgress(((readTotal * 100) / total).toInt())
                    }
                }
            }
        }
    }

    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
