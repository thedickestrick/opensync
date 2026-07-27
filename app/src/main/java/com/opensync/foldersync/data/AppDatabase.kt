package com.opensync.foldersync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Account::class, FolderPair::class, SyncLog::class, SyncStateEntry::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun folderPairDao(): FolderPairDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "opensync.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
