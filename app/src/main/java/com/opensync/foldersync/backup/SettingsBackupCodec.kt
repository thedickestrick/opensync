package com.opensync.foldersync.backup

import com.opensync.foldersync.data.Account
import com.opensync.foldersync.data.AccountType
import com.opensync.foldersync.data.ConflictRule
import com.opensync.foldersync.data.FolderPair
import com.opensync.foldersync.data.ScheduleMode
import com.opensync.foldersync.data.SyncDirection
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The on-disk format of a settings backup, with nothing Android-specific in it.
 *
 * Account passwords live in the database encrypted by an Android Keystore key that never leaves the
 * phone, so a backup can't just copy them: it carries the plaintext, and the whole payload is sealed
 * with AES-256-GCM under a key derived (PBKDF2) from a passphrase the user picks.
 */
object SettingsBackupCodec {
    private const val FORMAT = "opensync-settings"
    private const val VERSION = 1
    private const val ITERATIONS = 120_000
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128

    class BadPassphraseException : Exception("Wrong passphrase, or the backup file is damaged")

    /** Seal [payload] under [passphrase]; the result is a small self-describing JSON envelope. */
    fun seal(payload: String, passphrase: CharArray): ByteArray {
        val rng = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, nonce))
        val sealed = nonce + cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("format", FORMAT)
            .put("v", VERSION)
            .put("iter", ITERATIONS)
            .put("salt", Base64.getEncoder().encodeToString(salt))
            .put("data", Base64.getEncoder().encodeToString(sealed))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    /** Reverse of [seal]. Throws [BadPassphraseException] on a wrong passphrase or a tampered file. */
    fun open(file: ByteArray, passphrase: CharArray): String {
        val env = runCatching { JSONObject(String(file, Charsets.UTF_8)) }.getOrNull()
        if (env == null || env.optString("format") != FORMAT) {
            throw IllegalArgumentException("This isn't an OpenSync settings backup")
        }
        if (env.optInt("v") > VERSION) {
            throw IllegalArgumentException("This backup was made by a newer version of OpenSync")
        }
        val salt = Base64.getDecoder().decode(env.getString("salt"))
        val sealed = Base64.getDecoder().decode(env.getString("data"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(passphrase, salt, env.getInt("iter")),
            GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, NONCE_LEN))
        )
        return try {
            String(cipher.doFinal(sealed.copyOfRange(NONCE_LEN, sealed.size)), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw BadPassphraseException()
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** [password] is the plaintext; the row's own [Account.passwordEnc] is useless off this phone. */
    fun accountToJson(a: Account, password: String): JSONObject = JSONObject()
        .put("id", a.id)
        .put("name", a.name)
        .put("type", a.type.name)
        .put("host", a.host)
        .put("port", a.port)
        .put("username", a.username)
        .put("password", password)
        .put("basePath", a.basePath)
        .put("useTls", a.useTls)
        .put("passiveMode", a.passiveMode)
        .put("allowSelfSigned", a.allowSelfSigned)
        .put("domain", a.domain)

    /**
     * Returns the account (id 0, [Account.passwordEnc] blank) and its plaintext password, or null for
     * an account type this version doesn't know.
     */
    fun accountFromJson(o: JSONObject): Pair<Account, String>? {
        val type = runCatching { AccountType.valueOf(o.getString("type")) }.getOrNull() ?: return null
        val d = Account(name = "", type = type)
        return Account(
            name = o.getString("name"),
            type = type,
            host = o.optString("host", d.host),
            port = o.optInt("port", d.port),
            username = o.optString("username", d.username),
            basePath = o.optString("basePath", d.basePath),
            useTls = o.optBoolean("useTls", d.useTls),
            passiveMode = o.optBoolean("passiveMode", d.passiveMode),
            allowSelfSigned = o.optBoolean("allowSelfSigned", d.allowSelfSigned),
            domain = o.optString("domain", d.domain)
        ) to o.optString("password", "")
    }

    fun pairToJson(p: FolderPair): JSONObject = JSONObject()
        .put("name", p.name)
        .put("localFolder", p.localFolder)
        .put("remoteAccountId", p.remoteAccountId ?: JSONObject.NULL)
        .put("remoteFolder", p.remoteFolder)
        .put("direction", p.direction.name)
        .put("conflictRule", p.conflictRule.name)
        .put("deleteOrphans", p.deleteOrphans)
        .put("includeSubfolders", p.includeSubfolders)
        .put("includeFilter", p.includeFilter)
        .put("excludeFilter", p.excludeFilter)
        .put("scheduleMode", p.scheduleMode.name)
        .put("scheduleMinutes", p.scheduleMinutes)
        .put("dailyHour", p.dailyHour)
        .put("dailyMinute", p.dailyMinute)
        .put("daysOfWeek", p.daysOfWeek)
        .put("requireWifi", p.requireWifi)
        .put("requireCharging", p.requireCharging)
        .put("enabled", p.enabled)

    /** The pair as backed up: id 0, never synced, and [FolderPair.remoteAccountId] still the *old* phone's id. */
    fun pairFromJson(o: JSONObject): FolderPair {
        val d = FolderPair(name = "", localFolder = "")
        return FolderPair(
            name = o.getString("name"),
            localFolder = o.getString("localFolder"),
            remoteAccountId = if (o.isNull("remoteAccountId")) null else o.getLong("remoteAccountId"),
            remoteFolder = o.optString("remoteFolder", d.remoteFolder),
            direction = enumOr(o.optString("direction"), d.direction),
            conflictRule = enumOr(o.optString("conflictRule"), d.conflictRule),
            deleteOrphans = o.optBoolean("deleteOrphans", d.deleteOrphans),
            includeSubfolders = o.optBoolean("includeSubfolders", d.includeSubfolders),
            includeFilter = o.optString("includeFilter", d.includeFilter),
            excludeFilter = o.optString("excludeFilter", d.excludeFilter),
            scheduleMode = enumOr(o.optString("scheduleMode"), d.scheduleMode),
            scheduleMinutes = o.optInt("scheduleMinutes", d.scheduleMinutes),
            dailyHour = o.optInt("dailyHour", d.dailyHour),
            dailyMinute = o.optInt("dailyMinute", d.dailyMinute),
            daysOfWeek = o.optInt("daysOfWeek", d.daysOfWeek),
            requireWifi = o.optBoolean("requireWifi", d.requireWifi),
            requireCharging = o.optBoolean("requireCharging", d.requireCharging),
            enabled = o.optBoolean("enabled", d.enabled)
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(fallback)
}
