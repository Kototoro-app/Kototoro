package org.skepsun.kototoro.community.domain

import kotlinx.serialization.json.Json

/**
 * Reads the snapshot files and decides which local copies are still good (plan §6.3). Pure: the platform downloads,
 * stores files by path and asks this what to do with them.
 */
object CommunitySnapshotReader {

    private val json = Json { ignoreUnknownKeys = true }

    /** A manifest this app can read; null for a newer layout or anything malformed, which the app then ignores. */
    fun manifest(text: String): CommunitySnapshotManifest? = runCatching {
        json.decodeFromString(CommunitySnapshotManifest.serializer(), text)
    }.getOrNull()?.takeIf { it.version == CommunitySnapshotManifest.VERSION }

    fun keys(text: String): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(text) }.getOrDefault(emptyMap())

    fun works(text: String): Map<String, CommunityWorkSummary> =
        runCatching { json.decodeFromString<Map<String, CommunityWorkSummary>>(text) }.getOrDefault(emptyMap())

    fun chart(text: String): CommunityChart? = runCatching { json.decodeFromString(CommunityChart.serializer(), text) }.getOrNull()

    fun moderation(text: String): CommunityModeration =
        moderationOrNull(text) ?: CommunityModeration()

    fun moderationOrNull(text: String): CommunityModeration? =
        runCatching { json.decodeFromString(CommunityModeration.serializer(), text) }.getOrNull()

    /**
     * Whether a cached file at [path] is the one [manifest] lists: same content hash. A path the manifest does not list
     * is a shard nothing landed in, so it has no file to fetch at all.
     */
    fun cacheState(manifest: CommunitySnapshotManifest, path: String, cachedHash: String?): CacheState {
        val expected = manifest.files[path] ?: return CacheState.ABSENT
        return if (cachedHash == expected) CacheState.CURRENT else CacheState.STALE
    }

    enum class CacheState { CURRENT, STALE, ABSENT }

    /** The key shard files a work's [keys] can be in, so they are all loaded before [groupOf] asks. */
    fun keyShardPaths(keys: List<String>): List<String> =
        keys.map { CommunitySnapshotShards.keysPath(CommunitySnapshotShards.of(it)) }.distinct()

    /**
     * The group a work belongs to: the first of its [keys] (strong first, as CommunityWorkKeys orders them) that any
     * key shard knows. [keyShard] returns a shard's map, or null when it is not available right now.
     */
    fun groupOf(keys: List<String>, keyShard: (String) -> Map<String, String>?): String? {
        for (key in keys) {
            keyShard(CommunitySnapshotShards.of(key))?.get(key)?.let { return it }
        }
        return null
    }
}
