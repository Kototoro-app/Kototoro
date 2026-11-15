package org.skepsun.kototoro.core.ui.widgets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NavigationLabelFontScaleTest {

    @Test
    fun `labels follow the system font up to the cap`() {
        assertEquals(1f, navigationLabelFontScale(1f))
        assertEquals(1.15f, navigationLabelFontScale(1.15f))
        assertEquals(1.3f, navigationLabelFontScale(1.3f))
    }

    @Test
    fun `labels stop growing past the cap`() {
        assertEquals(1.3f, navigationLabelFontScale(1.5f))
        assertEquals(1.3f, navigationLabelFontScale(2f))
    }
}
