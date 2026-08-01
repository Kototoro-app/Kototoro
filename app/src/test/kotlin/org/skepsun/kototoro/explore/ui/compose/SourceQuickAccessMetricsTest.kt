package org.skepsun.kototoro.explore.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceQuickAccessMetricsTest {

    @Test
    fun `source title size follows grid scale`() {
        assertEquals(10f, resolveSourceQuickAccessTitleTextSize(0.5f).value)
        assertEquals(12f, resolveSourceQuickAccessTitleTextSize(1f).value)
        assertEquals(14f, resolveSourceQuickAccessTitleTextSize(1.5f).value)
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
}
