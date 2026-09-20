package org.skepsun.kototoro.reader.core

import kotlin.math.abs
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Oracle parity for [PagedReaderScene] viewport queries (improvement plan 2026-09 §6.1).
 *
 * The scene is being upgraded from a full linear slot scan to an O(1) range location +
 * O(k) candidate enumeration built on the fixed slot primary extent. The linear algorithms
 * below are verbatim copies of the pre-upgrade implementation and act as the oracle:
 * nodes, their order, the progress snapshot and the active-slot tie rules must stay
 * byte-identical under randomized and boundary viewports.
 */
class PagedReaderSceneRangeQueryTest {

    // ---------------------------------------------------------------------------------------------
    // Linear oracles (verbatim semantics of the pre-range-query implementation)
    // ---------------------------------------------------------------------------------------------

    private fun oracleResolve(scene: PagedReaderScene, viewport: ReaderViewport): ReaderFrame {
        val slots = scene.allSlots
        if (slots.isEmpty()) {
            return ReaderFrame(
                viewport = viewport,
                visibleNodes = emptyList(),
                progress = ReaderProgressSnapshot(
                    lowerPageId = null,
                    upperPageId = null,
                    activePageId = null,
                    intraPageOffsetPx = 0f,
                ),
            )
        }

        val visibleNodes = mutableListOf<VisibleNode>()
        var activeSlot: PagedSlot? = null
        var maxIntersectArea = -1f

        for (slot in slots) {
            val slotIntersect = slot.bounds.intersectionOrNull(viewport.bounds)
            if (slotIntersect != null && slotIntersect.width > 0f && slotIntersect.height > 0f) {
                val area = slotIntersect.width * slotIntersect.height
                if (area > maxIntersectArea) {
                    maxIntersectArea = area
                    activeSlot = slot
                }
                for (placement in slot.placements) {
                    val pageIntersect = placement.sceneBounds.intersectionOrNull(viewport.bounds)
                    if (pageIntersect != null && pageIntersect.width > 0f && pageIntersect.height > 0f) {
                        visibleNodes.add(VisibleNode(placement.pageId, placement.sceneBounds, pageIntersect))
                    }
                }
            }
        }

        val resolvedActiveSlot = activeSlot ?: oracleActiveSlot(scene, viewport)
        val activeAnchorId = resolvedActiveSlot?.progressAnchorPageId
        val isHorizontal = scene.readingDirection.isHorizontal
        val intraOffset = if (resolvedActiveSlot != null) {
            if (isHorizontal) {
                viewport.bounds.left - resolvedActiveSlot.bounds.left
            } else {
                viewport.bounds.top - resolvedActiveSlot.bounds.top
            }
        } else {
            0f
        }

        val firstVisiblePageId = visibleNodes.firstOrNull()?.pageId ?: activeAnchorId
        val lastVisiblePageId = visibleNodes.lastOrNull()?.pageId ?: activeAnchorId

        return ReaderFrame(
            viewport = viewport,
            visibleNodes = visibleNodes,
            progress = ReaderProgressSnapshot(
                lowerPageId = firstVisiblePageId,
                upperPageId = lastVisiblePageId,
                activePageId = activeAnchorId,
                intraPageOffsetPx = intraOffset,
            ),
        )
    }

    private fun oracleActiveSlot(scene: PagedReaderScene, viewport: ReaderViewport): PagedSlot? {
        val slots = scene.allSlots
        if (slots.isEmpty()) return null
        val isHorizontal = scene.readingDirection.isHorizontal
        val vpCenter = if (isHorizontal) {
            viewport.bounds.left + viewport.bounds.width / 2f
        } else {
            viewport.bounds.top + viewport.bounds.height / 2f
        }

        var closestSlot: PagedSlot? = null
        var minDistance = Float.MAX_VALUE
        for (slot in slots) {
            val slotCenter = if (isHorizontal) {
                slot.bounds.left + slot.bounds.width / 2f
            } else {
                slot.bounds.top + slot.bounds.height / 2f
            }
            val distance = abs(vpCenter - slotCenter)
            if (distance < minDistance) {
                minDistance = distance
                closestSlot = slot
            }
        }
        return closestSlot
    }

    private fun oracleIndexOf(scene: PagedReaderScene, pageId: PageId): Int {
        var index = 0
        for (slot in scene.allSlots) {
            for (placement in slot.placements) {
                if (placement.pageId == pageId) return index
                index++
            }
        }
        return -1
    }

    private fun oracleSlotIndexOf(scene: PagedReaderScene, pageId: PageId): Int {
        for (i in scene.allSlots.indices) {
            if (scene.allSlots[i].containsPage(pageId)) return i
        }
        return -1
    }

    // ---------------------------------------------------------------------------------------------
    // Randomized scene / viewport factories
    // ---------------------------------------------------------------------------------------------

    private class SceneFixture(val scene: PagedReaderScene) {
        val primaryExtent: Float = if (scene.readingDirection.isHorizontal) {
            scene.availableWidth.toFloat()
        } else {
            scene.viewportHeight.toFloat()
        }
    }

    private fun randomScene(random: Random): SceneFixture {
        val viewportWidth = random.nextInt(from = 320, until = 1600)
        val viewportHeight = random.nextInt(from = 480, until = 2400)
        val direction = SceneReadingDirection.entries[random.nextInt(SceneReadingDirection.entries.size)]
        val zoomMode = ZoomMode.entries[random.nextInt(ZoomMode.entries.size)]
        val isDoublePage = random.nextBoolean()
        val isCoverOffset = random.nextBoolean()
        val pageCount = random.nextInt(from = 1, until = 40)
        val chapterCount = random.nextInt(from = 1, until = 4)

        var nextPage = 1L
        val specs = ArrayList<PagedPageSpec>(pageCount)
        for (chapter in 0 until chapterCount) {
            val chapterLength = random.nextInt(from = 1, until = pageCount / chapterCount + 2)
            for (pageIndex in 0 until chapterLength) {
                if (specs.size >= pageCount) break
                val hint = when (random.nextInt(3)) {
                    0 -> PageGeometryHint.Exact(
                        random.nextInt(from = 200, until = 4000),
                        random.nextInt(from = 200, until = 4000),
                    )
                    1 -> PageGeometryHint.AspectRatio(random.nextFloat() * 3f + 0.2f)
                    else -> PageGeometryHint.Estimated(random.nextFloat() * 3f + 0.2f)
                }
                val segment = when (random.nextInt(10)) {
                    0 -> PageSegment.LEFT_HALF
                    1 -> PageSegment.RIGHT_HALF
                    else -> PageSegment.FULL
                }
                val spread = when (random.nextInt(12)) {
                    0, 1 -> SpreadBehavior.SOLO
                    2 -> SpreadBehavior.PAIRABLE
                    else -> SpreadBehavior.AUTO
                }
                specs.add(
                    PagedPageSpec(
                        pageId = PageId(nextPage++),
                        geometryHint = hint,
                        chapterId = chapter.toLong() + 1L,
                        chapterPageIndex = pageIndex,
                        segment = segment,
                        spreadBehavior = spread,
                    ),
                )
            }
        }

        val scene = PagedReaderScene(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            config = PagedSpreadConfig(
                isDoublePage = isDoublePage,
                isCoverOffset = isCoverOffset,
                readingDirection = direction,
                pageSpacingPx = random.nextInt(0, 24),
                zoomMode = zoomMode,
            ),
            initialSpecs = specs,
        )
        return SceneFixture(scene)
    }

    private fun randomViewportFor(fixture: SceneFixture, random: Random): ReaderViewport {
        val scene = fixture.scene
        val isHorizontal = scene.readingDirection.isHorizontal
        val primaryExtent = fixture.primaryExtent
        val crossExtent = if (isHorizontal) {
            scene.viewportHeight.toFloat()
        } else {
            scene.availableWidth.toFloat()
        }
        val totalExtent = scene.totalSceneExtent

        // Primary start anywhere from two slots before the scene to two slots past its end.
        val primaryStart = (random.nextFloat() * (totalExtent + 4f * primaryExtent)) - 2f * primaryExtent
        // Widths from degenerate to spanning several slots (wide viewport).
        val primarySize = when (random.nextInt(6)) {
            0 -> 0f
            1 -> primaryExtent * 0.01f
            2 -> primaryExtent
            3 -> primaryExtent * (1f + random.nextFloat() * 0.6f)
            4 -> primaryExtent * (2f + random.nextFloat() * 2f)
            else -> primaryExtent * random.nextFloat()
        }
        val crossStart = when (random.nextInt(8)) {
            0 -> -crossExtent * random.nextFloat()
            1 -> crossExtent * (1f + random.nextFloat())
            else -> 0f
        }
        val crossSize = when (random.nextInt(8)) {
            0 -> 0f
            1 -> crossExtent * 3f
            else -> crossExtent
        }

        val bounds = if (isHorizontal) {
            FloatRect.fromLtwh(primaryStart, crossStart, primarySize, crossSize)
        } else {
            FloatRect.fromLtwh(crossStart, primaryStart, crossSize, primarySize)
        }
        return ReaderViewport(bounds)
    }

    // ---------------------------------------------------------------------------------------------
    // Parity tests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `resolve matches the linear oracle across randomized scenes and viewports`() {
        val random = Random(20260920)
        repeat(80) { iteration ->
            val fixture = randomScene(random)
            repeat(30) {
                val viewport = randomViewportFor(fixture, random)
                val expected = oracleResolve(fixture.scene, viewport)
                val actual = fixture.scene.resolve(viewport)
                assertEquals(
                    expected.visibleNodes,
                    actual.visibleNodes,
                    "visibleNodes mismatch (iteration=$iteration, viewport=${viewport.bounds}, " +
                        "direction=${fixture.scene.readingDirection}, slots=${fixture.scene.slotCount})",
                )
                assertEquals(
                    expected.progress,
                    actual.progress,
                    "progress mismatch (iteration=$iteration, viewport=${viewport.bounds}, " +
                        "direction=${fixture.scene.readingDirection}, slots=${fixture.scene.slotCount})",
                )
            }
        }
    }

    @Test
    fun `resolveActivePageId matches the nearest-center oracle across randomized viewports`() {
        val random = Random(777)
        repeat(40) {
            val fixture = randomScene(random)
            repeat(30) {
                val viewport = randomViewportFor(fixture, random)
                val expected = oracleActiveSlot(fixture.scene, viewport)?.progressAnchorPageId
                assertEquals(
                    expected,
                    fixture.scene.resolveActivePageId(viewport),
                    "active anchor mismatch (viewport=${viewport.bounds}, " +
                        "direction=${fixture.scene.readingDirection})",
                )
            }
        }
    }

    @Test
    fun `empty scene resolves an empty frame without crashing`() {
        val scene = PagedReaderScene(viewportWidth = 800, viewportHeight = 1200)
        val viewport = ReaderViewport(FloatRect.fromLtwh(0f, 0f, 800f, 1200f))

        val expected = oracleResolve(scene, viewport)
        val actual = scene.resolve(viewport)

        assertEquals(0, actual.visibleNodes.size)
        assertEquals(expected.progress, actual.progress)
        assertNull(actual.progress.activePageId)
        assertNull(scene.resolveActivePageId(viewport))
        assertEquals(-1, scene.indexOf(PageId(1L)))
        assertEquals(-1, scene.slotIndexOf(PageId(1L)))
    }

    @Test
    fun `viewport entirely before the scene falls back to the first slot anchor`() {
        val scene = PagedReaderScene(
            viewportWidth = 1000,
            viewportHeight = 1200,
            config = PagedSpreadConfig(readingDirection = SceneReadingDirection.LEFT_TO_RIGHT),
            initialSpecs = singlePageSpecs(4),
        )
        val viewport = ReaderViewport(FloatRect.fromLtwh(-3000f, 0f, 1000f, 1200f))

        assertEquals(oracleResolve(scene, viewport), scene.resolve(viewport))
        assertEquals(oracleActiveSlot(scene, viewport)?.progressAnchorPageId, scene.resolveActivePageId(viewport))
        assertEquals(PageId(1L), scene.resolveActivePageId(viewport))
    }

    @Test
    fun `viewport entirely past the scene end falls back to the last slot anchor`() {
        val scene = PagedReaderScene(
            viewportWidth = 1000,
            viewportHeight = 1200,
            config = PagedSpreadConfig(readingDirection = SceneReadingDirection.LEFT_TO_RIGHT),
            initialSpecs = singlePageSpecs(4),
        )
        val viewport = ReaderViewport(FloatRect.fromLtwh(9000f, 0f, 1000f, 1200f))

        assertEquals(oracleResolve(scene, viewport), scene.resolve(viewport))
        assertEquals(PageId(4L), scene.resolveActivePageId(viewport))
    }

    @Test
    fun `viewport aligned to an exact slot boundary does not include the closed-out slot`() {
        // Slot 0 occupies [0, 1000). A viewport ending exactly at 1000 must not see slot 0's
        // neighbour-free geometry: a viewport [1000, 2000] starts exactly at slot 1's origin.
        val scene = PagedReaderScene(
            viewportWidth = 1000,
            viewportHeight = 1200,
            config = PagedSpreadConfig(readingDirection = SceneReadingDirection.LEFT_TO_RIGHT),
            initialSpecs = singlePageSpecs(4),
        )
        val viewport = ReaderViewport(FloatRect.fromLtwh(1000f, 0f, 1000f, 1200f))

        val frame = scene.resolve(viewport)
        assertEquals(oracleResolve(scene, viewport), frame)
        assertEquals(listOf(PageId(2L)), frame.visibleNodes.map { it.pageId })
    }

    @Test
    fun `viewport centered on a slot boundary keeps the lower slot active on equal areas`() {
        // A viewport straddling the [500, 1500] boundary sees slot 0 and slot 1 with equal
        // intersection area; the linear scan keeps the earlier slot (strict >).
        val scene = PagedReaderScene(
            viewportWidth = 1000,
            viewportHeight = 1200,
            config = PagedSpreadConfig(readingDirection = SceneReadingDirection.LEFT_TO_RIGHT),
            initialSpecs = singlePageSpecs(4),
        )
        val viewport = ReaderViewport(FloatRect.fromLtwh(500f, 0f, 1000f, 1200f))

        val frame = scene.resolve(viewport)
        assertEquals(oracleResolve(scene, viewport), frame)
        assertEquals(PageId(1L), frame.progress.activePageId)
    }

    @Test
    fun `viewport center equidistant between two slots resolves to the lower slot`() {
        // Center at exactly 1000 (the boundary between slot 0 and slot 1): both centers are
        // 500 away; the nearest-center scan keeps the lower index on ties.
        val scene = PagedReaderScene(
            viewportWidth = 1000,
            viewportHeight = 1200,
            config = PagedSpreadConfig(readingDirection = SceneReadingDirection.LEFT_TO_RIGHT),
            initialSpecs = singlePageSpecs(4),
        )
        val viewport = ReaderViewport(FloatRect.fromLtwh(600f, 0f, 800f, 1200f))

        assertEquals(PageId(1L), scene.resolveActivePageId(viewport))
        assertEquals(oracleActiveSlot(scene, viewport)?.progressAnchorPageId, scene.resolveActivePageId(viewport))
    }

    @Test
    fun `wide viewport covering many slots resolves all of them in reading order`() {
        for (direction in SceneReadingDirection.entries) {
            val scene = PagedReaderScene(
                viewportWidth = 1000,
                viewportHeight = 1200,
                config = PagedSpreadConfig(readingDirection = direction, isDoublePage = true),
                initialSpecs = singlePageSpecs(8),
            )
            // Straddle the scene two-and-a-half primary strides in. Note the stride differs
            // per direction: viewportWidth (1000) horizontally, viewportHeight (1200)
            // vertically — so the vertical view starts inside slot 1 and shows BOTH halves
            // of the straddled pairs, while horizontal views clip at the in-slot boundary.
            val primaryStart = 1500f
            val viewport = if (direction.isHorizontal) {
                ReaderViewport(FloatRect.fromLtwh(primaryStart, 0f, 3000f, 1200f))
            } else {
                ReaderViewport(FloatRect.fromLtwh(0f, primaryStart, 1000f, 3000f))
            }

            val frame = scene.resolve(viewport)
            val expected = oracleResolve(scene, viewport)
            assertEquals(expected.visibleNodes, frame.visibleNodes, "direction=$direction")
            assertEquals(expected.progress, frame.progress, "direction=$direction")
            // In-slot placements matter: each 800x1200 page fits a 500-wide half, so in
            // horizontal modes the page whose half ends exactly at 1500 is out (zero-width
            // intersection); vertically both halves of a pair stay inside the slot's y range.
            val expectedPages = when (direction) {
                SceneReadingDirection.RIGHT_TO_LEFT -> listOf(3L, 5L, 6L, 7L, 8L)
                SceneReadingDirection.LEFT_TO_RIGHT -> listOf(4L, 5L, 6L, 7L, 8L)
                SceneReadingDirection.TOP_TO_BOTTOM -> listOf(3L, 4L, 5L, 6L, 7L, 8L)
            }
            assertEquals(expectedPages, frame.visibleNodes.map { it.pageId.value }, "direction=$direction")
        }
    }

    @Test
    fun `double page and direction keep page order and anchors oracle-equal`() {
        val random = Random(31337)
        repeat(60) {
            val fixture = randomScene(random)
            for (multiplier in listOf(0.5f, 1f, 2.5f)) {
                val extent = fixture.primaryExtent
                val start = extent * multiplier * random.nextFloat()
                val isHorizontal = fixture.scene.readingDirection.isHorizontal
                val viewport = if (isHorizontal) {
                    ReaderViewport(FloatRect.fromLtwh(start, 0f, extent * multiplier, fixture.scene.viewportHeight.toFloat()))
                } else {
                    ReaderViewport(FloatRect.fromLtwh(0f, start, fixture.scene.availableWidth.toFloat(), extent * multiplier))
                }
                val expected = oracleResolve(fixture.scene, viewport)
                val actual = fixture.scene.resolve(viewport)
                assertEquals(expected.visibleNodes, actual.visibleNodes)
                assertEquals(expected.progress, actual.progress)
            }
        }
    }

    @Test
    fun `page and slot indices match the linear scan before and after rebuilds`() {
        val specs = singlePageSpecs(6)
        val scene = PagedReaderScene(
            viewportWidth = 1600,
            viewportHeight = 1200,
            config = PagedSpreadConfig(isDoublePage = true, readingDirection = SceneReadingDirection.RIGHT_TO_LEFT),
            initialSpecs = specs,
        )

        fun assertIndicesMatch() {
            for (spec in specs) {
                assertEquals(
                    oracleIndexOf(scene, spec.pageId),
                    scene.indexOf(spec.pageId),
                    "indexOf(${spec.pageId})",
                )
                assertEquals(
                    oracleSlotIndexOf(scene, spec.pageId),
                    scene.slotIndexOf(spec.pageId),
                    "slotIndexOf(${spec.pageId})",
                )
            }
            assertEquals(-1, scene.indexOf(PageId(999L)))
            assertEquals(-1, scene.slotIndexOf(PageId(999L)))
        }

        assertIndicesMatch()

        // A geometry update re-groups slots: page 2 becomes a wide solo page, reshuffling
        // every later slot. Both indices must track the rebuilt layout.
        scene.updatePageHint(
            pageId = PageId(2L),
            newHint = PageGeometryHint.Exact(2400, 1200),
            currentViewport = ReaderViewport(FloatRect.fromLtwh(0f, 0f, 1600f, 1200f)),
        )
        assertIndicesMatch()

        // Prepending a chapter shifts slot indices again.
        val prepended = listOf(
            PagedPageSpec(PageId(100L), PageGeometryHint.Exact(800, 1200), chapterId = 9L, chapterPageIndex = 0),
            PagedPageSpec(PageId(101L), PageGeometryHint.Exact(800, 1200), chapterId = 9L, chapterPageIndex = 1),
        ) + specs
        scene.updatePagedPages(prepended, null)
        for (spec in prepended) {
            assertEquals(oracleIndexOf(scene, spec.pageId), scene.indexOf(spec.pageId), "indexOf(${spec.pageId})")
            assertEquals(oracleSlotIndexOf(scene, spec.pageId), scene.slotIndexOf(spec.pageId), "slotIndexOf(${spec.pageId})")
        }
        assertEquals(-1, scene.indexOf(PageId(999L)))
    }

    @Test
    fun `resolvePageScrollPosition and viewport origins agree with slot indices`() {
        val scene = PagedReaderScene(
            viewportWidth = 1000,
            viewportHeight = 1200,
            config = PagedSpreadConfig(readingDirection = SceneReadingDirection.RIGHT_TO_LEFT),
            initialSpecs = singlePageSpecs(5),
        )
        for (spec in singlePageSpecs(5)) {
            val slotIndex = oracleSlotIndexOf(scene, spec.pageId)
            assertEquals(slotIndex * 1000f, scene.resolvePageScrollPosition(spec.pageId))
            val scrollPosition = scene.resolvePageScrollPosition(spec.pageId)
            assertNotNull(scrollPosition)
            assertEquals(
                scrollPosition!! + 42f,
                scene.resolveViewportOriginForPage(spec.pageId, 1000f, 42f),
            )
        }
        assertNull(scene.resolvePageScrollPosition(PageId(404L)))
    }

    // ---------------------------------------------------------------------------------------------
    // Range query API: base candidate range + exact-intersection slot set
    // ---------------------------------------------------------------------------------------------

    private fun linearIntersectingSlots(scene: PagedReaderScene, bounds: FloatRect): List<PagedSlot> {
        return scene.allSlots.filter { slot ->
            val intersect = slot.bounds.intersectionOrNull(bounds)
            intersect != null && intersect.width > 0f && intersect.height > 0f
        }
    }

    @Test
    fun `slotsIntersecting matches the linear filter across randomized scenes and viewports`() {
        val random = Random(9182736)
        repeat(50) {
            val fixture = randomScene(random)
            repeat(20) {
                val viewport = randomViewportFor(fixture, random)
                assertEquals(
                    linearIntersectingSlots(fixture.scene, viewport.bounds),
                    fixture.scene.slotsIntersecting(viewport.bounds),
                    "viewport=${viewport.bounds}, direction=${fixture.scene.readingDirection}, " +
                        "slots=${fixture.scene.slotCount}",
                )
            }
        }
    }

    @Test
    fun `slotsIntersecting on an empty scene is empty`() {
        val scene = PagedReaderScene(viewportWidth = 800, viewportHeight = 1200)
        assertEquals(0, scene.slotsIntersecting(FloatRect.fromLtwh(0f, 0f, 800f, 1200f)).size)
    }

    @Test
    fun `slotIndexRangeFor is a superset of the exactly intersecting slots`() {
        val random = Random(555)
        repeat(40) {
            val fixture = randomScene(random)
            repeat(15) {
                val viewport = randomViewportFor(fixture, random)
                val range = fixture.scene.slotIndexRangeFor(viewport.bounds)
                for ((index, slot) in fixture.scene.allSlots.withIndex()) {
                    val intersect = slot.bounds.intersectionOrNull(viewport.bounds)
                    val isIntersecting = intersect != null && intersect.width > 0f && intersect.height > 0f
                    if (isIntersecting) {
                        assertTrue(
                            index in range,
                            "intersecting slot $index missing from candidate range $range " +
                                "(viewport=${viewport.bounds}, direction=${fixture.scene.readingDirection})",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `candidate range stays bounded by viewport span not page count`() {
        // 50 / 500 / 5000 pages with a fixed visible window: the candidate range (and thus
        // the per-query work) must not grow with the total page count.
        for (pageCount in intArrayOf(50, 500, 5000)) {
            val scene = PagedReaderScene(
                viewportWidth = 1080,
                viewportHeight = 2400,
                config = PagedSpreadConfig(readingDirection = SceneReadingDirection.RIGHT_TO_LEFT),
                initialSpecs = (0 until pageCount).map { index ->
                    PagedPageSpec(PageId(index.toLong()), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = index)
                },
            )
            val offsets = listOf(
                0f,
                1080f * pageCount / 2f,
                1080f * (pageCount - 1),
                -1080f * 3f,
                1080f * (pageCount + 3f),
            )
            for (offset in offsets) {
                for (spanFactor in listOf(0.5f, 1f, 2.5f)) {
                    val vp = ReaderViewport(FloatRect.fromLtwh(offset, 0f, 1080f * spanFactor, 2400f))
                    val range = scene.slotIndexRangeFor(vp.bounds)
                    val spanSlots = (spanFactor + 1f).toInt() + 3
                    assertTrue(
                        range.last - range.first + 1 <= spanSlots,
                        "pageCount=$pageCount offset=$offset span=$spanFactor range=$range",
                    )
                    // Semantics must be oracle-identical everywhere, including far
                    // out-of-bounds windows.
                    val expected = oracleResolve(scene, vp)
                    val actual = scene.resolve(vp)
                    assertEquals(expected.visibleNodes, actual.visibleNodes, "pageCount=$pageCount viewport=${vp.bounds}")
                    assertEquals(expected.progress, actual.progress, "pageCount=$pageCount viewport=${vp.bounds}")
                }
            }
        }
    }

    @Test
    fun `query cost does not grow with chapter length for a fixed visible window`() {
        data class Measurement(val pageCount: Int, val rebuildNanos: Long, val resolveNanosPerQuery: Long)

        val measurements = mutableListOf<Measurement>()
        for (pageCount in intArrayOf(50, 500, 5000)) {
            val specs = (0 until pageCount).map { index ->
                PagedPageSpec(PageId(index.toLong()), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = index)
            }

            // Rebuild cost is counted separately: setPagedPages is O(n) by design (slot
            // resolution + index rebuild), the per-query path must not be.
            val rebuildStart = System.nanoTime()
            val scene = PagedReaderScene(
                viewportWidth = 1080,
                viewportHeight = 2400,
                config = PagedSpreadConfig(readingDirection = SceneReadingDirection.RIGHT_TO_LEFT),
                initialSpecs = specs,
            )
            val rebuildNanos = System.nanoTime() - rebuildStart

            fun viewportAt(queryIndex: Int): ReaderViewport {
                val strideSteps = pageCount.coerceAtLeast(1) - 1
                val offset = 1080f * (queryIndex % strideSteps)
                return ReaderViewport(FloatRect.fromLtwh(offset, 0f, 1080f, 2400f))
            }

            // JIT warmup, then measure a fixed window across the whole chapter.
            var sink = 0
            repeat(200) { sink += scene.resolve(viewportAt(it)).visibleNodes.size }
            val queryCount = 1000
            val start = System.nanoTime()
            repeat(queryCount) { sink += scene.resolve(viewportAt(it)).visibleNodes.size }
            val perQuery = (System.nanoTime() - start) / queryCount

            assertTrue(sink >= 0)
            assertTrue(perQuery in 1..200_000, "pageCount=$pageCount perQuery=${perQuery}ns")
            measurements.add(Measurement(pageCount, rebuildNanos, perQuery))
        }

        // Non-growth assertion: a linear scan would make 5000 pages ~100x the 50-page cost;
        // the range query must keep it inside generous JIT-noise headroom.
        val perQuery50 = measurements[0].resolveNanosPerQuery.coerceAtLeast(1)
        val perQuery5000 = measurements[2].resolveNanosPerQuery
        assertTrue(
            perQuery5000 <= perQuery50 * 10,
            "query cost grew with page count: 50p=${perQuery50}ns vs 5000p=${perQuery5000}ns ($measurements)",
        )

        // Evidence trail (also captured in the JUnit XML system-out) for the delivery record.
        println("PagedReaderScene fixed-window query cost (1000 queries after 200 warmups):")
        for (m in measurements) {
            println(
                "  pageCount=${m.pageCount} rebuild=${"%.2f".format(m.rebuildNanos / 1e6)}ms " +
                    "resolve/query=${m.resolveNanosPerQuery}ns",
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun singlePageSpecs(count: Int): List<PagedPageSpec> = (1..count).map { index ->
        PagedPageSpec(PageId(index.toLong()), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = index - 1)
    }
}
