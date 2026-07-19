package org.skepsun.kototoro.main.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.parsers.model.ContentType

class SwipeableFilterChipTest {

    @Test
    fun `expanded chip reserves three content cells`() {
        assertEquals(1f, swipeableFilterChipWidthMultiplier(0f))
        assertEquals(3f, swipeableFilterChipWidthMultiplier(1f))
    }

    @Test
    fun `drag position maps to video manga and novel slots`() {
        assertEquals(0, resolveSwipeableFilterIndex(-29f, 28f))
        assertEquals(1, resolveSwipeableFilterIndex(0f, 28f))
        assertEquals(2, resolveSwipeableFilterIndex(29f, 28f))
    }

    @Test
    fun `tap selects manga and reselecting manga clears the filter`() {
        assertEquals(ContentType.MANGA, resolveSwipeableFilterTapSelection(null))
        assertEquals(ContentType.MANGA, resolveSwipeableFilterTapSelection(ContentType.VIDEO))
        assertEquals(null, resolveSwipeableFilterTapSelection(ContentType.MANGA))
        assertEquals(null, resolveSwipeableFilterTapSelection(ContentType.HENTAI_MANGA))
    }
}
