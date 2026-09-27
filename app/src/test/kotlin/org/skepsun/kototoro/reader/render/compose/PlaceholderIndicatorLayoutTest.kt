package org.skepsun.kototoro.reader.render.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaceholderIndicatorLayoutTest {

    private val viewport = 2000f

    @Test
    fun `a page no taller than the viewport carries one indicator at its own center`() {
        assertEquals(listOf(350f), resolvePlaceholderIndicatorCenters(pageStart = 100f, pageExtent = 500f, viewportExtent = viewport))
        assertEquals(listOf(-150f), resolvePlaceholderIndicatorCenters(pageStart = -400f, pageExtent = 500f, viewportExtent = viewport))
    }

    @Test
    fun `indicators move one to one with the page while scrolling`() {
        // A still-loading placeholder is a uniform surface: its indicators are the only thing that can show
        // the page moving. Pinning an indicator to the visible slice made it drift at half speed, or stand
        // still once the placeholder filled the screen, so scrolling felt stuck.
        val extent = viewport * 3.4f
        for (start in listOf(1200f, 300f, 0f, -700f, -2500f, -4000f)) {
            val before = resolvePlaceholderIndicatorCenters(start, extent, viewport)
            val after = resolvePlaceholderIndicatorCenters(start - 137f, extent, viewport)
            assertEquals(before.size, after.size)
            before.zip(after).forEach { (b, a) -> assertEquals(b - 137f, a, 0.001f) }
        }
    }

    @Test
    fun `a placeholder covering the whole viewport always shows an indicator on screen`() {
        for (extent in listOf(viewport * 1.01f, viewport * 1.2f, viewport * 2.3f, viewport * 3f, viewport * 5.7f)) {
            var start = 0f
            while (start >= -(extent - viewport)) {
                val onScreen = resolvePlaceholderIndicatorCenters(start, extent, viewport)
                    .any { it in 0f..viewport }
                assertTrue(onScreen, "no indicator on screen for extent=$extent start=$start")
                start -= 50f
            }
        }
    }

    @Test
    fun `indicators never leave the page`() {
        val start = -900f
        val extent = viewport * 2.3f
        resolvePlaceholderIndicatorCenters(start, extent, viewport).forEach {
            assertTrue(it > start && it < start + extent)
        }
    }
}
