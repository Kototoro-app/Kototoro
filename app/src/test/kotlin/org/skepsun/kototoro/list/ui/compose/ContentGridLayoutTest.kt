package org.skepsun.kototoro.list.ui.compose

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.compactPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.gridPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.railPosterCardStyle
import org.skepsun.kototoro.core.prefs.ListMode

class ContentGridLayoutTest {

    private val baseStyle = CompactPosterCardStyle(
        itemWidth = 96.dp,
        posterHeight = 136.dp,
        cornerRadius = 18.dp,
    )

    @Test
    fun `two column compact movie badges leave a gap above the title`() {
        val width = 156.dp
        val height = width * (9f / 16f)
        val metrics = contentCardBadgeMetricsFor(width, height)
        val badgeHeight = metrics.textSize.value + 2 * metrics.containerVerticalPadding.value
        val titleClearance = 2 * (resolveCompactGridTitleFontSize(1f).value + 3.5f) + 6f
        val topBadgeBottom = 5f + badgeHeight
        val bottomBadgeTop = height.value - titleClearance - 5f - badgeHeight

        assertTrue(
            bottomBadgeTop - topBadgeBottom >= 4f,
            "counter and 18+ pills need a gap: top bottom=$topBadgeBottom, bottom top=$bottomBadgeTop",
        )
    }

    @Test
    fun `landscape badge text icons and padding grow with the actual card size`() {
        val small = contentCardBadgeMetricsFor(156.dp, 87.75.dp)
        val large = contentCardBadgeMetricsFor(264.dp, 148.5.dp)
        assertTrue(small.textSize < large.textSize)
        assertTrue(small.iconSize < large.iconSize)
        assertTrue(small.containerVerticalPadding < large.containerVerticalPadding)
        assertTrue(small.containerHorizontalPadding < large.containerHorizontalPadding)
        assertTrue(small.progressSize < large.progressSize)
    }

    @Test
    fun `movie cards preserve landscape ratio across scales and window sizes`() {
        for (mode in listOf(ListMode.MOVIE_GRID, ListMode.COMPACT_MOVIE_GRID)) {
            for (scale in listOf(0.5f, 1f, 1.5f, 2f)) {
                val base = mode.gridPosterCardStyle(scale)
                assertTrue(base.itemWidth >= 160.dp)
                assertEquals(base, mode.railPosterCardStyle(scale))
                for (width in listOf(80.dp, 352.dp, 792.dp)) {
                    val columns = resolveGridColumnCount(width, base.itemWidth, 6.dp)
                    val style = resolveGridPosterCardStyle(base, width, columns, 6.dp)
                    assertEquals(16f / 9f, style.itemWidth / style.posterHeight, 0.001f)
                    assertEquals(
                        width.value,
                        style.itemWidth.value * columns + 6f * (columns - 1),
                        0.001f,
                    )
                }
            }
        }
    }

    @Test
    fun `movie grid uses fewer columns than portrait grid`() {
        val movieWidth = ListMode.MOVIE_GRID.gridPosterCardStyle(1f).itemWidth
        assertEquals(2, resolveGridColumnCount(352.dp, movieWidth, 6.dp))
        assertEquals(4, resolveGridColumnCount(792.dp, movieWidth, 6.dp))
    }

    @Test
    fun `column count follows target width across window sizes`() {
        assertEquals(1, resolveGridColumnCount(80.dp, 96.dp, 6.dp))
        assertEquals(3, resolveGridColumnCount(352.dp, 96.dp, 6.dp))
        assertEquals(8, resolveGridColumnCount(792.dp, 96.dp, 6.dp))
    }

    @Test
    fun `single column card respects narrow window width`() {
        val style = resolveGridPosterCardStyle(
            baseStyle = baseStyle,
            availableWidth = 80.dp,
            columns = 1,
            spacing = 6.dp,
        )

        assertEquals(80f, style.itemWidth.value)
    }

    @Test
    fun `title size follows grid scale continuously`() {
        // 12sp metadata floor + 4sp per grid-scale unit (see resolveGridTitleFontSize)
        assertEquals(12f, resolveGridTitleFontSize(0.5f).value)
        assertEquals(14f, resolveGridTitleFontSize(1f).value)
        assertEquals(16f, resolveGridTitleFontSize(1.5f).value)
        assertEquals(15f, resolveGridTitleFontSize(1.25f).value)
        assertEquals(18f, resolveGridTitleFontSize(2f).value)
    }

    @Test
    fun `every step of the grid size slider changes the card width`() {
        assertEquals(200, GridSizeRange.last)
        for (mode in listOf(ListMode.GRID, ListMode.MOVIE_GRID)) {
            val widths = GridSizeRange.step(5).map { mode.gridPosterCardStyle(it / 100f).itemWidth.value }
            // Movie cards keep a 160dp floor at small scales; past it no slider step may be clamped away.
            val growing = widths.dropWhile { it <= 160f }
            assertTrue(growing.zipWithNext().all { (a, b) -> b > a }, "$mode: $widths")
        }
        assertEquals(192f, compactPosterCardStyle(2f).itemWidth.value)
        assertEquals(352f, ListMode.MOVIE_GRID.gridPosterCardStyle(2f).itemWidth.value)
    }

    @Test
    fun `compact overlay title fits two lines inside the scrim`() {
        // The overlay title sits on the cover, 1.5sp under the below-cover title, and its
        // scrim is tall enough for two lines at every grid scale.
        assertEquals(12.5f, resolveCompactGridTitleFontSize(1f).value)
        for (scale in listOf(0.5f, 1f, 1.5f)) {
            val fontSize = resolveCompactGridTitleFontSize(scale).value
            val posterHeight = compactPosterCardStyle(scale).posterHeight
            val textRoom = compactGridTitleOverlayHeight(posterHeight).value - 2 * 6f
            assertTrue(textRoom >= 2 * (fontSize + 3.5f), "scale=$scale")
        }
    }

    @Test
    fun `poster corner radius is independent from theme shapes`() {
        assertEquals(12.dp, compactPosterCardStyle(1f).cornerRadius)
    }

    @Test
    fun `cards fill each grid row for two through five columns`() {
        val availableWidth = 352.dp
        val spacing = 6.dp

        for (columns in 2..5) {
            val style = resolveGridPosterCardStyle(
                baseStyle = baseStyle,
                availableWidth = availableWidth,
                columns = columns,
                spacing = spacing,
            )
            val occupiedWidth = style.itemWidth.value * columns + spacing.value * (columns - 1)

            assertEquals(availableWidth.value, occupiedWidth, 0.001f, "$columns columns")
            assertEquals(
                baseStyle.itemWidth.value / baseStyle.posterHeight.value,
                style.itemWidth.value / style.posterHeight.value,
                0.001f,
                "$columns columns aspect ratio",
            )
        }
    }
}
