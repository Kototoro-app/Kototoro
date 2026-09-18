package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.ZoomMode

class PagedSpreadResolverTest {

    @Test
    fun `single page mode allocates one slot per page`() {
        val specs = listOf(
            PagedPageSpec(PageId(1L), PageGeometryHint.Exact(1000, 1500), chapterId = 1L, chapterPageIndex = 0),
            PagedPageSpec(PageId(2L), PageGeometryHint.Exact(1000, 1500), chapterId = 1L, chapterPageIndex = 1),
            PagedPageSpec(PageId(3L), PageGeometryHint.Exact(1000, 1500), chapterId = 1L, chapterPageIndex = 2),
        )

        val slots = PagedSpreadResolver.resolveSlots(
            specs = specs,
            viewportWidth = 1000,
            viewportHeight = 1500,
            config = PagedSpreadConfig(isDoublePage = false),
        )

        assertEquals(3, slots.size)
        assertEquals(listOf(PageId(1L)), slots[0].pageIds)
        assertEquals(listOf(PageId(2L)), slots[1].pageIds)
        assertEquals(listOf(PageId(3L)), slots[2].pageIds)
        assertEquals(PageId(1L), slots[0].progressAnchorPageId)
        assertEquals(PageId(2L), slots[1].progressAnchorPageId)
        assertEquals(PageId(3L), slots[2].progressAnchorPageId)
    }

    @Test
    fun `double page mode pairs consecutive pages in same chapter`() {
        val specs = listOf(
            PagedPageSpec(PageId(1L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 0),
            PagedPageSpec(PageId(2L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 1),
            PagedPageSpec(PageId(3L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 2),
            PagedPageSpec(PageId(4L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 3),
        )

        val slots = PagedSpreadResolver.resolveSlots(
            specs = specs,
            viewportWidth = 1600,
            viewportHeight = 1200,
            config = PagedSpreadConfig(isDoublePage = true, isCoverOffset = false, readingDirection = SceneReadingDirection.LEFT_TO_RIGHT),
        )

        assertEquals(2, slots.size)
        assertEquals(listOf(PageId(1L), PageId(2L)), slots[0].pageIds)
        assertEquals(listOf(PageId(3L), PageId(4L)), slots[1].pageIds)
        assertEquals(PageId(1L), slots[0].progressAnchorPageId)
        assertEquals(PageId(3L), slots[1].progressAnchorPageId)

        // In LTR: Page 1 is on the left half [0..800], Page 2 is on the right half [800..1600]
        val slot0 = slots[0]
        val p1 = slot0.placements.first { it.pageId == PageId(1L) }
        val p2 = slot0.placements.first { it.pageId == PageId(2L) }
        assertTrue(p1.boundsInSlot.left < p2.boundsInSlot.left)
    }

    @Test
    fun `double page mode in RTL places earlier reading page on the right`() {
        val specs = listOf(
            PagedPageSpec(PageId(1L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 0),
            PagedPageSpec(PageId(2L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 1),
        )

        val slots = PagedSpreadResolver.resolveSlots(
            specs = specs,
            viewportWidth = 1600,
            viewportHeight = 1200,
            config = PagedSpreadConfig(isDoublePage = true, isCoverOffset = false, readingDirection = SceneReadingDirection.RIGHT_TO_LEFT),
        )

        assertEquals(1, slots.size)
        val slot = slots[0]
        val p1 = slot.placements.first { it.pageId == PageId(1L) }
        val p2 = slot.placements.first { it.pageId == PageId(2L) }

        // In RTL Manga: Page 1 (first in reading order) must be on the RIGHT side of the spread!
        assertTrue(p1.boundsInSlot.left > p2.boundsInSlot.left)
        // Reading anchor remains Page 1
        assertEquals(PageId(1L), slot.progressAnchorPageId)
    }

    @Test
    fun `cover offset keeps page 0 solo and pairs subsequent pages`() {
        val specs = listOf(
            PagedPageSpec(PageId(1L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 0),
            PagedPageSpec(PageId(2L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 1),
            PagedPageSpec(PageId(3L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 2),
            PagedPageSpec(PageId(4L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 3),
        )

        val slots = PagedSpreadResolver.resolveSlots(
            specs = specs,
            viewportWidth = 1600,
            viewportHeight = 1200,
            config = PagedSpreadConfig(isDoublePage = true, isCoverOffset = true),
        )

        // Page 1 is cover (solo)
        // Page 2 & 3 are paired
        // Page 4 is solo at chapter end
        assertEquals(3, slots.size)
        assertEquals(listOf(PageId(1L)), slots[0].pageIds)
        assertEquals(listOf(PageId(2L), PageId(3L)), slots[1].pageIds)
        assertEquals(listOf(PageId(4L)), slots[2].pageIds)
    }

    @Test
    fun `wide page is isolated into solo slot and does not merge with adjacent pages`() {
        val specs = listOf(
            PagedPageSpec(PageId(1L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 0),
            // Wide page: 2000 x 1200 (aspect ratio 1.66 > 1.15)
            PagedPageSpec(PageId(2L), PageGeometryHint.Exact(2000, 1200), chapterId = 1L, chapterPageIndex = 1),
            PagedPageSpec(PageId(3L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 2),
            PagedPageSpec(PageId(4L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 3),
        )

        val slots = PagedSpreadResolver.resolveSlots(
            specs = specs,
            viewportWidth = 1600,
            viewportHeight = 1200,
            config = PagedSpreadConfig(isDoublePage = true, isCoverOffset = false),
        )

        // Page 1 cannot pair with Page 2 because Page 2 is wide -> Page 1 is solo
        // Page 2 is wide -> Page 2 is solo
        // Page 3 and Page 4 pair together
        assertEquals(3, slots.size)
        assertEquals(listOf(PageId(1L)), slots[0].pageIds)
        assertEquals(listOf(PageId(2L)), slots[1].pageIds)
        assertEquals(listOf(PageId(3L), PageId(4L)), slots[2].pageIds)
    }

    @Test
    fun `chapter boundary is never crossed within a spread slot`() {
        val specs = listOf(
            // Chapter 1: Pages 1, 2, 3 (odd count)
            PagedPageSpec(PageId(1L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 0),
            PagedPageSpec(PageId(2L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 1),
            PagedPageSpec(PageId(3L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 2),
            // Chapter 2: Pages 4, 5
            PagedPageSpec(PageId(4L), PageGeometryHint.Exact(800, 1200), chapterId = 2L, chapterPageIndex = 0),
            PagedPageSpec(PageId(5L), PageGeometryHint.Exact(800, 1200), chapterId = 2L, chapterPageIndex = 1),
        )

        val slots = PagedSpreadResolver.resolveSlots(
            specs = specs,
            viewportWidth = 1600,
            viewportHeight = 1200,
            config = PagedSpreadConfig(isDoublePage = true, isCoverOffset = false),
        )

        // Chapter 1: [1, 2], [3] (isolated, does NOT pair with chapter 2's page 4!)
        // Chapter 2: [4, 5]
        assertEquals(3, slots.size)
        assertEquals(listOf(PageId(1L), PageId(2L)), slots[0].pageIds)
        assertEquals(listOf(PageId(3L)), slots[1].pageIds)
        assertEquals(listOf(PageId(4L), PageId(5L)), slots[2].pageIds)
    }

    @Test
    fun `preserves odd chapter tail pairing in a truncated adjacent window`() {
        val specs = listOf(
            PagedPageSpec(PageId(4L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 3),
            PagedPageSpec(PageId(5L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 4),
            PagedPageSpec(PageId(6L), PageGeometryHint.Exact(800, 1200), chapterId = 2L, chapterPageIndex = 0),
            PagedPageSpec(PageId(7L), PageGeometryHint.Exact(800, 1200), chapterId = 2L, chapterPageIndex = 1),
        )

        val slots = PagedSpreadResolver.resolveSlots(
            specs = specs,
            viewportWidth = 1600,
            viewportHeight = 1200,
            config = PagedSpreadConfig(isDoublePage = true, isCoverOffset = false),
        )

        assertEquals(3, slots.size)
        assertEquals(listOf(PageId(4L)), slots[0].pageIds)
        assertEquals(listOf(PageId(5L)), slots[1].pageIds)
        assertEquals(listOf(PageId(6L), PageId(7L)), slots[2].pageIds)
        assertEquals(PageId(5L), slots[1].progressAnchorPageId)
    }

    @Test
    fun `preserves cover page phase in a truncated adjacent window`() {
        val specs = listOf(
            PagedPageSpec(PageId(5L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 4),
            PagedPageSpec(PageId(6L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 5),
            PagedPageSpec(PageId(7L), PageGeometryHint.Exact(800, 1200), chapterId = 2L, chapterPageIndex = 0),
        )

        val slots = PagedSpreadResolver.resolveSlots(
            specs = specs,
            viewportWidth = 1600,
            viewportHeight = 1200,
            config = PagedSpreadConfig(isDoublePage = true, isCoverOffset = true),
        )

        assertEquals(3, slots.size)
        assertEquals(listOf(PageId(5L)), slots[0].pageIds)
        assertEquals(listOf(PageId(6L)), slots[1].pageIds)
        assertEquals(listOf(PageId(7L)), slots[2].pageIds)
        assertEquals(PageId(6L), slots[1].progressAnchorPageId)
    }

    @Test
    fun `fit mode FIT_WIDTH scales page to full viewport width`() {
        val specs = listOf(
            PagedPageSpec(PageId(1L), PageGeometryHint.Exact(1000, 2000), chapterId = 1L, chapterPageIndex = 0),
        )

        val slots = PagedSpreadResolver.resolveSlots(
            specs = specs,
            viewportWidth = 800,
            viewportHeight = 1200,
            config = PagedSpreadConfig(
                isDoublePage = false,
                zoomMode = ZoomMode.FIT_WIDTH,
            ),
        )

        assertEquals(1, slots.size)
        val placement = slots[0].placements[0]
        assertEquals(800f, placement.boundsInSlot.width)
        assertEquals(1600f, placement.boundsInSlot.height)
        assertEquals(0f, placement.boundsInSlot.left)
    }

    @Test
    fun `fit mode FIT_HEIGHT scales page to full viewport height`() {
        val specs = listOf(
            PagedPageSpec(PageId(1L), PageGeometryHint.Exact(1000, 2000), chapterId = 1L, chapterPageIndex = 0),
        )

        val slots = PagedSpreadResolver.resolveSlots(
            specs = specs,
            viewportWidth = 1000,
            viewportHeight = 1500,
            config = PagedSpreadConfig(
                isDoublePage = false,
                zoomMode = ZoomMode.FIT_HEIGHT,
            ),
        )

        assertEquals(1, slots.size)
        val placement = slots[0].placements[0]
        assertEquals(1500f, placement.boundsInSlot.height)
        assertEquals(750f, placement.boundsInSlot.width)
        assertEquals(125f, placement.boundsInSlot.left)
    }

    @Test
    fun `fit mode KEEP_START aligns to start edge`() {
        val specs = listOf(
            PagedPageSpec(PageId(1L), PageGeometryHint.Exact(1000, 2000), chapterId = 1L, chapterPageIndex = 0),
        )

        // LTR: start is left (0f)
        val slotsLtr = PagedSpreadResolver.resolveSlots(
            specs = specs,
            viewportWidth = 1000,
            viewportHeight = 1000,
            config = PagedSpreadConfig(
                isDoublePage = false,
                readingDirection = SceneReadingDirection.LEFT_TO_RIGHT,
                zoomMode = ZoomMode.KEEP_START,
            ),
        )
        val pLtr = slotsLtr[0].placements[0]
        assertEquals(0f, pLtr.boundsInSlot.left)
        assertEquals(0f, pLtr.boundsInSlot.top)

        // RTL: the native-width page ends at the viewport right edge.
        val slotsRtl = PagedSpreadResolver.resolveSlots(
            specs = specs,
            viewportWidth = 1000,
            viewportHeight = 1000,
            config = PagedSpreadConfig(
                isDoublePage = false,
                readingDirection = SceneReadingDirection.RIGHT_TO_LEFT,
                zoomMode = ZoomMode.KEEP_START,
            ),
        )
        val pRtl = slotsRtl[0].placements[0]
        assertEquals(0f, pRtl.boundsInSlot.left)
        assertEquals(0f, pRtl.boundsInSlot.top)
    }
    @Test
    fun `keep start preserves native pixels for small and overflowing pages`() {
        for ((width, height) in listOf(200 to 300, 1800 to 2600)) {
            for (direction in SceneReadingDirection.entries) {
                val slot = PagedSpreadResolver.resolveSlots(
                    specs = listOf(PagedPageSpec(
                        PageId(1L), PageGeometryHint.Exact(width, height),
                        chapterId = 1L, chapterPageIndex = 0,
                    )),
                    viewportWidth = 1000,
                    viewportHeight = 1500,
                    config = PagedSpreadConfig(readingDirection = direction, zoomMode = ZoomMode.KEEP_START),
                ).single()
                val bounds = slot.placements.single().boundsInSlot
                assertEquals(width.toFloat(), bounds.width)
                assertEquals(height.toFloat(), bounds.height)
                assertEquals(0f, bounds.top)
                if (direction == SceneReadingDirection.RIGHT_TO_LEFT) {
                    assertEquals(1000f, bounds.right)
                } else {
                    assertEquals(0f, bounds.left)
                }
                assertEquals(1000f, slot.bounds.width)
                assertEquals(1500f, slot.bounds.height)
            }
        }
    }

    @Test
    fun `native double pages remain adjacent without overlap in either direction`() {
        for (direction in listOf(SceneReadingDirection.LEFT_TO_RIGHT, SceneReadingDirection.RIGHT_TO_LEFT)) {
            val slot = PagedSpreadResolver.resolveSlots(
                specs = listOf(
                    PagedPageSpec(PageId(1L), PageGeometryHint.Exact(800, 1200), 1L, 0),
                    PagedPageSpec(PageId(2L), PageGeometryHint.Exact(900, 1300), 1L, 1),
                ),
                viewportWidth = 1000,
                viewportHeight = 1500,
                config = PagedSpreadConfig(
                    isDoublePage = true, readingDirection = direction,
                    pageSpacingPx = 20, zoomMode = ZoomMode.KEEP_START,
                ),
            ).single()
            val first = slot.placements[0].boundsInSlot
            val second = slot.placements[1].boundsInSlot
            assertEquals(800f, first.width)
            assertEquals(1200f, first.height)
            assertEquals(900f, second.width)
            assertEquals(1300f, second.height)
            if (direction == SceneReadingDirection.RIGHT_TO_LEFT) {
                assertEquals(1000f, first.right)
                assertEquals(first.left - 20f, second.right)
            } else {
                assertEquals(0f, first.left)
                assertEquals(first.right + 20f, second.left)
            }
        }
    }

    @Test
    fun `native camera exposes content beyond slot bounds without changing the progress anchor`() {
        for (direction in listOf(SceneReadingDirection.LEFT_TO_RIGHT, SceneReadingDirection.RIGHT_TO_LEFT)) {
            val slot = PagedSpreadResolver.resolveSlots(
                specs = listOf(
                    PagedPageSpec(PageId(1L), PageGeometryHint.Exact(1800, 6000), 1L, 0),
                    PagedPageSpec(PageId(2L), PageGeometryHint.Exact(1900, 6000), 1L, 1),
                ),
                viewportWidth = 1000, viewportHeight = 1500,
                config = PagedSpreadConfig(
                    isDoublePage = true, zoomMode = ZoomMode.KEEP_START, readingDirection = direction,
                ),
            ).single()
            val rtl = direction == SceneReadingDirection.RIGHT_TO_LEFT
            val offsetX = if (rtl) 1800f else -1800f
            val node = slot.visibleContentNodes(scale = 1f, offsetX = offsetX, offsetY = -4000f).single()
            assertEquals(PageId(2L), node.pageId)
            assertEquals(FloatRect.fromLtwh(-offsetX, 4000f, 1000f, 1500f), node.visibleRegion)
            assertEquals(PageId(1L), slot.progressAnchorPageId)
        }
    }

    @Test
    fun `native size waits for exact dimensions and preserves slot position when they arrive`() {
        val pageId = PageId(2L)
        val scene = PagedReaderScene(
            viewportWidth = 1000, viewportHeight = 1500,
            config = PagedSpreadConfig(zoomMode = ZoomMode.KEEP_START),
            initialSpecs = listOf(
                PagedPageSpec(PageId(1L), PageGeometryHint.Estimated(0.5f), 1L, 0),
                PagedPageSpec(pageId, PageGeometryHint.AspectRatio(0.5f), 1L, 1),
            ),
        )
        assertEquals(750f, scene.allSlots[1].placements.single().boundsInSlot.width)
        val viewport = ReaderViewport(FloatRect.fromLtwh(1000f, 0f, 1000f, 1500f))
        val compensation = scene.updatePageHint(pageId, PageGeometryHint.Exact(2000, 4000), viewport)
        val actual = scene.allSlots[1].placements.single().boundsInSlot
        assertEquals(2000f, actual.width)
        assertEquals(4000f, actual.height)
        assertEquals(0f, compensation!!.deltaX)
        assertEquals(pageId, scene.resolve(compensation.compensatedViewport).progress.activePageId)
    }

}
