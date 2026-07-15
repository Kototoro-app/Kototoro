package org.skepsun.kototoro.main.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainFabModeTest {

    @Test
    fun `space switcher takes priority when enabled`() {
        assertEquals(
            MainFabMode.SPACE_SWITCHER,
            resolveMainFabMode(spaceSwitcherEnabled = true, resumeEnabled = true),
        )
    }

    @Test
    fun `continue reading is used when spaces are disabled`() {
        assertEquals(
            MainFabMode.CONTINUE_READING,
            resolveMainFabMode(spaceSwitcherEnabled = false, resumeEnabled = true),
        )
    }

    @Test
    fun `fab is hidden when neither action is available`() {
        assertEquals(
            MainFabMode.HIDDEN,
            resolveMainFabMode(spaceSwitcherEnabled = false, resumeEnabled = false),
        )
    }
}
