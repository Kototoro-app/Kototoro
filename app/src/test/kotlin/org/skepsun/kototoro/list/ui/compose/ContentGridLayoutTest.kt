package org.skepsun.kototoro.list.ui.compose

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.compactPosterCardStyle

class ContentGridLayoutTest {

    private val baseStyle = CompactPosterCardStyle(
        itemWidth = 96.dp,
        posterHeight = 136.dp,
        cornerRadius = 18.dp,
    )

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
        assertEquals(10f, resolveGridTitleFontSize(0.5f).value)
        assertEquals(12f, resolveGridTitleFontSize(1f).value)
        assertEquals(14f, resolveGridTitleFontSize(1.5f).value)
        assertEquals(13f, resolveGridTitleFontSize(1.25f).value)
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
