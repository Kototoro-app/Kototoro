package org.skepsun.kototoro.tracker.ui.feed.compose

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.FastScrollTouchWidth
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.rememberDeferredContentCoverBounds
import org.skepsun.kototoro.core.util.ext.mangaExtra

import org.skepsun.kototoro.list.ui.compose.ContentCardNsfwBadge
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
import org.skepsun.kototoro.tracker.ui.feed.model.FeedItem

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun FeedItemCard(
    item: FeedItem,
    onClick: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    timelineLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onContinueReading: (() -> Unit)? = null,
) {
    val coverBounds = rememberDeferredContentCoverBounds()
    val badgeMetrics = remember { contentCardBadgeMetricsFor(FEED_COVER_WIDTH) }
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val sharedElementKey = remember(item.id, item.imageUrl, item.manga.source.name) {
        contentCoverSharedKey(item.manga.source.name, item.imageUrl.orEmpty(), instanceKey = "feed_${item.id}")
    }
    val context = LocalContext.current
    val allowCrossfade = sharedTransitionScope == null || animatedVisibilityScope == null
    val imageRequest = remember(context, item.manga.source.name, item.manga.url, item.manga.publicUrl, item.imageUrl, allowCrossfade) {
        val cacheKey = contentCoverCacheKey(item.manga, item.imageUrl)
        ImageRequest.Builder(context)
            .data(item.imageUrl)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .mangaExtra(item.manga)
            .crossfade(allowCrossfade)
            .build()
    }
    val onImageSuccess = remember(sharedElementKey) {
        { state: coil3.compose.AsyncImagePainter.State.Success ->
            HeroCoverSnapshotStore.put(sharedElementKey, state.result.image)
        }
    }
    val timelineNodeColor = if (item.isNew) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.66f)
    }

    val chapterText = if (item.count > 0) {
        pluralStringResource(
            id = R.plurals.new_chapters,
            count = item.count,
            item.count,
        )
    } else {
        pluralStringResource(
            id = R.plurals.old_chapters_in_total,
            count = item.totalChapters,
            item.totalChapters,
        )
    }
    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.26f)
                    } else {
                        Color.Transparent
                    },
                )
                .drawBehind {
                    val railCenterX = AppLayoutTokens.screenHorizontalPadding.toPx() +
                        FEED_TIMELINE_CARD_LEADING_SPACE.toPx() +
                        (FEED_TIMELINE_RAIL_WIDTH.toPx() / 2f)
                    drawLine(
                        color = timelineNodeColor.copy(alpha = 0.28f),
                        start = Offset(railCenterX, 0f),
                        end = Offset(railCenterX, size.height),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Butt,
                    )
                }
                .combinedClickable(
                    onClick = { onClick(coverBounds.currentBounds()) },
                    onLongClick = onLongClick,
                )
                .padding(
                    start = AppLayoutTokens.screenHorizontalPadding,
                    end = FEED_CARD_END_PADDING,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(FEED_TIMELINE_CARD_LEADING_SPACE))

            FeedTimelineRail(
                modifier = Modifier
                    .width(FEED_TIMELINE_RAIL_WIDTH)
                    .fillMaxHeight(),
                nodeColor = timelineNodeColor,
                nodeSize = FEED_TIMELINE_NODE_SIZE,
                drawLine = false,
            )

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(FEED_COVER_WIDTH, FEED_COVER_HEIGHT)
                            .onGloballyPositioned { coordinates ->
                                coverBounds.updateCoordinates(coordinates)
                            }
                            .then(
                                if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                    with(sharedTransitionScope) {
                                        Modifier.sharedElement(
                                            rememberSharedContentState(key = sharedElementKey),
                                            animatedVisibilityScope = animatedVisibilityScope,
                                        )
                                    }
                                } else Modifier
                            )
                            .clip(FEED_COVER_SHAPE)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                            onSuccess = onImageSuccess,
                        )
                        if (item.manga.isNsfw()) {
                            ContentCardNsfwBadge(
                                metrics = badgeMetrics,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(badgeMetrics.outerPadding * 0.6f),
                            )
                        }
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                            )
                            Icon(
                                painter = painterResource(id = R.drawable.ic_check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(28.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .padding(4.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (item.isNew) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error),
                                )
                            }
                            Text(
                                text = chapterText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.isNew) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (onContinueReading != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalIconButton(
                            onClick = onContinueReading,
                            modifier = Modifier
                                // The feed scrollbar owns a [FastScrollTouchWidth]-wide drag strip on
                                // the trailing edge and turns taps inside it into drags, so keep the
                                // button fully outside it.
                                .padding(end = FastScrollTouchWidth - FEED_CARD_END_PADDING)
                                .size(34.dp),
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_read),
                                contentDescription = stringResource(R.string.continue_reading),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        timelineLabel
            ?.takeIf { it.isNotBlank() }
            ?.let { label ->
                FeedTimelineDateLabel(
                    text = label,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = FEED_TIMELINE_DATE_LABEL_START)
                        .width(FEED_TIMELINE_DATE_LABEL_WIDTH),
                )
            }
    }
}

@Composable
private fun FeedTimelineDateLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd,
    ) {
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
internal fun FeedTimelineRail(
    modifier: Modifier = Modifier,
    nodeSize: Dp,
    nodeColor: Color,
    drawLine: Boolean = true,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            if (drawLine) {
                drawLine(
                    color = nodeColor.copy(alpha = 0.28f),
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Butt,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(nodeSize)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .then(
                    Modifier.drawBehind {
                        drawCircle(
                            color = nodeColor,
                            radius = size.minDimension / 2f,
                            style = Stroke(width = FEED_TIMELINE_NODE_STROKE_WIDTH.toPx()),
                        )
                        drawCircle(
                            color = nodeColor,
                            radius = (size.minDimension / 2f) - 5.dp.toPx(),
                        )
                    },
                ),
        )
    }
}

private val FEED_COVER_WIDTH = 48.dp
private val FEED_COVER_HEIGHT = 60.dp
private val FEED_CARD_END_PADDING = 16.dp
private val FEED_COVER_SHAPE = RoundedCornerShape(14.dp)
internal val FEED_TIMELINE_RAIL_WIDTH = 28.dp
private val FEED_TIMELINE_NODE_SIZE = 10.dp
private val FEED_TIMELINE_NODE_STROKE_WIDTH = 3.dp
private val FEED_TIMELINE_DATE_LABEL_START = 2.dp
private val FEED_TIMELINE_DATE_LABEL_WIDTH = 48.dp
private val FEED_TIMELINE_DATE_LABEL_NODE_GAP = 4.dp
private val FEED_TIMELINE_CARD_LEADING_SPACE =
    FEED_TIMELINE_DATE_LABEL_START +
        FEED_TIMELINE_DATE_LABEL_WIDTH +
        FEED_TIMELINE_DATE_LABEL_NODE_GAP +
        (FEED_TIMELINE_NODE_SIZE / 2f) +
        (FEED_TIMELINE_NODE_STROKE_WIDTH / 2f) -
        AppLayoutTokens.screenHorizontalPadding -
        (FEED_TIMELINE_RAIL_WIDTH / 2f)
