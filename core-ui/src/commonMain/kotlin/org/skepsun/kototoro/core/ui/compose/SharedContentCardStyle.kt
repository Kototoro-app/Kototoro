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
    val normalizedScale = gridScale.coerceIn(0.5f, 2f)
    val width = (96f * normalizedScale).dp.coerceIn(48.dp, 192.dp)
    val height = (136f * normalizedScale).dp.coerceIn(68.dp, 272.dp)
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

fun moviePosterCardStyle(gridScale: Float): CompactPosterCardStyle {
    // Keep landscape cards wide enough for titles and corner badges at small grid scales.
    val width = (176f * gridScale.coerceIn(0.5f, 2f)).dp.coerceIn(160.dp, 352.dp)
    return CompactPosterCardStyle(
        itemWidth = width,
        posterHeight = width * (9f / 16f),
        cornerRadius = ContentCoverCornerRadius,
    )
}
