package org.skepsun.kototoro.core.ui.feed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Android's subscriptions (feed) presentation shared with the Windows host: the timeline card and the
 * "updated content" carousel. Hosts supply cover images, badges, transitions and text resources.
 */

val FeedCoverWidth = 48.dp
val FeedCoverHeight = 60.dp
private val FeedCardEndPadding = 16.dp
val FeedCoverShape = RoundedCornerShape(14.dp)
private val FeedTimelineRailWidth = 28.dp
private val FeedTimelineNodeSize = 10.dp
private val FeedTimelineNodeStrokeWidth = 3.dp
private val FeedTimelineDateLabelStart = 2.dp
private val FeedTimelineDateLabelWidth = 48.dp
private val FeedTimelineDateLabelNodeGap = 4.dp

private fun feedTimelineCardLeadingSpace(screenPadding: Dp): Dp =
    FeedTimelineDateLabelStart + FeedTimelineDateLabelWidth + FeedTimelineDateLabelNodeGap +
        (FeedTimelineNodeSize / 2f) + (FeedTimelineNodeStrokeWidth / 2f) - screenPadding - (FeedTimelineRailWidth / 2f)

/** The trailing "continue reading" button of a feed card. */
class FeedContinueAction(val icon: Painter, val label: String, val onClick: () -> Unit)

/**
 * One feed entry on the date timeline: node, cover, title, chapter line and an optional continue button.
 * [coverModifier] lets hosts attach transitions or bounds tracking to the cover box.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedTimelineCard(
    title: String,
    chapterText: String,
    isNew: Boolean,
    onClick: () -> Unit,
    cover: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    screenPadding: Dp = 16.dp,
    coverModifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectedIcon: Painter? = null,
    timelineLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    continueAction: FeedContinueAction? = null,
    /** Keeps the continue button clear of a trailing fast-scroll strip. */
    continueEndInset: Dp = 0.dp,
) {
    val nodeColor = if (isNew) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.66f)
    val leadingSpace = feedTimelineCardLeadingSpace(screenPadding)
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.26f) else Color.Transparent)
                .drawBehind {
                    val railCenterX = screenPadding.toPx() + leadingSpace.toPx() + (FeedTimelineRailWidth.toPx() / 2f)
                    drawLine(
                        color = nodeColor.copy(alpha = 0.28f),
                        start = Offset(railCenterX, 0f),
                        end = Offset(railCenterX, size.height),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Butt,
                    )
                }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = screenPadding, end = FeedCardEndPadding, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(leadingSpace))
            FeedTimelineRail(
                modifier = Modifier.width(FeedTimelineRailWidth).fillMaxHeight(),
                nodeColor = nodeColor,
                nodeSize = FeedTimelineNodeSize,
                drawLine = false,
            )
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(FeedCoverWidth, FeedCoverHeight)
                        .then(coverModifier)
                        .clip(FeedCoverShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    cover()
                    if (isSelected) {
                        Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)))
                        if (selectedIcon != null) Icon(
                            painter = selectedIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.align(Alignment.Center).size(28.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape).padding(4.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isNew) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                        }
                        Text(
                            text = chapterText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isNew) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (continueAction != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = continueAction.onClick,
                        modifier = Modifier.padding(end = continueEndInset).size(34.dp),
                    ) {
                        Icon(continueAction.icon, contentDescription = continueAction.label, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        timelineLabel?.takeIf { it.isNotBlank() }?.let { label ->
            FeedTimelineDateLabel(
                text = label,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = FeedTimelineDateLabelStart)
                    .width(FeedTimelineDateLabelWidth),
            )
        }
    }
}

@Composable
private fun FeedTimelineDateLabel(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        Text(
            text = text,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f))
                .padding(horizontal = 4.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun FeedTimelineRail(
    nodeSize: Dp,
    nodeColor: Color,
    modifier: Modifier = Modifier,
    drawLine: Boolean = true,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            if (drawLine) drawLine(
                color = nodeColor.copy(alpha = 0.28f),
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Butt,
            )
        }
        Box(
            modifier = Modifier
                .size(nodeSize)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .drawBehind {
                    drawCircle(color = nodeColor, radius = size.minDimension / 2f,
                        style = Stroke(width = FeedTimelineNodeStrokeWidth.toPx()))
                    drawCircle(color = nodeColor, radius = (size.minDimension / 2f) - 5.dp.toPx())
                },
        )
    }
}

/** What a carousel card needs to know about its slot; hosts draw the cover and badges from it. */
class UpdatedCarouselCardScope(val width: Dp, val height: Dp, val featured: Boolean)

/**
 * Android's "updated content" carousel: the focused card is widest and side cards shrink and tilt with their
 * distance from the focus. [itemWrapper] lets a host wrap each card (e.g. Android's rail entrance animation).
 */
@Composable
fun <T> UpdatedContentCarousel(
    items: List<T>,
    key: (T) -> Any,
    title: (T) -> String,
    newChapters: (T) -> Int,
    newChaptersLabel: @Composable (Int) -> String,
    headerTitle: String,
    moreLabel: String,
    onItemClick: (T) -> Unit,
    /** Null hides the header's more button. */
    onMoreClick: (() -> Unit)?,
    cover: @Composable BoxScope.(item: T, card: UpdatedCarouselCardScope) -> Unit,
    badges: @Composable BoxScope.(item: T, card: UpdatedCarouselCardScope) -> Unit,
    modifier: Modifier = Modifier,
    screenPadding: Dp = 16.dp,
    gridScale: Float = 1f,
    cardModifier: (T) -> Modifier = { Modifier },
    listState: LazyListState = rememberLazyListState(),
    itemWrapper: @Composable (index: Int, item: T, content: @Composable (Modifier) -> Unit) -> Unit =
        { _, _, content -> content(Modifier) },
) {
    if (items.isEmpty()) return
    val scale = gridScale.coerceIn(0.8f, 1.15f)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = screenPadding, top = 12.dp, end = screenPadding, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(headerTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface)
            if (onMoreClick != null) {
                TextButton(onClick = onMoreClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(moreLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val availableWidth = (maxWidth - screenPadding * 2).coerceAtLeast(0.dp)
            val featuredWidth = feedUpdatedFocusWidth(availableWidth, scale)
            val cardHeight = (featuredWidth * FeedGridCardHeightRatio).coerceIn(144.dp, 216.dp)
            val density = LocalDensity.current
            val featuredWidthPx = with(density) { featuredWidth.toPx() }
            val focusOffsetPx = with(density) { screenPadding.toPx() }
            // Only the offsets feed the tilt/width math. Reading layoutInfo itself in composition made every
            // LazyRow measure (a fresh result object) recompose the row: a self-sustaining redraw loop while idle.
            val railOffsets by remember(listState) {
                derivedStateOf {
                    val info = listState.layoutInfo
                    FeedRailOffsets(info.viewportStartOffset, info.visibleItemsInfo.associate { it.index to it.offset })
                }
            }
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = screenPadding, end = screenPadding),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(items = items, key = { _, item -> key(item) }, contentType = { _, _ -> "updated_card" }) { index, item ->
                    val position = feedUpdatedCardPosition(railOffsets.itemOffsets[index], railOffsets.viewportStartOffset,
                        focusOffsetPx, featuredWidthPx)
                    val tilt = (position * FeedCarouselTiltPerPosition).coerceIn(-FeedCarouselMaxTilt, FeedCarouselMaxTilt)
                    val focusProgress = (1f - abs(position)).coerceIn(0f, 1f)
                    itemWrapper(index, item) { animatedModifier ->
                        UpdatedContentCard(
                            title = title(item),
                            newChapters = newChapters(item),
                            newChaptersLabel = newChaptersLabel,
                            width = feedUpdatedCardWidthForPosition(featuredWidth, position),
                            height = cardHeight,
                            featured = focusProgress >= 0.5f,
                            tilt = tilt,
                            onClick = { onItemClick(item) },
                            modifier = animatedModifier.then(cardModifier(item)),
                            cover = { card -> cover(item, card) },
                            badges = { card -> badges(item, card) },
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun UpdatedContentCard(
    title: String,
    newChapters: Int,
    newChaptersLabel: @Composable (Int) -> String,
    width: Dp,
    height: Dp,
    featured: Boolean,
    tilt: Float,
    onClick: () -> Unit,
    modifier: Modifier,
    cover: @Composable BoxScope.(UpdatedCarouselCardScope) -> Unit,
    badges: @Composable BoxScope.(UpdatedCarouselCardScope) -> Unit,
) {
    val card = remember(width, height, featured) { UpdatedCarouselCardScope(width, height, featured) }
    val cardShape = remember(tilt) { FeedCarouselCardShape(tilt) }
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            cover(card)
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (featured) 0.82f else 0.74f))),
                ),
            )
        }
        badges(card)
        val contentPadding = if (featured) 14.dp else 10.dp
        // Side cards shrink with their distance from the focus. A multi-line title in a narrow card broke words
        // letter by letter, so side cards keep one ellipsized line and drop the text once too narrow for a word;
        // the counter badge still carries the update.
        val showText = featured || width >= FeedCarouselMinTextWidth
        val showChapters = featured || width >= FeedCarouselMinChaptersWidth
        if (showText) Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = contentPadding, top = contentPadding, end = contentPadding,
                    bottom = contentPadding + (height * abs(tilt))),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = if (featured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = if (featured) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showChapters && newChapters > 0) {
                Text(
                    text = newChaptersLabel(newChapters),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.84f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val FeedCarouselMinTextWidth = 88.dp
private val FeedCarouselMinChaptersWidth = 120.dp

internal fun feedUpdatedFocusWidth(availableWidth: Dp, scale: Float): Dp {
    if (availableWidth == 0.dp) return 0.dp
    return (availableWidth * (0.38f * scale)).coerceAtMost(availableWidth)
}

internal fun feedUpdatedCardWidthForPosition(focusWidth: Dp, position: Float): Dp {
    val distance = abs(position)
    val widthFraction = (FeedCarouselMinWidthFraction +
        ((1f - FeedCarouselMinWidthFraction) * FeedCarouselWidthDecay.pow(distance)))
        .coerceIn(FeedCarouselMinWidthFraction, 1f)
    return focusWidth * widthFraction
}

internal fun feedUpdatedCardPosition(itemOffset: Int?, viewportStartOffset: Int, focusOffsetPx: Float,
    featuredWidthPx: Float): Float {
    if (itemOffset == null || featuredWidthPx <= 0f) return 0f
    return ((itemOffset - viewportStartOffset).toFloat() - focusOffsetPx).div(featuredWidthPx)
        .coerceIn(-FeedCarouselMaxDistance, FeedCarouselMaxDistance)
}

private class FeedCarouselCardShape(private val tilt: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val normalizedTilt = tilt.coerceIn(-FeedCarouselMaxTilt, FeedCarouselMaxTilt)
        val heightDelta = size.height * abs(normalizedTilt)
        val cornerRadius = with(density) { 14.dp.toPx() }.coerceAtMost(size.minDimension * 0.12f)
        val rightSideHigher = normalizedTilt > 0f
        val topLeft = if (rightSideHigher) heightDelta else 0f
        val topRight = if (rightSideHigher) 0f else heightDelta
        val bottomLeft = if (rightSideHigher) size.height - heightDelta else size.height
        val bottomRight = if (rightSideHigher) size.height else size.height - heightDelta
        val topLeftCorner = Offset(0f, topLeft)
        val topRightCorner = Offset(size.width, topRight)
        val bottomRightCorner = Offset(size.width, bottomRight)
        val bottomLeftCorner = Offset(0f, bottomLeft)
        val topDirection = normalizedDirection(topLeftCorner, topRightCorner)
        val rightDirection = normalizedDirection(topRightCorner, bottomRightCorner)
        val bottomDirection = normalizedDirection(bottomRightCorner, bottomLeftCorner)
        val leftDirection = normalizedDirection(bottomLeftCorner, topLeftCorner)
        val topLeftOnTop = topLeftCorner.offsetBy(topDirection, cornerRadius)
        val topRightOnTop = topRightCorner.offsetBy(topDirection, -cornerRadius)
        val topRightOnRight = topRightCorner.offsetBy(rightDirection, cornerRadius)
        val bottomRightOnRight = bottomRightCorner.offsetBy(rightDirection, -cornerRadius)
        val bottomRightOnBottom = bottomRightCorner.offsetBy(bottomDirection, cornerRadius)
        val bottomLeftOnBottom = bottomLeftCorner.offsetBy(bottomDirection, -cornerRadius)
        val bottomLeftOnLeft = bottomLeftCorner.offsetBy(leftDirection, cornerRadius)
        val topLeftOnLeft = topLeftCorner.offsetBy(leftDirection, -cornerRadius)
        val path = Path().apply {
            moveTo(topLeftOnTop.x, topLeftOnTop.y)
            lineTo(topRightOnTop.x, topRightOnTop.y)
            quadraticTo(topRightCorner.x, topRightCorner.y, topRightOnRight.x, topRightOnRight.y)
            lineTo(bottomRightOnRight.x, bottomRightOnRight.y)
            quadraticTo(bottomRightCorner.x, bottomRightCorner.y, bottomRightOnBottom.x, bottomRightOnBottom.y)
            lineTo(bottomLeftOnBottom.x, bottomLeftOnBottom.y)
            quadraticTo(bottomLeftCorner.x, bottomLeftCorner.y, bottomLeftOnLeft.x, bottomLeftOnLeft.y)
            lineTo(topLeftOnLeft.x, topLeftOnLeft.y)
            quadraticTo(topLeftCorner.x, topLeftCorner.y, topLeftOnTop.x, topLeftOnTop.y)
            close()
        }
        return Outline.Generic(path)
    }
}

private fun normalizedDirection(from: Offset, to: Offset): Offset {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = sqrt((dx * dx) + (dy * dy))
    return if (length > 0f) Offset(dx / length, dy / length) else Offset.Zero
}

private fun Offset.offsetBy(direction: Offset, distance: Float): Offset =
    Offset(x = x + direction.x * distance, y = y + direction.y * distance)

private const val FeedCarouselTiltPerPosition = 0.07f
private const val FeedCarouselMaxTilt = 0.22f
private const val FeedCarouselMaxDistance = 3f
private const val FeedCarouselMinWidthFraction = 0.15f
private const val FeedCarouselWidthDecay = 0.50f
private const val FeedGridCardHeightRatio = 136f / 96f

private data class FeedRailOffsets(val viewportStartOffset: Int, val itemOffsets: Map<Int, Int>)
