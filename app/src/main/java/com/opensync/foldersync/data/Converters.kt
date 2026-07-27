package com.opensync.foldersync.data

import androidx.room.TypeConverter

/** Stores enum columns as their name strings. */
class Converters {
    @TypeConverter fun toAccountType(v: String): AccountType = AccountType.valueOf(v)
    @TypeConverter fun fromAccountType(v: AccountType): String = v.name

    @TypeConverter fun toSyncDirection(v: String): SyncDirection = SyncDirection.valueOf(v)
    @TypeConverter fun fromSyncDirection(v: SyncDirection): String = v.name

    @TypeConverter fun toConflictRule(v: String): ConflictRule = ConflictRule.valueOf(v)
    @TypeConverter fun fromConflictRule(v: ConflictRule): String = v.name

    @TypeConverter fun toScheduleMode(v: String): ScheduleMode = ScheduleMode.valueOf(v)
    @TypeConverter fun fromScheduleMode(v: ScheduleMode): String = v.name
}
