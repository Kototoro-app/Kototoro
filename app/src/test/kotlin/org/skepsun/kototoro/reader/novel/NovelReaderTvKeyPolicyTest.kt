package org.skepsun.kototoro.reader.novel

import android.view.KeyEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelReaderTvKeyPolicyTest {

    @Test
    fun `tv back intercepts when controls are visible and no sub overlays exist`() {
        assertTrue(
            shouldInterceptNovelReaderTvBack(
                isTvPresentation = true,
                controlsVisible = true,
                hasSubOverlay = false,
            ),
        )
    }

    @Test
    fun `tv back delegates when sub overlay exists`() {
        assertFalse(
            shouldInterceptNovelReaderTvBack(
                isTvPresentation = true,
                controlsVisible = true,
                hasSubOverlay = true,
            ),
        )
    }

    @Test
    fun `tv back delegates when controls are hidden`() {
        assertFalse(
            shouldInterceptNovelReaderTvBack(
                isTvPresentation = true,
                controlsVisible = false,
                hasSubOverlay = false,
            ),
        )
    }

    @Test
    fun `tv back delegates when not tv presentation`() {
        assertFalse(
            shouldInterceptNovelReaderTvBack(
                isTvPresentation = false,
                controlsVisible = true,
                hasSubOverlay = false,
            ),
        )
    }

    @Test
    fun `page keys resolve correct deltas considering navigation inverted setting`() {
        assertEquals(-1, resolveNovelReaderPageKeyDelta(KeyEvent.KEYCODE_PAGE_UP, isNavigationInverted = false))
        assertEquals(1, resolveNovelReaderPageKeyDelta(KeyEvent.KEYCODE_PAGE_UP, isNavigationInverted = true))

        assertEquals(1, resolveNovelReaderPageKeyDelta(KeyEvent.KEYCODE_PAGE_DOWN, isNavigationInverted = false))
        assertEquals(-1, resolveNovelReaderPageKeyDelta(KeyEvent.KEYCODE_PAGE_DOWN, isNavigationInverted = true))

        assertNull(resolveNovelReaderPageKeyDelta(KeyEvent.KEYCODE_DPAD_UP, isNavigationInverted = false))
        assertNull(resolveNovelReaderPageKeyDelta(KeyEvent.KEYCODE_BACK, isNavigationInverted = false))
    }
}
