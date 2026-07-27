package com.opensync.foldersync.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class AccountType(val label: String) {
    LOCAL("Local device"),
    FTP("FTP"),
    FTPS("FTP over TLS (FTPS)"),
    SFTP("SFTP (SSH)"),
    WEBDAV("WebDAV"),
    SMB("SMB / Windows share")
}

/**
 * A remote (or local) storage target that folder pairs sync against.
 * For [AccountType.LOCAL] only [basePath] is used. Passwords are stored encrypted
 * (see [com.opensync.foldersync.crypto.CryptoManager]).
 */
@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val passwordEnc: String = "",
    val basePath: String = "/",
    val useTls: Boolean = false,
    val passiveMode: Boolean = true,
    val allowSelfSigned: Boolean = false,
    val domain: String = ""
) {
    fun defaultPort(): Int = when (type) {
        AccountType.FTP, AccountType.FTPS -> 21
        AccountType.SFTP -> 22
        AccountType.WEBDAV -> if (useTls) 443 else 80
        AccountType.SMB -> 445
        AccountType.LOCAL -> 0
    }
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Account>>

    @Query("SELECT * FROM accounts ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<Account>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): Account?

    @Insert
    suspend fun insert(account: Account): Long

    @Update
    suspend fun update(account: Account)

    @Delete
    suspend fun delete(account: Account)
}
