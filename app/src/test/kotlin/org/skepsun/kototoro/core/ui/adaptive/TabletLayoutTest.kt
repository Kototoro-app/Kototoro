package org.skepsun.kototoro.core.ui.adaptive

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TabletLayoutTest {

    @Test
    fun `width classes switch at 600 and 1000 dp`() {
        assertEquals(TabletLayoutClass.COMPACT, tabletLayoutClass(599, tabletLayoutEnabled = true))
        assertEquals(TabletLayoutClass.MEDIUM, tabletLayoutClass(600, tabletLayoutEnabled = true))
        assertEquals(TabletLayoutClass.MEDIUM, tabletLayoutClass(999, tabletLayoutEnabled = true))
        assertEquals(TabletLayoutClass.EXPANDED, tabletLayoutClass(1000, tabletLayoutEnabled = true))
    }

    @Test
    fun `disabled tablet mode is always compact`() {
        assertEquals(TabletLayoutClass.COMPACT, tabletLayoutClass(1280, tabletLayoutEnabled = false))
    }

    @Test
    fun `preview card is 380dp on expanded windows and at most half a medium window`() {
        assertEquals(380.dp, tabletPreviewCardWidth(1280.dp))
        assertEquals(360.dp, tabletPreviewCardWidth(800.dp))
        assertEquals(320.dp, tabletPreviewCardWidth(640.dp))
    }

    @Test
    fun `both overlays fit on a landscape tablet but not a portrait one`() {
        assertTrue(tabletOverlaysFitTogether(1280.dp))
        assertFalse(tabletOverlaysFitTogether(800.dp))
    }
}
