package org.skepsun.kototoro.reader.ui.tapgrid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.reader.domain.TapGridArea

class TapGridConfigTest {

    @Test
    fun `defaults are Android's reader actions`() {
        val prev = listOf(TapGridArea.TOP_LEFT, TapGridArea.TOP_CENTER, TapGridArea.CENTER_LEFT, TapGridArea.BOTTOM_LEFT)
        val next = listOf(TapGridArea.TOP_RIGHT, TapGridArea.CENTER_RIGHT, TapGridArea.BOTTOM_CENTER, TapGridArea.BOTTOM_RIGHT)
        prev.forEach { assertEquals(TapActions(TapAction.PAGE_PREV, null), TapGridConfig.defaults[it]) }
        next.forEach { assertEquals(TapActions(TapAction.PAGE_NEXT, null), TapGridConfig.defaults[it]) }
        assertEquals(TapActions(TapAction.TOGGLE_UI, TapAction.SHOW_MENU), TapGridConfig.defaults[TapGridArea.CENTER])
        assertEquals(TapGridArea.entries.toSet(), TapGridConfig.defaults.keys)
    }

    @Test
    fun `preference keys match Android's tap_grid preferences`() {
        assertEquals("CENTER", TapGridConfig.prefKey(TapGridArea.CENTER, isLongTap = false))
        assertEquals("BOTTOM_RIGHT_long", TapGridConfig.prefKey(TapGridArea.BOTTOM_RIGHT, isLongTap = true))
    }

    @Test
    fun `editing one action keeps the others`() {
        val edited = TapGridConfig.with(TapGridConfig.defaults, TapGridArea.CENTER, isLongTap = true, TapAction.CHAPTER_NEXT)
        assertEquals(TapAction.CHAPTER_NEXT, TapGridConfig.action(edited, TapGridArea.CENTER, isLongTap = true))
        assertEquals(TapAction.TOGGLE_UI, TapGridConfig.action(edited, TapGridArea.CENTER, isLongTap = false))
        assertEquals(TapGridConfig.defaults - TapGridArea.CENTER, edited - TapGridArea.CENTER)

        val cleared = TapGridConfig.with(edited, TapGridArea.TOP_LEFT, isLongTap = false, null)
        assertNull(TapGridConfig.action(cleared, TapGridArea.TOP_LEFT, isLongTap = false))
        TapGridArea.entries.forEach { area ->
            assertNull(TapGridConfig.action(TapGridConfig.disabled, area, isLongTap = false))
            assertNull(TapGridConfig.action(TapGridConfig.disabled, area, isLongTap = true))
        }
    }
}
