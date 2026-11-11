package org.skepsun.kototoro.core.prefs

/** Keys that never leave the device in a backup; restore keeps their local values instead of wiping them. */
object BackupSettingsFilter {

    private val EXCLUDED_KEYS = setOf(
        AppSettings.KEY_APP_PASSWORD,
        AppSettings.KEY_PROXY_PASSWORD,
        AppSettings.KEY_PROXY_LOGIN,
        AppSettings.KEY_INCOGNITO_MODE,
    )

    fun isBackupableKey(key: String): Boolean =
        key !in EXCLUDED_KEYS && key !in AppSettings.DEVICE_LOCAL_KEYS
}
