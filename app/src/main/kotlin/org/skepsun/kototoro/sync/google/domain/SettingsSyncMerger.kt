package org.skepsun.kototoro.sync.google.domain

import kotlinx.serialization.Serializable

@Serializable
data class SettingEntry(
	val t: String,
	val v: String,
	val at: Long,
) {
	val isDeleted: Boolean get() = t == TYPE_DELETED

	companion object {
		/** Type tag of a tombstone: the key was removed (reset to default) at [at]. */
		const val TYPE_DELETED = "d"

		fun deleted(at: Long) = SettingEntry(t = TYPE_DELETED, v = "", at = at)
	}
}

@Serializable
data class SettingsSnapshot(
	val version: Int = 1,
	val deviceId: String = "",
	val syncedAt: Long = 0L,
	val stores: Map<String, Map<String, SettingEntry>> = emptyMap(),
)

object SettingsSyncMerger {

	private val excludedFragments = listOf(
		"password",
		"passwd",
		"token",
		"secret",
		"api_key",
		"apikey",
		"login",
		"credential",
		"cookie",
		"device_id",
		"clear",
		"incognito",
		// Custom HTTP headers may carry Authorization / Bearer credentials.
		"custom_headers",
		"authorization",
		"bearer",
	)
	// "_storage" keys (local_storage, local_novel_storage, local_video_storage) hold device-local File.path values.
	private val excludedSuffixes = listOf("_dir", "_dirs", "_dir_uris", "_dir_uri", "_uri", "_uris", "_path", "_storage")
	private val excludedPrefixes = listOf("sync_", "google_drive", "backup_periodic")
	private val excludedKeys = setOf("init", "app_version")

	fun isSyncableKey(key: String): Boolean {
		val normalized = key.lowercase()
		if (excludedFragments.any { normalized.contains(it) }) return false
		if (excludedSuffixes.any { normalized.endsWith(it) }) return false
		if (excludedPrefixes.any { normalized.startsWith(it) }) return false
		return normalized !in excludedKeys
	}

	fun merge(local: Map<String, SettingEntry>, remote: Map<String, SettingEntry>): Map<String, SettingEntry> {
		val result = LinkedHashMap<String, SettingEntry>()
		for (key in local.keys + remote.keys) {
			if (!isSyncableKey(key)) continue
			val localEntry = local[key]
			val remoteEntry = remote[key]
			val winner = when {
				localEntry == null -> remoteEntry
				remoteEntry == null -> localEntry
				remoteEntry.at >= localEntry.at -> remoteEntry
				else -> localEntry
			} ?: continue
			result[key] = winner
		}
		return result
	}

	fun mergeStores(
		local: Map<String, Map<String, SettingEntry>>,
		remote: Map<String, Map<String, SettingEntry>>,
	): Map<String, Map<String, SettingEntry>> {
		val result = LinkedHashMap<String, Map<String, SettingEntry>>()
		for (store in local.keys + remote.keys) {
			result[store] = merge(local[store].orEmpty(), remote[store].orEmpty())
		}
		return result
	}
}
