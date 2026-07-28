package com.opensync.foldersync.provider

import com.opensync.foldersync.crypto.CryptoManager
import com.opensync.foldersync.data.Account
import com.opensync.foldersync.data.AccountType
import com.opensync.foldersync.util.PathUtil

/** Builds a [StorageProvider] rooted at the folder used by a particular sync. */
object ProviderFactory {

    fun forLocal(absolutePath: String): StorageProvider = LocalProvider(absolutePath)

    /**
     * @param account the remote target
     * @param subPath the folder (relative to the account's base path / URL) to sync against
     */
    fun forAccount(account: Account, subPath: String): StorageProvider {
        val password = CryptoManager.decrypt(account.passwordEnc)
        val port = if (account.port > 0) account.port else account.defaultPort()
        return when (account.type) {
            AccountType.LOCAL ->
                LocalProvider(PathUtil.join(account.basePath, subPath))

            AccountType.FTP ->
                FtpProvider(
                    host = account.host, port = port, username = account.username,
                    password = password, rootPath = PathUtil.join(account.basePath, subPath),
                    useTls = false, passiveMode = account.passiveMode,
                    allowSelfSigned = account.allowSelfSigned
                )

            AccountType.FTPS ->
                FtpProvider(
                    host = account.host, port = port, username = account.username,
                    password = password, rootPath = PathUtil.join(account.basePath, subPath),
                    useTls = true, passiveMode = account.passiveMode,
                    allowSelfSigned = account.allowSelfSigned
                )

            AccountType.SFTP ->
                SftpProvider(
                    host = account.host, port = port, username = account.username,
                    password = password, rootPath = PathUtil.join(account.basePath, subPath)
                )

            AccountType.SMB ->
                SmbProvider(
                    host = account.host, port = port, domain = account.domain,
                    username = account.username, password = password,
                    rootPath = PathUtil.join(account.basePath, subPath)
                )

            AccountType.WEBDAV -> {
                val hostUrl = ensureScheme(account.host, account.useTls)
                val baseUrl = PathUtil.joinUrl(PathUtil.joinUrl(hostUrl, account.basePath), subPath)
                WebDavProvider(baseUrl, account.username, password, account.allowSelfSigned)
            }

            AccountType.S3 ->
                S3Provider(
                    endpoint = account.host,
                    region = account.domain.ifBlank { "us-east-1" },
                    accessKey = account.username,
                    secretKey = password,
                    bucket = account.basePath.trim('/'),
                    prefix = subPath,
                    useTls = account.useTls,
                    allowSelfSigned = account.allowSelfSigned
                )

            AccountType.DROPBOX ->
                DropboxProvider(
                    appKey = account.username,
                    refreshToken = password,
                    basePath = PathUtil.join(account.basePath, subPath)
                )

            AccountType.ONEDRIVE ->
                OneDriveProvider(
                    clientId = account.username,
                    refreshToken = password,
                    basePath = PathUtil.join(account.basePath, subPath)
                )
        }
    }

    private fun ensureScheme(host: String, useTls: Boolean): String {
        val h = host.trim()
        return when {
            h.startsWith("http://", true) || h.startsWith("https://", true) -> h
            useTls -> "https://$h"
            else -> "http://$h"
        }
    }
}
