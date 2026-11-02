package org.skepsun.kototoro.space.ui

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpaceSwitcherPanelWidthTest {

    @Test
    fun `television width stops at the phone cap`() {
        // 1920x1080 at 320dpi resolves to 960dp; 68% of it would cover most of the screen.
        assertEquals(280.dp, spacePanelWidth(960.dp))
    }

    @Test
    fun `phone width follows the design fraction`() {
        assertEquals(244.8f, spacePanelWidth(360.dp).value, 0.01f)
        assertEquals(279.48f, spacePanelWidth(411.dp).value, 0.01f)
    }

    @Test
    fun `fraction stops applying once it passes the cap`() {
        assertEquals(280.dp, spacePanelWidth(412.dp))
    }
}
