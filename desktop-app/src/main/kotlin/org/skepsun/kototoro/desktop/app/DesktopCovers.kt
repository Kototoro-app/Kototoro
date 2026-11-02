package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.*
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

@Serializable
private data class DesktopCoverRecord(val artifact: SourceCoverArtifact, val storedAt: Long)

/** Session-owned, bounded metadata cache. Last waiter cancellation cancels the actual source request. */
internal class DesktopCovers(
    private val directory: Path,
    private val preferences: SourcePreferences,
    /**
     * Persisted cover records. 256 was fewer than one library plus a little browsing, so shelf covers were dropped
     * and fetched again on every start; the files themselves stay under the image budget's LRU. Each record is about
     * 450 bytes and the store rewrites its file per edit, which bounds this well below the 4 MiB namespace limit.
     */
    private val maxRecords: Int = 2048,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: suspend (SourceContent, Boolean) -> SourceCoverArtifact,
) : Closeable {
    private class Active(val result: Deferred<Path>, var waiters: Int = 0)
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val permits = Semaphore(3)
    private val lock = Any()
    private var closed = false
    private val active = mutableMapOf<String, Active>()
    private val memory = object : LinkedHashMap<String, Path>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Path>) = size > 256
    }

    suspend fun load(content: SourceContent, large: Boolean = false, refresh: Boolean = false): Path {
        currentCoroutineContext().ensureActive()
        val url = (if (large) content.largeCoverUrl ?: content.coverUrl else content.coverUrl)
            ?.takeIf(String::isNotBlank) ?: throw SourceInvalidArgumentException()
        val key = imageKey("${content.source.name}\u0000${content.id}\u0000$url".toByteArray(Charsets.UTF_8))
        val request = synchronized(lock) {
            check(!closed) { "Cover cache is closed" }
            active.getOrPut(key) {
                Active(scope.async(start = CoroutineStart.LAZY) {
                    permits.withPermit {
                        val cached = if (refresh) null else cached(key, content)
                        if (cached != null) cached else {
                            val artifact = fetch(content, large)
                            check(artifact.contentId == content.id && artifact.source.name == content.source.name) {
                                "Cover identity mismatch"
                            }
                            val path = directory.resolve(artifact.relativePath)
                            Image.makeFromEncoded(Files.readAllBytes(path)).use { check(it.width > 0 && it.height > 0) }
                            currentCoroutineContext().ensureActive()
                            remember(key, artifact, path)
                            path
                        }
                    }
                })
            }.also { it.waiters++ }
        }
        try {
            request.result.start()
            return request.result.await()
        } finally {
            synchronized(lock) {
                request.waiters--
                if (request.waiters == 0) {
                    if (active[key] === request) active.remove(key)
                    if (!request.result.isCompleted) request.result.cancel()
                }
            }
        }
    }

    private suspend fun cached(key: String, content: SourceContent): Path? {
        val path = synchronized(lock) { memory[key] }
        if (path != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return path
        val record = record(preferences.snapshot()[key]) ?: return null
        val artifact = record.artifact
        if (artifact.contentId != content.id || artifact.source.name != content.source.name) return null
        val stored = verifiedImagePath(directory, artifact.relativePath, artifact.sha256, artifact.byteSize) ?: return null
        synchronized(lock) {
            check(!closed); memory[key] = stored
            // Least recently used, not first stored: the shelves' covers are read on every start and must outlive a
            // few pages of browsing. Refreshed at most hourly so reading a shelf does not rewrite the index per card.
            if (clock() - record.storedAt > TOUCH_INTERVAL_MS) write(key, artifact, emptySet())
        }
        return stored
    }

    private fun remember(key: String, artifact: SourceCoverArtifact, path: Path) = synchronized(lock) {
        check(!closed)
        memory[key] = path
        val snapshot = preferences.snapshot()
        val removals = if (key !in snapshot && snapshot.size >= maxRecords) {
            snapshot.entries.sortedBy { record(it.value)?.storedAt ?: Long.MIN_VALUE }
                .take(snapshot.size - maxRecords + 1).map { it.key }.toSet()
        } else emptySet()
        write(key, artifact, removals)
    }

    private fun write(key: String, artifact: SourceCoverArtifact, removals: Set<String>) {
        preferences.edit(SourcePreferenceEdit(changes = removals.associateWith { null } + mapOf(
            key to SourcePreferenceValue.Text(SourceProtocolJson.encodeToString(
                DesktopCoverRecord(artifact, clock()),
            )),
        )))
    }

    private fun record(value: SourcePreferenceValue?): DesktopCoverRecord? = try {
        (value as? SourcePreferenceValue.Text)?.let {
            SourceProtocolJson.decodeFromString<DesktopCoverRecord>(it.value)
        }
    } catch (_: IllegalArgumentException) { null }

    private companion object {
        const val TOUCH_INTERVAL_MS = 60 * 60 * 1000L
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true }
        runBlocking { owner.cancelAndJoin() }
        synchronized(lock) { active.clear(); memory.clear() }
    }

}
