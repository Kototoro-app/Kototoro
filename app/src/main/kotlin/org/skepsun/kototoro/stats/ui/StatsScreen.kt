package org.skepsun.kototoro.stats.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.KototoroColors
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.settings.compose.SettingsTopBarIconButton
import org.skepsun.kototoro.settings.compose.SettingsTopBarScaffold
import org.skepsun.kototoro.stats.domain.StatsContentKind
import org.skepsun.kototoro.stats.domain.StatsDailyActivity
import org.skepsun.kototoro.stats.domain.StatsDashboard
import org.skepsun.kototoro.stats.domain.StatsKindSummary
import org.skepsun.kototoro.stats.domain.StatsPeriod
import org.skepsun.kototoro.stats.domain.StatsRecord
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    period: StatsPeriod,
    selectedKind: StatsContentKind,
    dashboard: StatsDashboard,
    isLoading: Boolean,
    onNavigateUp: () -> Unit,
    onPeriodSelected: (StatsPeriod) -> Unit,
    onKindSelected: (StatsContentKind) -> Unit,
    onClearStats: () -> Unit,
    onContentClick: (Content) -> Unit,
) {
    var periodMenuExpanded by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    SettingsTopBarScaffold(
        title = stringResource(R.string.reading_stats),
        onNavigateUp = onNavigateUp,
        actions = {
            SettingsTopBarIconButton(onClick = { showClearDialog = true }) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.clear_stats),
                    modifier = Modifier.size(LocalInterfaceStyleTokens.current.topBarIconSize),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            StatsFilters(
                period = period,
                periodMenuExpanded = periodMenuExpanded,
                onPeriodMenuExpandedChange = { periodMenuExpanded = it },
                selectedKind = selectedKind,
                onPeriodSelected = onPeriodSelected,
                onKindSelected = onKindSelected,
            )
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!isLoading && dashboard.records.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.empty_stats_text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                StatsDashboardContent(
                    dashboard = dashboard,
                    period = period,
                    selectedKind = selectedKind,
                    onContentClick = onContentClick,
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_stats)) },
            text = { Text(stringResource(R.string.clear_stats_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    onClearStats()
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun StatsFilters(
    period: StatsPeriod,
    periodMenuExpanded: Boolean,
    onPeriodMenuExpandedChange: (Boolean) -> Unit,
    selectedKind: StatsContentKind,
    onPeriodSelected: (StatsPeriod) -> Unit,
    onKindSelected: (StatsContentKind) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FilledTonalButton(
                onClick = { onPeriodMenuExpandedChange(true) },
                modifier = Modifier.height(48.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp,
                    end = 10.dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DateRange,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(stringResource(period.titleResId))
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = periodMenuExpanded,
                onDismissRequest = { onPeriodMenuExpandedChange(false) },
            ) {
                StatsPeriod.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.titleResId)) },
                        onClick = {
                            onPeriodMenuExpandedChange(false)
                            onPeriodSelected(option)
                        },
                    )
                }
            }
        }
        ContentKindSelector(
            selectedKind = selectedKind,
            onKindSelected = onKindSelected,
            modifier = Modifier.height(48.dp),
        )
    }
}

@Composable
private fun ContentKindSelector(
    selectedKind: StatsContentKind,
    onKindSelected: (StatsContentKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatsContentKind.entries.forEach { kind ->
                val selected = selectedKind == kind
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .height(40.dp)
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onKindSelected(kind) }
                        .padding(horizontal = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = kind.label(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsDashboardContent(
    dashboard: StatsDashboard,
    period: StatsPeriod,
    selectedKind: StatsContentKind,
    onContentClick: (Content) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SummaryCard(dashboard = dashboard, period = period, selectedKind = selectedKind)
        }
        item {
            ActivityCard(
                activity = dashboard.dailyActivity,
                streak = dashboard.currentStreak,
            )
        }
        if (dashboard.hourlyActivity.any { it > 0L }) {
            item {
                TimeDistributionCard(
                    hourlyActivity = dashboard.hourlyActivity,
                    period = period,
                )
            }
        }
        if (selectedKind == StatsContentKind.ALL && dashboard.kindSummaries.size > 1) {
            item {
                KindBreakdownCard(dashboard.kindSummaries)
            }
        }
        item {
            Text(
                text = stringResource(R.string.stats_top_works),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        itemsIndexed(
            items = dashboard.records,
            key = { _, record -> record.manga?.id ?: "other-${record.kind}" },
        ) { index, record ->
            WorkStatsCard(
                rank = index + 1,
                record = record,
                maxDuration = dashboard.records.firstOrNull()?.duration ?: 0L,
                onContentClick = onContentClick,
            )
        }
    }
}

@Composable
private fun SummaryCard(
    dashboard: StatsDashboard,
    period: StatsPeriod,
    selectedKind: StatsContentKind,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "${stringResource(period.titleResId)} · ${selectedKind.label()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Text(
                    text = formatStatsDuration(dashboard.totalDuration),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.stats_total_time),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryMetric(
                    value = dashboard.activeDays.toString(),
                    label = stringResource(R.string.stats_active_days),
                    modifier = Modifier.weight(1f),
                )
                SummaryDivider()
                SummaryMetric(
                    value = dashboard.sessionCount.toString(),
                    label = stringResource(R.string.stats_sessions),
                    modifier = Modifier.weight(1f),
                )
                SummaryDivider()
                SummaryMetric(
                    value = dashboard.workCount.toString(),
                    label = stringResource(R.string.stats_works),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SummaryDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(34.dp)
            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f)),
    )
}

@Composable
private fun SummaryMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun ActivityCard(activity: List<StatsDailyActivity>, streak: Int) {
    StatsSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    text = stringResource(R.string.stats_activity),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.stats_activity_days, activity.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            if (streak > 0) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.stats_streak, streak),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        ActivityHeatmap(activity)
    }
}

@Composable
private fun ActivityHeatmap(activity: List<StatsDailyActivity>) {
    if (activity.isEmpty()) return
    val maxDuration = activity.maxOfOrNull { it.duration }?.coerceAtLeast(1L) ?: 1L
    if (activity.size <= 7) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (activity.size == 1) {
                Arrangement.Center
            } else {
                Arrangement.SpaceBetween
            },
        ) {
            activity.forEach { day ->
                Column(
                    modifier = Modifier.width(38.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(activityColor(day.duration, maxDuration), RoundedCornerShape(8.dp)),
                    )
                    Text(
                        text = day.date.format(DateTimeFormatter.ofPattern("E")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }
    val weeks = activity.chunked(7)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        weeks.forEach { week ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                week.forEach { day ->
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(activityColor(day.duration, maxDuration), RoundedCornerShape(5.dp)),
                    )
                }
            }
        }
    }
    val first = activity.first().date.format(DateTimeFormatter.ofPattern("MM/dd"))
    val last = activity.last().date.format(DateTimeFormatter.ofPattern("MM/dd"))
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(last, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimeDistributionCard(
    hourlyActivity: List<Long>,
    period: StatsPeriod,
) {
    val buckets = remember(hourlyActivity) {
        hourlyActivity.chunked(3).map { it.sum() }
    }
    val peakIndex = buckets.indices.maxByOrNull { buckets[it] } ?: 0
    val maxDuration = buckets.maxOrNull()?.coerceAtLeast(1L) ?: 1L

    StatsSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.stats_time_distribution),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        R.string.stats_time_distribution_subtitle,
                        stringResource(period.titleResId),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = stringResource(
                        R.string.stats_peak_period,
                        formatHourRange(peakIndex * 3, peakIndex * 3 + 3),
                    ),
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(126.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            buckets.forEachIndexed { index, duration ->
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .fillMaxHeight(
                                    if (duration == 0L) 0.04f
                                    else (duration.toFloat() / maxDuration.toFloat()).coerceAtLeast(0.12f),
                                )
                                .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp))
                                .background(
                                    if (duration == 0L) MaterialTheme.colorScheme.surfaceContainerHighest
                                    else MaterialTheme.colorScheme.primary.copy(
                                        alpha = 0.35f + 0.65f * duration.toFloat() / maxDuration.toFloat(),
                                    ),
                                ),
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = if (index % 2 == 0) "%02d".format(index * 3) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(
                text = stringResource(R.string.stats_time_midnight),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.stats_time_noon),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.stats_time_late_night),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun formatHourRange(startHour: Int, endHour: Int): String = stringResource(
    R.string.stats_hour_range,
    startHour,
    endHour,
)

@Composable
private fun activityColor(duration: Long, maxDuration: Long): Color {
    val intensity = duration.toFloat() / maxDuration.toFloat()
    return when {
        duration == 0L -> MaterialTheme.colorScheme.surfaceContainerHighest
        intensity < 0.25f -> MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
        intensity < 0.55f -> MaterialTheme.colorScheme.primary.copy(alpha = 0.52f)
        intensity < 0.8f -> MaterialTheme.colorScheme.primary.copy(alpha = 0.76f)
        else -> MaterialTheme.colorScheme.primary
    }
}

@Composable
private fun KindBreakdownCard(summaries: List<StatsKindSummary>) {
    val total = summaries.sumOf { it.duration }.coerceAtLeast(1L)
    StatsSectionCard {
        Text(
            text = stringResource(R.string.stats_content_breakdown),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(14.dp))
        summaries.forEachIndexed { index, summary ->
            if (index > 0) Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(summary.kind.color(), CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(summary.kind.label(), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${formatStatsDuration(summary.duration)} · ${summary.duration * 100 / total}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatsProgressBar(
                progress = summary.duration.toFloat() / total.toFloat(),
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp).height(7.dp),
                color = summary.kind.color(),
            )
        }
    }
}

@Composable
private fun WorkStatsCard(
    rank: Int,
    record: StatsRecord,
    maxDuration: Long,
    onContentClick: (Content) -> Unit,
) {
    val context = LocalContext.current
    val contentColor = Color(KototoroColors.ofContent(context, record.manga))
    val coverRequest = remember(record.manga?.id, record.manga?.coverUrl) {
        record.manga?.let { content ->
            ImageRequest.Builder(context)
                .data(content.coverUrl)
                .apply { mangaExtra(content) }
                .build()
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(record.manga?.let { content -> Modifier.clickable { onContentClick(content) } } ?: Modifier),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .height(70.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(contentColor.copy(alpha = 0.14f)),
            ) {
                if (coverRequest != null) {
                    AsyncImage(
                        model = coverRequest,
                        contentDescription = record.manga?.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Surface(
                    modifier = Modifier.padding(5.dp).size(23.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    tonalElevation = 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = rank.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = record.manga?.title ?: stringResource(R.string.other_manga),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = formatStatsDuration(record.duration),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                StatsProgressBar(
                    progress = if (maxDuration <= 0L) 0f else record.duration.toFloat() / maxDuration.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = contentColor,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatsKindBadge(record.kind)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = record.supportingText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsKindBadge(kind: StatsContentKind) {
    Surface(
        shape = RoundedCornerShape(50),
        color = kind.color().copy(alpha = 0.12f),
    ) {
        Text(
            text = kind.label(),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsProgressBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(48.dp)
                .background(color),
        )
    }
}

@Composable
private fun StatsSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp), content = content)
    }
}

@Composable
private fun StatsRecord.supportingText(): String {
    val sessionsText = stringResource(R.string.stats_sessions_count, sessions)
    if (units <= 0) return sessionsText
    val unitText = when (kind) {
        StatsContentKind.MANGA -> stringResource(R.string.stats_pages_count, units)
        StatsContentKind.NOVEL -> stringResource(R.string.stats_chapters_count, units)
        StatsContentKind.VIDEO -> stringResource(R.string.stats_episodes_count, units)
        StatsContentKind.ALL -> ""
    }
    return "$unitText · $sessionsText"
}

@Composable
private fun StatsContentKind.label(): String = when (this) {
    StatsContentKind.ALL -> stringResource(R.string.stats_kind_all)
    StatsContentKind.MANGA -> stringResource(R.string.stats_kind_manga)
    StatsContentKind.NOVEL -> stringResource(R.string.stats_kind_novel)
    StatsContentKind.VIDEO -> stringResource(R.string.stats_kind_video)
}

@Composable
private fun StatsContentKind.color(): Color = when (this) {
    StatsContentKind.ALL -> MaterialTheme.colorScheme.primary
    StatsContentKind.MANGA -> MaterialTheme.colorScheme.primary
    StatsContentKind.NOVEL -> MaterialTheme.colorScheme.tertiary
    StatsContentKind.VIDEO -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun formatStatsDuration(durationMs: Long): String {
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(durationMs).coerceAtLeast(0L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0L && minutes > 0L -> stringResource(R.string.stats_duration_hours_minutes, hours, minutes)
        hours > 0L -> stringResource(R.string.stats_duration_hours, hours)
        else -> stringResource(R.string.stats_duration_minutes, minutes)
    }
}
