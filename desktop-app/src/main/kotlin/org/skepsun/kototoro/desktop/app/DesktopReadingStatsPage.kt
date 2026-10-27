package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.library.LibraryReadingStats
import org.skepsun.kototoro.core.library.LibraryReadingStatsWork
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.stats.domain.ReadingDayStats
import org.skepsun.kototoro.stats.domain.ReadingStatsPeriod
import org.skepsun.kototoro.core.ui.stats.StatsActivityCard
import org.skepsun.kototoro.core.ui.stats.StatsCard
import org.skepsun.kototoro.core.ui.stats.StatsKindOption
import org.skepsun.kototoro.core.ui.stats.StatsKindSelector
import org.skepsun.kototoro.core.ui.stats.StatsSummaryCard
import org.skepsun.kototoro.core.ui.stats.StatsKindBreakdownCard
import org.skepsun.kototoro.core.ui.stats.StatsKindShare
import org.skepsun.kototoro.core.ui.stats.StatsTimeDistributionCard
import org.skepsun.kototoro.core.ui.stats.statsDurationText
import org.skepsun.kototoro.stats.domain.StatsContentKind

/**
 * Android's "阅读统计" screen. The statistics themselves were already there — the desktop backup and the WebDAV sync
 * read and merge the same `stats` and reading-session rows — but nothing on this host ever wrote them, and nothing
 * showed them. The reader now records every stop (see [DesktopController.beginReadingStop]) and this page puts the
 * shared derivation behind Android's period switch: the same total, streak, per-day activity, type breakdown and
 * "most enjoyed" list, with the works opening their own statistics sheet (see [DesktopContentStatsDialog]) the way
 * Android's list does. Android draws its summary as the first card without a section title, so this page does the same
 * under the screen title.
 */
@Composable
internal fun DesktopReadingStatsPage(controller: DesktopController, state: DesktopAppState) {
    DesktopReadingStatsContent(
        stats = state.readingStats,
        period = state.readingStatsPeriod,
        kind = state.readingStatsKind,
        active = !state.busy,
        covers = controller.session.covers,
        onBack = controller::showServices,
        onPeriod = controller::readingStatsPeriod,
        onKind = controller::readingStatsKind,
        onWork = controller::showContentStats,
        onClear = controller::clearReadingStats,
    )
    // Android opens the per-work sheet over the statistics screen; its "details" button is what leaves the screen.
    state.contentStats?.let { sheet ->
        DesktopContentStatsDialog(
            sheet = sheet,
            onDismiss = controller::closeContentStats,
            onOpenDetails = { content ->
                controller.closeContentStats()
                controller.details(content)
            },
        )
    }
}

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
        // Android's summary plate: the period and kind as an eyebrow, the total large, the three counts under it. The
        // streak is no longer a row of its own — Android shows it as the pill on the activity card below.
        StatsSummaryCard(
            eyebrow = "${period.title()} · ${kind.title()}",
            totalDuration = stats.totalDuration,
            activeDays = stats.activeDays,
            sessions = stats.sessionCount,
            works = stats.works.size,
            modifier = Modifier.testTag("stats-summary"),
        )
        // The cards below are the shared ones Android draws; only the day plot stays with this host.
        StatsActivityCard(
            dayCount = stats.dailyActivity.size,
            streak = stats.currentStreak,
            modifier = Modifier.testTag("stats-activity"),
        ) {
            ActivityBars(stats.dailyActivity)
        }
        if (stats.hourlyActivity.any { it > 0L }) {
            StatsTimeDistributionCard(
                hourlyActivity = stats.hourlyActivity,
                periodLabel = period.title(),
                modifier = Modifier.testTag("stats-hours"),
            )
        }
        val kinds = stats.works.groupBy { it.kind }.map { (kind, works) -> kind to works.sumOf { it.duration } }
            .sortedByDescending { it.second }
        if (kinds.size > 1) {
            StatsKindBreakdownCard(
                kinds = kinds.map { (kind, duration) ->
                    StatsKindShare(kind.name, kind.title(), kind.color(), duration)
                },
                modifier = Modifier.testTag("stats-kinds"),
            )
        }
        StatsCard(Modifier.testTag("stats-works-list")) {
            Text(AndroidStrings["stats_top_works"], style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
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
 * Android's `ContentKindSelector`, now the shared segmented pill: Windows runs no separate manga/novel/video surfaces,
 * so this filter and the type breakdown are where the distinction shows. The host keeps the tags its probe looks up
 * and disables the row while an action is running.
 */
@Composable
private fun KindSelector(selected: StatsContentKind, active: Boolean, onKind: (StatsContentKind) -> Unit) {
    StatsKindSelector(
        options = StatsContentKind.entries.map { StatsKindOption(it.name, it.title()) },
        selectedKey = selected.name,
        onSelect = { key -> StatsContentKind.entries.firstOrNull { it.name == key }?.let(onKind) },
        modifier = Modifier.testTag("stats-kind-filter"),
        enabled = active,
        optionModifier = { option, _ -> Modifier.testTag("stats-kind-option:${option.key}") },
    )
}

/** Android's `formatStatsDuration`, now the shared renderer so both hosts round the same way. */
internal fun formatStatsDuration(durationMs: Long): String = statsDurationText(durationMs, DesktopUiStrings)

/**
 * The day plot: one column per visible day, height relative to the busiest day. Android draws a heat of squares with
 * weekday initials over the same list; the shared card around this plot carries Android's title, day count and streak
 * pill, so only the plot itself differs.
 */
@Composable
private fun ActivityBars(activity: List<ReadingDayStats>) {
    val max = activity.maxOfOrNull { it.duration }?.coerceAtLeast(1L) ?: 1L
    Row(
        Modifier.fillMaxWidth().height(96.dp).testTag("stats-activity-bars"),
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom,
    ) {
        activity.forEach { day ->
            // A three-day window on a wide card would otherwise draw three slabs; the strip keeps Android's square-ish
            // proportions and stays centred.
            Box(Modifier.weight(1f, fill = false).widthIn(max = 44.dp).fillMaxHeight()
                .testTag("stats-day:${day.date}"), contentAlignment = Alignment.BottomCenter) {
                val fraction = if (day.duration <= 0L) 0f else (day.duration.toFloat() / max)
                Box(Modifier.fillMaxWidth().fillMaxSize(fraction.coerceAtLeast(.04f)).background(
                    if (day.duration > 0L) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(3.dp),
                ))
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
