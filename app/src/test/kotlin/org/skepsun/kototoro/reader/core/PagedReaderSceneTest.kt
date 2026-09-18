package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PagedReaderSceneTest {

    @Test
    fun `PagedReaderScene computes total scene extent and resolves slot origins`() {
        val specs = listOf(
            PagedPageSpec(PageId(1L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 0),
            PagedPageSpec(PageId(2L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 1),
            PagedPageSpec(PageId(3L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 2),
            PagedPageSpec(PageId(4L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 3),
        )

        // Double page mode: [1, 2] in Slot 0, [3, 4] in Slot 1
        val scene = PagedReaderScene(
            viewportWidth = 1600,
            viewportHeight = 1200,
            config = PagedSpreadConfig(isDoublePage = true),
            initialSpecs = specs,
        )

        assertEquals(4, scene.pageCount)
        assertEquals(2, scene.slotCount)
        assertEquals(3200f, scene.totalSceneExtent) // 2 slots * 1600

        // Pages in Slot 0 (Pages 1 & 2) both resolve to Slot 0 origin (0f)
        assertEquals(0f, scene.resolvePageScrollPosition(PageId(1L)))
        assertEquals(0f, scene.resolvePageScrollPosition(PageId(2L)))

        // Pages in Slot 1 (Pages 3 & 4) both resolve to Slot 1 origin (1600f)
        assertEquals(1600f, scene.resolvePageScrollPosition(PageId(3L)))
        assertEquals(1600f, scene.resolvePageScrollPosition(PageId(4L)))

        // Non-existent page
        assertNull(scene.resolvePageScrollPosition(PageId(999L)))
    }

    @Test
    fun `resolve returns intersecting pages from both slots during swipe transition`() {
        val specs = listOf(
            PagedPageSpec(PageId(1L), PageGeometryHint.Exact(1000, 1200), chapterId = 1L, chapterPageIndex = 0),
            PagedPageSpec(PageId(2L), PageGeometryHint.Exact(1000, 1200), chapterId = 1L, chapterPageIndex = 1),
        )

        val scene = PagedReaderScene(
            viewportWidth = 1000,
            viewportHeight = 1200,
            config = PagedSpreadConfig(isDoublePage = false),
            initialSpecs = specs,
        )

        // 1. Static at Slot 0: only Page 1 visible
        val frame0 = scene.resolve(ReaderViewport(FloatRect.fromLtwh(0f, 0f, 1000f, 1200f)))
        assertEquals(1, frame0.visibleNodes.size)
        assertEquals(PageId(1L), frame0.visibleNodes[0].pageId)
        assertEquals(PageId(1L), frame0.progress.activePageId)

        // 2. Mid-transition (offset = 400px): both Slot 0 (600px visible) and Slot 1 (400px visible) are intersecting!
        val frameMid = scene.resolve(ReaderViewport(FloatRect.fromLtwh(400f, 0f, 1000f, 1200f)))
        assertEquals(2, frameMid.visibleNodes.size)
        assertEquals(PageId(1L), frameMid.visibleNodes[0].pageId)
        assertEquals(PageId(2L), frameMid.visibleNodes[1].pageId)
        // Slot 0 has 600px intersection area vs Slot 1's 400px -> Slot 0 is dominant active slot
        assertEquals(PageId(1L), frameMid.progress.activePageId)

        // 3. Past midpoint (offset = 700px): Slot 1 has 700px intersection area -> Page 2 is dominant active slot
        val framePast = scene.resolve(ReaderViewport(FloatRect.fromLtwh(700f, 0f, 1000f, 1200f)))
        assertEquals(2, framePast.visibleNodes.size)
        assertEquals(PageId(2L), framePast.progress.activePageId)
    }

    @Test
    fun `updatePageHint preserves active viewport position with zero-CLS compensation`() {
        val specs = listOf(
            PagedPageSpec(PageId(1L), PageGeometryHint.Estimated(1.0f), chapterId = 1L, chapterPageIndex = 0),
            PagedPageSpec(PageId(2L), PageGeometryHint.Estimated(1.0f), chapterId = 1L, chapterPageIndex = 1),
            PagedPageSpec(PageId(3L), PageGeometryHint.Estimated(1.0f), chapterId = 1L, chapterPageIndex = 2),
        )

        val scene = PagedReaderScene(
            viewportWidth = 1600,
            viewportHeight = 1200,
            config = PagedSpreadConfig(isDoublePage = true, isCoverOffset = false),
            initialSpecs = specs,
        )

        // Initial pairing: [1, 2] in Slot 0 (0..1600), [3] in Slot 1 (1600..3200)
        assertEquals(2, scene.slotCount)

        val vp = ReaderViewport(FloatRect.fromLtwh(1600f, 0f, 1600f, 1200f))
        val activeBefore = scene.resolve(vp).progress.activePageId
        assertEquals(PageId(3L), activeBefore)

        // Now Page 1 resolves as a wide page (ratio 1.8 > 1.15)
        // This causes Page 1 to become SOLO:
        // Slot 0: [1] (solo wide)
        // Slot 1: [2, 3] (paired)
        val comp = scene.updatePageHint(
            pageId = PageId(1L),
            newHint = PageGeometryHint.Exact(2160, 1200),
            currentViewport = vp,
        )

        assertNotNull(comp)
        // Page 3 moved from Slot 1 (origin 1600) to Slot 1 (origin 1600)
        // Visual reading position on Page 3 is preserved!
        assertEquals(0f, comp!!.deltaX)
    }

    @Test
    fun `PagedSnapResolver determines target slot by distance fraction and velocity`() {
        val totalSlots = 5

        // Case 1: Small displacement without velocity -> snap back to current
        val snapBack = PagedSnapResolver.resolveTargetSlot(
            currentSlot = 2,
            offsetFraction = 0.15f,
            normalizedVelocity = 0f,
            totalSlots = totalSlots,
        )
        assertEquals(2, snapBack)

        // Case 2: Displacement >= 20% -> advance to next slot
        val advance = PagedSnapResolver.resolveTargetSlot(
            currentSlot = 2,
            offsetFraction = 0.25f,
            normalizedVelocity = 0f,
            totalSlots = totalSlots,
        )
        assertEquals(3, advance)

        // Case 3: Negative displacement <= -20% -> retreat to previous slot
        val retreat = PagedSnapResolver.resolveTargetSlot(
            currentSlot = 2,
            offsetFraction = -0.22f,
            normalizedVelocity = 0f,
            totalSlots = totalSlots,
        )
        assertEquals(1, retreat)

        // Case 4: Low displacement (5%) but high forward velocity (0.8) -> fling to next slot
        val flingNext = PagedSnapResolver.resolveTargetSlot(
            currentSlot = 2,
            offsetFraction = 0.05f,
            normalizedVelocity = 0.8f,
            totalSlots = totalSlots,
        )
        assertEquals(3, flingNext)

        // Case 5: Low displacement (-5%) but high backward velocity (-0.7) -> fling to previous slot
        val flingPrev = PagedSnapResolver.resolveTargetSlot(
            currentSlot = 2,
            offsetFraction = -0.05f,
            normalizedVelocity = -0.7f,
            totalSlots = totalSlots,
        )
        assertEquals(1, flingPrev)

        // Case 6: Edge clamping (slot 0 cannot go backward, last slot cannot go forward)
        val clampStart = PagedSnapResolver.resolveTargetSlot(
            currentSlot = 0,
            offsetFraction = -0.5f,
            normalizedVelocity = -2f,
            totalSlots = totalSlots,
        )
        assertEquals(0, clampStart)

        val clampEnd = PagedSnapResolver.resolveTargetSlot(
            currentSlot = 4,
            offsetFraction = 0.5f,
            normalizedVelocity = 2f,
            totalSlots = totalSlots,
        )
        assertEquals(4, clampEnd)
    }
}
