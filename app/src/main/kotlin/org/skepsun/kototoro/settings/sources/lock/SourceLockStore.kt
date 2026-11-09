package org.skepsun.kototoro.settings.sources.lock

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-source "lock" of the enabled state. A locked source keeps the stored value (true = locked on,
 * false = locked off) and ignores bulk enable/disable actions (disable failing, group apply, ...).
 * Process-wide singleton so every screen observes the same map.
 */
class SourceLockStore private constructor(context: Context) {

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val _locks = MutableStateFlow(read())

	/** Pre-sync storage of the group-applied marks; migrated into [prefs] as `group_applied:<source>` keys. */
	private val groupAppliedPrefs = context.getSharedPreferences(GROUP_APPLIED_PREFS_NAME, Context.MODE_PRIVATE)

	/**
	 * Bumped on every change of the prefs file, including writes by someone else (Google Drive settings sync
	 * writes it directly); the repository then re-applies the locks to the DB, which is idempotent.
	 */
	val externalRevision = AtomicInteger(0)

	/** Keys written by [setAll] whose (asynchronous) listener callback is still due; those are already mirrored. */
	private val pendingBatchKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

	// Held strongly: SharedPreferences keeps listeners weakly.
	private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		// A batch publishes [locks] and bumps the revision once itself instead of once per key.
		if (key != null && key.startsWith(GROUP_APPLIED_PREFIX)) return@OnSharedPreferenceChangeListener
		if (key == null || !pendingBatchKeys.remove(key)) {
			applyKeyChange(key)
			externalRevision.incrementAndGet()
		}
	}

	init {
		prefs.registerOnSharedPreferenceChangeListener(changeListener)
		migrateLegacyGroupApplied()
	}

	private fun migrateLegacyGroupApplied() {
		val legacy = groupAppliedPrefs.all.keys
		if (legacy.isEmpty()) return
		prefs.edit {
			for (name in legacy) {
				if (!prefs.contains(GROUP_APPLIED_PREFIX + name)) putBoolean(GROUP_APPLIED_PREFIX + name, true)
			}
		}
		groupAppliedPrefs.edit { clear() }
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
		// A manual change makes the lock the user's own: a later group apply must not undo it.
		forgetGroupApplied(listOf(name))
		setAll(mapOf(name to lockedEnabled))
	}

	/** Sources whose lock was last written by a group apply (synced together with the locks). */
	fun groupAppliedNames(): Set<String> = prefs.all.mapNotNullTo(HashSet()) { (key, value) ->
		if (key.startsWith(GROUP_APPLIED_PREFIX) && value == true) key.removePrefix(GROUP_APPLIED_PREFIX) else null
	}

	@Synchronized
	fun setGroupApplied(names: Set<String>) {
		val current = groupAppliedNames()
		if (current == names) return
		prefs.edit {
			for (name in current - names) remove(GROUP_APPLIED_PREFIX + name)
			for (name in names - current) putBoolean(GROUP_APPLIED_PREFIX + name, true)
		}
	}

	private fun forgetGroupApplied(names: Collection<String>) {
		val stored = prefs.all
		if (names.none { stored.containsKey(GROUP_APPLIED_PREFIX + it) }) return
		prefs.edit { names.forEach { remove(GROUP_APPLIED_PREFIX + it) } }
	}

	/** [set] for many sources: one prefs edit and one [locks] update. */
	@Synchronized
	fun setAll(entries: Map<String, Boolean?>) {
		if (entries.isEmpty()) return
		// Android only notifies for keys whose value really changes; tracking others would swallow a later external write.
		val stored = prefs.all
		val changed = entries.filter { (name, lockedEnabled) -> stored[name] != (lockedEnabled ?: UNLOCKED) }.keys
		pendingBatchKeys.addAll(changed)
		prefs.edit {
			for ((name, lockedEnabled) in entries) {
				if (lockedEnabled == null) putString(name, UNLOCKED) else putBoolean(name, lockedEnabled)
			}
		}
		_locks.value = _locks.value.toMutableMap().also { map ->
			for ((name, lockedEnabled) in entries) {
				if (lockedEnabled == null) map.remove(name) else map[name] = lockedEnabled
			}
		}
		externalRevision.incrementAndGet()
	}

	/** Drops the entry entirely (no marker): for sources that no longer exist anywhere, e.g. a deleted JSON source. */
	@Synchronized
	fun remove(names: Collection<String>) {
		if (names.isEmpty()) return
		forgetGroupApplied(names)
		prefs.edit { names.forEach { remove(it) } }
		_locks.value = _locks.value.toMutableMap().also { map -> names.forEach { map.remove(it) } }
	}

	/** Mirrors a single changed key (own or external write) into [locks] without re-reading the whole file. */
	@Synchronized
	private fun applyKeyChange(key: String?) {
		if (key != null && key.startsWith(GROUP_APPLIED_PREFIX)) return
		if (key == null) {
			_locks.value = read()
			return
		}
		val value = try {
			if (prefs.contains(key)) prefs.getBoolean(key, false) else null
		} catch (_: ClassCastException) {
			null
		}
		val current = _locks.value
		if (current[key] == value) return
		_locks.value = current.toMutableMap().also { if (value == null) it.remove(key) else it[key] = value }
	}

	private fun read(): Map<String, Boolean> = prefs.all.mapNotNull { (key, value) ->
		if (key.startsWith(GROUP_APPLIED_PREFIX)) return@mapNotNull null
		(value as? Boolean)?.let { key to it }
	}.toMap()

	companion object {

		private const val PREFS_NAME = "source_locks"
		private const val GROUP_APPLIED_PREFS_NAME = "group_applied_locks"
		private const val GROUP_APPLIED_PREFIX = "group_applied:"
		private const val UNLOCKED = "none"

		@Volatile
		private var instance: SourceLockStore? = null

		fun get(context: Context): SourceLockStore = instance ?: synchronized(this) {
			instance ?: SourceLockStore(context.applicationContext).also { instance = it }
		}
	}
}
