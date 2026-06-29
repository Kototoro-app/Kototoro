package org.skepsun.kototoro.sync.google.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onStart
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleDriveSyncSettings @Inject constructor(
	@ApplicationContext context: Context,
) {

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	var accountEmail: String?
		get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)
		set(value) = prefs.edit { putString(KEY_ACCOUNT_EMAIL, value) }

	var accountName: String?
		get() = prefs.getString(KEY_ACCOUNT_NAME, null)
		set(value) = prefs.edit { putString(KEY_ACCOUNT_NAME, value) }

	var googleAccountName: String?
		get() = prefs.getString(KEY_GOOGLE_ACCOUNT_NAME, null)
		set(value) = prefs.edit { putString(KEY_GOOGLE_ACCOUNT_NAME, value) }

	val isSignedIn: Boolean
		get() = !accountEmail.isNullOrBlank()

	var isSyncEnabled: Boolean
		get() = prefs.getBoolean(KEY_SYNC_ENABLED, true)
		set(value) = prefs.edit { putBoolean(KEY_SYNC_ENABLED, value) }

	val deviceId: String
		get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
			prefs.edit { putString(KEY_DEVICE_ID, it) }
		}

	var intervalMinutes: Int
		get() = prefs.getString(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES.toString())?.toIntOrNull()
			?: DEFAULT_INTERVAL_MINUTES
		set(value) = prefs.edit { putString(KEY_INTERVAL_MINUTES, value.coerceAtLeast(0).toString()) }

	var isWifiOnly: Boolean
		get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
		set(value) = prefs.edit { putBoolean(KEY_WIFI_ONLY, value) }

	var isSyncOnStart: Boolean
		get() = prefs.getBoolean(KEY_SYNC_ON_START, true)
		set(value) = prefs.edit { putBoolean(KEY_SYNC_ON_START, value) }

	var lastSyncTimestamp: Long
		get() = prefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)
		set(value) = prefs.edit { putLong(KEY_LAST_SYNC_TIMESTAMP, value) }

	var lastSyncAttemptTimestamp: Long
		get() = prefs.getLong(KEY_LAST_SYNC_ATTEMPT_TIMESTAMP, 0L)
		set(value) = prefs.edit { putLong(KEY_LAST_SYNC_ATTEMPT_TIMESTAMP, value) }

	var lastSyncError: String?
		get() = prefs.getString(KEY_LAST_SYNC_ERROR, null)
		set(value) = prefs.edit { putString(KEY_LAST_SYNC_ERROR, value) }

	var isDirty: Boolean
		get() = prefs.getBoolean(KEY_DIRTY, false)
		set(value) = prefs.edit { putBoolean(KEY_DIRTY, value) }

	fun changes(vararg keys: String): Flow<String?> {
		val observedKeys = keys.toSet()
		return callbackFlow {
			val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
				if (observedKeys.isEmpty() || key in observedKeys) {
					trySend(key)
				}
			}
			prefs.registerOnSharedPreferenceChangeListener(listener)
			awaitClose {
				prefs.unregisterOnSharedPreferenceChangeListener(listener)
			}
		}.onStart { emit(null) }
	}

	fun clearAccount() = prefs.edit {
		remove(KEY_ACCOUNT_EMAIL)
		remove(KEY_ACCOUNT_NAME)
		remove(KEY_GOOGLE_ACCOUNT_NAME)
		remove(KEY_LAST_SYNC_TIMESTAMP)
		remove(KEY_LAST_SYNC_ATTEMPT_TIMESTAMP)
		remove(KEY_LAST_SYNC_ERROR)
		remove(KEY_DIRTY)
	}

	companion object {

		private const val PREFS_NAME = "google_drive_sync"
		private const val KEY_ACCOUNT_EMAIL = "account_email"
		private const val KEY_ACCOUNT_NAME = "account_name"
		private const val KEY_GOOGLE_ACCOUNT_NAME = "google_account_name"
		const val KEY_SYNC_ENABLED = "sync_enabled"
		private const val KEY_DEVICE_ID = "device_id"
		const val KEY_INTERVAL_MINUTES = "interval_minutes"
		const val KEY_WIFI_ONLY = "wifi_only"
		const val KEY_SYNC_ON_START = "sync_on_start"
		private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
		private const val KEY_LAST_SYNC_ATTEMPT_TIMESTAMP = "last_sync_attempt_timestamp"
		private const val KEY_LAST_SYNC_ERROR = "last_sync_error"
		private const val KEY_DIRTY = "dirty"

		const val DEFAULT_INTERVAL_MINUTES = 360
		const val START_SYNC_COOLDOWN_MS = 15L * 60L * 1000L
	}
}
