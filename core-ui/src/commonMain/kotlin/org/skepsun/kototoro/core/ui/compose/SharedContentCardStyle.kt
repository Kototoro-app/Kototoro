package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val ContentCoverCornerRadius = 12.dp
val CompactContentCoverCornerRadius = 8.dp
val ContentCoverShape: Shape = RoundedCornerShape(ContentCoverCornerRadius)
val CompactContentCoverShape: Shape = RoundedCornerShape(CompactContentCoverCornerRadius)

data class CompactPosterCardStyle(
    val itemWidth: Dp,
    val posterHeight: Dp,
    val cornerRadius: Dp,
)

fun compactPosterCardStyle(gridScale: Float): CompactPosterCardStyle {
    val normalizedScale = gridScale.coerceIn(0.5f, 1.4f)
    val width = (96f * normalizedScale).dp.coerceIn(48.dp, 134.dp)
    val height = (136f * normalizedScale).dp.coerceIn(68.dp, 190.dp)
    return CompactPosterCardStyle(
        itemWidth = width,
        posterHeight = height,
        cornerRadius = ContentCoverCornerRadius,
    )
}

fun compactPosterRailCardStyle(gridScale: Float): CompactPosterCardStyle {
    val baseStyle = compactPosterCardStyle(gridScale)
    val scale = 80f / 88f
    return CompactPosterCardStyle(
        itemWidth = (baseStyle.itemWidth.value * scale).dp,
        posterHeight = (baseStyle.posterHeight.value * scale).dp,
        cornerRadius = baseStyle.cornerRadius,
    )
}
