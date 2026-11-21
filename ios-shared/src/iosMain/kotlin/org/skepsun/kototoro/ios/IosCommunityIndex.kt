package org.skepsun.kototoro.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.skepsun.kototoro.community.domain.CommunitySnapshotManifest
import org.skepsun.kototoro.community.domain.CommunitySnapshotReader
import org.skepsun.kototoro.community.domain.CommunitySnapshotShards
import org.skepsun.kototoro.community.domain.CommunityWorkSummary
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCompatibilityMapping
import platform.Foundation.stringByApplyingTransform

internal class IosCommunityIndex(
    private val readSnapshot: suspend (String) -> String? = { IosHttp.communitySnapshot.fetch(it)?.text },
) {
    private val lock = Mutex()
    private val snapshotFiles = mutableMapOf<String, Pair<String, String>>()
    private var lastManifest: CommunitySnapshotManifest? = null
    private var lastChecked = 0L

    suspend fun snapshot(): Snapshot? = lock.withLock {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (now - lastChecked > 60 * 60 * 1000L || lastManifest == null) {
            val manifest = withTimeoutOrNull(5_000L) {
                fetchSnapshot(CommunitySnapshotShards.MANIFEST_PATH)?.let(CommunitySnapshotReader::manifest)
            }
            if (manifest != null) {
                lastChecked = now
                lastManifest = manifest
            }
        }
        lastManifest?.let(::Snapshot)
    }

    private suspend fun fetchSnapshot(path: String): String? =
        communityAttempt { readSnapshot(path) }.getOrNull()

    /** One index file straight from the network, for the shared service's own copy of the index. */
    suspend fun read(path: String): String? = readSnapshot(path)

    inner class Snapshot(val manifest: CommunitySnapshotManifest) {
        suspend fun files(paths: Collection<String>, cachedOnly: Boolean = false): Map<String, String> = lock.withLock {
            val files = mutableMapOf<String, String>()
            for (batch in paths.distinct().chunked(8)) {
                val missing = batch.mapNotNull { path ->
                    val hash = manifest.files[path] ?: return@mapNotNull null
                    val cached = snapshotFiles[path]
                    if (cached != null) files[path] = cached.second
                    if (!cachedOnly && cached?.first != hash) path to hash else null
                }
                val fetched = coroutineScope {
                    missing.map { (path, hash) -> async { Triple(path, hash, fetchSnapshot(path)) } }.awaitAll()
                }
                for ((path, hash, body) in fetched) {
                    if (body != null) {
                        snapshotFiles[path] = hash to body
                        files[path] = body
                    }
                }
            }
            files
        }

        suspend fun groups(
            keys: Map<Long, List<String>>,
            cachedOnly: Boolean = false,
            requireListedFiles: Boolean = false,
        ): Map<Long, String> {
            val paths = keys.values.flatMap(CommunitySnapshotReader::keyShardPaths)
            val bodies = files(paths, cachedOnly)
            check(!requireListedFiles || paths.all { it !in manifest.files || it in bodies }) {
                localized("暂时无法读取社区作品索引")
            }
            val shards = bodies.mapValues { CommunitySnapshotReader.keys(it.value) }
            return keys.mapNotNull { (id, keys) ->
                CommunitySnapshotReader.groupOf(keys) { shards[CommunitySnapshotShards.keysPath(it)] }
                    ?.let { id to it }
            }.toMap()
        }

        suspend fun works(ids: Collection<String>, cachedOnly: Boolean = false): Map<String, CommunityWorkSummary> {
            val shards = ids.groupBy(CommunitySnapshotShards::of)
            val files = files(shards.keys.map(CommunitySnapshotShards::worksPath), cachedOnly)
            return shards.flatMap { (shard, groups) ->
                val works = files[CommunitySnapshotShards.worksPath(shard)]?.let(CommunitySnapshotReader::works).orEmpty()
                groups.mapNotNull { id -> works[id]?.let { id to it } }
            }.toMap()
        }
    }
}
@OptIn(BetaInteropApi::class)
internal fun foldCommunityTitle(text: String): String {
    val normalized = NSString.create(string = text).precomposedStringWithCompatibilityMapping
    return (NSString.create(string = normalized).stringByApplyingTransform("Traditional-Simplified", false)
        ?: normalized).lowercase()
}

internal inline fun <T> communityAttempt(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }
