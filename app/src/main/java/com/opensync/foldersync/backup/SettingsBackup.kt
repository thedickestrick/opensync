package com.opensync.foldersync.backup

import com.opensync.foldersync.Graph
import com.opensync.foldersync.crypto.CryptoManager
import com.opensync.foldersync.update.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saves accounts, folder pairs and app settings to one passphrase-protected file and loads them
 * again — so moving to a new phone doesn't mean typing every server and schedule in by hand.
 *
 * Not included: sync history, the vault (it has its own files and passphrase) and home-screen widgets.
 */
object SettingsBackup {

    data class ImportResult(val accounts: Int, val pairs: Int, val skippedPairs: Int) {
        fun summary(): String = buildString {
            append("Imported $accounts account(s) and $pairs folder pair(s)")
            if (skippedPairs > 0) append("; skipped $skippedPairs pair(s) whose account wasn't in the backup")
        }
    }

    suspend fun export(passphrase: CharArray): ByteArray = withContext(Dispatchers.IO) {
        val db = Graph.database
        val prefs = AppPrefs(Graph.appContext)
        val accounts = JSONArray()
        db.accountDao().getAll().forEach {
            accounts.put(SettingsBackupCodec.accountToJson(it, CryptoManager.decrypt(it.passwordEnc)))
        }
        val pairs = JSONArray()
        db.folderPairDao().getAll().forEach { pairs.put(SettingsBackupCodec.pairToJson(it)) }
        val payload = JSONObject()
            .put("accounts", accounts)
            .put("pairs", pairs)
            .put(
                "prefs",
                JSONObject()
                    .put("updateOwner", prefs.updateOwner)
                    .put("updateRepo", prefs.updateRepo)
                    .put("notesDir", prefs.notesDir)
                    .put("pinnedNotes", JSONArray(prefs.pinnedNotes.toList()))
            )
        SettingsBackupCodec.seal(payload.toString(), passphrase)
    }

    /**
     * Merges a backup into this phone: an account with the same type and name — or a pair with the
     * same name — is updated in place, everything else is added. Nothing is ever deleted.
     */
    suspend fun restore(file: ByteArray, passphrase: CharArray): ImportResult = withContext(Dispatchers.IO) {
        val payload = JSONObject(SettingsBackupCodec.open(file, passphrase))
        val db = Graph.database

        // Accounts first: pairs point at them by id, and ids are different on this phone.
        val existingAccounts = db.accountDao().getAll()
        val idMap = HashMap<Long, Long>()
        var accountCount = 0
        val accounts = payload.optJSONArray("accounts") ?: JSONArray()
        for (i in 0 until accounts.length()) {
            val o = accounts.getJSONObject(i)
            val (parsed, password) = SettingsBackupCodec.accountFromJson(o) ?: continue
            val account = parsed.copy(passwordEnc = CryptoManager.encrypt(password))
            val match = existingAccounts.firstOrNull { it.type == account.type && it.name == account.name }
            val newId = if (match != null) {
                db.accountDao().update(account.copy(id = match.id))
                match.id
            } else {
                db.accountDao().insert(account)
            }
            idMap[o.optLong("id")] = newId
            accountCount++
        }

        val existingPairs = db.folderPairDao().getAll()
        var pairCount = 0
        var skipped = 0
        val pairs = payload.optJSONArray("pairs") ?: JSONArray()
        for (i in 0 until pairs.length()) {
            val parsed = SettingsBackupCodec.pairFromJson(pairs.getJSONObject(i))
            val accountId = parsed.remoteAccountId?.let { idMap[it] }
            if (parsed.remoteAccountId != null && accountId == null) { skipped++; continue }
            val pair = parsed.copy(remoteAccountId = accountId)
            val match = existingPairs.firstOrNull { it.name == pair.name }
            if (match != null) {
                db.folderPairDao().update(
                    pair.copy(id = match.id, lastSyncTime = match.lastSyncTime, lastStatus = match.lastStatus)
                )
            } else {
                db.folderPairDao().insert(pair)
            }
            pairCount++
        }

        payload.optJSONObject("prefs")?.let { p ->
            val prefs = AppPrefs(Graph.appContext)
            p.optString("updateOwner").takeIf { it.isNotBlank() }?.let { prefs.updateOwner = it }
            p.optString("updateRepo").takeIf { it.isNotBlank() }?.let { prefs.updateRepo = it }
            p.optString("notesDir").takeIf { it.isNotBlank() }?.let { prefs.notesDir = it }
            p.optJSONArray("pinnedNotes")?.let { arr ->
                prefs.pinnedNotes = prefs.pinnedNotes + (0 until arr.length()).map { arr.getString(it) }
            }
        }

        Graph.syncManager.rescheduleAll()
        ImportResult(accountCount, pairCount, skipped)
    }
}
