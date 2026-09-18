package org.skepsun.kototoro.reader.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.core.model.ZoomMode
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PagedPanBoundsResolver
import org.skepsun.kototoro.reader.render.compose.SceneImagePresentationCoordinator
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.PageSegment
import org.skepsun.kototoro.reader.core.PagedPageSpec
import org.skepsun.kototoro.reader.core.PagedReaderScene
import org.skepsun.kototoro.reader.core.PagedSnapResolver
import org.skepsun.kototoro.reader.core.PagedSpreadConfig
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.ui.pager.ReaderAutoBackground
import org.skepsun.kototoro.reader.ui.pager.ReaderPage
import org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit

class ComposeScenePagedInteractionTest {

    private fun createPage(
        id: Long,
        chapterId: Long,
        index: Int,
        split: ReaderPageSplit = ReaderPageSplit.NONE,
    ): ReaderPage {
        return ReaderPage(
            id = id,
            url = "https://example.com/ch$chapterId/p$index.jpg",
            preview = null,
            headers = null,
            chapterId = chapterId,
            index = index,
            source = TestContentSource,
            split = split,
        )
    }

    @Test
    fun `initial paged specs conversion preserves chapter, index, and split geometry`() {
        val pages = listOf(
            createPage(id = 1L, chapterId = 10L, index = 0, split = ReaderPageSplit.NONE),
            createPage(id = 2L, chapterId = 10L, index = 1, split = ReaderPageSplit.LEFT),
            createPage(id = 3L, chapterId = 10L, index = 2, split = ReaderPageSplit.RIGHT),
        )

        val specs = createInitialPagedPageSpecs(pages)

        assertEquals(3, specs.size)
        assertEquals(PageSegment.FULL, specs[0].segment)
        assertEquals(PageSegment.LEFT_HALF, specs[1].segment)
        assertEquals(PageSegment.RIGHT_HALF, specs[2].segment)

        assertEquals(10L, specs[0].chapterId)
        assertEquals(0, specs[0].chapterPageIndex)
        assertEquals(PageId(pages[0].readerKey), specs[0].pageId)
    }

    @Test
    fun `single page mode isolates every page to its own discrete slot`() {
        val pages = (0 until 4).map { i ->
            createPage(id = i.toLong(), chapterId = 1L, index = i)
        }
        val specs = createInitialPagedPageSpecs(pages)

        val scene = PagedReaderScene(
            viewportWidth = 1080,
            viewportHeight = 1920,
            config = PagedSpreadConfig(
                isDoublePage = false,
                readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
            ),
            initialSpecs = specs,
        )

        assertEquals(4, scene.slotCount)
        assertEquals(4, scene.pageCount)
        assertEquals(4 * 1080f, scene.totalSceneExtent)

        for (i in 0 until 4) {
            val slot = scene.allSlots[i]
            assertEquals(1, slot.placements.size)
            assertEquals(PageId(pages[i].readerKey), slot.progressAnchorPageId)
            assertEquals(i * 1080f, slot.bounds.left)
        }
    }

    @Test
    fun `double page mode groups pairable pages within chapter but isolates chapter boundaries`() {
        val ch1Pages = (0 until 3).map { i -> createPage(id = i.toLong(), chapterId = 1L, index = i) }
        val ch2Pages = (0 until 2).map { i -> createPage(id = (10 + i).toLong(), chapterId = 2L, index = i) }
        val allPages = ch1Pages + ch2Pages
        val specs = createInitialPagedPageSpecs(allPages)

        val scene = PagedReaderScene(
            viewportWidth = 2000,
            viewportHeight = 1200,
            config = PagedSpreadConfig(
                isDoublePage = true,
                isCoverOffset = false,
                readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
            ),
            initialSpecs = specs,
        )

        // Chapter 1 has 3 pages: [0, 1] in Slot 0, [2] solo in Slot 1
        // Chapter 2 has 2 pages: [3, 4] in Slot 2
        // Total slots: 3
        assertEquals(3, scene.slotCount)

        val slot0 = scene.allSlots[0]
        assertEquals(2, slot0.placements.size)
        assertEquals(PageId(ch1Pages[0].readerKey), slot0.placements[0].pageId)
        assertEquals(PageId(ch1Pages[1].readerKey), slot0.placements[1].pageId)

        val slot1 = scene.allSlots[1]
        assertEquals(1, slot1.placements.size)
        assertEquals(PageId(ch1Pages[2].readerKey), slot1.placements[0].pageId)

        val slot2 = scene.allSlots[2]
        assertEquals(2, slot2.placements.size)
        assertEquals(PageId(ch2Pages[0].readerKey), slot2.placements[0].pageId)
        assertEquals(PageId(ch2Pages[1].readerKey), slot2.placements[1].pageId)
    }

    @Test
    fun `RTL manga spread places earlier page on right and second on left within slot`() {
        val pages = listOf(
            createPage(id = 1L, chapterId = 1L, index = 0),
            createPage(id = 2L, chapterId = 1L, index = 1),
        )
        val specs = createInitialPagedPageSpecs(pages)

        val scene = PagedReaderScene(
            viewportWidth = 2000,
            viewportHeight = 1200,
            config = PagedSpreadConfig(
                isDoublePage = true,
                isCoverOffset = false,
                readingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
            ),
            initialSpecs = specs,
        )

        assertEquals(1, scene.slotCount)
        val slot = scene.allSlots[0]
        assertEquals(2, slot.placements.size)

        // Canonical reading order in slot: Page 1, Page 2
        val page1Placement = slot.placements.first { it.pageId == PageId(pages[0].readerKey) }
        val page2Placement = slot.placements.first { it.pageId == PageId(pages[1].readerKey) }

        // In RTL: Page 1 is on the right side of the spread (left >= 1000)
        assertTrue(page1Placement.boundsInSlot.left >= 1000f, "Page 1 should be on the right in RTL")
        // Page 2 is on the left side of the spread (left < 1000)
        assertTrue(page2Placement.boundsInSlot.left < 1000f, "Page 2 should be on the left in RTL")
    }

    @Test
    fun `PagedSnapResolver resolves target slots across displacement and velocity thresholds`() {
        val totalSlots = 5
        val currentSlot = 2

        // 1. Weak displacement, low velocity -> snap back to current
        assertEquals(
            currentSlot,
            PagedSnapResolver.resolveTargetSlot(currentSlot, offsetFraction = 0.10f, normalizedVelocity = 0.1f, totalSlots = totalSlots),
        )
        assertEquals(
            currentSlot,
            PagedSnapResolver.resolveTargetSlot(currentSlot, offsetFraction = -0.10f, normalizedVelocity = -0.1f, totalSlots = totalSlots),
        )

        // 2. Sufficient displacement (>= 0.20 threshold) -> turn page
        assertEquals(
            3,
            PagedSnapResolver.resolveTargetSlot(currentSlot, offsetFraction = 0.25f, normalizedVelocity = 0.0f, totalSlots = totalSlots),
        )
        assertEquals(
            1,
            PagedSnapResolver.resolveTargetSlot(currentSlot, offsetFraction = -0.25f, normalizedVelocity = 0.0f, totalSlots = totalSlots),
        )

        // 3. Low displacement but high fling velocity (>= 0.5f threshold) -> fling turn
        assertEquals(
            3,
            PagedSnapResolver.resolveTargetSlot(currentSlot, offsetFraction = 0.05f, normalizedVelocity = 0.8f, totalSlots = totalSlots),
        )
        assertEquals(
            1,
            PagedSnapResolver.resolveTargetSlot(currentSlot, offsetFraction = -0.05f, normalizedVelocity = -0.8f, totalSlots = totalSlots),
        )

        // 4. Clamping at lower boundary
        assertEquals(
            0,
            PagedSnapResolver.resolveTargetSlot(0, offsetFraction = -0.5f, normalizedVelocity = -1.0f, totalSlots = totalSlots),
        )

        // 5. Clamping at upper boundary
        assertEquals(
            4,
            PagedSnapResolver.resolveTargetSlot(4, offsetFraction = 0.5f, normalizedVelocity = 1.0f, totalSlots = totalSlots),
        )
    }

    @Test
    fun `scene frame resolution accurately reports progress and visible nodes during transition`() {
        val pages = (0 until 3).map { i -> createPage(id = i.toLong(), chapterId = 1L, index = i) }
        val specs = createInitialPagedPageSpecs(pages)

        val scene = PagedReaderScene(
            viewportWidth = 1000,
            viewportHeight = 1500,
            config = PagedSpreadConfig(
                isDoublePage = false,
                readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
            ),
            initialSpecs = specs,
        )

        // 1. Centered at slot 0
        val frame0 = scene.resolve(ReaderViewport(FloatRect.fromLtwh(0f, 0f, 1000f, 1500f)))
        assertEquals(1, frame0.visibleNodes.size)
        assertEquals(PageId(pages[0].readerKey), frame0.progress.activePageId)
        assertEquals(PageId(pages[0].readerKey), frame0.progress.lowerPageId)
        assertEquals(PageId(pages[0].readerKey), frame0.progress.upperPageId)

        // 2. Dragged 300px towards slot 1 (30% through transition): both slot 0 and slot 1 are intersecting
        val frameMid = scene.resolve(ReaderViewport(FloatRect.fromLtwh(300f, 0f, 1000f, 1500f)))
        assertEquals(2, frameMid.visibleNodes.size)
        // Slot 0 has 700px visible vs Slot 1's 300px -> Slot 0 is dominant active anchor
        assertEquals(PageId(pages[0].readerKey), frameMid.progress.activePageId)
        assertEquals(PageId(pages[0].readerKey), frameMid.progress.lowerPageId)
        assertEquals(PageId(pages[1].readerKey), frameMid.progress.upperPageId)

        // 3. Dragged 700px towards slot 1 (70% through transition): Slot 1 is now dominant
        val framePast = scene.resolve(ReaderViewport(FloatRect.fromLtwh(700f, 0f, 1000f, 1500f)))
        assertEquals(2, framePast.visibleNodes.size)
        assertEquals(PageId(pages[1].readerKey), framePast.progress.activePageId)
    }

    @Test
    fun `dynamic geometry update with updatePageHint executes zero CLS compensation`() {
        val pages = listOf(
            createPage(id = 1L, chapterId = 1L, index = 0),
            createPage(id = 2L, chapterId = 1L, index = 1),
            createPage(id = 3L, chapterId = 1L, index = 2),
            createPage(id = 4L, chapterId = 1L, index = 3),
        )
        val specs = pages.map { page ->
            PagedPageSpec(
                pageId = PageId(page.readerKey),
                geometryHint = PageGeometryHint.Estimated(1.0f),
                chapterId = page.chapterId,
                chapterPageIndex = page.index,
            )
        }

        val scene = PagedReaderScene(
            viewportWidth = 2000,
            viewportHeight = 1200,
            config = PagedSpreadConfig(isDoublePage = true, isCoverOffset = false),
            initialSpecs = specs,
        )

        // Initial pairing: [1, 2] in Slot 0 (0px), [3, 4] in Slot 1 (2000px)
        // Viewport viewing Page 3 at Slot 1 (offset 2000px)
        val vp = ReaderViewport(FloatRect.fromLtwh(2000f, 0f, 2000f, 1200f))
        val activeBefore = scene.resolve(vp).progress.activePageId
        assertEquals(PageId(pages[2].readerKey), activeBefore)

        // Now Page 2 resolves as wide aspect ratio (solo page)
        // New pairing:
        // Slot 0: [1] (solo because 2 is solo)
        // Slot 1: [2] (solo wide)
        // Slot 2: [3, 4] (paired at 4000px)
        val compensation = scene.updatePageHint(
            pageId = PageId(pages[1].readerKey),
            newHint = PageGeometryHint.Exact(2500, 1200),
            currentViewport = vp,
        )

        // Page 3 moved from Slot 1 (2000px) to Slot 2 (4000px): deltaX = +2000px
        assertNotNull(compensation)
        assertEquals(2000f, compensation!!.deltaX)

        // After applying compensation to viewport, the active anchor page remains Page 3!
        val frameAfter = scene.resolve(compensation.compensatedViewport)
        assertEquals(PageId(pages[2].readerKey), frameAfter.progress.activePageId)
    }

    @Test
    fun `ZoomMode config correctly propagates to PagedReaderScene placements`() {
        val pages = listOf(
            createPage(id = 1L, chapterId = 1L, index = 0),
        )
        val specs = listOf(
            PagedPageSpec(PageId(pages[0].readerKey), PageGeometryHint.Exact(1000, 2000), chapterId = 1L, chapterPageIndex = 0),
        )
        val scene = PagedReaderScene(
            viewportWidth = 800,
            viewportHeight = 1200,
            config = PagedSpreadConfig(
                isDoublePage = false,
                zoomMode = ZoomMode.FIT_WIDTH,
            ),
            initialSpecs = specs,
        )

        val slot = scene.allSlots.first()
        val placement = slot.placements.first()
        assertEquals(800f, placement.boundsInSlot.width)
        assertEquals(1600f, placement.boundsInSlot.height)
    }

    @Test
    fun `paged camera snapshot visible bounds calculation maps zoomed viewport to scene bounds`() {
        // Slot 2 in horizontal paged reader (offset 2000px, viewport 1000px)
        val visibleBounds = SceneImagePresentationCoordinator.computeVisibleBounds(
            viewportWidth = 1000f,
            viewportHeight = 1500f,
            scrollOffset = 2000f,
            isHorizontal = true,
            canvasScale = 2.5f,
            canvasOffsetX = 100f,
            canvasOffsetY = -50f,
            totalSceneExtent = 10000f,
            totalCrossExtent = 1500f,
        )

        val snapshot = SceneImagePresentationCoordinator.createCameraSnapshot(2.5f, visibleBounds)
        assertEquals(2.5f, snapshot.scale)
        assertTrue(snapshot.visibleBoundsInScene.left >= 2000f)
        assertTrue(snapshot.visibleBoundsInScene.right <= 3000f)
    }

    @Test
    fun `FIT_WIDTH tall page in PagedReaderScene calculates overflow pan range and start alignment`() {
        val pages = listOf(createPage(id = 1L, chapterId = 1L, index = 0))
        val specs = listOf(
            PagedPageSpec(PageId(pages[0].readerKey), PageGeometryHint.Exact(1000, 2000), chapterId = 1L, chapterPageIndex = 0),
        )
        val scene = PagedReaderScene(
            viewportWidth = 800,
            viewportHeight = 1200,
            config = PagedSpreadConfig(
                isDoublePage = false,
                zoomMode = ZoomMode.FIT_WIDTH,
            ),
            initialSpecs = specs,
        )

        val slot = scene.allSlots.first()
        val placement = slot.placements.first()
        val bounds = placement.boundsInSlot

        val panXRange = PagedPanBoundsResolver.resolvePanRange(bounds.left, bounds.right, 800f, 1.0f)
        val panYRange = PagedPanBoundsResolver.resolvePanRange(bounds.top, bounds.bottom, 1200f, 1.0f)

        // Width fits 800px: panXRange is neutral
        assertEquals(0f, panXRange.start)
        assertEquals(0f, panXRange.endInclusive)

        // Height is 1600px in 1200px viewport: vertical pan range allows scrolling full height
        assertEquals(-200f, panYRange.start, 0.01f)
        assertEquals(200f, panYRange.endInclusive, 0.01f)

        // Initial offset starts at top of page (+200f)
        val initialY = PagedPanBoundsResolver.resolveInitialOverflowOffset(
            contentMin = bounds.top,
            contentMax = bounds.bottom,
            viewportSize = 1200f,
            scale = 1.0f,
            isStartReversed = false,
        )
        assertEquals(200f, initialY, 0.01f)
    }

    @Test
    fun `FIT_HEIGHT wide page in PagedReaderScene calculates overflow pan range and RTL start alignment`() {
        val pages = listOf(createPage(id = 1L, chapterId = 1L, index = 0))
        val specs = listOf(
            PagedPageSpec(PageId(pages[0].readerKey), PageGeometryHint.Exact(2000, 1000), chapterId = 1L, chapterPageIndex = 0),
        )
        val scene = PagedReaderScene(
            viewportWidth = 1200,
            viewportHeight = 800,
            config = PagedSpreadConfig(
                isDoublePage = false,
                readingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
                zoomMode = ZoomMode.FIT_HEIGHT,
            ),
            initialSpecs = specs,
        )

        val slot = scene.allSlots.first()
        val placement = slot.placements.first()
        val bounds = placement.boundsInSlot

        val panXRange = PagedPanBoundsResolver.resolvePanRange(bounds.left, bounds.right, 1200f, 1.0f)
        val panYRange = PagedPanBoundsResolver.resolvePanRange(bounds.top, bounds.bottom, 800f, 1.0f)

        // Height fits 800px: panYRange is neutral
        assertEquals(0f, panYRange.start)
        assertEquals(0f, panYRange.endInclusive)

        // Width is 1600px in 1200px viewport: horizontal pan allowed
        assertEquals(-200f, panXRange.start, 0.01f)
        assertEquals(200f, panXRange.endInclusive, 0.01f)

        // In RTL, initial starts at right/end of page (-200f)
        val initialX = PagedPanBoundsResolver.resolveInitialOverflowOffset(
            contentMin = bounds.left,
            contentMax = bounds.right,
            viewportSize = 1200f,
            scale = 1.0f,
            isStartReversed = true,
        )
        assertEquals(-200f, initialX, 0.01f)
    }

    @Test
    fun `resolveDoublePageBackground with AUTO merges spread background colors`() {
        val firstColor = android.graphics.Color.WHITE
        val secondColor = android.graphics.Color.WHITE

        val merged = resolveDoublePageBackground(
            background = ReaderBackground.AUTO,
            configuredColor = android.graphics.Color.BLACK,
            firstAutoColor = firstColor,
            secondAutoColor = secondColor,
        )
        assertEquals(android.graphics.Color.WHITE, merged)

        // When non-AUTO background configured, returns configuredColor
        val blackConfigured = resolveDoublePageBackground(
            background = ReaderBackground.BLACK,
            configuredColor = android.graphics.Color.BLACK,
            firstAutoColor = firstColor,
            secondAutoColor = secondColor,
        )
        assertEquals(android.graphics.Color.BLACK, blackConfigured)
    }
}
