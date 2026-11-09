package org.skepsun.kototoro.backups.data

import org.skepsun.kototoro.core.prefs.AppSettings

object BackupSettingsFilter {

    private val SECRET_PARTS = listOf(
        "password",
        "token",
        "secret",
        "api_key",
        "cookie",
        "device_id",
        "custom_headers",
        "bearer",
    )

    private val LOCAL_SUFFIXES = listOf(
        "_dir",
        "_dirs",
        "_dir_uri",
        "_dir_uris",
        "_uri",
        "_uris",
        "_path",
        "_storage",
    )

    private val EXCLUDED_KEYS = setOf(
        AppSettings.KEY_APP_PASSWORD,
        AppSettings.KEY_PROXY_PASSWORD,
        AppSettings.KEY_PROXY_LOGIN,
        AppSettings.KEY_INCOGNITO_MODE,
        AppSettings.KEY_BACKUP_DEVICE_ID,
    )

    fun isBackupableKey(key: String): Boolean {
        if (key in EXCLUDED_KEYS || key in AppSettings.DEVICE_LOCAL_KEYS) return false
        val lower = key.lowercase()
        if (SECRET_PARTS.any { lower.contains(it) }) return false
        return LOCAL_SUFFIXES.none { lower.endsWith(it) }
    }
}
