package org.skepsun.kototoro.core.ui.adaptive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TvSliderStepValueTest {

    private val brightness = 0f..1f

    @Test
    fun `a continuous slider moves a twentieth of its range per press`() {
        assertEquals(0.75f, tvSliderStepValue(0.8f, brightness, steps = 0, direction = -1), 1e-4f)
        assertEquals(0.85f, tvSliderStepValue(0.8f, brightness, steps = 0, direction = 1), 1e-4f)
    }

    @Test
    fun `stepping stops at both ends of the range`() {
        assertEquals(0f, tvSliderStepValue(0f, brightness, steps = 0, direction = -1), 1e-4f)
        assertEquals(1f, tvSliderStepValue(1f, brightness, steps = 0, direction = 1), 1e-4f)
    }

    @Test
    fun `a discrete slider moves exactly one stop`() {
        // Three stops between the ends means four intervals of a quarter.
        val stopped = 0f..1f
        assertEquals(0.5f, tvSliderStepValue(0.25f, stopped, steps = 3, direction = 1), 1e-4f)
        assertEquals(0f, tvSliderStepValue(0.25f, stopped, steps = 3, direction = -1), 1e-4f)
        assertEquals(0.75f, tvSliderStepValue(0.5f, stopped, steps = 3, direction = 1), 1e-4f)
        assertEquals(0.25f, tvSliderStepValue(0.5f, stopped, steps = 3, direction = -1), 1e-4f)
    }

    @Test
    fun `off grid values snap onto the grid before they move`() {
        assertEquals(0.9f, tvSliderStepValue(0.83f, brightness, steps = 0, direction = 1), 1e-4f)
        assertEquals(0.8f, tvSliderStepValue(0.83f, brightness, steps = 0, direction = -1), 1e-4f)
    }

    @Test
    fun `repeated presses never drift`() {
        var value = 0.05f
        repeat(19) { value = tvSliderStepValue(value, brightness, steps = 0, direction = 1) }
        assertEquals(1f, value, 1e-4f)
        repeat(19) { value = tvSliderStepValue(value, brightness, steps = 0, direction = -1) }
        assertEquals(0.05f, value, 1e-4f)
    }

    @Test
    fun `a range that does not start at zero keeps its own grid`() {
        assertEquals(0.5f, tvSliderStepValue(0.5f, 0.5f..0.5f, steps = 0, direction = 1), 1e-4f)
        assertEquals(1.05f, tvSliderStepValue(1.0f, 1.0f..2.0f, steps = 0, direction = 1), 1e-4f)
    }
}
