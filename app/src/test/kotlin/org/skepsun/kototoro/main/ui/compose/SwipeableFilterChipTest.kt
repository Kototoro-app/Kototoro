package org.skepsun.kototoro.main.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SwipeableFilterChipTest {

    @Test
    fun `expanded chip reserves three content cells`() {
        assertEquals(1f, swipeableFilterChipWidthMultiplier(0f))
        assertEquals(3f, swipeableFilterChipWidthMultiplier(1f))
    }

    @Test
    fun `drag position maps to video manga and novel slots`() {
        assertEquals(0, resolveSwipeableFilterIndex(-25f, 24f))
        assertEquals(1, resolveSwipeableFilterIndex(0f, 24f))
        assertEquals(2, resolveSwipeableFilterIndex(25f, 24f))
    }
}
