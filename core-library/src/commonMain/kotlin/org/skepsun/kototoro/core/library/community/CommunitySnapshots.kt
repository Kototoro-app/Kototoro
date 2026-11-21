package org.skepsun.kototoro.core.library.community

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kotlincrypto.hash.sha2.SHA256
import org.skepsun.kototoro.community.domain.CommunityChart
import org.skepsun.kototoro.community.domain.CommunityHighlights
import org.skepsun.kototoro.community.domain.CommunityModeration
import org.skepsun.kototoro.community.domain.CommunitySnapshotManifest
import org.skepsun.kototoro.community.domain.CommunitySnapshotReader
import org.skepsun.kototoro.community.domain.CommunitySnapshotShards
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import kotlin.coroutines.CoroutineContext

/**
 * Where a host keeps downloaded snapshot files, by their snapshot path, with the hash of what was stored beside each
 * one. Calls are blocking; [CommunitySnapshots] makes them on its IO context.
 */
interface CommunitySnapshotCache {
    fun read(path: String): String?
    fun readHash(path: String): String?
    fun write(path: String, text: String, hash: String)
    fun clear()
}

/**
 * The community index on this device (plan §6.3, §7.3), Android's `CommunitySnapshotRepository` without the Android
 * file layer: a file is downloaded again only when the manifest lists another hash, the manifest is asked for at most
 * hourly, and a device that cannot reach it keeps using the last one.
 */
class CommunitySnapshots(
    /** Downloads one snapshot path; null when the file does not exist. */
    private val fetch: suspend (String) -> String?,
    private val cache: CommunitySnapshotCache,
    private val io: CoroutineContext = Dispatchers.Default,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private var manifest: CommunitySnapshotManifest? = null
    private var checkedAt = 0L

    suspend fun manifest(force: Boolean = false): CommunitySnapshotManifest? = lock.withLock {
        withContext(io) {
            val time = now()
            val current = manifest ?: cache.read(CommunitySnapshotShards.MANIFEST_PATH)?.let(CommunitySnapshotReader::manifest)
            if (!force && current != null && time - checkedAt < MANIFEST_TTL_MILLIS) {
                return@withContext current.also { manifest = it }
            }
            val fetched = attempt { fetch(CommunitySnapshotShards.MANIFEST_PATH) }
            checkedAt = time
            val fresh = fetched?.let { text ->
                CommunitySnapshotReader.manifest(text)?.also { write(CommunitySnapshotShards.MANIFEST_PATH, text) }
            }
            (fresh ?: current).also { manifest = it }
        }
    }

    /** What the index knows about the work named by [keys]; null when nothing, or when the index cannot be reached. */
    suspend fun workSummary(keys: List<String>): CommunityWorkSummary? {
        if (keys.isEmpty()) return null
        val manifest = manifest() ?: return null
        val shards = CommunitySnapshotReader.keyShardPaths(keys).associateWith { path ->
            file(manifest, path)?.let(CommunitySnapshotReader::keys)
        }
        val group = CommunitySnapshotReader.groupOf(keys) { shards[CommunitySnapshotShards.keysPath(it)] } ?: return null
        val works = file(manifest, CommunitySnapshotShards.worksPath(CommunitySnapshotShards.of(group))) ?: return null
        return CommunitySnapshotReader.works(works)[group]
    }

    /** The group of each work in [keysByWork], every key shard read once. [cachedOnly] skips all network requests. */
    suspend fun <K> groupsOf(keysByWork: Map<K, List<String>>, cachedOnly: Boolean = false): Map<K, String> {
        val manifest = snapshotManifest(cachedOnly) ?: return emptyMap()
        val paths = keysByWork.values.flatMap(CommunitySnapshotReader::keyShardPaths).distinct()
        val shards = paths.associateWith { path -> file(manifest, path, cachedOnly)?.let(CommunitySnapshotReader::keys) }
        return keysByWork.mapNotNull { (work, keys) ->
            CommunitySnapshotReader.groupOf(keys) { shards[CommunitySnapshotShards.keysPath(it)] }?.let { work to it }
        }.toMap()
    }

    /** Summaries of [groupIds], each works shard read once. [cachedOnly] skips all network requests. */
    suspend fun works(groupIds: Collection<String>, cachedOnly: Boolean = false): Map<String, CommunityWorkSummary> {
        if (groupIds.isEmpty()) return emptyMap()
        val manifest = snapshotManifest(cachedOnly) ?: return emptyMap()
        return groupIds.groupBy(CommunitySnapshotShards::of).flatMap { (shard, ids) ->
            val works = file(manifest, CommunitySnapshotShards.worksPath(shard), cachedOnly)
                ?.let(CommunitySnapshotReader::works).orEmpty()
            ids.mapNotNull { id -> works[id]?.let { id to it } }
        }.toMap()
    }

    /** Every work of an index small enough to read whole ([CommunityHighlights.worksShardPaths]); empty past that. */
    suspend fun allWorks(): List<CommunityWorkSummary> {
        val manifest = manifest() ?: return emptyList()
        return CommunityHighlights.worksShardPaths(manifest).flatMap { path ->
            file(manifest, path)?.let(CommunitySnapshotReader::works)?.values.orEmpty()
        }
    }

    suspend fun chart(window: String, medium: String, nsfw: Boolean): CommunityChart? {
        val manifest = manifest() ?: return null
        return file(manifest, CommunitySnapshotShards.chartPath(window, medium, nsfw))?.let(CommunitySnapshotReader::chart)
    }

    /** The maintainer's blocks and the comments the index hid, for every comment list. */
    suspend fun moderation(): CommunityModeration {
        val manifest = manifest() ?: return CommunityModeration()
        return file(manifest, CommunitySnapshotShards.MODERATION_PATH)?.let(CommunitySnapshotReader::moderation)
            ?: CommunityModeration()
    }

    /** The relays the index currently recommends, or null before the first manifest. */
    suspend fun relays(): List<String>? = manifest()?.relays?.takeIf { it.isNotEmpty() }

    suspend fun clear() = lock.withLock {
        withContext(io) {
            manifest = null
            checkedAt = 0
            cache.clear()
        }
    }

    private suspend fun snapshotManifest(cachedOnly: Boolean): CommunitySnapshotManifest? = if (cachedOnly) {
        withContext(io) { manifest ?: cache.read(CommunitySnapshotShards.MANIFEST_PATH)?.let(CommunitySnapshotReader::manifest) }
    } else {
        manifest()
    }

    private suspend fun file(manifest: CommunitySnapshotManifest, path: String, cachedOnly: Boolean = false): String? =
        withContext(io) {
            if (cachedOnly) return@withContext if (path in manifest.files) cache.read(path) else null
            when (CommunitySnapshotReader.cacheState(manifest, path, cache.readHash(path))) {
                CommunitySnapshotReader.CacheState.ABSENT -> null
                CommunitySnapshotReader.CacheState.CURRENT -> cache.read(path)
                CommunitySnapshotReader.CacheState.STALE -> {
                    // A mirror can lag behind the manifest; storing the hash of what came keeps it stale until it matches.
                    attempt { fetch(path) }?.also { write(path, it) } ?: cache.read(path)
                }
            }
        }

    private fun write(path: String, text: String) = cache.write(path, text, sha256(text))

    private companion object {
        const val MANIFEST_TTL_MILLIS = 60 * 60 * 1000L

        fun sha256(text: String): String =
            SHA256().digest(text.encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

internal inline fun <T> attempt(block: () -> T): T? = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}
