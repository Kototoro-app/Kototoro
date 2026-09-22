package org.skepsun.kototoro.reader.image

import android.os.Build
import android.os.Trace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.PageId
import java.util.concurrent.ConcurrentHashMap

/**
 * One decoded tile: placement spec + opaque platform payload + resident cost.
 *
 * The payload is owned by the tile store; it is whatever [TileDecodeSession.decodeRegion]
 * produced (android.graphics.Bitmap on Android). The rendering layer (Phase 1C)
 * applies the orientation transform described by the owning [TileGrid].
 */
class ReaderTile(
    val spec: TileSpec,
    val payload: Any,
    val costBytes: Long,
)

/**
 * Orchestrates tiled region decoding for the scene reader:
 * session reuse per page, deduplicated/cancellable tile decode jobs, and a
 * four-level retention ladder ([TileRetention]) enforced by [TileMemoryBudget].
 *
 * Concurrency model:
 * - [requestTiles]/[requestOverview]/[releasePage] are cheap, main-thread-safe calls
 *   that only mutate ledgers and launch jobs.
 * - Decoding runs on [decodeDispatcher] inside [scope]; the session deferred is
 *   shared so every tile of a page reuses one [TileDecodeSession] (single native
 *   decoder parse for arbitrarily long strips).
 * - [budget] is the authoritative residency ledger; the [tiles] snapshot follows
 *   it with best-effort atomic updates.
 */
class ReaderTileManager(
    private val scope: CoroutineScope,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val budget: TileMemoryBudget = TileMemoryBudget(),
    private val sourceFactory: (PageId) -> RegionDecodeSource?,
    private val costOf: (payload: Any) -> Long,
    private val payloadReleaser: (payload: Any) -> Unit = {},
) : TileStore {
    private val listeners = ArrayList<TileStore.Listener>()
    private val listenersLock = Any()

    private val sessions = ConcurrentHashMap<PageId, Deferred<Result<TileDecodeSession>>>()
    private val tileJobs = ConcurrentHashMap<TileKey, Job>()
    private val missCounts = ConcurrentHashMap<TileKey, Int>()
    private val notifiedMissingSources: MutableSet<PageId> = ConcurrentHashMap.newKeySet()

    private val mutableTiles = MutableStateFlow<Map<TileKey, ReaderTile>>(emptyMap())

    /** Snapshot of resident tiles. Follows the budget ledger; not gap-free under churn. */
    val tiles: StateFlow<Map<TileKey, ReaderTile>> = mutableTiles.asStateFlow()

    /** Cumulative tile decode launches, reported as telemetry to expose churn. */
    private val decodeRequests = java.util.concurrent.atomic.AtomicLong()

    /** Anomaly probe counter; only the first few oversized requests carry a stack trace. */
    private val probeLogged = java.util.concurrent.atomic.AtomicLong()

    /** Cumulative evictions, reported as telemetry: churn means re-decode and re-upload. */
    private val evictionCount = java.util.concurrent.atomic.AtomicLong()
    override fun addListener(listener: TileStore.Listener) {
        synchronized(listenersLock) { listeners.add(listener) }
    }

    override fun removeListener(listener: TileStore.Listener) {
        synchronized(listenersLock) { listeners.remove(listener) }
    }

    override fun tile(key: TileKey): ReaderTile? = mutableTiles.value[key]

    /**
     * Replaces the desired tile window of [grid.pageId].
     *
     * Tiles intersecting [visibleRegion] (plus the grid's output gutter) are
     * requested at [TileRetention.VISIBLE]; tiles only inside [lookaheadRegion]
     * are requested at [TileRetention.NEARBY]. Lattice tiles of this page that
     * are in neither set step down the demotion ladder: STANDBY on the first
     * missed request, CACHE from the second onward — pressure evicts CACHE first.
     * Overview tiles are pinned VISIBLE until [releasePage].
     */
    fun requestTiles(
        grid: TileGrid,
        visibleRegion: IntRect,
        lookaheadRegion: IntRect? = null,
    ) {
        // Residency telemetry: bounds the tile ledger's own claim about how much it holds, which is
        // what a decode-path investigation needs to separate tile memory from everything else.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            Trace.setCounter("Reader.TileResidentBytes", budget.residentBytes)
            Trace.setCounter("Reader.TileResidentCount", tiles.value.size.toLong())
        }
        val visibleSpecs = grid.tilesIntersecting(visibleRegion)
        // Anomaly probe: a request far larger than a viewport's worth of tiles is the shape that
        // pins hundreds of megabytes, and it is otherwise invisible in aggregate telemetry.
        val requestedArea = visibleRegion.width.toLong() * visibleRegion.height.toLong()
        if (visibleSpecs.size > 12 || requestedArea > 6_000_000L) {
            val probeCount = probeLogged.incrementAndGet()
            android.util.Log.w(
                "TileProbe",
                "huge tile request page=${grid.pageId.value} specs=${visibleSpecs.size} " +
                    "region=$visibleRegion area=$requestedArea sampleSize=${grid.sampleSize} " +
                    "pageSize=${grid.pageSize} lookahead=$lookaheadRegion",
                // A handful of stack traces identify which caller builds these regions.
                if (probeCount <= 5) Throwable("tile probe caller") else null,
            )
        }
        val visibleKeys = HashSet<TileKey>(visibleSpecs.size)
        visibleSpecs.forEach { visibleKeys.add(it.key) }

        val nearbySpecs = lookaheadRegion
            ?.let { grid.tilesIntersecting(it) }
            ?.filter { it.key !in visibleKeys }
            ?: emptyList()

        val desired = HashMap<TileKey, TileSpec>(visibleSpecs.size + nearbySpecs.size)
        visibleSpecs.forEach { desired[it.key] = it }
        nearbySpecs.forEach { desired[it.key] = it }

        // Promote already-resident desired tiles and reset their demotion counters.
        for (spec in visibleSpecs) {
            missCounts.remove(spec.key)
            if (budget.contains(spec.key)) {
                budget.updateRetention(spec.key, TileRetention.VISIBLE)
            }
        }
        for (spec in nearbySpecs) {
            missCounts.remove(spec.key)
            if (budget.contains(spec.key)) {
                budget.updateRetention(spec.key, TileRetention.NEARBY)
            }
        }

        demoteAbsentLatticeTiles(grid.pageId, grid.sampleSize, desired)

        for (spec in visibleSpecs) launchDecode(spec, TileRetention.VISIBLE)
        for (spec in nearbySpecs) launchDecode(spec, TileRetention.NEARBY)
    }

    /**
     * Additively requests the whole-page overview (LOD0 base band) at
     * [overviewSampleSize], pinned at [TileRetention.VISIBLE] until [releasePage].
     * Does not demote lattice tiles of the page.
     */
    fun requestOverview(grid: TileGrid, overviewSampleSize: Int) {
        if (sourceFactory(grid.pageId) == null) {
            notifyMissingSource(grid.pageId)
            return
        }
        val spec = grid.overviewTile(overviewSampleSize)
        if (budget.contains(spec.key)) {
            budget.updateRetention(spec.key, TileRetention.VISIBLE)
            return
        }
        launchDecode(spec, TileRetention.VISIBLE)
    }

    /**
     * Drops every tile, job, and session belonging to [pageId] and releases
     * payloads. Safe to call repeatedly; a later request re-opens the session.
     */
    fun releasePage(pageId: PageId) {
        notifiedMissingSources.remove(pageId)

        val jobKeys = tileJobs.keys.filter { it.pageId == pageId }
        for (key in jobKeys) {
            tileJobs.remove(key)?.cancel()
        }

        closeSession(pageId)

        val droppedKeys = ArrayList<TileKey>()
        val releasedPayloads = ArrayList<Any>()
        mutableTiles.update { map ->
            val kept = HashMap<TileKey, ReaderTile>(map.size)
            for ((key, tile) in map) {
                if (key.pageId == pageId) {
                    droppedKeys.add(key)
                    releasedPayloads.add(tile.payload)
                } else {
                    kept[key] = tile
                }
            }
            kept
        }
        budget.releaseWhere { it.pageId == pageId }
        releasedPayloads.forEach(payloadReleaser)
        for (key in droppedKeys) notifyTileDropped(key)

        missCounts.keys.removeAll { it.pageId == pageId }
    }

    /**
     * Drops all tiles matching [predicate] and cancels their in-flight jobs.
     */
    fun releaseTiles(predicate: (TileKey) -> Boolean) {
        val jobKeys = tileJobs.keys.filter(predicate)
        for (key in jobKeys) {
            tileJobs.remove(key)?.cancel()
        }

        val droppedKeys = ArrayList<TileKey>()
        val releasedPayloads = ArrayList<Any>()
        mutableTiles.update { map ->
            val kept = HashMap<TileKey, ReaderTile>(map.size)
            for ((key, tile) in map) {
                if (predicate(key)) {
                    droppedKeys.add(key)
                    releasedPayloads.add(tile.payload)
                } else {
                    kept[key] = tile
                }
            }
            kept
        }
        budget.releaseWhere(predicate)
        releasedPayloads.forEach(payloadReleaser)
        for (key in droppedKeys) notifyTileDropped(key)

        missCounts.keys.removeAll(predicate)
    }

    /** Releases everything. Terminal. */
    fun close() {
        for (pageId in sessions.keys.toList()) releasePage(pageId)
        for (key in tileJobs.keys.toList()) tileJobs.remove(key)?.cancel()
        val dropped = mutableTiles.value.values.toList()
        mutableTiles.value = emptyMap()
        budget.clear()
        dropped.forEach { payloadReleaser(it.payload) }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /**
     * Steps down tiles of [pageId] **at the requested layer** that this request did not ask for.
     *
     * Scoped by `sampleSize` because one page is painted from several levels at once (the fit-level
     * base and the camera-level target), and the adapter requests them one after the other for the
     * same visible band. Demoting by page alone made each request cancel the other layer's in-flight
     * decodes and step its resident tiles down: the ladder A/B measured 10,304 decode launches
     * against 282 for the single-layer build, with residency stuck at 16 tiles.
     */
    private fun demoteAbsentLatticeTiles(pageId: PageId, sampleSize: Int, desired: Map<TileKey, TileSpec>) {
        val trackedKeys = HashSet<TileKey>()
        for (key in mutableTiles.value.keys) {
            if (key.pageId == pageId && key.kind == TileKind.LATTICE && key.sampleSize == sampleSize) {
                trackedKeys.add(key)
            }
        }
        for (key in tileJobs.keys) {
            if (key.pageId == pageId && key.kind == TileKind.LATTICE && key.sampleSize == sampleSize) {
                trackedKeys.add(key)
            }
        }

        for (key in trackedKeys) {
            if (key in desired) continue
            val misses = (missCounts[key] ?: 0) + 1
            missCounts[key] = misses
            if (budget.contains(key)) {
                budget.updateRetention(
                    key = key,
                    retention = if (misses >= DEMOTE_TO_CACHE_AFTER_MISSES) TileRetention.CACHE else TileRetention.STANDBY,
                )
            } else {
                // Not resident: stop wasting decode work on tiles nobody wants.
                tileJobs.remove(key)?.cancel()
                if (misses >= DEMOTE_TO_CACHE_AFTER_MISSES) missCounts.remove(key)
            }
        }
    }

    private fun launchDecode(spec: TileSpec, retention: TileRetention) {
        if (tileJobs.containsKey(spec.key)) return
        // Resident already: the in-flight guard above only covers a decode that has not finished yet, so
        // without this check every request decoded the tile again. On device that showed up as decode
        // launches scaling with how *fast* tiles decode (2.5x: 238 -> 596 per run when the payload
        // shrank), i.e. redundant work on the decode threads and a frame overrun tail of +113ms.
        if (budget.contains(spec.key)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            Trace.setCounter("Reader.TileDecodeRequests", decodeRequests.incrementAndGet())
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { decodeTile(spec, retention) }
        if (tileJobs.putIfAbsent(spec.key, job) != null) {
            return // An existing job already covers this spec.
        }
        job.start()
        job.invokeOnCompletion { tileJobs.remove(spec.key, job) }
    }

    private suspend fun decodeTile(spec: TileSpec, retention: TileRetention) {
        val session = try {
            openSessionFor(spec.key.pageId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // Session open failure was already reported (deduplicated) by openSessionFor.
            return
        }

        val payload = try {
            withContext(decodeDispatcher) {
                session.decodeRegion(spec.decodeRegion, spec.sampleSize)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // A tile that fails to decode used to disappear silently: the caller sees a missing tile
            // and the frame falls back to the coarse overview, which reads as a quality bug rather
            // than a decode failure.
            android.util.Log.w(
                "ReaderTile",
                "tile decode failed kind=${spec.key.kind} sampleSize=${spec.key.sampleSize} " +
                    "logical=${spec.logicalRect} decodeRegion=${spec.decodeRegion}",
                error,
            )
            notifyTileFailed(spec.key.pageId, spec.key, error)
            return
        }
        currentCoroutineContext().ensureActive()

        val cost = costOf(payload)
        val evicted = budget.charge(spec.key, cost, retention)
        if (evicted.isEmpty()) {
            val tile = ReaderTile(spec, payload, cost)
            mutableTiles.update { it + (spec.key to tile) }
            notifyTileReady(tile)
            return
        }

        val evictedSet = evicted.toHashSet()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            Trace.setCounter("Reader.TileEvictions", evictionCount.addAndGet(evictedSet.size.toLong()))
        }
        val releasedPayloads = ArrayList<Any>()
        mutableTiles.update { map ->
            for (key in evictedSet) {
                map[key]?.let { releasedPayloads.add(it.payload) }
            }
            (map + (spec.key to ReaderTile(spec, payload, cost))) - evictedSet
        }
        releasedPayloads.forEach(payloadReleaser)
        for (key in evictedSet) notifyTileDropped(key)
        if (spec.key in evictedSet) {
            // Our own payload was trimmed away by the budget.
            payloadReleaser(payload)
            return
        }
        notifyTileReady(ReaderTile(spec, payload, cost))
    }

    private suspend fun openSessionFor(pageId: PageId): TileDecodeSession {
        sessions[pageId]?.let { return awaitSession(pageId, it) }

        val source = sourceFactory(pageId)
        if (source == null) {
            // Permanent configuration gap: report at most once per residency.
            notifyMissingSource(pageId)
            throw NoSuchRegionDecodeSourceException(pageId)
        }
        // Wrap into Result: a failed `async` would otherwise cancel the manager
        // scope through structured concurrency; the failure must stay local so
        // the listener protocol (and a later retry) can handle it.
        val created = scope.async {
            try {
                Result.success(source.openSession())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Result.failure<TileDecodeSession>(error)
            }
        }
        val winner = sessions.putIfAbsent(pageId, created)
        if (winner != null) {
            // Another request won registration; await theirs. Cancelling ours is
            // best-effort cleanup: if it already finished, cancel is a no-op.
            created.cancel()
            return awaitSession(pageId, winner)
        }
        return awaitSession(pageId, created)
    }

    private suspend fun awaitSession(
        pageId: PageId,
        deferred: Deferred<Result<TileDecodeSession>>,
    ): TileDecodeSession {
        val outcome = try {
            deferred.await()
        } catch (cancellation: CancellationException) {
            sessions.remove(pageId, deferred)
            throw cancellation
        }
        return try {
            outcome.getOrThrow()
        } catch (error: Throwable) {
            if (sessions.remove(pageId, deferred)) {
                // We are the first awaiter to observe this failure: report once.
                notifySessionFailed(pageId, error)
            }
            throw error
        }
    }

    private fun closeSession(pageId: PageId) {
        val deferred = sessions.remove(pageId) ?: return
        // Improvement plan 2026-09 zoom trace analysis: closing must hop off the caller
        // (host main) thread — TileDecodeSession.close() takes a fair write lock that
        // waits for in-flight readers to drain, which took 0.7s+ of main-thread sleep
        // during decode storms. NonCancellable guards the close itself once started:
        // a decoder leaking its native instance is worse than a late close.
        scope.launch(decodeDispatcher) {
            val traceClose = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()
            if (traceClose) Trace.beginSection("Reader.TileSessionClose")
            try {
                withContext(NonCancellable) {
                    deferred.await().getOrNull()?.close()
                }
            } catch (cancellation: CancellationException) {
                deferred.cancel()
            } catch (_: Throwable) {
                // Closing a failed session is best-effort.
            } finally {
                if (traceClose) Trace.endSection()
            }
        }
    }

    private fun notifyMissingSource(pageId: PageId) {
        if (notifiedMissingSources.add(pageId)) {
            notifySessionFailed(pageId, NoSuchRegionDecodeSourceException(pageId))
        }
    }

    private fun notifyTileReady(tile: ReaderTile) {
        val snapshot = synchronized(listenersLock) { listeners.toList() }
        snapshot.forEach { it.onTileReady(tile) }
    }

    private fun notifyTileDropped(key: TileKey) {
        val snapshot = synchronized(listenersLock) { listeners.toList() }
        snapshot.forEach { it.onTileDropped(key) }
    }

    private fun notifySessionFailed(pageId: PageId, error: Throwable) {
        val snapshot = synchronized(listenersLock) { listeners.toList() }
        snapshot.forEach { it.onSessionFailed(pageId, error) }
    }

    private fun notifyTileFailed(pageId: PageId, key: TileKey, error: Throwable) {
        val snapshot = synchronized(listenersLock) { listeners.toList() }
        snapshot.forEach { it.onTileFailed(pageId, key, error) }
    }

    private class NoSuchRegionDecodeSourceException(pageId: PageId) :
        IllegalStateException("No RegionDecodeSource available for $pageId")

    private companion object {
        const val DEMOTE_TO_CACHE_AFTER_MISSES = 2
    }
}
