package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.compose.ContentSourceResolutionProvider
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.parsers.model.ContentSource

/** One source's place in the library: how many works came from it, their share, and the places it gets in turn. */
data class SuggestionSourceUsageRow(
    /** The source by its name; its title and icon are resolved where it is drawn, as the browse pages do. */
    val source: ContentSource,
    val type: SourceType,
    val works: Int,
    val share: Float,
    val weight: Double,
)

/** What the library says about the sources; [equalShares] while it is too small to tell them apart. */
data class SuggestionSourceUsage(
    val rows: List<SuggestionSourceUsageRow> = emptyList(),
    val totalWorks: Int = 0,
    val sourceCount: Int = 0,
    val equalShares: Boolean = true,
)

/** How many sources get a slice of their own in the chart; the rest are one more. */
private const val CHART_SLICES = 5

@Composable
internal fun SuggestionSourceUsageDialog(usage: SuggestionSourceUsage, weighing: Boolean, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = listOf(
        colors.primary,
        colors.tertiary,
        colors.secondary,
        colors.primary.copy(alpha = 0.55f),
        colors.tertiary.copy(alpha = 0.55f),
    )
    val otherColor = colors.outline.copy(alpha = 0.55f)
    val showWeights = weighing && !usage.equalShares
    ContentSourceResolutionProvider {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = colors.surfaceContainerHigh,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(max = 680.dp),
        ) {
            Column {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 8.dp),
                ) {
                    item {
                        Text(
                            stringResource(R.string.suggestions_source_usage),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            stringResource(R.string.suggestions_source_usage_total, usage.totalWorks, usage.sourceCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (usage.rows.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.suggestions_source_usage_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    } else {
                        item {
                            UsageChart(
                                usage = usage,
                                palette = palette,
                                otherColor = otherColor,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            )
                        }
                        if (!showWeights) {
                            item {
                                Text(
                                    stringResource(
                                        if (weighing) R.string.suggestions_source_usage_equal else R.string.suggestions_source_usage_off,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                        }
                        val widest = usage.rows.maxOf { it.share }.coerceAtLeast(0.0001f)
                        items(usage.rows.withIndex().toList()) { (index, row) ->
                            UsageRow(
                                row = row,
                                color = palette.getOrNull(index)?.takeIf { index < CHART_SLICES } ?: otherColor,
                                relativeShare = row.share / widest,
                                showWeight = showWeights,
                            )
                        }
                        if (showWeights) {
                            item {
                                Text(
                                    stringResource(R.string.suggestions_source_usage_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
                }
            }
        }
    }
    }
}

/** A ring of the biggest sources by share, with the number of works at its centre. */
@Composable
private fun UsageChart(usage: SuggestionSourceUsage, palette: List<Color>, otherColor: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val total = usage.totalWorks.coerceAtLeast(1).toFloat()
    val named = usage.rows.take(CHART_SLICES)
    val slices = named.mapIndexed { index, row -> row.works / total to palette[index] } +
        listOfNotNull((1f - named.sumOf { (it.works / total).toDouble() }.toFloat()).takeIf { it > 0.001f }?.let { it to otherColor })
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(168.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(168.dp)) {
                val stroke = 22.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                // A small gap between slices, none when there is only one.
                val gap = if (slices.size > 1) 3f else 0f
                var start = -90f
                for ((fraction, color) in slices) {
                    val sweep = (fraction * 360f - gap).coerceAtLeast(0.5f)
                    drawArc(color, start + gap / 2, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += fraction * 360f
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(usage.totalWorks.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.suggestions_source_usage_unit),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsageRow(row: SuggestionSourceUsageRow, color: Color, relativeShare: Float, showWeight: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ContentSourceIcon(source = row.source, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rememberResolvedSourceTitle(row.source),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), color = colors.secondaryContainer) {
                        Text(
                            stringResource(row.type.titleResId),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.suggestions_source_usage_works, row.works),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${(row.share * 100).let { if (it < 1f && it > 0f) 1 else it.toInt() }}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
                if (showWeight) {
                    val container = when {
                        row.weight >= 3.0 -> colors.primaryContainer
                        row.weight >= 2.0 -> colors.secondaryContainer
                        else -> colors.surfaceVariant
                    }
                    Surface(shape = CircleShape, color = container, modifier = Modifier.padding(top = 3.dp)) {
                        Text(
                            "×" + if (row.weight % 1.0 == 0.0) row.weight.toInt().toString() else row.weight.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.surfaceVariant.copy(alpha = 0.6f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(relativeShare.coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}
