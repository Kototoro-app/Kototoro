package org.skepsun.kototoro.reader.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PagedPanBoundsResolverTest {

    @Test
    fun `content fitting inside viewport at 1x returns neutral degenerate range`() {
        // Content 800px centered in 1200px viewport: contentMin = 200, contentMax = 1000
        val range = PagedPanBoundsResolver.resolvePanRange(
            contentMin = 200f,
            contentMax = 1000f,
            viewportSize = 1200f,
            scale = 1.0f,
        )

        assertEquals(0f, range.start)
        assertEquals(0f, range.endInclusive)

        val initial = PagedPanBoundsResolver.resolveInitialOverflowOffset(
            contentMin = 200f,
            contentMax = 1000f,
            viewportSize = 1200f,
            scale = 1.0f,
        )
        assertEquals(0f, initial)
    }

    @Test
    fun `FIT_WIDTH tall page at 1x allows vertical pan and aligns top at maxTranslation`() {
        // Page 800x1600 in 800x1200 viewport.
        // In PagedSpreadResolver, topInSlot = (1200 - 1600) / 2 = -200f, bottomInSlot = 1400f
        val range = PagedPanBoundsResolver.resolvePanRange(
            contentMin = -200f,
            contentMax = 1400f,
            viewportSize = 1200f,
            scale = 1.0f,
        )

        assertEquals(-200f, range.start, 0.01f)
        assertEquals(200f, range.endInclusive, 0.01f)

        // At maxTranslation (+200f), top of page aligns with 0 (top of viewport)
        val initialOffset = PagedPanBoundsResolver.resolveInitialOverflowOffset(
            contentMin = -200f,
            contentMax = 1400f,
            viewportSize = 1200f,
            scale = 1.0f,
            isStartReversed = false,
        )
        assertEquals(200f, initialOffset, 0.01f)

        // Screen top with translation = +200:
        // center (600) + (-200 - 600) * 1 + 200 = 600 - 800 + 200 = 0f
        val screenTopAtInitial = 600f + (-200f - 600f) * 1f + initialOffset
        assertEquals(0f, screenTopAtInitial, 0.01f)

        // Screen bottom with translation = -200 (scrolled to bottom):
        // center (600) + (1400 - 600) * 1 - 200 = 600 + 800 - 200 = 1200f
        val screenBottomAtEnd = 600f + (1400f - 600f) * 1f + range.start
        assertEquals(1200f, screenBottomAtEnd, 0.01f)
    }

    @Test
    fun `FIT_HEIGHT wide page at 1x allows horizontal pan and respects RTL alignment`() {
        // Page 1600x800 in 1200x800 viewport.
        // leftInSlot = -200f, rightInSlot = 1400f
        val range = PagedPanBoundsResolver.resolvePanRange(
            contentMin = -200f,
            contentMax = 1400f,
            viewportSize = 1200f,
            scale = 1.0f,
        )

        assertEquals(-200f, range.start, 0.01f)
        assertEquals(200f, range.endInclusive, 0.01f)

        // In LTR, initial starts at left (+200f)
        val ltrInitial = PagedPanBoundsResolver.resolveInitialOverflowOffset(
            contentMin = -200f,
            contentMax = 1400f,
            viewportSize = 1200f,
            scale = 1.0f,
            isStartReversed = false,
        )
        assertEquals(200f, ltrInitial, 0.01f)

        // In RTL, initial starts at right (-200f)
        val rtlInitial = PagedPanBoundsResolver.resolveInitialOverflowOffset(
            contentMin = -200f,
            contentMax = 1400f,
            viewportSize = 1200f,
            scale = 1.0f,
            isStartReversed = true,
        )
        assertEquals(-200f, rtlInitial, 0.01f)
        val screenRightAtRtl = 600f + (1400f - 600f) * 1f + rtlInitial
        assertEquals(1200f, screenRightAtRtl, 0.01f)
    }

    @Test
    fun `zooming to 2x expands pan range proportionally`() {
        // Page 800x1200 in 800x1200 viewport, scaled 2.5x
        val rangeY = PagedPanBoundsResolver.resolvePanRange(
            contentMin = 0f,
            contentMax = 1200f,
            viewportSize = 1200f,
            scale = 2.5f,
        )

        // Transformed height = 3000px. Excess = 1800px. maxPan = 900px.
        assertEquals(-900f, rangeY.start, 0.01f)
        assertEquals(900f, rangeY.endInclusive, 0.01f)
    }

    @Test
    fun `KEEP_START mode anchors start at zero and allows scrolling downwards`() {
        // Page 800x1600 in 800x1200 viewport, KEEP_START has topInSlot = 0f, bottomInSlot = 1600f
        val range = PagedPanBoundsResolver.resolvePanRange(
            contentMin = 0f,
            contentMax = 1600f,
            viewportSize = 1200f,
            scale = 1.0f,
        )

        // At translation = 0f: top is at 600 + (0 - 600) * 1 + 0 = 0f (aligned to top)
        // At translation = -400f: bottom is at 600 + (1600 - 600) * 1 - 400 = 1200f (aligned to bottom)
        assertEquals(-400f, range.start, 0.01f)
        assertEquals(0f, range.endInclusive, 0.01f)

        val initial = PagedPanBoundsResolver.resolveInitialOverflowOffset(
            contentMin = 0f,
            contentMax = 1600f,
            viewportSize = 1200f,
            scale = 1.0f,
            isStartReversed = false,
        )
        assertEquals(0f, initial, 0.01f)
    }
}
