package org.skepsun.kototoro.sync.google.domain

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsSyncStore @Inject constructor(
	@ApplicationContext context: Context,
	appSettings: AppSettings,
) {

	private val stores: Map<String, SharedPreferences> = linkedMapOf(
		STORE_APP to appSettings.prefs,
		STORE_TAP_GRID to context.getSharedPreferences("tap_grid", Context.MODE_PRIVATE),
		STORE_NOVEL_READER to context.getSharedPreferences("novel_reader_settings", Context.MODE_PRIVATE),
		// Source locks (per-source enabled state lock): name -> true/false, "none" = unlocked marker.
		STORE_SOURCE_LOCKS to context.getSharedPreferences("source_locks", Context.MODE_PRIVATE),
	)
	private val meta = context.getSharedPreferences("sync_settings_meta", Context.MODE_PRIVATE)

	// SharedPreferences keeps weak references; keep listeners alive for the process lifetime.
	private var applyingRemote = false
	private val listeners = stores.map { (store, prefs) ->
		SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			recordLocalChange(store, prefs, key)
		}.also(prefs::registerOnSharedPreferenceChangeListener)
	}

	@Synchronized
	private fun recordLocalChange(store: String, prefs: SharedPreferences, key: String?) {
		if (applyingRemote || meta.getString(META_INITIALIZED, null) != "true") return
		if (key != null && !SettingsSyncMerger.isSyncableKey(key)) return
		val current = prefs.all
		val keys = if (key != null) {
			setOf(key)
		} else {
			current.keys + meta.all.keys.filter { it.startsWith("$store/") }.map { it.removePrefix("$store/") }
		}
		val updates = HashMap<String, String>()
		val now = System.currentTimeMillis()
		for (changedKey in keys) {
			if (!SettingsSyncMerger.isSyncableKey(changedKey)) continue
			val metaKey = "$store/$changedKey"
			val stored = meta.getString(metaKey, null)
			val encoded = encode(current[changedKey])
			if (encoded == null) {
				if (!current.containsKey(changedKey) && stored != null && !stored.startsWith(DELETED_PREFIX)) {
					updates[metaKey] = "$DELETED_PREFIX$now"
				}
			} else {
				val hash = hashOf(encoded)
				if (stored?.substringBefore('|')?.toIntOrNull() != hash) {
					updates[metaKey] = "$hash|$now"
				}
			}
		}
		saveMeta(updates)
	}

	/** First-sync/unknown values are initial state; listeners capture subsequent edits and resets. */
	@Synchronized
	fun buildLocal(): Map<String, Map<String, SettingEntry>> {
		val metaUpdates = HashMap<String, String>()
		val initialized = meta.getString(META_INITIALIZED, null) == "true"
		val metaAll = meta.all
		val result = LinkedHashMap<String, Map<String, SettingEntry>>()
		for ((store, prefs) in stores) {
			val entries = LinkedHashMap<String, SettingEntry>()
			val current = prefs.all
			for ((key, value) in current) {
				if (!SettingsSyncMerger.isSyncableKey(key)) continue
				val encoded = encode(value) ?: continue
				val metaKey = "$store/$key"
				val hash = hashOf(encoded)
				val stored = metaAll[metaKey] as? String
				val storedHash = stored?.substringBefore('|')?.toIntOrNull()
				val storedAt = stored?.substringAfter('|', "")?.toLongOrNull()
				val at = when {
					stored == null -> {
						val initial = 0L
						metaUpdates[metaKey] = "$hash|$initial"
						initial
					}
					storedHash == hash && storedAt != null -> storedAt
					else -> {
						// No matching edit metadata: do not invent an edit time during sync.
						metaUpdates[metaKey] = "$hash|0"
						0L
					}
				}
				entries[key] = SettingEntry(t = encoded.first, v = encoded.second, at = at)
			}
			val prefix = "$store/"
			for ((metaKey, stored) in metaAll) {
				if (!metaKey.startsWith(prefix) || stored !is String) continue
				val key = metaKey.removePrefix(prefix)
				if (key in current || !SettingsSyncMerger.isSyncableKey(key)) continue
				val deletedAt = if (stored.startsWith(DELETED_PREFIX)) {
					stored.removePrefix(DELETED_PREFIX).toLongOrNull() ?: 0L
				} else {
					0L.also { metaUpdates[metaKey] = "${DELETED_PREFIX}0" }
				}
				entries[key] = SettingEntry.deleted(deletedAt)
			}
			result[store] = entries
		}
		if (!initialized) metaUpdates[META_INITIALIZED] = "true"
		saveMeta(metaUpdates)
		return result
	}

	@Synchronized
	fun apply(merged: Map<String, Map<String, SettingEntry>>): Int {
		applyingRemote = true
		return try {
			applyEntries(merged)
		} finally {
			applyingRemote = false
		}
	}

	private fun applyEntries(merged: Map<String, Map<String, SettingEntry>>): Int {
		val metaUpdates = HashMap<String, String>()
		var applied = 0
		for ((store, entries) in merged) {
			val prefs = stores[store] ?: continue
			val localValues = prefs.all
			val writes = LinkedHashMap<String, Any>()
			val removals = ArrayList<String>()
			for ((key, entry) in entries) {
				if (!SettingsSyncMerger.isSyncableKey(key)) continue
				val storedAt = meta.getString("$store/$key", null)?.substringAfter('|', "")?.toLongOrNull() ?: 0L
				if (storedAt > entry.at) continue
				if (entry.isDeleted) {
					if (localValues.containsKey(key)) removals += key
					metaUpdates["$store/$key"] = "$DELETED_PREFIX${entry.at}"
					continue
				}
				val value = decode(entry) ?: continue
				val encoded = encode(value) ?: continue
				metaUpdates["$store/$key"] = "${hashOf(encoded)}|${entry.at}"
				if (encode(localValues[key]) != encoded) writes[key] = value
			}
			if (writes.isEmpty() && removals.isEmpty()) continue
			prefs.edit {
				removals.forEach { remove(it) }
				for ((key, value) in writes) {
					when (value) {
						is Boolean -> putBoolean(key, value)
						is Int -> putInt(key, value)
						is Long -> putLong(key, value)
						is Float -> putFloat(key, value)
						is String -> putString(key, value)
						is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
					}
				}
			}
			applied += writes.size + removals.size
		}
		saveMeta(metaUpdates)
		return applied
	}

	private fun encode(value: Any?): Pair<String, String>? {
		return when (value) {
			is Boolean -> "b" to value.toString()
			is Int -> "i" to value.toString()
			is Long -> "l" to value.toString()
			is Float -> "f" to value.toString()
			is String -> "s" to value
			is Set<*> -> {
				val strings = value.filterIsInstance<String>()
				if (strings.size != value.size) {
					null
				} else {
					"ss" to Json.encodeToString(ListSerializer(String.serializer()), strings.sorted())
				}
			}
			else -> null
		}
	}

	private fun decode(entry: SettingEntry): Any? = runCatching {
		when (entry.t) {
			"b" -> entry.v.toBooleanStrict()
			"i" -> entry.v.toInt()
			"l" -> entry.v.toLong()
			"f" -> entry.v.toFloat()
			"s" -> entry.v
			"ss" -> Json.decodeFromString(ListSerializer(String.serializer()), entry.v).toSet()
			else -> null
		}
	}.getOrNull()

	private fun hashOf(encoded: Pair<String, String>): Int = (encoded.first + ":" + encoded.second).hashCode()

	private fun saveMeta(updates: Map<String, String>) {
		if (updates.isEmpty()) return
		meta.edit {
			for ((key, value) in updates) {
				putString(key, value)
			}
		}
	}

	private companion object {
		const val META_INITIALIZED = "__initialized__"
		const val DELETED_PREFIX = "d|"
		const val STORE_APP = "app"
		const val STORE_TAP_GRID = "tap_grid"
		const val STORE_NOVEL_READER = "novel_reader"
		const val STORE_SOURCE_LOCKS = "source_locks"
	}
}
