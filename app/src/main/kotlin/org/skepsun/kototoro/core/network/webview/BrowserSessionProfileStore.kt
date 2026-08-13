package org.skepsun.kototoro.core.network.webview

import android.content.Context

internal class BrowserSessionProfileStore(context: Context) {

	private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

	fun storedVersion(): Int = preferences.getInt(PROFILE_VERSION_KEY, NO_PROFILE_VERSION)

	fun markMigrated(version: Int): Boolean = preferences.edit()
		.putInt(PROFILE_VERSION_KEY, version)
		.commit()

	companion object {
		internal const val NO_PROFILE_VERSION = 0
		private const val PREFERENCES_NAME = "browser_session_profiles"
		private const val PROFILE_VERSION_KEY = "chromium_profile_version"

		internal fun requiresMigration(storedVersion: Int, currentVersion: Int): Boolean =
			storedVersion < currentVersion
	}
}
