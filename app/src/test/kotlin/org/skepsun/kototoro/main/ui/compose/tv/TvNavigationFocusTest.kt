package org.skepsun.kototoro.main.ui.compose.tv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TvNavigationFocusTest {

    @Test
    fun `entering navigation falls back to selected visible destination`() {
        assertEquals(2, resolveTvNavigationFocusItem(listOf(1, 2, 3), 2))
    }

    @Test
    fun `hidden selected destination falls back to first visible destination`() {
        assertEquals(3, resolveTvNavigationFocusItem(listOf(3, 1), 2))
    }

    @Test
    fun `empty navigation leaves focus fallback to the framework`() {
        assertNull(resolveTvNavigationFocusItem(emptyList(), 2))
    }
}
