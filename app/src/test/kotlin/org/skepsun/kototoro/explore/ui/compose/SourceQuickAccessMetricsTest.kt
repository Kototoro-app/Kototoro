package org.skepsun.kototoro.explore.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.prefs.ListMode

class SourceQuickAccessMetricsTest {

    @Test
    fun `source title size follows grid scale`() {
        // 12sp metadata floor + 4sp per grid-scale unit (see resolveSourceQuickAccessTitleTextSize)
        assertEquals(12f, resolveSourceQuickAccessTitleTextSize(0.5f).value)
        assertEquals(14f, resolveSourceQuickAccessTitleTextSize(1f).value)
        assertEquals(16f, resolveSourceQuickAccessTitleTextSize(1.5f).value)
    }

    @Test
    fun `source card geometry changes only at density thresholds`() {
        val fiveColumnsStart = sourceQuickAccessMetrics(0.5f)
        val fiveColumnsEnd = sourceQuickAccessMetrics(0.8f)
        val fourColumnsStart = sourceQuickAccessMetrics(0.85f)
        val fourColumnsEnd = sourceQuickAccessMetrics(1.1f)
        val threeColumnsStart = sourceQuickAccessMetrics(1.15f)
        val threeColumnsEnd = sourceQuickAccessMetrics(1.5f)

        assertSameGeometry(fiveColumnsStart, fiveColumnsEnd, expectedColumns = 5)
        assertSameGeometry(fourColumnsStart, fourColumnsEnd, expectedColumns = 4)
        assertSameGeometry(threeColumnsStart, threeColumnsEnd, expectedColumns = 3)
    }

    private fun assertSameGeometry(
        first: SourceQuickAccessMetrics,
        second: SourceQuickAccessMetrics,
        expectedColumns: Int,
    ) {
        assertEquals(expectedColumns, first.preferredColumns)
        assertEquals(expectedColumns, second.preferredColumns)
        assertEquals(first.minCardWidth, second.minCardWidth)
        assertEquals(first.cardHeight, second.cardHeight)
        assertEquals(first.gridSpacing, second.gridSpacing)
        assertEquals(first.iconContainerSize, second.iconContainerSize)
        assertEquals(first.iconSize, second.iconSize)
    }

    @Test
    fun `source grid columns shrink as the system font grows`() {
        val metrics = sourceQuickAccessMetrics(1f)
        val width = 379.dp
        val columns = listOf(1f, 1.3f, 1.5f, 2f).map {
            calculateSourceGridColumns(width, metrics, ListMode.GRID, fontScale = it)
        }
        assertEquals(4, columns[0])
        assertTrue(columns.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(3, columns[2])
        assertEquals(2, columns[3])
    }

    @Test
    fun `source grid keeps its columns at the default font and on wide windows`() {
        val metrics = sourceQuickAccessMetrics(1f)
        assertEquals(
            calculateSourceGridColumns(379.dp, metrics, ListMode.GRID),
            calculateSourceGridColumns(379.dp, metrics, ListMode.GRID, fontScale = 1f),
        )
        assertTrue(calculateSourceGridColumns(1000.dp, metrics, ListMode.GRID, fontScale = 1.5f) >= 4)
    }
}
