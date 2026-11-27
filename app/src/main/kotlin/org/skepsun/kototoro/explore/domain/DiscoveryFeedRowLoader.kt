package org.skepsun.kototoro.explore.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
import org.skepsun.kototoro.parsers.model.Content
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DiscoveryFeedRowLoadResult {
    data class Loaded(
        val items: List<Content>,
        val fetchedAtMs: Long,
    ) : DiscoveryFeedRowLoadResult

    data class Error(
        val throwable: Throwable,
    ) : DiscoveryFeedRowLoadResult
}

/**
 * Lazy, concurrency-capped, TTL-cached, error-isolated loader for Discovery Feed rows.
 *
 * - Enforces a global concurrency cap of [DEFAULT_MAX_CONCURRENCY] via [Semaphore].
 * - Caches per-row results in memory for [DEFAULT_CACHE_TTL_MS] (30 minutes) keyed by row ID and
 *   request fingerprint (including NSFW setting state).
 * - Isolates single-row failures as [DiscoveryFeedRowLoadResult.Error] while always rethrowing
 *   [CancellationException] so scope cancellation cleanly aborts in-flight work.
 */
@Singleton
class DiscoveryFeedRowLoader internal constructor(
    private val fetcher: DiscoveryFeedRowFetcher,
    private val isNsfwContentDisabled: () -> Boolean,
    private val clock: () -> Long,
    maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
) {

    @Inject
    constructor(
        fetcher: DefaultDiscoveryFeedRowFetcher,
        settings: AppSettings,
    ) : this(
        fetcher = fetcher,
        isNsfwContentDisabled = { settings.isNsfwContentDisabled },
        clock = System::currentTimeMillis,
        maxConcurrency = DEFAULT_MAX_CONCURRENCY,
        cacheTtlMs = DEFAULT_CACHE_TTL_MS,
    )

    init {
        require(maxConcurrency > 0) { "maxConcurrency must be positive" }
        require(cacheTtlMs >= 0L) { "cacheTtlMs must be non-negative" }
    }

    private data class RequestFingerprint(
        val sourceName: String,
        val savedFilterId: Int?,
        val normalizedQuery: String?,
        val nsfwDisabled: Boolean,
    )

    private data class CacheEntry(
        val fingerprint: RequestFingerprint,
        val items: List<Content>,
        val fetchedAtMs: Long,
    )

    private val semaphore = Semaphore(maxConcurrency)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun getCached(row: DiscoveryFeedRow): DiscoveryFeedRowLoadResult.Loaded? {
        val fingerprint = fingerprintFor(row)
        return lookupFreshCache(row.id, fingerprint)
    }

    suspend fun loadRow(
        row: DiscoveryFeedRow,
        forceRefresh: Boolean = false,
    ): DiscoveryFeedRowLoadResult {
        val fingerprint = fingerprintFor(row)
        if (!forceRefresh) {
            lookupFreshCache(row.id, fingerprint)?.let { return it }
        } else {
            cache.remove(row.id)
        }

        return semaphore.withPermit {
            if (!forceRefresh) {
                lookupFreshCache(row.id, fingerprint)?.let { return@withPermit it }
            }
            try {
                val rawItems = fetcher.fetch(row)
                val items = if (fingerprint.nsfwDisabled) {
                    rawItems.filterNot { it.isNsfw() }
                } else {
                    rawItems
                }
                val fetchedAtMs = clock()
                cache[row.id] = CacheEntry(
                    fingerprint = fingerprint,
                    items = items,
                    fetchedAtMs = fetchedAtMs,
                )
                DiscoveryFeedRowLoadResult.Loaded(
                    items = items,
                    fetchedAtMs = fetchedAtMs,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DiscoveryFeedRowLoadResult.Error(e)
            }
        }
    }

    fun invalidate(rowId: String) {
        cache.remove(rowId)
    }

    fun invalidateAll() {
        cache.clear()
    }

    private fun lookupFreshCache(
        rowId: String,
        fingerprint: RequestFingerprint,
    ): DiscoveryFeedRowLoadResult.Loaded? {
        val entry = cache[rowId] ?: return null
        if (entry.fingerprint != fingerprint) {
            return null
        }
        val ageMs = clock() - entry.fetchedAtMs
        if (ageMs < 0L || ageMs >= cacheTtlMs) {
            cache.remove(rowId, entry)
            return null
        }
        return DiscoveryFeedRowLoadResult.Loaded(
            items = entry.items,
            fetchedAtMs = entry.fetchedAtMs,
        )
    }

    private fun fingerprintFor(row: DiscoveryFeedRow): RequestFingerprint = RequestFingerprint(
        sourceName = row.sourceName,
        savedFilterId = row.savedFilterId,
        normalizedQuery = row.normalizedQuery,
        nsfwDisabled = isNsfwContentDisabled(),
    )

    companion object {
        const val DEFAULT_MAX_CONCURRENCY = 3
        const val DEFAULT_CACHE_TTL_MS = 30L * 60L * 1000L
    }
}
