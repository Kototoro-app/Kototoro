package org.skepsun.kototoro.settings.sources.lock

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-source "lock" of the enabled state. A locked source keeps the stored value (true = locked on,
 * false = locked off) and ignores bulk enable/disable actions (disable failing, group apply, ...).
 * Process-wide singleton so every screen observes the same map.
 */
class SourceLockStore private constructor(context: Context) {

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val _locks = MutableStateFlow(read())

	/** Source name to locked enabled state. */
	val locks: StateFlow<Map<String, Boolean>> = _locks.asStateFlow()

	fun get(name: String): Boolean? = _locks.value[name]

	fun set(name: String, lockedEnabled: Boolean?) {
		prefs.edit {
			if (lockedEnabled == null) remove(name) else putBoolean(name, lockedEnabled)
		}
		_locks.value = read()
	}

	private fun read(): Map<String, Boolean> = prefs.all.mapNotNull { (key, value) ->
		(value as? Boolean)?.let { key to it }
	}.toMap()

	companion object {

		private const val PREFS_NAME = "source_locks"

		@Volatile
		private var instance: SourceLockStore? = null

		fun get(context: Context): SourceLockStore = instance ?: synchronized(this) {
			instance ?: SourceLockStore(context.applicationContext).also { instance = it }
		}
	}
}
