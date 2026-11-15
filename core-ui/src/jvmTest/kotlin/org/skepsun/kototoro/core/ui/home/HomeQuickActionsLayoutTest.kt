package org.skepsun.kototoro.core.ui.home

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The quick access grid's column and row rules. Large system fonts used to break tile labels mid-word ("Favorite / s")
 * because only the label grew while the tile stayed 68dp; the column count now follows the font scale.
 */
class HomeQuickActionsLayoutTest {

    @Test
    fun `a large system font asks for wider tiles`() {
        assertEquals(68.dp, homeQuickActionTileWidth(68.dp, fontScale = 1f))
        assertEquals(102.dp, homeQuickActionTileWidth(68.dp, fontScale = 1.5f))
        assertEquals(136.dp, homeQuickActionTileWidth(68.dp, fontScale = 2f))
    }

    @Test
    fun `the tile width ignores a font scale below normal and caps runaway scales`() {
        assertEquals(68.dp, homeQuickActionTileWidth(68.dp, fontScale = 0.85f))
        assertEquals(204.dp, homeQuickActionTileWidth(68.dp, fontScale = 5f))
    }

    @Test
    fun `a phone fits five tiles at normal text and three at 1_5x`() {
        // 411dp window, 6dp gaps, the same arithmetic the grid runs inside BoxWithConstraints.
        fun columns(width: Float, fontScale: Float): Int {
            val tile = homeQuickActionTileWidth(68.dp, fontScale)
            return ((width.dp + 6.dp) / (tile + 6.dp)).toInt().coerceAtLeast(2)
        }
        assertEquals(5, columns(411f, 1f))
        assertEquals(3, columns(411f, 1.5f))
        assertEquals(2, columns(411f, 2f))
    }

    @Test
    fun `rows spread evenly instead of leaving one straggler`() {
        assertEquals(listOf(5, 5), homeQuickActionRows(10, 5))
        assertEquals(listOf(3, 3, 2, 2), homeQuickActionRows(10, 3))
        assertEquals(listOf(2, 2, 2, 2, 2), homeQuickActionRows(10, 2))
        assertEquals(listOf(3, 3), homeQuickActionRows(6, 4))
    }

    @Test
    fun `every row keeps its tile count and the total is untouched`() {
        for (count in 1..24) {
            for (max in 1..8) {
                val rows = homeQuickActionRows(count, max)
                assertEquals(count, rows.sum(), "count=$count max=$max rows=$rows")
                assertTrue(rows.all { it <= max }, "a row exceeded $max: $rows")
                assertTrue(rows.all { it > 0 }, "an empty row appeared: $rows")
                // No row may be shorter than the last one by more than one tile.
                assertTrue(rows.max() - rows.min() <= 1 || rows.size == 1, "uneven split: $rows")
            }
        }
    }

    @Test
    fun `an empty grid asks for no rows`() {
        assertTrue(homeQuickActionRows(0, 4).isEmpty())
        assertTrue(homeQuickActionRows(-3, 4).isEmpty())
    }
}
