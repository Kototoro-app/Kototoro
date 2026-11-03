package org.skepsun.kototoro.settings.sources.lock

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-source "lock" of the enabled state. A locked source keeps the stored value (true = locked on,
 * false = locked off) and ignores bulk enable/disable actions (disable failing, group apply, ...).
 * Process-wide singleton so every screen observes the same map.
 */
class SourceLockStore private constructor(context: Context) {

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val _locks = MutableStateFlow(read())

	/**
	 * Bumped on every change of the prefs file, including writes by someone else (Google Drive settings sync
	 * writes it directly); the repository then re-applies the locks to the DB, which is idempotent.
	 */
	val externalRevision = AtomicInteger(0)

	// Held strongly: SharedPreferences keeps listeners weakly.
	private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
		_locks.value = read()
		externalRevision.incrementAndGet()
	}

	init {
		prefs.registerOnSharedPreferenceChangeListener(changeListener)
	}

	/** Source name to locked enabled state. */
	val locks: StateFlow<Map<String, Boolean>> = _locks.asStateFlow()

	fun get(name: String): Boolean? = _locks.value[name]

	/**
	 * null = unlocked. The unlock is stored as a marker string instead of removing the key, so the settings
	 * sync (which merges per key and never deletes) can propagate it to other devices.
	 */
	@Synchronized
	fun set(name: String, lockedEnabled: Boolean?) {
		prefs.edit {
			if (lockedEnabled == null) putString(name, UNLOCKED) else putBoolean(name, lockedEnabled)
		}
		_locks.value = read()
	}

	private fun read(): Map<String, Boolean> = prefs.all.mapNotNull { (key, value) ->
		(value as? Boolean)?.let { key to it }
	}.toMap()

	companion object {

		private const val PREFS_NAME = "source_locks"
		private const val UNLOCKED = "none"

		@Volatile
		private var instance: SourceLockStore? = null

		fun get(context: Context): SourceLockStore = instance ?: synchronized(this) {
			instance ?: SourceLockStore(context.applicationContext).also { instance = it }
		}
	}
}
