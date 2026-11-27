package org.skepsun.kototoro.core.prefs

/** Keys that never leave the device in a backup; restore keeps their local values instead of wiping them. */
object BackupSettingsFilter {

    private val EXCLUDED_KEYS = setOf(
        AppSettings.KEY_APP_PASSWORD,
        AppSettings.KEY_PROXY_PASSWORD,
        AppSettings.KEY_PROXY_LOGIN,
        AppSettings.KEY_INCOGNITO_MODE,
        // A webhook URL is a secret: anyone holding it can post to the user's channel. It never
        // leaves the device in a backup, and a restore keeps the local value instead of taking one.
        AppSettings.KEY_TRACKER_WEBHOOK_URL,
        AppSettings.KEY_TRACKER_WEBHOOK_LAST_SENT,
    )

    fun isBackupableKey(key: String): Boolean =
        key !in EXCLUDED_KEYS && key !in AppSettings.DEVICE_LOCAL_KEYS
}
