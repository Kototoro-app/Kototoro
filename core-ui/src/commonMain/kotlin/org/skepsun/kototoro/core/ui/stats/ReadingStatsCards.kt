package org.skepsun.kototoro.core.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.ReadOnlyComposable
import org.skepsun.kototoro.core.ui.text.LocalUiStrings
import org.skepsun.kototoro.core.ui.text.UiStrings
import org.skepsun.kototoro.core.ui.text.uiString

/**
 * The cards Android's reading statistics screen is built from (`StatsScreen`), for the hosts that draw the same
 * dashboard over the same read model (`core-domain`'s `deriveReadingStatistics`): one card surface, a header that can
 * carry Android's badge, the day-activity card (its plot stays with the host, because a phone draws a heatmap and a
 * window a bar strip), the hour-of-day split, and the content-kind breakdown.
 *
 * A duration is rendered by [statsDurationText] exactly as Android renders it, so both hosts round the same way.
 */

/**
 * Android's `formatStatsDuration`: hours with the minutes that matter, minutes alone under an hour. The catalog is a
 * parameter so hosts can format a duration outside composition (a unit test, a log line) and still read one text.
 */
fun statsDurationText(durationMs: Long, strings: UiStrings): String {
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0L && minutes > 0L -> strings.format("stats_duration_hours_minutes", hours, minutes)
        hours > 0L -> strings.format("stats_duration_hours", hours)
        else -> strings.format("stats_duration_minutes", minutes)
    }
}

/** [statsDurationText] in the catalog the surrounding shared UI reads. */
@Composable
@ReadOnlyComposable
fun statsDurationText(durationMs: Long): String = statsDurationText(durationMs, LocalUiStrings.current)

/**
 * One content kind's share of the reading time, as the breakdown card draws it. The host projects its own kind enum
 * into this: `core-ui` is the presentation layer for both hosts and deliberately does not depend on the stats domain.
 */
data class StatsKindShare(
    /** Stable key used in the row's test tag (Android's kind name). */
    val key: String,
    val label: String,
    val color: Color,
    val duration: Long,
)

/** Android's `StatsSectionCard`: the rounded plate every card on the dashboard sits on. */
@Composable
fun StatsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp), content = content)
    }
}

/**
 * Android's summary plate, the first card of the dashboard: the picked period and kind as an eyebrow, the total reading
 * time large, and the three counts under it. The host supplies the eyebrow text, since the period and the kind are its
 * own enums.
 */
@Composable
fun StatsSummaryCard(
    eyebrow: String,
    totalDuration: Long,
    activeDays: Int,
    sessions: Int,
    works: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Text(
                    text = statsDurationText(totalDuration),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("stats-total"),
                )
                Text(
                    text = uiString("stats_total_time"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatsMetric(activeDays.toString(), uiString("stats_active_days"), "stats-active-days",
                    Modifier.weight(1f))
                StatsMetricDivider()
                StatsMetric(sessions.toString(), uiString("stats_sessions"), "stats-sessions", Modifier.weight(1f))
                StatsMetricDivider()
                StatsMetric(works.toString(), uiString("stats_works"), "stats-works", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatsMetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(34.dp)
            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f)),
    )
}

@Composable
private fun StatsMetric(value: String, label: String, tag: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
    }
}

/**
 * Android's card header: the section title, the line under it, and a pill on the right that carries the card's own
 * figure (the streak on the activity card, the peak period on the hour split).
 */
@Composable
fun StatsCardHeader(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    badgeContainer: Color = MaterialTheme.colorScheme.tertiaryContainer,
    badgeContent: Color = MaterialTheme.colorScheme.onTertiaryContainer,
    subtitleTag: String? = null,
    badgeTag: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (subtitleTag == null) Modifier else Modifier.testTag(subtitleTag),
                )
            }
        }
        if (badge != null) {
            Spacer(Modifier.width(12.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = badgeContainer,
                modifier = if (badgeTag == null) Modifier else Modifier.testTag(badgeTag),
            ) {
                Text(
                    text = badge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = badgeContent,
                )
            }
        }
    }
}

/**
 * Android's activity card: a title, how many days the period covers, the streak pill, and the plot below. The plot is
 * the host's ([plot]): a phone draws a heat of squares with weekday initials, a window a strip of bars that can carry a
 * date per column, over the same `ReadingDayStats` list.
 */
@Composable
fun StatsActivityCard(
    dayCount: Int,
    streak: Int,
    modifier: Modifier = Modifier,
    plot: @Composable () -> Unit,
) {
    StatsCard(modifier) {
        StatsCardHeader(
            title = uiString("stats_activity"),
            subtitle = uiString("stats_activity_days", dayCount),
            badge = if (streak > 0) uiString("stats_streak", streak) else null,
            subtitleTag = "stats-activity-days",
            badgeTag = "stats-activity-streak",
        )
        Spacer(Modifier.height(16.dp))
        plot()
    }
}

/**
 * Android's "reading times" card: the 24 hours grouped into the eight three-hour buckets Android groups them into, the
 * tallest bucket deciding the heights, the peak named in the header pill, and the three axis labels under them.
 */
@Composable
fun StatsTimeDistributionCard(
    hourlyActivity: List<Long>,
    periodLabel: String,
    modifier: Modifier = Modifier,
) {
    val buckets = hourlyActivity.chunked(3).map { it.sum() }
    if (buckets.isEmpty()) return
    val maxDuration = buckets.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val peakIndex = buckets.indices.maxByOrNull { buckets[it] } ?: 0
    StatsCard(modifier) {
        StatsCardHeader(
            title = uiString("stats_time_distribution"),
            subtitle = uiString("stats_time_distribution_subtitle", periodLabel),
            badge = uiString(
                "stats_peak_period",
                uiString("stats_hour_range", peakIndex * 3, (peakIndex * 3 + 3) % 24),
            ),
            badgeContainer = MaterialTheme.colorScheme.secondaryContainer,
            badgeContent = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(126.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            buckets.forEachIndexed { index, duration ->
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().testTag("stats-hour:${index * 3}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .fillMaxHeight(
                                    if (duration == 0L) {
                                        0.04f
                                    } else {
                                        (duration.toFloat() / maxDuration.toFloat()).coerceAtLeast(0.12f)
                                    },
                                )
                                .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))
                                .background(
                                    if (duration == 0L) {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(
                                            alpha = 0.35f + 0.65f * duration.toFloat() / maxDuration.toFloat(),
                                        )
                                    },
                                ),
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = if (index % 2 == 0) (index * 3).toString().padStart(2, '0') else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(
                text = uiString("stats_time_midnight"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = uiString("stats_time_noon"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = uiString("stats_time_late_night"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Android's content-kind breakdown: one line per kind with its colour dot, its name, its share of the reading time and
 * the same share as a bar. Kinds that hold no time are the host's decision — it passes the ones it means to show.
 */
@Composable
fun StatsKindBreakdownCard(
    kinds: List<StatsKindShare>,
    modifier: Modifier = Modifier,
) {
    if (kinds.isEmpty()) return
    val total = kinds.sumOf { it.duration }.coerceAtLeast(1L)
    StatsCard(modifier) {
        Text(
            text = uiString("stats_content_breakdown"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(14.dp))
        kinds.forEachIndexed { index, kind ->
            val duration = kind.duration
            if (index > 0) Spacer(Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("stats-kind:${kind.key}"),
            ) {
                Box(Modifier.size(10.dp).background(kind.color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(kind.label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${statsDurationText(duration)} · ${duration * 100 / total}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 7.dp)
                    .height(7.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((duration.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(kind.color),
                )
            }
        }
    }
}
