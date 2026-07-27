package com.opensync.foldersync.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A record of a single completed (or failed) sync run, shown in the Sync log screen. */
@Entity(tableName = "sync_logs")
data class SyncLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pairId: Long,
    val pairName: String,
    val startTime: Long,
    val endTime: Long,
    val success: Boolean,
    val filesCopied: Int,
    val filesDeleted: Int,
    val conflicts: Int,
    val bytesTransferred: Long,
    val message: String
)

@Dao
interface SyncLogDao {
    @Query("SELECT * FROM sync_logs ORDER BY startTime DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<SyncLog>>

    @Insert
    suspend fun insert(log: SyncLog): Long

    @Query("DELETE FROM sync_logs")
    suspend fun clear()

    @Query("DELETE FROM sync_logs WHERE pairId = :pairId")
    suspend fun clearForPair(pairId: Long)
}
