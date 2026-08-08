package com.opensync.foldersync.provider

import com.opensync.foldersync.Graph
import com.opensync.foldersync.crypto.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Writes a rotated OAuth refresh token back onto its account row.
 *
 * Providers hold the refresh token they were built with; if the provider ever hands back a *new*
 * one, the stored copy is stale and the account would die the next time the app restarts. Saving is
 * best-effort and happens off the caller's thread — the in-memory provider keeps working either way.
 */
object OAuthTokenStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun saveRefreshToken(accountId: Long, refreshToken: String) {
        if (accountId <= 0L || refreshToken.isBlank()) return
        scope.launch {
            runCatching {
                val dao = Graph.database.accountDao()
                val account = dao.getById(accountId) ?: return@runCatching
                // Ciphertext differs on every encrypt (random IV), so compare the plaintext.
                if (CryptoManager.decrypt(account.passwordEnc) == refreshToken) return@runCatching
                dao.update(account.copy(passwordEnc = CryptoManager.encrypt(refreshToken)))
            }
        }
    }
}
