package com.opensync.foldersync.vault

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.opensync.foldersync.Graph
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

data class VaultEntry(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val addedAt: Long
)

/** Outcome of moving a file into the vault. [originalRemoved] is false if the source still remains. */
data class ImportResult(val entry: VaultEntry, val originalRemoved: Boolean)

/**
 * The encrypted file vault. Encrypted blobs live in the app's private internal storage (unreadable
 * by other apps); each blob is AES-256-GCM. The master key is held only in memory while unlocked and
 * is wrapped on disk by a PBKDF2 key derived from the passphrase — so a lost passphrase is unrecoverable.
 */
object VaultManager {
    private const val AUTO_LOCK_MS = 120_000L

    private val ctx: Context get() = Graph.appContext
    private val vaultDir: File get() = File(ctx.filesDir, "vault")
    private val blobsDir: File get() = File(vaultDir, "blobs")
    private val metaFile: File get() = File(vaultDir, "vault.json")
    private val indexFile: File get() = File(vaultDir, "index.enc")
    private val openCacheDir: File get() = File(ctx.cacheDir, "vault_open")

    @Volatile private var masterKey: SecretKey? = null
    @Volatile private var backgroundedAt = 0L

    val isUnlocked: Boolean get() = masterKey != null
    fun exists(): Boolean = metaFile.exists()

    /** Create a brand-new vault protected by [password]; leaves it unlocked. */
    fun create(password: CharArray) {
        require(!exists()) { "Vault already exists" }
        blobsDir.mkdirs()
        val salt = VaultCrypto.randomBytes(VaultCrypto.SALT_LEN)
        val kek = VaultCrypto.deriveKey(password, salt)
        val dek = VaultCrypto.randomAesKey()
        val meta = JSONObject()
            .put("version", 1)
            .put("salt", VaultCrypto.b64(salt))
            .put("iterations", VaultCrypto.PBKDF2_ITERATIONS)
            .put("wrappedKey", VaultCrypto.b64(VaultCrypto.seal(kek, dek.encoded)))
        metaFile.writeText(meta.toString())
        masterKey = dek
        saveIndex(emptyList())
    }

    /** @return true if [password] was correct and the vault is now unlocked. */
    fun unlock(password: CharArray): Boolean {
        if (!exists()) return false
        val meta = JSONObject(metaFile.readText())
        val salt = VaultCrypto.unb64(meta.getString("salt"))
        val iterations = meta.optInt("iterations", VaultCrypto.PBKDF2_ITERATIONS)
        val wrapped = VaultCrypto.unb64(meta.getString("wrappedKey"))
        val kek = VaultCrypto.deriveKey(password, salt, iterations)
        return try {
            masterKey = SecretKeySpec(VaultCrypto.open(kek, wrapped), "AES")
            true
        } catch (e: Exception) {
            false // GCM tag mismatch == wrong passphrase
        }
    }

    fun lock() {
        masterKey = null
        backgroundedAt = 0L
        runCatching { openCacheDir.deleteRecursively() }
    }

    fun markBackgrounded() { if (isUnlocked) backgroundedAt = System.currentTimeMillis() }

    /** Auto-lock if the app has been in the background longer than [AUTO_LOCK_MS]. */
    fun autoLockIfStale() {
        if (isUnlocked && backgroundedAt > 0 && System.currentTimeMillis() - backgroundedAt > AUTO_LOCK_MS) {
            lock()
        }
        backgroundedAt = 0L
    }

    fun listEntries(): List<VaultEntry> {
        val key = masterKey ?: return emptyList()
        if (!indexFile.exists()) return emptyList()
        val json = String(decryptBytes(key, indexFile.readBytes()))
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VaultEntry(
                id = o.getString("id"),
                name = o.getString("name"),
                size = o.getLong("size"),
                mime = o.optString("mime", "application/octet-stream"),
                addedAt = o.optLong("addedAt", 0)
            )
        }.sortedByDescending { it.addedAt }
    }

    /**
     * Move the file behind [uri] into the vault: encrypt + record it, then delete the plaintext
     * original (the whole point of a vault). [ImportResult.originalRemoved] is false if the source
     * couldn't be deleted, so the caller can warn the user.
     */
    fun importFile(uri: Uri): ImportResult {
        val key = masterKey ?: error("Vault is locked")
        blobsDir.mkdirs()
        val name = queryName(uri)
        val mime = ctx.contentResolver.getType(uri) ?: guessMime(name)
        val id = UUID.randomUUID().toString()
        val blob = File(blobsDir, id)
        val size = ctx.contentResolver.openInputStream(uri)?.use { input ->
            blob.outputStream().use { out -> VaultCrypto.encryptStream(key, input, out) }
        } ?: run { blob.delete(); error("Could not read the selected file") }
        val entry = VaultEntry(id, name, size, mime, System.currentTimeMillis())
        saveIndex(listEntries() + entry)
        // Only delete the original after the encrypted copy is safely stored above.
        val removed = runCatching { deleteOriginal(uri) }.getOrDefault(false)
        return ImportResult(entry, removed)
    }

    /** Delete the source file a picked [uri] points at. Uses the real path when we can find it. */
    private fun deleteOriginal(uri: Uri): Boolean {
        if (uri.scheme == "file") return uri.path?.let { File(it).delete() } ?: false
        resolveFsPath(uri)?.let { path ->
            val f = File(path)
            if (f.exists() && f.delete()) return true
        }
        // Last resort: ask the document provider to delete it (needs a write grant on the document).
        return runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }.getOrDefault(false)
    }

    /** Best-effort resolve of a content [uri] to an on-disk path (we hold All-Files access). */
    private fun resolveFsPath(uri: Uri): String? {
        runCatching {
            if (DocumentsContract.isDocumentUri(ctx, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":", limit = 2)
                if (split.size == 2) {
                    val (type, rel) = split
                    // Some providers hand back an absolute path directly (e.g. "raw:/storage/…").
                    if (rel.startsWith("/") && File(rel).exists()) return rel
                    if (type.equals("primary", ignoreCase = true)) {
                        return Environment.getExternalStorageDirectory().absolutePath + "/" + rel
                    }
                    if (File("/storage/$type").exists()) return "/storage/$type/$rel"
                }
            }
        }
        // MediaStore-style _data column.
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex("_data")
                    if (i >= 0 && !c.isNull(i)) c.getString(i) else null
                } else null
            }
        }.getOrNull()
    }

    /** Decrypt an entry straight to a caller-provided stream (e.g. an export target). */
    fun exportTo(entry: VaultEntry, out: OutputStream) {
        val key = masterKey ?: error("Vault is locked")
        File(blobsDir, entry.id).inputStream().use { input -> VaultCrypto.decryptStream(key, input, out) }
    }

    /**
     * Decrypt to a private cache file so a viewer can open it. Each entry gets its own subfolder so a
     * media viewer's folder-swipe can't wander into other decrypted vault files. Cache is wiped on lock.
     */
    fun decryptToCache(entry: VaultEntry): File {
        val key = masterKey ?: error("Vault is locked")
        val safe = entry.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
        val dir = File(openCacheDir, entry.id).apply { mkdirs() }
        val out = File(dir, safe)
        if (out.length() == 0L) {
            File(blobsDir, entry.id).inputStream().use { input ->
                out.outputStream().use { o -> VaultCrypto.decryptStream(key, input, o) }
            }
        }
        return out
    }

    fun deleteEntry(entry: VaultEntry) {
        masterKey ?: return
        File(blobsDir, entry.id).delete()
        saveIndex(listEntries().filter { it.id != entry.id })
    }

    /** Re-wrap the master key under a new passphrase (does not touch any blobs). */
    fun changePassword(current: CharArray, new: CharArray): Boolean {
        if (!unlock(current)) return false
        val dek = masterKey ?: return false
        val salt = VaultCrypto.randomBytes(VaultCrypto.SALT_LEN)
        val kek = VaultCrypto.deriveKey(new, salt)
        val meta = JSONObject(metaFile.readText())
            .put("salt", VaultCrypto.b64(salt))
            .put("iterations", VaultCrypto.PBKDF2_ITERATIONS)
            .put("wrappedKey", VaultCrypto.b64(VaultCrypto.seal(kek, dek.encoded)))
        metaFile.writeText(meta.toString())
        return true
    }

    private fun saveIndex(entries: List<VaultEntry>) {
        val key = masterKey ?: error("Vault is locked")
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id).put("name", e.name).put("size", e.size)
                    .put("mime", e.mime).put("addedAt", e.addedAt)
            )
        }
        vaultDir.mkdirs()
        indexFile.writeBytes(encryptBytes(key, arr.toString().toByteArray()))
    }

    private fun encryptBytes(key: SecretKey, data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        VaultCrypto.encryptStream(key, data.inputStream(), bos)
        return bos.toByteArray()
    }

    private fun decryptBytes(key: SecretKey, data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        VaultCrypto.decryptStream(key, data.inputStream(), bos)
        return bos.toByteArray()
    }

    private fun queryName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) c.getString(i)?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun guessMime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
}
