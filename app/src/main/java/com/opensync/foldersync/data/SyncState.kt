package com.opensync.foldersync.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Snapshot of each file's size/mtime on both sides at the end of the last successful
 * sync. Two-way sync compares the current state against this to tell which side changed
 * (or whether a file was deleted), instead of naively comparing the two sides to each other.
 */
@Entity(tableName = "sync_state", primaryKeys = ["pairId", "relPath"])
data class SyncStateEntry(
    val pairId: Long,
    val relPath: String,
    val localSize: Long,
    val localMtime: Long,
    val remoteSize: Long,
    val remoteMtime: Long
)

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE pairId = :pairId")
    suspend fun getForPair(pairId: Long): List<SyncStateEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<SyncStateEntry>)

    @Query("DELETE FROM sync_state WHERE pairId = :pairId")
    suspend fun deleteForPair(pairId: Long)

    @Transaction
    suspend fun replaceForPair(pairId: Long, entries: List<SyncStateEntry>) {
        deleteForPair(pairId)
        if (entries.isNotEmpty()) insertAll(entries)
    }
}
