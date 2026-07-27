package com.opensync.foldersync.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Account::class, FolderPair::class, SyncLog::class, SyncStateEntry::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun folderPairDao(): FolderPairDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        /** Adds time-of-day scheduling columns; keeps existing interval pairs working. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folder_pairs ADD COLUMN scheduleMode TEXT NOT NULL DEFAULT 'MANUAL'")
                db.execSQL("ALTER TABLE folder_pairs ADD COLUMN dailyHour INTEGER NOT NULL DEFAULT 2")
                db.execSQL("ALTER TABLE folder_pairs ADD COLUMN dailyMinute INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE folder_pairs ADD COLUMN daysOfWeek INTEGER NOT NULL DEFAULT 127")
                db.execSQL("UPDATE folder_pairs SET scheduleMode = 'INTERVAL' WHERE scheduleMinutes > 0")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "opensync.db")
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
    }
}
