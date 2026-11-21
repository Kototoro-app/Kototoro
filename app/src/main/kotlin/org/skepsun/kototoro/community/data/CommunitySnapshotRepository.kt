package org.skepsun.kototoro.community.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.community.domain.CommunityChart
import org.skepsun.kototoro.community.domain.CommunityHighlights
import org.skepsun.kototoro.community.domain.CommunityModeration
import org.skepsun.kototoro.community.domain.CommunitySnapshotManifest
import org.skepsun.kototoro.community.domain.CommunitySnapshotReader
import org.skepsun.kototoro.community.domain.CommunitySnapshotShards
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import org.skepsun.kototoro.community.snapshot.CommunitySnapshotFetcher
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The community index on this device (plan §6.3, §7.3). Files are kept under their snapshot paths with their hash beside
 * them; one is downloaded again only when the manifest lists another hash. The manifest is asked for at most hourly, and
 * a device that cannot reach it keeps using the last one.
 */
@Singleton
class CommunitySnapshotRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val fetcher: CommunitySnapshotFetcher,
) {

    private val root = File(context.filesDir, "community/snapshot")
    private val lock = Mutex()

    @Volatile
    private var manifest: CommunitySnapshotManifest? = null
    private var checkedAt = 0L

    suspend fun manifest(force: Boolean = false): CommunitySnapshotManifest? = lock.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val current = manifest ?: readLocal(CommunitySnapshotShards.MANIFEST_PATH)?.let(CommunitySnapshotReader::manifest)
            if (!force && current != null && now - checkedAt < MANIFEST_TTL_MILLIS) {
                return@withContext current.also { manifest = it }
            }
            val fetched = runCatchingCancellable { fetcher.fetch(CommunitySnapshotShards.MANIFEST_PATH) }.getOrNull()
            checkedAt = now
            val fresh = fetched?.text?.let { text -> CommunitySnapshotReader.manifest(text)?.also { writeLocal(CommunitySnapshotShards.MANIFEST_PATH, text) } }
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
        val group = CommunitySnapshotReader.groupOf(keys) { shard -> shards[CommunitySnapshotShards.keysPath(shard)] } ?: return null
        val works = file(manifest, CommunitySnapshotShards.worksPath(CommunitySnapshotShards.of(group))) ?: return null
        return CommunitySnapshotReader.works(works)[group]
    }

    /**
     * The group of each work in [keysByWork], for many works at once: every key shard any of them needs is read once.
     * Works the index does not know are left out. [cachedOnly] skips all network requests.
     */
    suspend fun <K> groupsOf(keysByWork: Map<K, List<String>>, cachedOnly: Boolean = false): Map<K, String> {
        val manifest = snapshotManifest(cachedOnly) ?: return emptyMap()
        val paths = keysByWork.values.flatMap(CommunitySnapshotReader::keyShardPaths).distinct()
        val shards = paths.associateWith { path -> file(manifest, path, cachedOnly)?.let(CommunitySnapshotReader::keys) }
        return keysByWork.mapNotNull { (work, keys) ->
            CommunitySnapshotReader.groupOf(keys) { shard -> shards[CommunitySnapshotShards.keysPath(shard)] }?.let { work to it }
        }.toMap()
    }

    /** Summaries of [groupIds], each works shard read once. [cachedOnly] skips all network requests. */
    suspend fun works(groupIds: Collection<String>, cachedOnly: Boolean = false): Map<String, CommunityWorkSummary> {
        if (groupIds.isEmpty()) return emptyMap()
        val manifest = snapshotManifest(cachedOnly) ?: return emptyMap()
        val shards = groupIds.groupBy(CommunitySnapshotShards::of)
        return shards.flatMap { (shard, ids) ->
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
        return file(manifest, CommunitySnapshotShards.MODERATION_PATH)?.let(CommunitySnapshotReader::moderation) ?: CommunityModeration()
    }

    /** The relays the index currently recommends, or null before the first manifest. */
    suspend fun relays(): List<String>? = manifest()?.relays?.takeIf { it.isNotEmpty() }

    suspend fun clear() = lock.withLock {
        withContext(Dispatchers.IO) {
            manifest = null
            checkedAt = 0
            root.deleteRecursively()
        }
    }

    private suspend fun snapshotManifest(cachedOnly: Boolean): CommunitySnapshotManifest? = if (cachedOnly) {
        withContext(Dispatchers.IO) {
            manifest ?: readLocal(CommunitySnapshotShards.MANIFEST_PATH)?.let(CommunitySnapshotReader::manifest)
        }
    } else {
        manifest()
    }

    private suspend fun file(
        manifest: CommunitySnapshotManifest,
        path: String,
        cachedOnly: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        if (cachedOnly) return@withContext if (path in manifest.files) readLocal(path) else null
        val cachedHash = File(root, "$path$HASH_SUFFIX").takeIf { it.exists() }?.readText()
        when (CommunitySnapshotReader.cacheState(manifest, path, cachedHash)) {
            CommunitySnapshotReader.CacheState.ABSENT -> null
            CommunitySnapshotReader.CacheState.CURRENT -> readLocal(path)
            CommunitySnapshotReader.CacheState.STALE -> {
                val text = runCatchingCancellable { fetcher.fetch(path)?.text }.getOrNull()
                if (text == null) {
                    readLocal(path)
                } else {
                    // A mirror can lag behind the manifest; storing the hash of what came keeps it stale until it matches.
                    writeLocal(path, text)
                    text
                }
            }
        }
    }

    private fun readLocal(path: String): String? = File(root, path).takeIf { it.exists() }?.readText()

    private fun writeLocal(path: String, text: String) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.part")
        temp.writeText(text)
        temp.renameTo(file)
        File(root, "$path$HASH_SUFFIX").writeText(sha256(text))
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MANIFEST_TTL_MILLIS = 60 * 60 * 1000L
        const val HASH_SUFFIX = ".sha256"
    }
}
