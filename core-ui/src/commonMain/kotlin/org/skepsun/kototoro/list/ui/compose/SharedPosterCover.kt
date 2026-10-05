package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle

/** The Android poster's draw order, rim, spine and title scrim; hosts supply image and badge content. */
@Composable
fun TabletPosterCover(
    title: String,
    style: CompactPosterCardStyle,
    rimBorderBrush: Brush,
    cover: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    compactOverlay: Boolean = true,
    gridScale: Float = 1f,
    overlays: @Composable BoxScope.() -> Unit = {},
    progress: @Composable BoxScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(style.cornerRadius)
    Box(modifier.shadow(2.dp, shape, clip = false).clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, rimBorderBrush, shape)) {
        cover()
        ContentCardBookSpine(Modifier.align(Alignment.CenterStart), width = 3.5.dp)
        overlays()
        if (compactOverlay) CompactGridTitleOverlay(title, compactGridTitleOverlayHeight(style.posterHeight),
            resolveCompactGridTitleFontSize(gridScale), Modifier.align(Alignment.BottomCenter))
        progress()
    }
}

private val CompactGridScrimStops = arrayOf(
    0.0f to Color.Transparent,
    0.20f to Color.Black.copy(alpha = 0.05f),
    0.40f to Color.Black.copy(alpha = 0.16f),
    0.60f to Color.Black.copy(alpha = 0.34f),
    0.80f to Color.Black.copy(alpha = 0.54f),
    1.0f to Color.Black.copy(alpha = 0.72f),
)

@Composable
fun CompactGridTitleOverlay(
    title: String,
    height: androidx.compose.ui.unit.Dp,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val overlayBrush = remember {
        Brush.verticalGradient(colorStops = CompactGridScrimStops)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(overlayBrush)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = fontSize,
                lineHeight = (fontSize.value + 3.5f).sp,
                fontWeight = FontWeight.SemiBold,
                // Narrow rail covers cannot hold a long word on one line; hyphenate it
                // instead of breaking it at an arbitrary letter ("Tide / r").
                hyphens = androidx.compose.ui.text.style.Hyphens.Auto,
                lineBreak = androidx.compose.ui.text.style.LineBreak.Paragraph,
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.75f),
                    offset = Offset(0f, 1.5f),
                    blurRadius = 5f,
                ),
            ),
            color = Color.White,
            softWrap = true,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val BookSpineBrush = Brush.horizontalGradient(
    0.00f to Color.White.copy(alpha = 0.14f),
    0.28f to Color.Black.copy(alpha = 0.18f),
    0.70f to Color.Black.copy(alpha = 0.06f),
    1.00f to Color.Transparent,
)

@Composable
fun ContentCardBookSpine(
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 3.5.dp,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .background(BookSpineBrush),
    )
}

fun resolveCompactGridTitleFontSize(gridScale: Float): TextUnit =
    (resolveGridTitleFontSize(gridScale).value - 1.5f).sp

/** Scrim height of the compact grid title: room for two title lines on any cover size. */
fun compactGridTitleOverlayHeight(posterHeight: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    (posterHeight.value * 0.42f).dp.coerceIn(54.dp, 72.dp)

fun resolveGridTitleFontSize(gridScale: Float): TextUnit {
    val normalized = ((gridScale.coerceIn(0.5f, 1.5f) - 0.5f) / 1f).coerceIn(0f, 1f)
    return (12f + 4f * normalized).sp
}
