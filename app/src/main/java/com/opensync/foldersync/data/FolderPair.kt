package com.opensync.foldersync.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class SyncDirection(val label: String) {
    TO_REMOTE("To remote (upload)"),
    FROM_REMOTE("From remote (download)"),
    TWO_WAY("Two-way")
}

enum class ConflictRule(val label: String) {
    NEWER_WINS("Newer file wins"),
    LOCAL_WINS("Local wins"),
    REMOTE_WINS("Remote wins"),
    SKIP("Skip conflicts")
}

enum class ScheduleMode(val label: String) {
    MANUAL("Manual only"),
    INTERVAL("Repeat every…"),
    DAILY("Daily at a set time")
}

/**
 * A configured synchronization between a local device folder and a remote target.
 * When [remoteAccountId] is null, the "remote" side is another folder on the device
 * (local ↔ local sync) and [remoteFolder] is an absolute device path.
 */
@Entity(tableName = "folder_pairs")
data class FolderPair(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val localFolder: String,
    val remoteAccountId: Long? = null,
    val remoteFolder: String = "",
    val direction: SyncDirection = SyncDirection.TWO_WAY,
    val conflictRule: ConflictRule = ConflictRule.NEWER_WINS,
    val deleteOrphans: Boolean = false,
    val includeSubfolders: Boolean = true,
    val includeFilter: String = "",
    val excludeFilter: String = "",
    val scheduleMode: ScheduleMode = ScheduleMode.MANUAL,
    val scheduleMinutes: Int = 0,
    /** DAILY: hour (0–23) and minute (0–59) of the daily run. */
    val dailyHour: Int = 2,
    val dailyMinute: Int = 0,
    /** DAILY: bitmask of allowed weekdays, bit i where i = Calendar.DAY_OF_WEEK - 1 (0=Sun … 6=Sat). 127 = every day. */
    val daysOfWeek: Int = 0b1111111,
    val requireWifi: Boolean = true,
    val requireCharging: Boolean = false,
    val enabled: Boolean = true,
    val lastSyncTime: Long = 0,
    val lastStatus: String = ""
)

@Dao
interface FolderPairDao {
    @Query("SELECT * FROM folder_pairs ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderPair>>

    @Query("SELECT * FROM folder_pairs WHERE enabled = 1 AND scheduleMinutes > 0")
    suspend fun getScheduled(): List<FolderPair>

    @Query("SELECT * FROM folder_pairs")
    suspend fun getAll(): List<FolderPair>

    @Query("SELECT * FROM folder_pairs WHERE id = :id")
    suspend fun getById(id: Long): FolderPair?

    @Query("SELECT * FROM folder_pairs WHERE id = :id")
    fun observeById(id: Long): Flow<FolderPair?>

    @Insert
    suspend fun insert(pair: FolderPair): Long

    @Update
    suspend fun update(pair: FolderPair)

    @Delete
    suspend fun delete(pair: FolderPair)

    @Query("UPDATE folder_pairs SET lastSyncTime = :time, lastStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, time: Long, status: String)
}
