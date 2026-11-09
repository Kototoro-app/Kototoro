package org.skepsun.kototoro.sync.google.domain

import kotlinx.serialization.Serializable

@Serializable
data class SettingEntry(
	val t: String,
	val v: String,
	val at: Long,
	val version: Long = 0L,
) {
	val isDeleted: Boolean get() = t == TYPE_DELETED

	companion object {
		/** Type tag of a tombstone: the key was removed (reset to default) at [at]. */
		const val TYPE_DELETED = "d"

		fun deleted(at: Long, version: Long = 0L) = SettingEntry(t = TYPE_DELETED, v = "", at = at, version = version)
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
	// available/empty/cloudflare_sources are per-device source availability verdicts (see AppSettings.DEVICE_LOCAL_KEYS).
	private val excludedKeys = setOf("init", "app_version", "available_sources", "empty_sources", "cloudflare_sources")

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
				compareEntries(remoteEntry, localEntry) > 0 -> remoteEntry
				else -> localEntry
			} ?: continue
			result[key] = if (localEntry != null && remoteEntry != null) {
				winner.copy(version = maxOf(localEntry.version, remoteEntry.version))
			} else {
				winner
			}
		}
		return result
	}

	/** Orders by logical version (when both sides have one), then wall clock, then content as a deterministic tie-break. */
	fun compareEntries(a: SettingEntry, b: SettingEntry): Int {
		val stamp = compareStamp(a.version, a.at, b.version, b.at)
		if (stamp != 0) return stamp
		val type = a.t.compareTo(b.t)
		return if (type != 0) type else a.v.compareTo(b.v)
	}

	/** Records without a version (0, legacy) fall back to the wall clock. */
	fun compareStamp(versionA: Long, atA: Long, versionB: Long, atB: Long): Int {
		if (versionA > 0L && versionB > 0L && versionA != versionB) return versionA.compareTo(versionB)
		return atA.compareTo(atB)
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
