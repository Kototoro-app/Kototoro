package org.skepsun.kototoro.stats.ui.sheet.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.model.DateTimeAgo
import org.skepsun.kototoro.core.util.KototoroColors
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.format
import org.skepsun.kototoro.stats.ui.sheet.ContentStatsViewModel
import androidx.collection.IntList
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

private fun IntList.toList(): List<Int> = buildList { this@toList.forEach { add(it) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentStatsRoute(
    manga: Content,
    onOpenDetails: () -> Unit,
    onDismissRequest: () -> Unit,
    viewModel: ContentStatsViewModel = hiltViewModel(key = "content-stats-${manga.id}"),
) {
    LaunchedEffect(manga) {
        viewModel.initialize(manga)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(0.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        KototoroSheetSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SheetDragHandle(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Text(
                    text = stringResource(R.string.reading_stats),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                ContentStatsSheetContent(
                    manga = manga,
                    viewModel = viewModel,
                    onOpenDetails = onOpenDetails,
                    modifier = Modifier,
                )
            }
        }
    }
}

@Composable
fun ContentStatsDialog(
    viewModel: ContentStatsViewModel,
    onDismissRequest: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val manga = viewModel.manga
    val context = LocalContext.current
    val stats by viewModel.stats.collectAsState()
    val startDate by viewModel.startDate.collectAsState()
    val totalPagesRead by viewModel.totalPagesRead.collectAsState()
    val barColor = remember(manga) {
        Color(KototoroColors.ofContent(context, manga))
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                )
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(28.dp),
                style = GlassDefaults.prominentStyle(),
                dialogSurface = true,
                componentRole = GlassComponentRole.Dialog,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = manga.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ContentStatsBody(
                        startDate = startDate,
                        totalPagesRead = totalPagesRead,
                        stats = stats.toList(),
                        barColor = barColor,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissRequest) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        Button(onClick = onOpenDetails) {
                            Text(stringResource(R.string.details))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContentStatsSheetContent(
    manga: Content,
    viewModel: ContentStatsViewModel,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val stats by viewModel.stats.collectAsState()
    val startDate by viewModel.startDate.collectAsState()
    val totalPagesRead by viewModel.totalPagesRead.collectAsState()
    val barColor = remember(manga) {
        Color(KototoroColors.ofContent(context, manga))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = manga.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenDetails) {
                Icon(
                    painter = painterResource(R.drawable.ic_open_external),
                    contentDescription = stringResource(R.string.details),
                )
            }
        }

        ContentStatsBody(
            startDate = startDate,
            totalPagesRead = totalPagesRead,
            stats = stats.toList(),
            barColor = barColor,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ContentStatsBody(
    startDate: DateTimeAgo?,
    totalPagesRead: Int,
    stats: List<Int>,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassSurface(
            shape = RoundedCornerShape(16.dp),
            style = GlassDefaults.subtleStyle(),
        ) {
            ContentStatsBarChart(
                stats = stats,
                barColor = barColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        startDate?.let {
            Text(
                text = it.format(context),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(R.string.pages_read_s, totalPagesRead.format()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ContentStatsBarChart(
    stats: List<Int>,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    if (stats.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.empty_stats_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val minBarSpacingDp = 12.dp
    val barWidthDp = 12.dp
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val dashedPathEffect = remember {
        PathEffect.dashPathEffect(floatArrayOf(15f, 15f), 0f)
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val minBarSpacing = minBarSpacingDp.toPx()
        val barWidth = barWidthDp.toPx()
        val maxRawWidth = stats.size * (barWidth + minBarSpacing) + minBarSpacing
        val windowSize = kotlin.math.ceil(maxRawWidth / width).toInt().coerceAtLeast(1)
        val compressedBars = stats.chunked(windowSize) { chunk ->
            chunk.average().toInt()
        }
        val maxValue = compressedBars.maxOrNull()?.toFloat() ?: 0f

        if (maxValue <= 0f) {
            return@Canvas
        }

        val step = computeValueStep(height, maxValue)
        for (value in 0..maxValue.toInt() step step) {
            val y = height - (height * value / maxValue)
            drawLine(
                color = outlineColor,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dashedPathEffect,
            )
        }

        drawLine(
            color = outlineColor,
            start = Offset(0f, height),
            end = Offset(width, height),
            strokeWidth = 2.dp.toPx(),
        )

        val spacing = (width - (barWidth * compressedBars.size)) / (compressedBars.size + 1)
        val cornerRadius = barWidth / 2f

        compressedBars.forEachIndexed { index, value ->
            if (value <= 0) {
                return@forEachIndexed
            }
            val barHeight = (height * value / maxValue).coerceAtLeast(barWidth)
            val x = spacing + index * (barWidth + spacing)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(cornerRadius, cornerRadius),
            )
        }
    }
}

private fun computeValueStep(heightPx: Float, maxValue: Float): Int {
    val minSpacePx = 60f
    var step = 1
    while (heightPx / (maxValue / step) <= minSpacePx) {
        step++
    }
    return step
}
