package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The Android cover badge pill, reading-progress bar/ring and rim brush. Hosts decide which badges
 * a card shows and supply icons; the colours, sizes and draw rules live here for both platforms.
 */

@Immutable
data class ContentCardBadgeMetrics(
    val containerHorizontalPadding: Dp = 7.dp,
    val containerVerticalPadding: Dp = 4.dp,
    val itemSpacing: Dp = 4.dp,
    val iconSize: Dp = 14.dp,
    val textSize: TextUnit = 11.sp,
    val outerPadding: Dp = 7.dp,
    val badgeEdgePadding: Dp = 0.dp,
    val progressSize: Dp = 26.dp,
    val progressAnchorInset: Dp = 8.dp,
    val progressSpacing: Dp = 4.dp,
    val innerCornerRadius: Dp = 10.dp,
)

fun contentCardBadgeMetricsFor(coverWidth: Dp): ContentCardBadgeMetrics {
    val scale = (coverWidth.value / 112f).coerceIn(0.66f, 1.15f)
    val isSmallCard = coverWidth < 80.dp
    return ContentCardBadgeMetrics(
        containerHorizontalPadding = 7.dp * scale,
        containerVerticalPadding = 4.dp * scale,
        itemSpacing = 4.dp * scale,
        iconSize = 14.dp * scale,
        textSize = 11.sp * scale,
        outerPadding = 7.dp * scale,
        badgeEdgePadding = 0.dp,
        progressSize = if (isSmallCard) 24.dp else 26.dp,
        progressAnchorInset = 8.dp * scale,
        progressSpacing = 4.dp * scale,
        innerCornerRadius = 10.dp * scale,
    )
}

/** A pill holding only the unread counter or only the NSFW mark gets its own accent; anything else is neutral. */
enum class ContentCardBadgeTone { NEUTRAL, COUNTER, NSFW }

@Immutable
data class ContentCardBadgeColors(
    val container: Color,
    val content: Color,
    val border: Color?,
)

@Composable
fun contentCardBadgeColors(tone: ContentCardBadgeTone, isIosStyle: Boolean): ContentCardBadgeColors {
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        ContentCardBadgeTone.NSFW -> if (isIosStyle) {
            ContentCardBadgeColors(scheme.error.copy(alpha = 0.90f), Color.White, null)
        } else {
            ContentCardBadgeColors(scheme.errorContainer.copy(alpha = 0.95f), scheme.onErrorContainer, null)
        }
        ContentCardBadgeTone.COUNTER -> if (isIosStyle) {
            ContentCardBadgeColors(Color(0xFFFF3B30).copy(alpha = 0.92f), Color.White, null)
        } else {
            ContentCardBadgeColors(scheme.primary.copy(alpha = 0.92f), scheme.onPrimary, null)
        }
        ContentCardBadgeTone.NEUTRAL -> if (isIosStyle) {
            ContentCardBadgeColors(Color.Black.copy(alpha = 0.60f), Color.White, Color.White.copy(alpha = 0.18f))
        } else {
            ContentCardBadgeColors(scheme.surfaceContainerHigh.copy(alpha = 0.88f), scheme.onSurface, null)
        }
    }
}

@Composable
fun ContentCardBadgePill(
    tone: ContentCardBadgeTone,
    isIosStyle: Boolean,
    metrics: ContentCardBadgeMetrics,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(ContentCardBadgeColors) -> Unit,
) {
    val colors = contentCardBadgeColors(tone, isIosStyle)
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .background(colors.container, shape)
            .then(colors.border?.let { Modifier.border(0.5.dp, it, shape) } ?: Modifier)
            .padding(horizontal = metrics.containerHorizontalPadding, vertical = metrics.containerVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.itemSpacing),
    ) {
        content(colors)
    }
}

@Composable
fun ContentCardBadgeText(text: String, color: Color, metrics: ContentCardBadgeMetrics) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = metrics.textSize, lineHeight = metrics.textSize),
        fontWeight = FontWeight.Bold,
    )
}

/** The standalone NSFW pill; unlike an NSFW-only corner pill it keeps the iOS rim. */
@Composable
fun ContentCardNsfwBadge(
    label: String,
    isIosStyle: Boolean,
    metrics: ContentCardBadgeMetrics = ContentCardBadgeMetrics(),
    modifier: Modifier = Modifier,
) = ContentCardBadgePill(
    tone = ContentCardBadgeTone.NSFW,
    isIosStyle = isIosStyle,
    metrics = metrics,
    modifier = if (isIosStyle) {
        modifier.border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(percent = 50))
    } else {
        modifier
    },
) { colors ->
    ContentCardBadgeText(label, colors.content, metrics)
}

/** The 4dp bar along the cover's bottom edge; green once the work is completed. */
@Composable
fun ContentCardBottomProgressBar(
    percent: Float,
    completed: Boolean,
    modifier: Modifier = Modifier,
) {
    if (percent !in 0f..1f || percent <= 0f) return
    val strokeColor = if (completed) Color(0xFF34C759) else MaterialTheme.colorScheme.primary
    val displayPercent = percent.coerceIn(0.04f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        val fillShape = if (displayPercent < 0.98f) {
            RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)
        } else {
            RectangleShape
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(displayPercent)
                .fillMaxHeight()
                .background(strokeColor, fillShape),
        )
    }
}

/** The circular badge variant: an arc with the host-formatted label, or [completedIcon] once read through. */
@Composable
fun ContentCardReadingProgressRing(
    percent: Float,
    completed: Boolean,
    label: String,
    completedIcon: Painter,
    modifier: Modifier = Modifier,
) {
    val fraction = percent.coerceIn(0f, 1f)
    val strokeColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)
    val contentColor = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.075f
            val arcDiameter = size.minDimension - strokeWidth
            drawCircle(color = backgroundColor, radius = size.minDimension / 2f)
            if (fraction > 0f && !completed) drawArc(
                color = strokeColor,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                size = Size(arcDiameter, arcDiameter),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        if (completed) {
            Icon(painter = completedIcon, contentDescription = null, tint = contentColor,
                modifier = Modifier.fillMaxSize(0.55f))
        } else {
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (label.length > 3) 9.sp else 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * Top rim-light bevel border brush for cover cards: a crisp highlight on the top edge fading to a
 * delicate ambient tone at the bottom. Hosts with their own light/dark preference pass [isDark].
 */
@Composable
fun rememberCoverRimBorderBrush(isIosStyle: Boolean, isDark: Boolean = isSystemInDarkTheme()): Brush {
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    return remember(isIosStyle, isDark, outlineVariant) {
        val topColor = if (isIosStyle) {
            if (isDark) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.14f)
        } else {
            if (isDark) Color.White.copy(alpha = 0.22f) else outlineVariant.copy(alpha = 0.45f)
        }
        val midColor = if (isIosStyle) {
            if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
        } else {
            if (isDark) Color.White.copy(alpha = 0.08f) else outlineVariant.copy(alpha = 0.22f)
        }
        val bottomColor = if (isDark) Color.White.copy(alpha = 0.04f) else if (isIosStyle) {
            Color.Black.copy(alpha = 0.04f)
        } else {
            outlineVariant.copy(alpha = 0.10f)
        }
        Brush.verticalGradient(0.0f to topColor, 0.4f to midColor, 1.0f to bottomColor)
    }
}
