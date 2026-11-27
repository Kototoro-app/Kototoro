package org.skepsun.kototoro.main.ui.compose

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CenteredTopBarPlacementTest {

    // Pixel sizes at density 1: 44dp buttons, 8dp spacing, 12dp screen padding on each side.
    private val button = 44
    private val spacing = 8
    private val capsule = 126

    private fun groupWidth(buttons: Int) = segmentedActionGroupWidth(buttons, button.dp).value.toInt()

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
            val sources = centeredTopBarPlacement(row, capsule, groupWidth(3), spacing)
            val discover = centeredTopBarPlacement(row, capsule, groupWidth(2), spacing)
            assertEquals(sources.centerX, discover.centerX)
            assertEquals(row / 2, sources.centerX + capsule / 2)
        }
    }

    @Test
    fun `actions that do not fit beside the capsule collapse into the menu on a 360dp screen`() {
        val row = 360 - 24
        val sources = centeredTopBarPlacement(row, capsule, groupWidth(3), spacing)
        assertTrue(sources.collapsed)
        val discover = centeredTopBarPlacement(row, capsule, groupWidth(2), spacing)
        assertFalse(discover.collapsed)
        listOf(sources, discover).forEach { placement ->
            val endWidth = if (placement.collapsed) groupWidth(1) else groupWidth(2)
            // The end group is right-aligned and must stay clear of the capsule plus spacing.
            assertTrue(row - endWidth >= placement.centerX + capsule + spacing)
            // The search button on the left also clears the capsule.
            assertTrue(button + spacing <= placement.centerX)
        }
    }

    @Test
    fun `wide screens keep every action in the bar`() {
        val placement = centeredTopBarPlacement(840 - 24, capsule, groupWidth(3), spacing)
        assertFalse(placement.collapsed)
    }
}
