package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.library.LibraryReadingStats
import org.skepsun.kototoro.core.library.LibraryReadingStatsWork
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow
import org.skepsun.kototoro.stats.domain.ReadingDayStats
import org.skepsun.kototoro.stats.domain.ReadingStatsPeriod
import org.skepsun.kototoro.stats.domain.StatsContentKind

/**
 * Android's "阅读统计" screen. The statistics themselves were already there — the desktop backup and the WebDAV sync
 * read and merge the same `stats` and reading-session rows — but nothing on this host ever wrote them, and nothing
 * showed them. The reader now records every stop (see [DesktopController.beginReadingStop]) and this page puts the
 * shared derivation behind Android's period switch: the same total, streak, per-day activity, type breakdown and
 * "most enjoyed" list, with the works opening their details. Android draws its summary as the first card without a
 * section title, so this page does the same under the screen title.
 */
@Composable
internal fun DesktopReadingStatsPage(controller: DesktopController, state: DesktopAppState) =
    DesktopReadingStatsContent(
        stats = state.readingStats,
        period = state.readingStatsPeriod,
        kind = state.readingStatsKind,
        active = !state.busy,
        covers = controller.session.covers,
        onBack = controller::more,
        onPeriod = controller::readingStatsPeriod,
        onKind = controller::readingStatsKind,
        onWork = controller::details,
        onClear = controller::clearReadingStats,
    )

/**
 * The screen without the controller: the summary, the filters, the activity bars, the type breakdown and the clear
 * confirmation all read from [stats] alone, so the dashboard can be rendered against a fixed set of statistics.
 */
@Composable
internal fun DesktopReadingStatsContent(
    stats: LibraryReadingStats?,
    period: ReadingStatsPeriod,
    kind: StatsContentKind,
    active: Boolean,
    /** Null draws the plate Android shows for a work without a cover; the app always has its cache. */
    covers: DesktopCovers?,
    onBack: () -> Unit,
    onPeriod: (ReadingStatsPeriod) -> Unit,
    onKind: (StatsContentKind) -> Unit,
    onWork: (SourceContent) -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton(onBack, enabled = active, modifier = Modifier.testTag("stats-back")) {
            Text(desktopText("common.back"))
        }
        // Android puts the period menu and the kind pills in one sideways-scrolling row; a desktop window is wide
        // enough for two rows, and keeping each row a plain one avoids nesting a second scroll viewport in the page.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("stats-periods")) {
            ReadingStatsPeriod.entries.forEach { candidate ->
                if (candidate == period) {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(50),
                        modifier = Modifier.testTag("stats-period:${candidate.name}")) {
                        Text(candidate.title(), Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onPrimary)
                    }
                } else {
                    OutlinedButton({ onPeriod(candidate) }, enabled = active,
                        modifier = Modifier.testTag("stats-period:${candidate.name}")) {
                        Text(candidate.title())
                    }
                }
            }
        }
        KindSelector(kind, active, onKind)
        if (stats == null || stats.works.isEmpty()) {
            Text(AndroidStrings["empty_stats_text"], color = Muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("stats-empty"))
            return@Column
        }
        SettingsGroup(Modifier.testTag("stats-summary")) {
            SettingsValueRow(AndroidStrings["stats_total_time"], null, formatStatsDuration(stats.totalDuration), false,
                {}, testTag = "stats-total")
            SettingsRowDivider()
            SettingsValueRow(AndroidStrings["stats_active_days"], null, stats.activeDays.toString(), false, {},
                testTag = "stats-active-days")
            SettingsRowDivider()
            SettingsValueRow(AndroidStrings["stats_sessions"], null, stats.sessionCount.toString(), false, {},
                testTag = "stats-sessions")
            SettingsRowDivider()
            SettingsValueRow(AndroidStrings["stats_works"], null, stats.works.size.toString(), false, {},
                testTag = "stats-works")
            if (stats.currentStreak > 0) {
                SettingsRowDivider()
                SettingsValueRow(AndroidStrings["stats_streak"], null,
                    AndroidStrings.format("stats_streak", stats.currentStreak), false, {},
                    testTag = "stats-streak")
            }
        }
        SettingsSectionHeader(AndroidStrings["stats_activity"])
        ActivityCard(stats.dailyActivity, stats.currentStreak)
        if (stats.hourlyActivity.any { it > 0L }) {
            SettingsSectionHeader(AndroidStrings["stats_time_distribution"])
            TimeDistributionCard(stats.hourlyActivity)
        }
        val kinds = stats.works.groupBy { it.kind }.map { (kind, works) -> kind to works.sumOf { it.duration } }
            .sortedByDescending { it.second }
        if (kinds.size > 1) {
            SettingsSectionHeader(AndroidStrings["stats_content_breakdown"])
            KindBreakdownCard(kinds)
        }
        SettingsSectionHeader(AndroidStrings["stats_top_works"])
        StatsSectionCard(Modifier.testTag("stats-works-list")) {
            stats.works.forEachIndexed { index, work ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                WorkStatsRow(active, index + 1, work, covers) { onWork(work.content) }
            }
        }
        OutlinedButton({ confirmClear = true }, enabled = active,
            modifier = Modifier.padding(top = 8.dp).testTag("stats-clear")) {
            Text(AndroidStrings["clear_stats"])
        }
    }
    if (confirmClear) {
        DesktopConfirmDialog(
            title = AndroidStrings["clear_stats"],
            text = AndroidStrings["clear_stats_confirm"],
            confirmLabel = AndroidStrings["clear"],
            cancelLabel = AndroidStrings["cancel"],
            bodyTag = "stats-clear-dialog",
            confirmTag = "stats-clear-accept",
            cancelTag = "stats-clear-cancel",
            onConfirm = { confirmClear = false; onClear() },
            onDismiss = { confirmClear = false },
        )
    }
}

/** Android's period titles come from the catalog its `StatsPeriod` enum points at. */
private fun ReadingStatsPeriod.title(): String = AndroidStrings[when (this) {
    ReadingStatsPeriod.DAY -> "day"
    ReadingStatsPeriod.WEEK -> "week"
    ReadingStatsPeriod.MONTH -> "month"
    ReadingStatsPeriod.MONTHS_3 -> "three_months"
    ReadingStatsPeriod.ALL -> "all_time"
}]

/**
 * Android's `ContentKindSelector`: the period menu and this pill sit in one sideways-scrolling row, a chip per content
 * type with the selected one filled with the primary colour. Windows runs no separate manga/novel/video surfaces, so
 * this filter and the type breakdown are where the distinction shows.
 */
@Composable
private fun KindSelector(selected: StatsContentKind, active: Boolean, onKind: (StatsContentKind) -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.testTag("stats-kind-filter")) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            StatsContentKind.entries.forEach { candidate ->
                val isSelected = candidate == selected
                Box(Modifier.height(36.dp).clip(RoundedCornerShape(50))
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable(enabled = active) { onKind(candidate) }
                    .padding(horizontal = 13.dp).testTag("stats-kind-option:${candidate.name}"),
                    contentAlignment = Alignment.Center) {
                    Text(candidate.title(), style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Android's `formatStatsDuration`: hours with the minutes that matter, minutes alone under an hour. */
internal fun formatStatsDuration(durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0L && minutes > 0L -> AndroidStrings.format("stats_duration_hours_minutes", hours, minutes)
        hours > 0L -> AndroidStrings.format("stats_duration_hours", hours)
        else -> AndroidStrings.format("stats_duration_minutes", minutes)
    }
}

/**
 * Android's activity card: one column per visible day, height relative to the busiest day. A period of 90 days (and the
 * all-time view) is a dense heatmap there; Windows keeps the same bars but the columns carry the date they stand for.
 */
@Composable
private fun ActivityCard(activity: List<ReadingDayStats>, streak: Int) {
    val max = activity.maxOfOrNull { it.duration }?.coerceAtLeast(1L) ?: 1L
    StatsSectionCard(Modifier.testTag("stats-activity")) {
        Text(AndroidStrings.format("stats_activity_days", activity.size),
            style = MaterialTheme.typography.bodySmall, color = Muted,
            modifier = Modifier.testTag("stats-activity-days"))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom) {
            activity.forEach { day ->
                Box(Modifier.weight(1f).fillMaxSize().testTag("stats-day:${day.date}"),
                    contentAlignment = Alignment.BottomCenter) {
                    val fraction = if (day.duration <= 0L) 0f else (day.duration.toFloat() / max)
                    Box(Modifier.fillMaxWidth().fillMaxSize(fraction.coerceAtLeast(.04f)).background(
                        if (day.duration > 0L) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(3.dp),
                    ))
                }
            }
        }
        if (streak > 0) {
            Spacer(Modifier.height(8.dp))
            Text(AndroidStrings.format("stats_streak", streak), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/** Android's "reading times": the same 24 hours grouped in the shared derivation, peak first. */
@Composable
private fun TimeDistributionCard(hourly: List<Long>) {
    val peak = hourly.indices.maxByOrNull { hourly[it] } ?: 0
    StatsSectionCard(Modifier.testTag("stats-hours")) {
        Text(AndroidStrings.format("stats_peak_period", hourLabel(peak)),
            style = MaterialTheme.typography.bodySmall, color = Muted)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.Bottom) {
            hourly.forEachIndexed { hour, duration ->
                Box(Modifier.weight(1f).testTag("stats-hour:$hour"), contentAlignment = Alignment.BottomCenter) {
                    val fraction = duration.toFloat() / (hourly.maxOrNull()?.coerceAtLeast(1L) ?: 1L)
                    Box(Modifier.fillMaxWidth().fillMaxSize(fraction.coerceAtLeast(.02f)).background(
                        MaterialTheme.colorScheme.secondary.copy(alpha = .7f), RoundedCornerShape(2.dp)))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(AndroidStrings["stats_time_midnight"], style = MaterialTheme.typography.labelSmall, color = Muted)
            Spacer(Modifier.weight(1f))
            Text(AndroidStrings["stats_time_noon"], style = MaterialTheme.typography.labelSmall, color = Muted)
            Spacer(Modifier.weight(1f))
            Text(AndroidStrings["stats_time_late_night"], style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

private fun hourLabel(hour: Int): String = AndroidStrings.format("stats_hour_range", hour, (hour + 1) % 24)

/** Android's type breakdown: a colour dot, the type, its share in time and the same share as a bar. */
@Composable
private fun KindBreakdownCard(kinds: List<Pair<StatsContentKind, Long>>) {
    val total = kinds.sumOf { it.second }.coerceAtLeast(1L)
    StatsSectionCard(Modifier.testTag("stats-kinds")) {
        kinds.forEachIndexed { index, (kind, duration) ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("stats-kind:${kind.name}")) {
                Box(Modifier.size(10.dp).background(kind.color(), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(kind.title(), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text("${formatStatsDuration(duration)} · ${duration * 100 / total}%",
                    style = MaterialTheme.typography.labelLarge, color = Muted)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(50))) {
                Box(Modifier.fillMaxWidth(duration.toFloat() / total).fillMaxSize().background(kind.color(),
                    RoundedCornerShape(50)))
            }
        }
    }
}

/** Android's "most enjoyed" row: rank, cover, title, the units that were read and how long it took. */
@Composable
private fun WorkStatsRow(enabled: Boolean, rank: Int, work: LibraryReadingStatsWork, cache: DesktopCovers?,
    onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
        .testTag("stats-work:${work.content.id}"), verticalAlignment = Alignment.CenterVertically) {
        Text(rank.toString(), style = MaterialTheme.typography.labelLarge, color = Muted, modifier = Modifier.width(24.dp))
        Box(Modifier.width(40.dp).height(56.dp)) {
            if (cache == null) {
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(6.dp)))
            } else {
                DesktopCover(work.content, cache, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(work.content.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold)
            Text(work.supportingText(), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Text(formatStatsDuration(work.duration), style = MaterialTheme.typography.labelLarge)
    }
}

/** Android's `StatsRecord.supportingText`: the units of the type in front of the session count. */
private fun LibraryReadingStatsWork.supportingText(): String {
    val sessions = AndroidStrings.format("stats_sessions_count", sessions)
    if (units <= 0) return sessions
    val unit = when (kind) {
        StatsContentKind.MANGA -> AndroidStrings.format("stats_pages_count", units)
        StatsContentKind.NOVEL -> AndroidStrings.format("stats_chapters_count", units)
        StatsContentKind.VIDEO -> AndroidStrings.format("stats_episodes_count", units)
        StatsContentKind.ALL -> ""
    }
    return if (unit.isEmpty()) sessions else "$unit · $sessions"
}

private fun StatsContentKind.title(): String = when (this) {
    StatsContentKind.ALL -> AndroidStrings["stats_kind_all"]
    StatsContentKind.MANGA -> AndroidStrings["stats_kind_manga"]
    StatsContentKind.NOVEL -> AndroidStrings["stats_kind_novel"]
    StatsContentKind.VIDEO -> AndroidStrings["stats_kind_video"]
}

@Composable
private fun StatsContentKind.color(): Color = when (this) {
    StatsContentKind.ALL, StatsContentKind.MANGA -> MaterialTheme.colorScheme.primary
    StatsContentKind.NOVEL -> MaterialTheme.colorScheme.tertiary
    StatsContentKind.VIDEO -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun StatsSectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

/** The one confirmation Android shows before clearing; the shared dialogs only cover settings pages. */
@Composable
private fun DesktopConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    cancelLabel: String,
    bodyTag: String,
    confirmTag: String,
    cancelTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 460.dp)
            .testTag(bodyTag)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = Muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onDismiss, modifier = Modifier.testTag(cancelTag)) { Text(cancelLabel) }
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Button(onConfirm, modifier = Modifier.testTag(confirmTag)) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}
