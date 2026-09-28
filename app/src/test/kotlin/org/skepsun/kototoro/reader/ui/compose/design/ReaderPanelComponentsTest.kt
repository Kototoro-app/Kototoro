package org.skepsun.kototoro.reader.ui.compose.design

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderPanelComponentsTest {

    @Test
    fun `stepping moves one step within the range`() {
        assertEquals(18f, steppedValue(17f, step = 1f, direction = 1, range = 14f..24f))
        assertEquals(16f, steppedValue(17f, step = 1f, direction = -1, range = 14f..24f))
    }

    @Test
    fun `stepping stops at the ends of the range`() {
        assertEquals(24f, steppedValue(24f, step = 1f, direction = 1, range = 14f..24f))
        assertEquals(14f, steppedValue(14f, step = 1f, direction = -1, range = 14f..24f))
    }

    @Test
    fun `stepping snaps off-grid values back onto the grid`() {
        assertEquals(18f, steppedValue(17.3f, step = 1f, direction = 1, range = 14f..24f))
        assertEquals(40f, steppedValue(36f, step = 4f, direction = 1, range = 12f..120f))
    }

    @Test
    fun `fractional steps do not drift`() {
        assertEquals(1.7f, steppedValue(1.6f, step = 0.1f, direction = 1, range = 1.2f..2.0f), 1e-4f)
    }
}
