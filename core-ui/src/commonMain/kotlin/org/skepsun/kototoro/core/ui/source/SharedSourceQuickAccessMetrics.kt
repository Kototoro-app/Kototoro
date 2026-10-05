package org.skepsun.kototoro.explore.ui.compose

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SourceQuickAccessMetrics(
    val preferredColumns: Int,
    val minCardWidth: androidx.compose.ui.unit.Dp,
    val cardHeight: androidx.compose.ui.unit.Dp,
    val gridSpacing: androidx.compose.ui.unit.Dp,
    val iconContainerSize: androidx.compose.ui.unit.Dp,
    val iconSize: androidx.compose.ui.unit.Dp,
    val titleTextSize: TextUnit,
)
fun sourceQuickAccessMetrics(gridScale: Float): SourceQuickAccessMetrics {
    val titleTextSize = resolveSourceQuickAccessTitleTextSize(gridScale)
    return when {
        gridScale <= 0.8f -> SourceQuickAccessMetrics(
            preferredColumns = 5,
            minCardWidth = 64.dp,
            cardHeight = 92.dp,
            gridSpacing = 4.dp,
            iconContainerSize = 56.dp,
            iconSize = 46.dp,
            titleTextSize = titleTextSize,
        )
        gridScale < 1.15f -> SourceQuickAccessMetrics(
            preferredColumns = 4,
            minCardWidth = 80.dp,
            cardHeight = 108.dp,
            gridSpacing = 5.dp,
            iconContainerSize = 68.dp,
            iconSize = 56.dp,
            titleTextSize = titleTextSize,
        )
        else -> SourceQuickAccessMetrics(
            preferredColumns = 3,
            minCardWidth = 108.dp,
            cardHeight = 134.dp,
            gridSpacing = 6.dp,
            iconContainerSize = 88.dp,
            iconSize = 72.dp,
            titleTextSize = titleTextSize,
        )
    }
}

fun resolveSourceQuickAccessTitleTextSize(gridScale: Float): TextUnit {
    val normalized = ((gridScale.coerceIn(0.5f, 1.5f) - 0.5f) / 1f).coerceIn(0f, 1f)
    return (12f + 4f * normalized).sp
}
