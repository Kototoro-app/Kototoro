package org.skepsun.kototoro.core.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * One bar per day of a work's own history — Android's `ContentStatsBarChart`, the chart in the per-work statistics
 * dialog/sheet. Both hosts drew the same canvas twice (Android in `ContentStatsDialog`, Windows in
 * `DesktopContentStatsDialog`), so the geometry lives here now: bars keep a 12dp width and at least 12dp of air,
 * days are averaged together once the series no longer fits the card, the value gridlines are dashed and the baseline
 * is solid. Days without reading stay empty so the gaps read as gaps.
 *
 * The host supplies the series in its own unit (Android counts pages/chapters/episodes, Windows counts reading
 * seconds), the bar colour from its content kind, and the height: Android's sheet uses 240dp, the desktop dialog 200dp.
 * An all-zero or empty series shows [emptyText] centred instead of an axis with nothing on it.
 */
@Composable
fun ContentStatsBarChart(
    values: List<Int>,
    barColor: Color,
    emptyText: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val tagModifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
    if (values.isEmpty() || values.none { it > 0 }) {
        Box(modifier.then(tagModifier), contentAlignment = Alignment.Center) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val dashed = remember { PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f) }
    Canvas(modifier.then(tagModifier)) {
        val barWidth = 12.dp.toPx()
        val minSpacing = 12.dp.toPx()
        val rawWidth = values.size * (barWidth + minSpacing) + minSpacing
        val windowSize = ceil(rawWidth / size.width).toInt().coerceAtLeast(1)
        // Averaging a window keeps the shape readable, but a window that holds any activity at all must not average
        // away to an empty bar: a single second among five quiet days would leave the whole chart blank. Such a
        // window keeps a one-unit bar, so a barely-read day still shows up instead of a void.
        val bars = values.chunked(windowSize) { chunk ->
            val average = chunk.average().toInt()
            if (average == 0 && chunk.any { it > 0 }) 1 else average
        }
        val maxValue = (bars.maxOrNull() ?: 0).toFloat().coerceAtLeast(1f)
        val step = contentStatsGridStep(size.height, maxValue)
        for (value in 0..maxValue.toInt() step step) {
            val y = size.height - (size.height * value / maxValue)
            drawLine(
                color = outlineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dashed,
            )
        }
        drawLine(
            color = outlineColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 2.dp.toPx(),
        )
        val spacing = (size.width - barWidth * bars.size) / (bars.size + 1)
        bars.forEachIndexed { index, value ->
            if (value <= 0) return@forEachIndexed
            val barHeight = (size.height * value / maxValue).coerceAtLeast(barWidth)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(spacing + index * (barWidth + spacing), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** Gridlines stay at least 60px apart, exactly as the two host copies spaced them. */
private fun contentStatsGridStep(heightPx: Float, maxValue: Float): Int {
    val minSpacePx = 60f
    var step = 1
    while (heightPx / (maxValue / step) <= minSpacePx) {
        step++
    }
    return step
}
