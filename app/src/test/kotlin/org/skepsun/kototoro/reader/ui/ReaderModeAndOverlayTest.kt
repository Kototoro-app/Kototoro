package org.skepsun.kototoro.reader.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.reader.core.FloatRect
import org.skepsun.kototoro.reader.core.PageGeometryHint
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.core.PagedPageSpec
import org.skepsun.kototoro.reader.core.PagedReaderScene
import org.skepsun.kototoro.reader.core.PagedSpreadConfig
import org.skepsun.kototoro.reader.core.ReaderViewport
import org.skepsun.kototoro.reader.core.SceneReadingDirection
import org.skepsun.kototoro.reader.core.VerticalReaderScene

class ReaderModeAndOverlayTest {

    @Test
    fun `ReaderMode continuous and paged classification is correct for all modes`() {
        // Continuous modes
        assertTrue(ReaderMode.WEBTOON.isContinuous)
        assertFalse(ReaderMode.WEBTOON.isPaged)

        assertTrue(ReaderMode.CONTINUOUS_HORIZONTAL.isContinuous)
        assertFalse(ReaderMode.CONTINUOUS_HORIZONTAL.isPaged)

        // Paged modes
        assertFalse(ReaderMode.STANDARD.isContinuous)
        assertTrue(ReaderMode.STANDARD.isPaged)

        assertFalse(ReaderMode.REVERSED.isContinuous)
        assertTrue(ReaderMode.REVERSED.isPaged)

        assertFalse(ReaderMode.VERTICAL.isContinuous)
        assertTrue(ReaderMode.VERTICAL.isPaged)

        // Serialization and enum stability
        assertEquals(ReaderMode.CONTINUOUS_HORIZONTAL, ReaderMode.valueOf("CONTINUOUS_HORIZONTAL"))
        assertEquals(5, ReaderMode.entries.size)
    }

    @Test
    fun `Double page spread computes independent centers for each placement`() {
        val viewportWidth = 1600f
        val viewportHeight = 1200f
        val specs = listOf(
            PagedPageSpec(PageId(1L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 0),
            PagedPageSpec(PageId(2L), PageGeometryHint.Exact(800, 1200), chapterId = 1L, chapterPageIndex = 1),
        )
        val scene = PagedReaderScene(
            viewportWidth = viewportWidth.toInt(),
            viewportHeight = viewportHeight.toInt(),
            config = PagedSpreadConfig(isDoublePage = true, readingDirection = SceneReadingDirection.LEFT_TO_RIGHT),
            initialSpecs = specs,
        )

        val slot = scene.allSlots.first()
        assertEquals(2, slot.placements.size)

        val p1 = slot.placements[0]
        val p2 = slot.placements[1]

        val p1CenterX = p1.boundsInSlot.left + p1.boundsInSlot.width / 2f
        val p1CenterY = p1.boundsInSlot.top + p1.boundsInSlot.height / 2f

        val p2CenterX = p2.boundsInSlot.left + p2.boundsInSlot.width / 2f
        val p2CenterY = p2.boundsInSlot.top + p2.boundsInSlot.height / 2f

        // First page is on the left half: center is at 400 (1/4 of 1600)
        assertEquals(400f, p1CenterX, 1f)
        assertEquals(600f, p1CenterY, 1f)

        // Second page is on the right half: center is at 1200 (3/4 of 1600)
        assertEquals(1200f, p2CenterX, 1f)
        assertEquals(600f, p2CenterY, 1f)
    }

    @Test
    fun `Webtoon visible center clamps to visible portion of tall image`() {
        val viewportWidth = 1080
        val viewportHeight = 2400
        val scene = VerticalReaderScene(
            availableWidth = viewportWidth,
            defaultViewportHeight = viewportHeight,
            initialPages = listOf(
                PageId(1L) to PageGeometryHint.Exact(1080, 7200), // 3x screen height
            ),
        )

        // Scroll is at 1000px down: image occupies [0, 7200] in scene, [-1000, 6200] on screen
        val scrollY = 1000f
        val vp = ReaderViewport(FloatRect.fromLtwh(0f, scrollY, viewportWidth.toFloat(), viewportHeight.toFloat()))
        val frame = scene.resolve(vp)
        assertEquals(1, frame.visibleNodes.size)

        val node = frame.visibleNodes.first()
        val screenTop = node.sceneBounds.top - scrollY // -1000f
        val screenBottom = node.sceneBounds.bottom - scrollY // 6200f

        val visibleTop = maxOf(screenTop, 0f) // 0f
        val visibleBottom = minOf(screenBottom, viewportHeight.toFloat()) // 2400f

        val centerY = (visibleTop + visibleBottom) / 2f
        // Spinner should be at the center of the current screen (1200f), not at center of entire 7200px image (2600f)
        assertEquals(1200f, centerY, 0.1f)
    }
}
