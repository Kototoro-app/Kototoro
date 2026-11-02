package org.skepsun.kototoro.stats.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.stats.StatsKindOption
import org.skepsun.kototoro.core.ui.stats.StatsKindSelector
import org.skepsun.kototoro.core.ui.compose.TvDropdownMenuItem
import org.skepsun.kototoro.core.util.KototoroColors
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.settings.compose.SettingsTopBarIconButton
import org.skepsun.kototoro.settings.compose.SettingsTopBarScaffold
import org.skepsun.kototoro.stats.domain.StatsContentKind
import org.skepsun.kototoro.stats.domain.StatsDailyActivity
import org.skepsun.kototoro.stats.domain.StatsDashboard
import org.skepsun.kototoro.core.ui.stats.StatsActivityCard
import org.skepsun.kototoro.core.ui.stats.StatsKindBreakdownCard
import org.skepsun.kototoro.core.ui.stats.StatsKindShare
import org.skepsun.kototoro.core.ui.stats.StatsSummaryCard
import org.skepsun.kototoro.core.ui.stats.StatsTimeDistributionCard
import org.skepsun.kototoro.core.ui.stats.statsDurationText
import org.skepsun.kototoro.stats.domain.StatsPeriod
import org.skepsun.kototoro.stats.domain.StatsRecord
import java.time.format.DateTimeFormatter

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
        // Back is the only escape key a remote has, and the platform default reached the activity
        // instead of the dialog: pressing it dropped the confirm dialog *and* the whole screen, so a
        // user who only wanted out of the question lost their place. Closing the question first also
        // matches what the same key does on a phone.
        BackHandler { showClearDialog = false }
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_stats)) },
            text = { Text(stringResource(R.string.clear_stats_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearStats()
                    },
                    // A bare TextButton in an AlertDialog has no outline, so on a television the two
                    // answers were indistinguishable apart from the dialog's own layout.
                    modifier = Modifier.tvFocusable(
                        shape = RoundedCornerShape(20.dp),
                        addFocusTarget = false,
                    ),
                ) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearDialog = false },
                    modifier = Modifier.tvFocusable(
                        shape = RoundedCornerShape(20.dp),
                        addFocusTarget = false,
                    ),
                ) {
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
                modifier = Modifier
                    .height(48.dp)
                    // The tonal container already reads as a pill, so the Material state layer of a
                    // focused button is invisible from a sofa; the ring is what tells the remote user
                    // they are standing on the period picker.
                    .tvFocusable(shape = RoundedCornerShape(percent = 50), addFocusTarget = false),
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
                    TvDropdownMenuItem(
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
    // Android's segmented pill is the shared one now (Windows draws the same control on its statistics page); this
    // host only adds its 40dp height and the remote's focus ring.
    StatsKindSelector(
        options = StatsContentKind.entries.map { StatsKindOption(it.name, it.label()) },
        selectedKey = selectedKind.name,
        onSelect = { key ->
            StatsContentKind.entries.firstOrNull { it.name == key }?.let(onKindSelected)
        },
        modifier = modifier,
        pillHeight = 40.dp,
        optionModifier = { _, selected ->
            Modifier.tvFocusable(
                shape = RoundedCornerShape(percent = 50),
                addFocusTarget = false,
                // The selected pill is filled with the ring colour, so it needs the on-container colour instead.
                ringColor = if (selected) MaterialTheme.colorScheme.onPrimary else Color.Unspecified,
            )
        },
    )
}

@Composable
private fun StatsDashboardContent(
    dashboard: StatsDashboard,
    period: StatsPeriod,
    selectedKind: StatsContentKind,
    onContentClick: (Content) -> Unit,
) {
    val listState = rememberLazyListState()
    val cardRequesters = remember { mutableStateMapOf<Any, FocusRequester>() }
    var bodyFocused by remember { mutableStateOf(false) }
    val focusTopWork: () -> Boolean = {
        val visibleCard = listState.layoutInfo.visibleItemsInfo.firstOrNull { cardRequesters.containsKey(it.key) }
        val requester = visibleCard?.let { cardRequesters[it.key] }
        requester != null && runCatching { requester.requestFocus() }.isSuccess
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { bodyFocused = it.isFocused }
            .tvScrollableDashboard(listState, bodyFocused, focusTopWork),
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
            StatsActivityCard(
                dayCount = dashboard.dailyActivity.size,
                streak = dashboard.currentStreak,
            ) {
                ActivityHeatmap(dashboard.dailyActivity)
            }
        }
        if (dashboard.hourlyActivity.any { it > 0L }) {
            item {
                StatsTimeDistributionCard(
                    hourlyActivity = dashboard.hourlyActivity,
                    periodLabel = stringResource(period.titleResId),
                )
            }
        }
        if (selectedKind == StatsContentKind.ALL && dashboard.kindSummaries.size > 1) {
            item {
                StatsKindBreakdownCard(
                    kinds = dashboard.kindSummaries.map { summary ->
                        StatsKindShare(
                            key = summary.kind.name,
                            label = summary.kind.label(),
                            color = summary.kind.color(),
                            duration = summary.duration,
                        )
                    },
                )
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
            key = { _, record -> record.workKey() },
        ) { index, record ->
            val workKey = record.workKey()
            val cardRequester = remember(workKey) { FocusRequester() }
            DisposableEffect(workKey) {
                cardRequesters[workKey] = cardRequester
                onDispose { cardRequesters.remove(workKey) }
            }
            WorkStatsCard(
                rank = index + 1,
                record = record,
                maxDuration = dashboard.records.firstOrNull()?.duration ?: 0L,
                onContentClick = onContentClick,
                focusRequester = cardRequester,
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
    // The plate is the shared one: both hosts draw Android's eyebrow, total and three counts from the same numbers.
    StatsSummaryCard(
        eyebrow = "${stringResource(period.titleResId)} · ${selectedKind.label()}",
        totalDuration = dashboard.totalDuration,
        activeDays = dashboard.activeDays,
        sessions = dashboard.sessionCount,
        works = dashboard.workCount,
    )
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
private fun WorkStatsCard(
    rank: Int,
    record: StatsRecord,
    maxDuration: Long,
    onContentClick: (Content) -> Unit,
    focusRequester: FocusRequester,
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
    val content = record.manga
    val cardModifier = if (content != null) {
        Modifier
            .fillMaxWidth()
            // The sheet surface is the only feedback a focused card would otherwise give, and that
            // tint is invisible from across a room; the ring marks the row the remote can open.
            .tvFocusable(shape = RoundedCornerShape(20.dp), addFocusTarget = false)
            .focusRequester(focusRequester)
            .clickable { onContentClick(content) }
    } else {
        Modifier.fillMaxWidth()
    }
    Surface(
        modifier = cardModifier,
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
                        text = statsDurationText(record.duration),
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

/**
 * Makes the dashboard drivable with a remote.
 *
 * Everything above the top-works leaderboard is read-only, so a television had nothing below the
 * filter row to move focus to: pressing down on a kind chip did nothing at all and the summary, the
 * activity heatmap, the hour split and the kind breakdown stayed behind the screen edge, reachable
 * only by touch. The list is therefore a focus target of its own, ringed, and up/down page it by most
 * of a viewport during the preview pass. At the top the key falls through so focus climbs back to the
 * filters; at the bottom it moves into the first visible work card instead, because the cards are the
 * only rows that do something and a reader who scrolled all the way down is usually after one.
 *
 * The interception stops as soon as a card owns the ring: `isBodyFocused` is false then, and the
 * card navigates the leaderboard like any other vertical list.
 */
@Composable
private fun Modifier.tvScrollableDashboard(
    state: LazyListState,
    isBodyFocused: Boolean,
    focusTopWork: () -> Boolean,
): Modifier {
    val scope = rememberCoroutineScope()
    return this
        .tvFocusable(shape = RoundedCornerShape(24.dp))
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || !isBodyFocused) {
                return@onPreviewKeyEvent false
            }
            // The viewport size is zero until the first layout pass; fall back to a fixed nudge so an
            // early press still moves the cards instead of doing nothing.
            val step = when (val viewport = state.layoutInfo.viewportSize.height) {
                0 -> STATS_SCROLL_MIN_STEP
                else -> viewport * STATS_SCROLL_FRACTION
            }
            when {
                event.key == Key.DirectionUp && state.canScrollBackward -> {
                    scope.launch { state.animateScrollBy(-step) }
                    true
                }

                event.key == Key.DirectionDown && state.canScrollForward -> {
                    scope.launch { state.animateScrollBy(step) }
                    true
                }

                event.key == Key.DirectionDown -> focusTopWork()

                else -> false
            }
        }
}

private const val STATS_SCROLL_FRACTION = 0.8f
private const val STATS_SCROLL_MIN_STEP = 480f

private fun StatsRecord.workKey(): Any = manga?.id ?: "other-$kind"
