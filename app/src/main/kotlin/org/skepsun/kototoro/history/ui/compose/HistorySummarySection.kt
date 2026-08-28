package org.skepsun.kototoro.history.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.stats.domain.StatsDailyActivity
import org.skepsun.kototoro.stats.domain.StatsDashboard
import java.util.concurrent.TimeUnit

/**
 * A compact reading statistics summary card for the top of the history page.
 * Uses the most-read work's cover as a blurred image background when available,
 * falling back to a plain color card. Tapping opens the full statistics screen.
 */
@Composable
fun HistoryStatsSummaryCard(
    dashboard: StatsDashboard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgContent = remember(dashboard.records) {
        dashboard.records.mapNotNull { it.manga }.firstOrNull { it.coverUrl != null }
    }
    val imageUrl = bgContent?.coverUrl
    val hasImage = imageUrl != null
    val light = !hasImage
    val textColor = if (light) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        Color.White
    }
    val textMuted = if (light) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
    } else {
        Color.White.copy(alpha = 0.7f)
    }
    val trackColor = if (light) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)
    } else {
        Color.White.copy(alpha = 0.24f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = if (light) MaterialTheme.colorScheme.primaryContainer else Color(0xFF14161A),
    ) {
        Box {
            if (hasImage) {
                BlurredCoverBackground(content = bgContent, imageUrl = imageUrl)
            } else {
                DecorativeGlows()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                StatsCardHeader(
                    dashboard = dashboard,
                    textColor = textColor,
                    textMuted = textMuted,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = formatStatsDuration(dashboard.totalDuration),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = textColor,
                )
                Text(
                    text = stringResource(R.string.stats_total_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = textMuted,
                )
                if (dashboard.dailyActivity.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    CompactActivityBars(
                        activity = dashboard.dailyActivity,
                        trackColor = trackColor,
                        accentColor = textColor,
                    )
                }
                Spacer(Modifier.height(12.dp))
                StatsMetricsFooter(
                    activeDays = dashboard.activeDays,
                    sessions = dashboard.sessionCount,
                    works = dashboard.workCount,
                    textColor = textColor,
                    textMuted = textMuted,
                    imageMode = hasImage,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.BlurredCoverBackground(content: Content, imageUrl: String?) {
    val context = LocalContext.current
    val imageRequest = remember(context, content.source.name, content.url, content.publicUrl, imageUrl) {
        val cacheKey = contentCoverCacheKey(content, imageUrl.orEmpty())
        ImageRequest.Builder(context)
            .data(imageUrl)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .mangaExtra(content)
            .crossfade(false)
            .build()
    }
    if (imageUrl != null) {
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            modifier = Modifier
                .matchParentSize()
                .blur(radius = 22.dp),
            contentScale = ContentScale.Crop,
        )
    }
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.30f),
                        Color.Black.copy(alpha = 0.74f),
                    ),
                ),
            ),
    )
}

@Composable
private fun BoxScope.DecorativeGlows() {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = 72.dp, y = (-84).dp)
            .size(200.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
    )
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = (-32).dp, y = 30.dp)
            .size(70.dp)
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f), CircleShape),
    )
}

@Composable
private fun StatsCardHeader(
    dashboard: StatsDashboard,
    textColor: Color,
    textMuted: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_bar_chart),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = textMuted,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.statistics),
            style = MaterialTheme.typography.labelLarge,
            color = textMuted,
        )
        Spacer(Modifier.weight(1f))
        if (dashboard.currentStreak > 0) {
            StreakBadge(days = dashboard.currentStreak)
        }
    }
}

@Composable
private fun StreakBadge(days: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = stringResource(R.string.stats_streak, days),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1,
        )
    }
}

/**
 * A slim 7-day activity bar sparkline. Bar height/opacity scale with the
 * daily reading duration; the track stays visible for empty days.
 */
@Composable
private fun CompactActivityBars(
    activity: List<StatsDailyActivity>,
    trackColor: Color,
    accentColor: Color,
) {
    val maxDuration = activity.maxOfOrNull { it.duration }?.coerceAtLeast(1L) ?: 1L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        activity.forEach { day ->
            val intensity = day.duration.toFloat() / maxDuration.toFloat()
            val barHeight = if (day.duration == 0L) {
                3.dp
            } else {
                (4.dp + 18.dp * intensity).coerceAtMost(22.dp)
            }
            val barColor = when {
                day.duration == 0L -> trackColor.copy(alpha = 0.55f)
                intensity < 0.3f -> accentColor.copy(alpha = 0.4f)
                intensity < 0.6f -> accentColor.copy(alpha = 0.7f)
                else -> accentColor
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(barHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor),
            )
        }
    }
}

@Composable
private fun StatsMetricsFooter(
    activeDays: Int,
    sessions: Int,
    works: Int,
    textColor: Color,
    textMuted: Color,
    imageMode: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineMetric(
            value = activeDays.toString(),
            label = stringResource(R.string.stats_active_days),
            textColor = textColor,
            textMuted = textMuted,
        )
        MetricSeparator(textMuted)
        InlineMetric(
            value = sessions.toString(),
            label = stringResource(R.string.stats_sessions),
            textColor = textColor,
            textMuted = textMuted,
        )
        MetricSeparator(textMuted)
        InlineMetric(
            value = works.toString(),
            label = stringResource(R.string.stats_works),
            textColor = textColor,
            textMuted = textMuted,
        )
        Spacer(Modifier.weight(1f))
        Surface(
            shape = CircleShape,
            color = if (imageMode) {
                Color.White.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)
            },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_forward),
                contentDescription = null,
                modifier = Modifier
                    .padding(5.dp)
                    .size(13.dp),
                tint = textMuted,
            )
        }
    }
}

@Composable
private fun InlineMetric(
    value: String,
    label: String,
    textColor: Color,
    textMuted: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MetricSeparator(textMuted: Color) {
    Text(
        text = "·",
        style = MaterialTheme.typography.labelLarge,
        color = textMuted,
        modifier = Modifier.padding(horizontal = 7.dp),
    )
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
