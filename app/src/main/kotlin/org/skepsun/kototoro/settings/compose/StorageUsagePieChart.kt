package org.skepsun.kototoro.settings.compose

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.FileSize
import org.skepsun.kototoro.settings.userdata.storage.StorageUsage
import org.skepsun.kototoro.settings.userdata.storage.StorageUsageCategory
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.sqrt

data class StoragePieSlice(
    val category: StorageUsageCategory,
    val label: String,
    val bytes: Long,
    val fraction: Float,
    val color: Color,
)

data class StoragePieChartData(
    val slices: List<StoragePieSlice>,
    val totalAppBytes: Long,
    val availableBytes: Long,
)

internal fun categoryColor(category: StorageUsageCategory): Color = when (category) {
    StorageUsageCategory.LOCAL_MANGA -> Color(0xFF3F51B5) // Indigo
    StorageUsageCategory.LOCAL_NOVELS -> Color(0xFF009688) // Teal
    StorageUsageCategory.LOCAL_VIDEOS -> Color(0xFFE91E63) // Pink
    StorageUsageCategory.THUMBS_CACHE -> Color(0xFF2196F3) // Blue
    StorageUsageCategory.FAVICONS_CACHE -> Color(0xFF00BCD4) // Cyan
    StorageUsageCategory.PAGES_CACHE -> Color(0xFF4CAF50) // Green
    StorageUsageCategory.NOVELS_CACHE -> Color(0xFF8BC34A) // Light Green
    StorageUsageCategory.VIDEO_CACHE -> Color(0xFFFF5722) // Deep Orange
    StorageUsageCategory.VIDEO_PROXY_CACHE -> Color(0xFFFF9800) // Orange
    StorageUsageCategory.TORRENT_CACHE -> Color(0xFF795548) // Brown
    StorageUsageCategory.DANMAKU_CACHE -> Color(0xFF9C27B0) // Purple
    StorageUsageCategory.TTS_CACHE -> Color(0xFF673AB7) // Deep Purple
    StorageUsageCategory.SUPER_RESOLUTION_CACHE -> Color(0xFF03A9F4) // Light Blue
    StorageUsageCategory.HTTP_CACHE -> Color(0xFF607D8B) // Blue Grey
    StorageUsageCategory.AI_MODELS -> Color(0xFFAB47BC) // Medium Purple
    StorageUsageCategory.OTHER_CACHE -> Color(0xFF78909C) // Slate
    StorageUsageCategory.AVAILABLE -> Color(0xFFB0BEC5) // Neutral Grey
}

internal fun storageCategoryLabel(
    context: Context,
    category: StorageUsageCategory,
): String = when (category) {
    StorageUsageCategory.LOCAL_MANGA -> context.getString(R.string.local_manga_storage)
    StorageUsageCategory.LOCAL_NOVELS -> context.getString(R.string.local_novel_storage)
    StorageUsageCategory.LOCAL_VIDEOS -> context.getString(R.string.local_video_storage)
    StorageUsageCategory.THUMBS_CACHE -> context.getString(R.string.thumbnails_cache)
    StorageUsageCategory.FAVICONS_CACHE -> context.getString(R.string.favicons_cache)
    StorageUsageCategory.PAGES_CACHE -> context.getString(R.string.pages_cache)
    StorageUsageCategory.NOVELS_CACHE -> context.getString(R.string.novel_reader_cache)
    StorageUsageCategory.VIDEO_CACHE -> context.getString(R.string.video_playback_cache)
    StorageUsageCategory.VIDEO_PROXY_CACHE -> context.getString(R.string.video_proxy_cache)
    StorageUsageCategory.TORRENT_CACHE -> context.getString(R.string.torrent_cache)
    StorageUsageCategory.DANMAKU_CACHE -> context.getString(R.string.danmaku_cache)
    StorageUsageCategory.TTS_CACHE -> context.getString(R.string.tts_audio_cache)
    StorageUsageCategory.SUPER_RESOLUTION_CACHE -> context.getString(R.string.reader_super_resolution_cache)
    StorageUsageCategory.HTTP_CACHE -> context.getString(R.string.network_cache)
    StorageUsageCategory.AI_MODELS -> context.getString(R.string.ai_local_models)
    StorageUsageCategory.OTHER_CACHE -> context.getString(R.string.other_cache)
    StorageUsageCategory.AVAILABLE -> context.getString(R.string.available)
}

internal fun computeStoragePieChartData(
    storageUsage: StorageUsage?,
    context: Context,
): StoragePieChartData {
    if (storageUsage == null) {
        return StoragePieChartData(emptyList(), 0L, 0L)
    }

    val availableBytes = storageUsage.find(StorageUsageCategory.AVAILABLE)?.bytes ?: 0L

    val appItems = storageUsage.items
        .filter { it.category != StorageUsageCategory.AVAILABLE && it.bytes > 0L }
        .sortedByDescending { it.bytes }

    val totalAppBytes = appItems.sumOf { it.bytes }

    val slices = appItems.map { item ->
        val fraction = if (totalAppBytes > 0L) {
            (item.bytes.toDouble() / totalAppBytes).toFloat()
        } else {
            0f
        }
        StoragePieSlice(
            category = item.category,
            label = storageCategoryLabel(context, item.category),
            bytes = item.bytes,
            fraction = fraction,
            color = categoryColor(item.category),
        )
    }

    return StoragePieChartData(
        slices = slices,
        totalAppBytes = totalAppBytes,
        availableBytes = availableBytes,
    )
}

@Composable
fun StorageUsageChartSection(
    storageUsage: StorageUsage?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val chartData = remember(storageUsage, context) {
        computeStoragePieChartData(storageUsage, context)
    }

    var selectedCategory by remember { mutableStateOf<StorageUsageCategory?>(null) }

    // Clear selection if the selected category is no longer present
    LaunchedEffect(chartData.slices) {
        if (selectedCategory != null && chartData.slices.none { it.category == selectedCategory }) {
            selectedCategory = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Donut Chart
        StorageUsageDonutChart(
            chartData = chartData,
            selectedCategory = selectedCategory,
            onSelectCategory = { selectedCategory = it },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Companion Summary Bar: App Usage & Available Storage
        StorageUsageSummaryBar(
            totalAppBytes = chartData.totalAppBytes,
            availableBytes = chartData.availableBytes,
        )

        if (chartData.slices.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Legend & Breakdown
            StorageUsageLegend(
                slices = chartData.slices,
                selectedCategory = selectedCategory,
                onSelectCategory = { selectedCategory = it },
            )
        }
    }
}

@Composable
private fun StorageUsageDonutChart(
    chartData: StoragePieChartData,
    selectedCategory: StorageUsageCategory?,
    onSelectCategory: (StorageUsageCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val slices = chartData.slices
    val totalAppBytes = chartData.totalAppBytes

    val animationProgress = remember { Animatable(0f) }
    LaunchedEffect(slices) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        )
    }

    val selectedSlice = remember(slices, selectedCategory) {
        slices.firstOrNull { it.category == selectedCategory }
    }

    val chartDiameter = 200.dp
    val strokeWidth = 26.dp
    val gapDegrees = if (slices.size > 1) 2.5f else 0f

    val emptyColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier.size(chartDiameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(chartDiameter)
                .pointerInput(slices, selectedCategory) {
                    detectTapGestures { offset ->
                        if (slices.isEmpty()) return@detectTapGestures

                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val dx = offset.x - centerX
                        val dy = offset.y - centerY
                        val dist = sqrt(dx * dx + dy * dy)

                        val strokePx = strokeWidth.toPx()
                        val outerRadius = size.width / 2f
                        val innerRadius = outerRadius - strokePx

                        // Tap in the center cutout resets selection
                        if (dist < innerRadius) {
                            onSelectCategory(null)
                            return@detectTapGestures
                        }

                        // Outside the ring
                        if (dist > outerRadius + 12.dp.toPx()) {
                            return@detectTapGestures
                        }

                        // Determine angle: 0 degrees at 12 o'clock, proceeding clockwise
                        val rad = atan2(dy.toDouble(), dx.toDouble()).toFloat()
                        val degFromEast = Math.toDegrees(rad.toDouble()).toFloat()
                        var normalizedDeg = degFromEast + 90f
                        if (normalizedDeg < 0f) normalizedDeg += 360f

                        // Calculate slice angular ranges
                        val totalGap = slices.size * gapDegrees
                        val availableDegrees = 360f - totalGap
                        var curAngle = 0f
                        var clickedCategory: StorageUsageCategory? = null

                        for (slice in slices) {
                            val sweep = slice.fraction * availableDegrees
                            val sliceStart = curAngle
                            val sliceEnd = curAngle + sweep + gapDegrees
                            if (normalizedDeg in sliceStart..sliceEnd) {
                                clickedCategory = slice.category
                                break
                            }
                            curAngle += sweep + gapDegrees
                        }

                        if (clickedCategory != null) {
                            if (selectedCategory == clickedCategory) {
                                onSelectCategory(null)
                            } else {
                                onSelectCategory(clickedCategory)
                            }
                        }
                    }
                },
        ) {
            val strokePx = strokeWidth.toPx()
            val canvasSize = size.width
            val arcSize = Size(canvasSize - strokePx, canvasSize - strokePx)
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)

            if (slices.isEmpty() || totalAppBytes <= 0L) {
                // Empty state ring
                drawArc(
                    color = emptyColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                return@Canvas
            }

            val totalGap = slices.size * gapDegrees
            val availableDegrees = 360f - totalGap
            var startAngle = -90f // Start at top (12 o'clock)

            for (slice in slices) {
                val baseSweep = (slice.fraction * availableDegrees) * animationProgress.value
                val isSelected = selectedCategory == slice.category
                val isAnySelected = selectedCategory != null

                val sliceAlpha = when {
                    isSelected -> 1f
                    isAnySelected -> 0.45f
                    else -> 1f
                }

                val currentStroke = if (isSelected) {
                    strokePx + 5.dp.toPx()
                } else {
                    strokePx
                }

                val arcTopLeft = Offset(
                    (canvasSize - (canvasSize - currentStroke)) / 2f,
                    (canvasSize - (canvasSize - currentStroke)) / 2f,
                )
                val activeArcSize = Size(canvasSize - currentStroke, canvasSize - currentStroke)

                drawArc(
                    color = slice.color.copy(alpha = sliceAlpha),
                    startAngle = startAngle + (gapDegrees / 2f),
                    sweepAngle = baseSweep.coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = activeArcSize,
                    style = Stroke(
                        width = currentStroke,
                        cap = if (gapDegrees > 0f) StrokeCap.Round else StrokeCap.Butt,
                    ),
                )

                startAngle += (slice.fraction * availableDegrees) + gapDegrees
            }
        }

        // Center Info Billboard
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = strokeWidth + 6.dp),
        ) {
            if (selectedSlice != null) {
                Text(
                    text = FileSize.BYTES.format(context, selectedSlice.bytes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = selectedSlice.color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = selectedSlice.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = String.format(Locale.getDefault(), "%.1f%%", selectedSlice.fraction * 100f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = FileSize.BYTES.format(context, totalAppBytes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.storage_usage),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StorageUsageSummaryBar(
    totalAppBytes: Long,
    availableBytes: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.storage_usage),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = FileSize.BYTES.format(context, totalAppBytes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (availableBytes > 0L) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.available),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = FileSize.BYTES.format(context, availableBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun StorageUsageLegend(
    slices: List<StoragePieSlice>,
    selectedCategory: StorageUsageCategory?,
    onSelectCategory: (StorageUsageCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        slices.forEach { slice ->
            val isSelected = selectedCategory == slice.category
            Surface(
                onClick = {
                    onSelectCategory(if (isSelected) null else slice.category)
                },
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) {
                    slice.color.copy(alpha = 0.14f)
                } else {
                    Color.Transparent
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(slice.color, CircleShape),
                    )
                    Text(
                        text = slice.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = FileSize.BYTES.format(context, slice.bytes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%.1f%%", slice.fraction * 100f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = 44.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}
