package org.skepsun.kototoro.core.ui.glass

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Chrome keeps a legible plate under its tuned tint without flattening the tuner's range. */
class ChromeLegibleSurfaceAlphaTest {

    @Test
    fun `the preset alpha lands on a plate that holds labels`() {
        assertEquals(0.61f, chromeLegibleSurfaceAlpha(0.40f), 0.001f)
    }

    @Test
    fun `the slider still moves the result across its whole range`() {
        val steps = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f).map(::chromeLegibleSurfaceAlpha)
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
        assertEquals(0.35f, steps.first(), 0.001f)
        assertEquals(1f, steps.last(), 0.001f)
    }

    @Test
    fun `out of range values are clamped`() {
        assertEquals(1f, chromeLegibleSurfaceAlpha(1.5f), 0.001f)
        assertEquals(0.35f, chromeLegibleSurfaceAlpha(-1f), 0.001f)
    }
}
