package org.skepsun.kototoro.main.ui.compose

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.TopBarStyle

class CenteredTopBarPlacementTest {

    // Pixel sizes at density 1: 44dp buttons, 8dp spacing, 12dp screen padding on each side.
    private val button = 44
    private val spacing = 8

    // Two 40dp icon segments plus the capsule's 3dp inner padding on each side.
    private val capsule = 2 * 40 + 6

    private fun groupWidth(buttons: Int) = segmentedActionGroupWidth(buttons, button.dp).value.toInt()

    private fun place(screen: Int, buttons: Int) =
        centeredTopBarPlacement(screen - 24, capsule, buttons, spacing, ::groupWidth)

    @Test
    fun `action group width matches the capsule row`() {
        assertEquals(0, groupWidth(0))
        assertEquals(44, groupWidth(1))
        assertEquals(94, groupWidth(2))
        assertEquals(140, groupWidth(3))
    }

    @Test
    fun `capsule center does not move between the sources and discover pages`() {
        listOf(360, 412, 600).forEach { screen ->
            val row = screen - 24
            val sources = place(screen, buttons = 3)
            val discover = place(screen, buttons = 2)
            assertEquals(sources.centerX, discover.centerX)
            assertEquals(row / 2, sources.centerX + capsule / 2)
        }
    }

    @Test
    fun `icon capsule keeps all three sources buttons on a 412dp phone`() {
        assertEquals(3, place(412, buttons = 3).shownEndButtons)
        assertEquals(3, place(411, buttons = 3).shownEndButtons)
        assertEquals(2, place(412, buttons = 2).shownEndButtons)
    }

    @Test
    fun `only the buttons that do not fit fold into the menu on a 360dp screen`() {
        val row = 360 - 24
        val sources = place(360, buttons = 3)
        assertEquals(2, sources.shownEndButtons)
        val discover = place(360, buttons = 2)
        assertEquals(2, discover.shownEndButtons)
        listOf(sources, discover).forEach { placement ->
            val endWidth = groupWidth(placement.shownEndButtons)
            // The end group is right-aligned and must stay clear of the capsule plus spacing.
            assertTrue(row - endWidth >= placement.centerX + capsule + spacing)
            // The search button on the left also clears the capsule.
            assertTrue(button + spacing <= placement.centerX)
        }
    }

    @Test
    fun `the more button always stays`() {
        assertEquals(1, centeredTopBarPlacement(200, 150, 4, spacing, ::groupWidth).shownEndButtons)
    }

    @Test
    fun `long search pill moves the switcher below the bar, compact keeps it centred in the bar`() {
        val switcher = TopBarSegmentedSwitcherState(
            items = listOf(TopBarSegmentedSwitcherItem("SOURCES", "Sources")),
            selectedItemId = "SOURCES",
            onItemSelected = {},
        )
        assertEquals(
            SegmentedSwitcherPlacement.BELOW_BAR,
            segmentedSwitcherPlacement(switcher, TopBarStyle.EXPANDED_SEARCH),
        )
        assertEquals(
            SegmentedSwitcherPlacement.IN_BAR,
            segmentedSwitcherPlacement(switcher, TopBarStyle.COMPACT),
        )
        assertNull(segmentedSwitcherPlacement(null, TopBarStyle.EXPANDED_SEARCH))
    }
}
