package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.library.LibraryContentStats
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.stats.domain.StatsContentKind
import androidx.compose.material3.Button as M3Button

/** Android's statistics sheet for one work: the picked work plus the snapshot its sheet loads. */
data class DesktopContentStats(val content: SourceContent, val stats: LibraryContentStats)

/**
 * Android's `ContentStatsDialog`: picking a work in the statistics list opens that work's own numbers instead of its
 * details, and the sheet's "details" button is what navigates. The chart is Android's `ContentStatsBarChart` — one bar
 * per day of the last ninety days with dashed gridlines — drawn from the same shared derivation, so the shape matches
 * the Android sheet. The line under the chart is Android's bare "first read" time (its `DateTimeAgo`); Windows reuses
 * the feed's relative date formatting for it, which is the same set of buckets.
 */
@Composable
internal fun DesktopContentStatsDialog(
    sheet: DesktopContentStats,
    onDismiss: () -> Unit,
    onOpenDetails: (SourceContent) -> Unit,
) {
    val stats = sheet.stats
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(sheet.content.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column(Modifier.width(460.dp).testTag("content-stats"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ContentStatsChart(stats, kindColor = stats.kind.statsColor())
                stats.firstActivityAt?.let { at ->
                    // Android prints the bare relative time under the chart; no label above it.
                    Text(feedDateLabel(at).orEmpty(), style = MaterialTheme.typography.labelSmall, color = Muted,
                        modifier = Modifier.testTag("content-stats-first"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContentStatsMetric(formatStatsDuration(stats.totalDuration),
                        AndroidStrings["stats_total_time"], Modifier.weight(1f).testTag("content-stats-total"))
                    ContentStatsMetric(stats.sessionCount.toString(),
                        AndroidStrings["stats_sessions"], Modifier.weight(1f).testTag("content-stats-sessions"))
                    ContentStatsMetric(stats.units.toString(),
                        stats.kind.unitLabel(), Modifier.weight(1f).testTag("content-stats-units"))
                }
            }
        },
        confirmButton = {
            M3Button(onClick = { onOpenDetails(sheet.content) }, modifier = Modifier.testTag("content-stats-details")) {
                Text(AndroidStrings["details"])
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("content-stats-close")) {
                Text(desktopText("common.cancel"))
            }
        },
    )
}

/** Android's three equal metric plates: value on top, label under it. */
@Composable
private fun ContentStatsMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1)
        }
    }
}

/** Android's `stats_unit_pages`/`_chapters`/`_episodes` under the unit count; "works" when nothing is picked. */
private fun StatsContentKind.unitLabel(): String = AndroidStrings[when (this) {
    StatsContentKind.MANGA -> "stats_unit_pages"
    StatsContentKind.NOVEL -> "stats_unit_chapters"
    StatsContentKind.VIDEO -> "stats_unit_episodes"
    StatsContentKind.ALL -> "stats_works"
}]

@Composable
private fun StatsContentKind.statsColor(): Color = when (this) {
    StatsContentKind.NOVEL -> MaterialTheme.colorScheme.tertiary
    StatsContentKind.VIDEO -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

/**
 * Android's `ContentStatsBarChart` over the ninety-day series: bars compressed by averaging when they no longer fit the
 * card, dashed value gridlines and a solid baseline. Days without reading stay empty so the gaps read as gaps.
 */
@Composable
private fun ContentStatsChart(stats: LibraryContentStats, kindColor: Color) {
    val seconds = stats.dailyActivity.map { (it.duration / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    if (seconds.all { it <= 0 }) {
        Box(Modifier.fillMaxWidth().height(200.dp).testTag("content-stats-chart"), contentAlignment = Alignment.Center) {
            Text(AndroidStrings["empty_stats_text"], style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        return
    }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val dashed = PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
    Canvas(Modifier.fillMaxWidth().height(200.dp).testTag("content-stats-chart")) {
        val minSpacing = 12.dp.toPx()
        val barWidth = 12.dp.toPx()
        val rawWidth = seconds.size * (barWidth + minSpacing) + minSpacing
        val windowSize = (rawWidth / size.width).toInt().coerceAtLeast(1)
        val bars = seconds.chunked(windowSize) { chunk -> chunk.average().toInt() }
        val maxValue = (bars.maxOrNull() ?: 0).toFloat()
        if (maxValue <= 0f) return@Canvas
        var step = 1
        while (size.height / (maxValue / step) <= 60f) step++
        for (value in 0..maxValue.toInt() step step) {
            val y = size.height - (size.height * value / maxValue)
            drawLine(outline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dashed)
        }
        drawLine(outline, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())
        val spacing = (size.width - barWidth * bars.size) / (bars.size + 1)
        bars.forEachIndexed { index, value ->
            if (value <= 0) return@forEachIndexed
            val barHeight = (size.height * value / maxValue).coerceAtLeast(barWidth)
            drawRoundRect(
                color = kindColor,
                topLeft = Offset(spacing + index * (barWidth + spacing), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
