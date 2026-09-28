package org.skepsun.kototoro.home.ui.compose

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.adaptive.TabletLayoutClass

class HomeTabletLayoutTest {

    @Test
    fun `quick actions split into even rows instead of leaving one alone`() {
        assertEquals(5, balancedGridColumns(itemCount = 10, maxColumns = 9))
        assertEquals(10, balancedGridColumns(itemCount = 10, maxColumns = 13))
        assertEquals(4, balancedGridColumns(itemCount = 10, maxColumns = 4))
        assertEquals(4, balancedGridColumns(itemCount = 7, maxColumns = 5))
    }

    @Test
    fun `balanced columns never exceed the maximum and stay at least one`() {
        assertEquals(1, balancedGridColumns(itemCount = 0, maxColumns = 6))
        assertEquals(1, balancedGridColumns(itemCount = 5, maxColumns = 0))
    }

    @Test
    fun `phone hero cards keep their fixed width`() {
        assertEquals(312.dp, homeHeroCardWidth(420.dp, 16.dp, 6.dp, TabletLayoutClass.COMPACT))
        assertEquals(312.dp * 0 + 360.dp * 0.78f, homeHeroCardWidth(360.dp, 16.dp, 6.dp, TabletLayoutClass.COMPACT))
    }

    @Test
    fun `medium windows show one and a half hero cards`() {
        // (800 - 16 - 6) / 1.5
        assertEquals(518.6667f, homeHeroCardWidth(800.dp, 16.dp, 6.dp, TabletLayoutClass.MEDIUM).value, 0.01f)
    }

    @Test
    fun `expanded windows fill the row with two or three capped cards`() {
        // (1200 - 32 - 6) / 2
        assertEquals(581f, homeHeroCardWidth(1200.dp, 16.dp, 6.dp, TabletLayoutClass.EXPANDED).value, 0.01f)
        // Three cards from 1400dp: (1600 - 32 - 12) / 3
        assertEquals(518.6667f, homeHeroCardWidth(1600.dp, 16.dp, 6.dp, TabletLayoutClass.EXPANDED).value, 0.01f)
        assertEquals(640f, homeHeroCardWidth(1399.dp, 16.dp, 6.dp, TabletLayoutClass.EXPANDED).value, 0.01f)
    }
}
