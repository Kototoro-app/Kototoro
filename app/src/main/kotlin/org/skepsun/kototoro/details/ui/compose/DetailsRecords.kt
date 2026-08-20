package org.skepsun.kototoro.details.ui.compose


import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.rememberGlassSurfaceColors
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.readingrecord.data.ReadingChapterAggregateEntity
import org.skepsun.kototoro.readingrecord.data.ReadingJumpPointEntity
import org.skepsun.kototoro.readingrecord.data.ReadingRecordEntity
import org.skepsun.kototoro.readingrecord.data.ReadingRecordSnapshot
import org.skepsun.kototoro.stats.ui.sheet.ContentStatsViewModel
import org.skepsun.kototoro.stats.ui.sheet.compose.ContentStatsHistoryChart
import kotlin.math.roundToInt
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingRecordSheet(
    manga: Content,
    statsViewModel: ContentStatsViewModel?,
    snapshot: ReadingRecordSnapshot,
    chapterTitle: (Long) -> String,
    progressPercent: Float,
    onDismissRequest: () -> Unit,
    onJumpPointClick: (ReadingJumpPointEntity) -> Unit,
) {
    val sessions = snapshot.sessions
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val sheetColors = rememberGlassSurfaceColors(
        style = GlassDefaults.regularStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    val lastReadAt = snapshot.summary.lastReadAt ?: sessions.maxOfOrNull { it.endAt }
    val totalDuration = snapshot.summary.totalDuration.takeIf { it > 0L }
        ?: sessions.sumOf { (it.endAt - it.startAt).coerceAtLeast(0L) }
    val readingDays = snapshot.summary.readingDays.takeIf { it > 0 }
        ?: sessions.map { it.startAt / MILLIS_PER_DAY }.distinct().size
    val timelineItems = remember(sessions, snapshot.jumpPoints) {
        (sessions.map { ReadingTimelineItem.Session(it) } +
            snapshot.jumpPoints.map { ReadingTimelineItem.Jump(it) })
            .sortedByDescending { it.time }
            .take(30)
    }
    val progress = progressPercent.coerceIn(0f, 1f)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val configuration = LocalConfiguration.current
    val maxListHeight = remember(configuration.screenHeightDp) {
        (configuration.screenHeightDp.dp * 0.86f).coerceAtLeast(360.dp)
    }
    ModalBottomSheet(
        onDismissRequest = {
            onDismissRequest()
        },
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(),
            shape = RoundedCornerShape(topStart = if (expressive) 36.dp else 28.dp, topEnd = if (expressive) 36.dp else 28.dp),
            color = sheetColors.containerColor.detailsPanelContainerColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = sheetColors.border,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxListHeight)
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(
                    top = 20.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.reading_record),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item {
                    ReadingRecordSummaryCard(
                        totalDuration = totalDuration,
                        readingDays = readingDays,
                        lastReadAt = lastReadAt,
                        progress = progress,
                    )
                }
                if (statsViewModel != null) {
                    item {
                        Text(
                            text = stringResource(R.string.reading_stats),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    item {
                        ContentStatsHistoryChart(
                            manga = manga,
                            viewModel = statsViewModel,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (snapshot.chapters.isNotEmpty()) {
                    item {
                        ChapterStatisticsSummary(
                            chapters = snapshot.chapters.take(4),
                            chapterTitle = chapterTitle,
                        )
                    }
                }
                item {
                    RecordSectionHeader(
                        title = stringResource(R.string.timeline),
                        count = timelineItems.size,
                    )
                }
                if (timelineItems.isEmpty()) {
                    item { RecordEmptyLine(stringResource(R.string.no_reading_record)) }
                } else {
                    items(timelineItems, key = { it.key }) { item ->
                        when (item) {
                            is ReadingTimelineItem.Session -> TimelineSessionRow(item.session, chapterTitle)
                            is ReadingTimelineItem.Jump -> TimelineJumpRow(item.point, chapterTitle) { point ->
                                onJumpPointClick(point)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingRecordSummaryCard(
    totalDuration: Long,
    readingDays: Int,
    lastReadAt: Long?,
    progress: Float,
    ) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val summaryCardColors = rememberGlassSurfaceColors(
        style = GlassDefaults.subtleStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    Surface(
        shape = RoundedCornerShape(if (expressive) 28.dp else 22.dp),
        color = summaryCardColors.containerColor.detailsPanelContainerColor(),
        border = summaryCardColors.border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SummaryMetric(
                    label = stringResource(R.string.total_reading_time),
                    value = formatDuration(totalDuration),
                    modifier = Modifier.weight(1.2f),
                )
                SummaryMetric(
                    label = stringResource(R.string.reading_days),
                    value = readingDays.toString(),
                    modifier = Modifier.weight(0.8f),
                )
                SummaryMetric(
                    label = stringResource(R.string.current_progress),
                    value = "${(progress * 100f).roundToInt()}%",
                    modifier = Modifier.weight(0.8f),
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp)),
            )
            Text(
                text = "${stringResource(R.string.recent_reading)}: ${lastReadAt?.let(::formatDateTime) ?: "-"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private sealed interface ReadingTimelineItem {
    val time: Long
    val key: String

    data class Session(val session: ReadingRecordEntity) : ReadingTimelineItem {
        override val time: Long = session.endAt
        override val key: String = "session_${session.id}"
    }

    data class Jump(val point: ReadingJumpPointEntity) : ReadingTimelineItem {
        override val time: Long = point.createdAt
        override val key: String = "jump_${point.id}"
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChapterStatisticsSummary(
    chapters: List<ReadingChapterAggregateEntity>,
    chapterTitle: (Long) -> String,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val summaryCardColors = rememberGlassSurfaceColors(
        style = GlassDefaults.subtleStyle(),
        glassPrefs = rememberDetailsSheetGlassPrefs(),
    )
    Surface(
        shape = RoundedCornerShape(if (expressive) 28.dp else 22.dp),
        color = summaryCardColors.containerColor.detailsPanelContainerColor(),
        border = summaryCardColors.border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.chapter_statistics),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            chapters.forEach { chapter ->
                RecordCompactRow(
                    title = chapterTitle(chapter.chapterId),
                    body = stringResource(
                        R.string.reading_chapter_record_format,
                        chapter.sessionsCount,
                        formatDuration(chapter.duration),
                    ),
                    trailing = formatDateTime(chapter.lastReadAt),
                    modifier = Modifier,
                )
            }
        }
    }
}

@Composable
private fun RecordSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RecordEmptyLine(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun TimelineSessionRow(
    session: ReadingRecordEntity,
    chapterTitle: (Long) -> String,
) {
    val lineColor = MaterialTheme.colorScheme.surfaceVariant
    val nodeColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 10.dp.toPx()
                val centerY = size.height / 2f
                drawLine(
                    color = lineColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
                drawCircle(
                    color = nodeColor,
                    radius = 4.dp.toPx(),
                    center = Offset(x, centerY),
                )
            }
            .padding(start = 28.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTime(session.endAt),
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(chapterTitle(session.endChapterId), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = formatDuration(session.endAt - session.startAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = "${(session.endPercent.coerceIn(0f, 1f) * 100f).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TimelineJumpRow(
    point: ReadingJumpPointEntity,
    chapterTitle: (Long) -> String,
    onJumpPointClick: (ReadingJumpPointEntity) -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.surfaceVariant
    val nodeColor = MaterialTheme.colorScheme.secondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 10.dp.toPx()
                val centerY = size.height / 2f
                drawLine(
                    color = lineColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
                drawCircle(
                    color = nodeColor,
                    radius = 4.dp.toPx(),
                    center = Offset(x, centerY),
                )
            }
            .clip(MaterialTheme.shapes.small)
            .clickable { onJumpPointClick(point) }
            .padding(start = 28.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTime(point.createdAt),
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${chapterTitle(point.fromChapterId)} P${point.fromPage + 1} -> ${chapterTitle(point.toChapterId)} P${point.toPage + 1}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.restore_jump_point),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun RecordCompactRow(
    title: String,
    body: String,
    trailing: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.width(92.dp),
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs.coerceAtLeast(0L))
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours > 0 -> "${hours}h ${remainingMinutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}

private fun formatDateTime(timestamp: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
}

private fun formatTime(timestamp: Long): String {
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
}

private const val MILLIS_PER_DAY = 86_400_000L

