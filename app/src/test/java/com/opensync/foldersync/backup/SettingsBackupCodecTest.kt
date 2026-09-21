package com.opensync.foldersync.backup

import com.opensync.foldersync.data.Account
import com.opensync.foldersync.data.AccountType
import com.opensync.foldersync.data.ConflictRule
import com.opensync.foldersync.data.FolderPair
import com.opensync.foldersync.data.ScheduleMode
import com.opensync.foldersync.data.SyncDirection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsBackupCodecTest {

    @Test
    fun sealedPayloadOpensWithTheSamePassphrase() {
        val payload = """{"accounts":[],"note":"héllo ✓"}"""
        val file = SettingsBackupCodec.seal(payload, "correct horse".toCharArray())
        assertEquals(payload, SettingsBackupCodec.open(file, "correct horse".toCharArray()))
    }

    @Test
    fun theFileDoesNotContainThePayloadInTheClear() {
        val file = String(SettingsBackupCodec.seal("""{"password":"hunter2"}""", "pw".toCharArray()))
        assertFalse(file.contains("hunter2"))
    }

    @Test
    fun wrongPassphraseIsReportedAsSuch() {
        val file = SettingsBackupCodec.seal("{}", "right".toCharArray())
        assertThrows(SettingsBackupCodec.BadPassphraseException::class.java) {
            SettingsBackupCodec.open(file, "wrong".toCharArray())
        }
    }

    @Test
    fun aFileThatIsNotABackupIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupCodec.open("just some text".toByteArray(), "pw".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupCodec.open("""{"format":"something-else"}""".toByteArray(), "pw".toCharArray())
        }
    }

    @Test
    fun accountSurvivesARoundTripWithItsPlaintextPassword() {
        val account = Account(
            id = 7, name = "NAS", type = AccountType.SMB, host = "vfs4", port = 445,
            username = "rich", passwordEnc = "keystore-ciphertext", basePath = "/storage",
            useTls = true, passiveMode = false, allowSelfSigned = true, domain = "WORKGROUP"
        )
        val json = JSONObject(SettingsBackupCodec.accountToJson(account, "s3cret").toString())
        val (back, password) = SettingsBackupCodec.accountFromJson(json)!!
        // The id is the new phone's to assign, and the old phone's ciphertext is meaningless there.
        assertEquals(account.copy(id = 0, passwordEnc = ""), back)
        assertEquals("s3cret", password)
        assertEquals(7L, json.getLong("id"))
        assertFalse(json.toString().contains("keystore-ciphertext"))
    }

    @Test
    fun anUnknownAccountTypeIsSkippedRatherThanCrashing() {
        assertNull(SettingsBackupCodec.accountFromJson(JSONObject().put("name", "x").put("type", "FLOPPY")))
    }

    @Test
    fun pairSurvivesARoundTripButNotItsSyncHistory() {
        val pair = FolderPair(
            id = 3, name = "Photos", localFolder = "/storage/emulated/0/DCIM", remoteAccountId = 7,
            remoteFolder = "sync/photos", direction = SyncDirection.TO_REMOTE,
            conflictRule = ConflictRule.LOCAL_WINS, deleteOrphans = true, includeSubfolders = false,
            includeFilter = "*.jpg", excludeFilter = ".trash", scheduleMode = ScheduleMode.DAILY,
            scheduleMinutes = 30, dailyHour = 4, dailyMinute = 15, daysOfWeek = 0b0111110,
            requireWifi = false, requireCharging = true, enabled = false,
            lastSyncTime = 123456789, lastStatus = "OK"
        )
        val back = SettingsBackupCodec.pairFromJson(JSONObject(SettingsBackupCodec.pairToJson(pair).toString()))
        assertEquals(pair.copy(id = 0, lastSyncTime = 0, lastStatus = ""), back)
    }

    @Test
    fun aLocalToLocalPairKeepsItsNullAccount() {
        val pair = FolderPair(name = "Mirror", localFolder = "/a", remoteAccountId = null, remoteFolder = "/b")
        val back = SettingsBackupCodec.pairFromJson(JSONObject(SettingsBackupCodec.pairToJson(pair).toString()))
        assertNull(back.remoteAccountId)
    }
}
