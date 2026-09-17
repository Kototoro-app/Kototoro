package org.skepsun.kototoro.reader.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.core.IntRect
import org.skepsun.kototoro.reader.core.IntSize
import org.skepsun.kototoro.reader.core.PageId
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderTileManagerTest {

    private val pageId = PageId(1L)

    private class FakeTileDecodeSession(override val encodedSize: IntSize) : TileDecodeSession {
        val decoded = mutableListOf<Pair<IntRect, Int>>()
        var gate: CompletableDeferred<Unit>? = null
        var failAll: Throwable? = null
        var closed = false

        override suspend fun decodeRegion(region: IntRect, sampleSize: Int): Any {
            failAll?.let { throw it }
            val g = gate
            if (g != null) {
                gate = null // single-shot: later tiles pass through
                g.await()
            }
            decoded += region to sampleSize
            return "payload:${region.left},${region.top},${region.width}x${region.height}@${sampleSize}"
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeRegionDecodeSource(
        override val metadata: ImageSourceMetadata,
        val session: TileDecodeSession,
    ) : RegionDecodeSource {
        var openCount = 0
        var failNextOpen: Throwable? = null

        override suspend fun openSession(): TileDecodeSession {
            openCount++
            failNextOpen?.let {
                failNextOpen = null
                throw it
            }
            return session
        }
    }

    private class RecordingListener : ReaderTileManager.Listener {
        val ready = mutableListOf<ReaderTile>()
        val dropped = mutableListOf<TileKey>()
        val sessionFailures = mutableListOf<Pair<PageId, Throwable>>()
        val tileFailures = mutableListOf<Triple<PageId, TileKey, Throwable>>()

        override fun onTileReady(tile: ReaderTile) {
            ready += tile
        }

        override fun onTileDropped(key: TileKey) {
            dropped += key
        }

        override fun onSessionFailed(pageId: PageId, error: Throwable) {
            sessionFailures += pageId to error
        }

        override fun onTileFailed(pageId: PageId, key: TileKey, error: Throwable) {
            tileFailures += Triple(pageId, key, error)
        }
    }

    private fun stripGrid(
        sampleSize: Int = 1,
        tileHeight: Int = 2048,
        gutter: Int = 128,
        page: PageId = pageId,
    ): TileGrid = TileGrid(
        pageId = page,
        geometry = ImageSourceGeometry(IntSize(800, 16000)),
        tileDimension = IntSize(800, tileHeight),
        sampleSize = sampleSize,
        outputGutterPx = gutter,
    )

    private fun source(
        encoded: IntSize = IntSize(800, 16000),
    ): FakeRegionDecodeSource = FakeRegionDecodeSource(
        metadata = ImageSourceMetadata(size = encoded, mimeType = "image/jpeg"),
        session = FakeTileDecodeSession(encoded),
    )

    private fun TestScope.makeManager(
        source: FakeRegionDecodeSource,
        budgetBytes: Long = 1_000_000L,
    ): Triple<ReaderTileManager, RecordingListener, MutableList<Any>> {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val listener = RecordingListener()
        val released = mutableListOf<Any>()
        val manager = ReaderTileManager(
            scope = this,
            decodeDispatcher = dispatcher,
            budget = TileMemoryBudget(maxBytes = budgetBytes),
            sourceFactory = { if (it == pageId) source else null },
            costOf = { TILE_COST },
            payloadReleaser = { released += it },
        )
        manager.addListener(listener)
        return Triple(manager, listener, released)
    }

    @Test
    fun `requestTiles decodes only intersecting tiles reusing one session`() = runTest {
        val source = source()
        val (manager, listener, _) = makeManager(source)
        val grid = stripGrid()

        // Visible rows 0..1 + gutter halo pulls row 2.
        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 4096))
        advanceUntilIdle()

        assertEquals(1, source.openCount)
        assertEquals(3, manager.tiles.value.size)
        assertEquals(setOf(0, 1, 2), manager.tiles.value.keys.map { it.row }.toSet())
        assertEquals(3, listener.ready.size)

        // Decode regions match TileGrid specs (top rows gutter-clamped at encoded edges).
        val expected = grid.tilesIntersecting(IntRect(0, 0, 800, 4096)).map { it.decodeRegion }
        assertEquals(expected, manager.tiles.value.values.map { it.spec.decodeRegion }.sortedBy { it.top })
    }

    @Test
    fun `lookahead region tiles are retained as NEARBY while visible stay VISIBLE`() = runTest {
        val source = source()
        val (manager, listener, _) = makeManager(source)
        val grid = stripGrid(gutter = 0, tileHeight = 1024)

        manager.requestTiles(
            grid = grid,
            visibleRegion = IntRect(0, 0, 800, 1024),
            lookaheadRegion = IntRect(0, 1024, 800, 2048),
        )
        advanceUntilIdle()

        val visibleKey = manager.tiles.value.keys.first { it.row == 0 }
        val nearbyKey = manager.tiles.value.keys.first { it.row == 1 }
        assertEquals(TileRetention.VISIBLE, manager.budget.retentionOf(visibleKey))
        assertEquals(TileRetention.NEARBY, manager.budget.retentionOf(nearbyKey))
        assertEquals(2, listener.ready.size)
    }

    @Test
    fun `demotion ladder steps resident tiles STANDBY then CACHE`() = runTest {
        val source = source()
        val (manager, _, _) = makeManager(source)
        val grid = stripGrid(gutter = 0, tileHeight = 1024)

        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 1024))
        advanceUntilIdle()
        val key = manager.tiles.value.keys.single()

        // Scroll away: first missed request -> STANDBY.
        manager.requestTiles(grid, visibleRegion = IntRect(0, 4096, 800, 5120))
        advanceUntilIdle()
        assertEquals(TileRetention.STANDBY, manager.budget.retentionOf(key))

        // Still absent: second miss -> CACHE.
        manager.requestTiles(grid, visibleRegion = IntRect(0, 4096, 800, 5120))
        advanceUntilIdle()
        assertEquals(TileRetention.CACHE, manager.budget.retentionOf(key))
    }

    @Test
    fun `pressure evicts CACHE before STANDBY and never VISIBLE`() = runTest {
        val source = source()
        // Budget: exactly two tiles.
        val (manager, listener, released) = makeManager(source, budgetBytes = TILE_COST * 2)
        val grid = stripGrid(gutter = 0, tileHeight = 1024)

        // row 0 visible, row 1 nearby.
        manager.requestTiles(
            grid,
            visibleRegion = IntRect(0, 0, 800, 1024),
            lookaheadRegion = IntRect(0, 1024, 800, 2048),
        )
        advanceUntilIdle()
        val row0 = manager.tiles.value.keys.first { it.row == 0 }
        val row1 = manager.tiles.value.keys.first { it.row == 1 }

        // Drop row 1 from desired set: misses 1 then 2 -> CACHE. Row 0 stays desired VISIBLE.
        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 1024))
        advanceUntilIdle()
        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 1024))
        advanceUntilIdle()
        assertEquals(TileRetention.VISIBLE, manager.budget.retentionOf(row0))
        assertEquals(TileRetention.CACHE, manager.budget.retentionOf(row1))

        // New visible row 2 charges the budget over the limit: row 1 (CACHE) is evicted,
        // row 0 (now STANDBY after its first miss) must survive.
        manager.requestTiles(grid, visibleRegion = IntRect(0, 2048, 800, 3072))
        advanceUntilIdle()

        assertTrue(listener.dropped.contains(row1), "CACHE tile should be evicted first, got ${listener.dropped}")
        assertTrue(listener.dropped.none { it == row0 }, "visible-pinned tiles must survive")
        assertNull(manager.tiles.value[row1])
        assertTrue(manager.tiles.value.containsKey(row0))
        assertTrue(released.isNotEmpty(), "evicted payloads must be released")
        assertEquals(TILE_COST * 2, manager.budget.residentBytes)
    }

    @Test
    fun `visible burst over budget stays resident`() = runTest {
        val source = source()
        val (manager, _, _) = makeManager(source, budgetBytes = TILE_COST)
        val grid = stripGrid(gutter = 0, tileHeight = 1024)

        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 2048))
        advanceUntilIdle()

        assertEquals(2, manager.tiles.value.size)
        assertEquals(TILE_COST * 2, manager.budget.residentBytes)
        manager.tiles.value.keys.forEach {
            assertEquals(TileRetention.VISIBLE, manager.budget.retentionOf(it))
        }
    }

    @Test
    fun `in-flight decode of an undesired tile is cancelled`() = runTest {
        val source = source()
        val fakeSession = FakeTileDecodeSession(IntSize(800, 16000))
        val realSource = FakeRegionDecodeSource(
            metadata = ImageSourceMetadata(IntSize(800, 16000)),
            session = fakeSession,
        )
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val manager = ReaderTileManager(
            scope = this,
            decodeDispatcher = dispatcher,
            budget = TileMemoryBudget(),
            sourceFactory = { if (it == pageId) realSource else null },
            costOf = { TILE_COST },
        )
        val grid = stripGrid(gutter = 0, tileHeight = 1024)

        val gate = CompletableDeferred<Unit>()
        fakeSession.gate = gate
        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 1024))
        advanceUntilIdle() // row 0 runs, consumes the single-shot gate, suspends on it
        // Row 0 decode is now suspended at the gate; nothing recorded yet.
        assertTrue(fakeSession.decoded.isEmpty())

        // Scroll far away: row 0 is no longer desired and its job is cancelled.
        manager.requestTiles(grid, visibleRegion = IntRect(0, 14336, 800, 15360))
        advanceUntilIdle()

        // The far row decodes (gate was single-shot consumed by the cancelled call).
        assertEquals(1, fakeSession.decoded.size)
        assertEquals(14336, fakeSession.decoded.single().first.top)
        assertNull(manager.tiles.value.keys.firstOrNull { it.row == 0 })

        // Releasing the gate must not resurrect the cancelled row-0 job.
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, fakeSession.decoded.size)
        assertNull(manager.tiles.value.keys.firstOrNull { it.row == 0 })
    }

    @Test
    fun `session open failure is reported once and the next request retries`() = runTest {
        val source = source()
        val (manager, listener, _) = makeManager(source)
        val grid = stripGrid(gutter = 0, tileHeight = 1024)

        source.failNextOpen = IOException("disk error")
        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 1024))
        advanceUntilIdle()

        assertTrue(manager.tiles.value.isEmpty())
        assertEquals(1, listener.sessionFailures.size)
        assertEquals(pageId, listener.sessionFailures.single().first)

        // Retry after the transient failure succeeds.
        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 1024))
        advanceUntilIdle()
        assertEquals(1, manager.tiles.value.size)
        assertEquals(1, listener.sessionFailures.size) // no repeated failure reports
        assertEquals(2, source.openCount)
    }

    @Test
    fun `tile decode failure is isolated from other tiles`() = runTest {
        val fakeSession = FakeTileDecodeSession(IntSize(800, 16000))
        fakeSession.failAll = IOException("corrupt region")
        val realSource = FakeRegionDecodeSource(
            metadata = ImageSourceMetadata(IntSize(800, 16000)),
            session = fakeSession,
        )
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val listener = RecordingListener()
        val manager = ReaderTileManager(
            scope = this,
            decodeDispatcher = dispatcher,
            budget = TileMemoryBudget(),
            sourceFactory = { if (it == pageId) realSource else null },
            costOf = { TILE_COST },
        )
        manager.addListener(listener)
        val grid = stripGrid(gutter = 0, tileHeight = 1024)

        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 2048))
        advanceUntilIdle()

        assertEquals(2, listener.tileFailures.size)
        assertTrue(manager.tiles.value.isEmpty())
        assertTrue(listener.sessionFailures.isEmpty())
    }

    @Test
    fun `releasePage cancels work drops tiles closes session and releases payloads`() = runTest {
        val source = source()
        val (manager, listener, released) = makeManager(source)
        val grid = stripGrid(gutter = 0, tileHeight = 1024)

        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 2048))
        advanceUntilIdle()
        assertEquals(2, manager.tiles.value.size)

        manager.releasePage(pageId)
        advanceUntilIdle()

        assertTrue(manager.tiles.value.isEmpty())
        assertEquals(0L, manager.budget.residentBytes)
        assertEquals(2, listener.dropped.size)
        assertEquals(2, released.size)
        assertTrue(source.session.let { it is FakeTileDecodeSession && it.closed }, "session must be closed")

        // Page can be re-entered: session reopens, tiles decode again.
        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 1024))
        advanceUntilIdle()
        assertEquals(1, manager.tiles.value.size)
        assertEquals(2, source.openCount)
    }

    @Test
    fun `requestOverview decodes full-page overview tile pinned visible`() = runTest {
        val source = source()
        val (manager, listener, _) = makeManager(source)
        val grid = stripGrid()

        manager.requestOverview(grid, overviewSampleSize = 8)
        advanceUntilIdle()

        val key = manager.tiles.value.keys.single()
        assertEquals(TileKind.OVERVIEW, key.kind)
        assertEquals(8, key.sampleSize)
        assertEquals(TileRetention.VISIBLE, manager.budget.retentionOf(key))
        assertEquals(1, listener.ready.size)

        // Subsequent lattice requests never demote the pinned overview.
        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 2048))
        advanceUntilIdle()
        assertEquals(TileRetention.VISIBLE, manager.budget.retentionOf(key))
        assertTrue(manager.tiles.value.containsKey(key))
    }

    @Test
    fun `missing source is reported once per residency`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val listener = RecordingListener()
        val manager = ReaderTileManager(
            scope = this,
            decodeDispatcher = dispatcher,
            budget = TileMemoryBudget(),
            sourceFactory = { null },
            costOf = { TILE_COST },
        )
        manager.addListener(listener)
        val grid = stripGrid(gutter = 0, tileHeight = 1024)

        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 1024))
        advanceUntilIdle()
        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 1024))
        advanceUntilIdle()

        assertEquals(1, listener.sessionFailures.size)

        manager.releasePage(pageId)
        manager.requestTiles(grid, visibleRegion = IntRect(0, 0, 800, 1024))
        advanceUntilIdle()
        assertEquals(2, listener.sessionFailures.size) // re-notified after re-entry
    }

    companion object {
        private const val TILE_COST = 1024L
    }
}
