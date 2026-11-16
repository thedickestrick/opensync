package com.opensync.foldersync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.opensync.foldersync.provider.ProviderFactory
import com.opensync.foldersync.util.PathUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/*
 * Saving edits of photos and videos that live on one of the user's accounts (a cloud drive, a NAS,
 * a server…). The gallery downloads such a file to edit it; saving uploads the result back to the
 * account — over the original, or as a new file beside it — instead of into the private download.
 */

private const val EXTRA_CLOUD_ACCOUNT = "cloud_account"
private const val EXTRA_CLOUD_NAME = "cloud_account_name"
private const val EXTRA_CLOUD_PATH = "cloud_path"

/** The account file a photo or video being edited was downloaded from. */
internal data class CloudOrigin(val accountId: Long, val accountName: String, val relPath: String) {
    val folder: String get() = relPath.trim('/').substringBeforeLast('/', "")

    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_CLOUD_ACCOUNT, accountId)
        .putExtra(EXTRA_CLOUD_NAME, accountName)
        .putExtra(EXTRA_CLOUD_PATH, relPath)
}

/**
 * True when OpenSync itself opened this activity (with startActivityForResult). Android reports the
 * caller, so another app can't pretend to be us to have its own file written to the user's accounts.
 */
internal val Activity.openedByThisApp: Boolean get() = callingPackage == packageName

/** Where the editor was asked to save back to, if anywhere. Only honoured when OpenSync opened it. */
internal fun Activity.cloudOrigin(): CloudOrigin? {
    if (!openedByThisApp) return null
    val i = intent ?: return null
    val id = i.getLongExtra(EXTRA_CLOUD_ACCOUNT, -1L).takeIf { it >= 0 } ?: return null
    val path = i.getStringExtra(EXTRA_CLOUD_PATH)?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
    return CloudOrigin(id, i.getStringExtra(EXTRA_CLOUD_NAME)?.takeIf { it.isNotBlank() } ?: "the account", path)
}

/** Where a saved edit ended up, for the message shown afterwards. */
internal data class SavedEdit(
    val file: File? = null, // written on the phone (the original, a copy, or the fallback when the upload failed)
    val cloudName: String? = null, // uploaded to the original's account under this name
    val overwrote: Boolean = false,
    val uploadError: String? = null // the upload failed; the edit was kept in [file] instead
) {
    /** The folder the edit was kept in on the phone, e.g. "Pictures/OpenSync". */
    val keptIn: String
        get() = file?.parentFile?.let { d -> d.parentFile?.name?.let { "$it/${d.name}" } ?: d.name } ?: "this phone"

    /** E.g. "Copy saved to Google Drive as IMG_1_edited.jpg". [what] is "Photo", "Copy", "Video"… */
    fun message(what: String, cloud: CloudOrigin?): String {
        val account = cloud?.accountName ?: "the account"
        return when {
            uploadError != null -> "Couldn't upload to $account ($uploadError). Saved to $keptIn on this phone instead."
            cloudName != null && overwrote -> "$what saved to $account"
            cloudName != null -> "$what saved to $account as $cloudName"
            file != null && !overwrote -> "$what saved to ${file.parentFile?.name ?: "storage"}"
            else -> "$what saved"
        }
    }
}

/**
 * Says an upload failed and where the edit was kept instead. A dialog rather than a toast: the user
 * needs to know the account doesn't have the edit, and Android cuts toasts to two lines.
 */
@Composable
internal fun UploadFailedDialog(cloud: CloudOrigin?, saved: SavedEdit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Couldn't upload to ${cloud?.accountName ?: "the account"}") },
        text = { Text("The edit was saved on this phone in ${saved.keptIn} instead.\n\n${saved.uploadError}") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

/** "[base].[ext]", or "[base] (1).[ext]", "[base] (2).[ext]"… — the first not already in [taken]. */
internal fun uniqueName(taken: Collection<String>, base: String, ext: String): String {
    val used = taken.mapTo(HashSet()) { it.lowercase() } // many servers ignore case
    var candidate = "$base.$ext"
    var i = 1
    while (candidate.lowercase() in used) { candidate = "$base ($i).$ext"; i++ }
    return candidate
}

/**
 * Uploads [rendered] (a finished edit in a temporary file) to [cloud]'s account: over the original
 * when [overwrite], else as "[copyBase].[ext]" (numbered if taken) in the original's folder. If the
 * upload fails, the edit is kept on the phone in [fallbackDir]/OpenSync instead, so it's never lost.
 */
internal suspend fun deliverToCloud(
    context: Context,
    cloud: CloudOrigin,
    rendered: File,
    overwrite: Boolean,
    copyBase: String,
    ext: String,
    fallbackDir: String,
    onUploading: () -> Unit = {}
): SavedEdit {
    onUploading()
    return try {
        SavedEdit(cloudName = uploadEdit(cloud, rendered, overwrite, copyBase, ext), overwrote = overwrite)
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        val kept = withContext(Dispatchers.IO) {
            val dir = File(Environment.getExternalStoragePublicDirectory(fallbackDir), "OpenSync").apply { mkdirs() }
            uniqueFile(dir, copyBase, ext).also { rendered.copyTo(it, overwrite = true); scanFile(context, it) }
        }
        SavedEdit(file = kept, uploadError = e.message?.take(120) ?: e.javaClass.simpleName)
    }
}

/** Returns the name the edit was uploaded as. Tries twice, the second time on a fresh connection. */
private suspend fun uploadEdit(cloud: CloudOrigin, file: File, overwrite: Boolean, copyBase: String, ext: String): String =
    withContext(Dispatchers.IO) {
        val account = Graph.database.accountDao().getById(cloud.accountId)
            ?: throw IOException("the account was removed")
        var target: String? = if (overwrite) cloud.relPath else null
        var failure: Exception? = null
        repeat(2) {
            val provider = ProviderFactory.forAccount(account, "")
            try {
                provider.connect()
                // Picked once, so a retry finishes the same file rather than starting a second copy.
                val path = target ?: PathUtil.childRel(
                    cloud.folder,
                    uniqueName(provider.listDir(cloud.folder).map { it.name }, copyBase, ext)
                ).also { target = it }
                provider.upload(file, path, System.currentTimeMillis())
                return@withContext PathUtil.name(path)
            } catch (e: Exception) {
                failure = e
            } finally {
                runCatching { provider.close() }
            }
        }
        throw failure ?: IOException("upload failed")
    }
