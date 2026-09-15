package org.skepsun.kototoro.tracker.ui.feed.compose

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.HorizontalRailAnimatedVisibility
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.rememberRailAnimationFactor
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.rememberDeferredContentCoverBounds
import org.skepsun.kototoro.core.util.ext.mangaExtra

import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.list.ui.compose.ContentCardCornerBadges
import org.skepsun.kototoro.list.ui.compose.ContentCardNsfwBadge
import org.skepsun.kototoro.list.ui.compose.asBadgeModel
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
import org.skepsun.kototoro.core.ui.compose.rememberHorizontalRailScrollIntensity
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeader
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeaderItem
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

@Immutable
data class UpdatedContentCarouselPrefs(
    val gridScale: Float,
    val badgesBottomRight: Set<String>,
)

@Composable
fun UpdatedContentCarousel(
    header: UpdatedContentHeader,
    prefs: UpdatedContentCarouselPrefs,
    onItemClick: (UpdatedContentHeaderItem, Rect?) -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (header.list.isEmpty()) return

    val listState = rememberLazyListState()
    val scrollIntensity = rememberHorizontalRailScrollIntensity(listState)
    val scale = prefs.gridScale.coerceIn(0.8f, 1.15f)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = AppLayoutTokens.screenHorizontalPadding,
                    top = 12.dp,
                    end = AppLayoutTokens.screenHorizontalPadding,
                    bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.updates),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(
                onClick = onMoreClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.more),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val availableWidth = (maxWidth - AppLayoutTokens.screenHorizontalPadding * 2).coerceAtLeast(0.dp)
            val featuredWidth = feedUpdatedFocusWidth(availableWidth, scale)
            val cardHeight = (featuredWidth * FEED_GRID_CARD_HEIGHT_RATIO).coerceIn(144.dp, 216.dp)
            val density = LocalDensity.current
            val featuredWidthPx = with(density) { featuredWidth.toPx() }
            val focusOffsetPx = with(density) { AppLayoutTokens.screenHorizontalPadding.toPx() }
            val layoutInfo = listState.layoutInfo
            val railAnimationFactor = rememberRailAnimationFactor()

            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = AppLayoutTokens.screenHorizontalPadding,
                    end = AppLayoutTokens.screenHorizontalPadding,
                ),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(
                    items = header.list,
                    key = { _, item -> "updated_${item.groupKey}" },
                    contentType = { _, _ -> "updated_card" },
                ) { index, headerItem ->
                    val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                    val position = feedUpdatedCardPosition(
                        itemOffset = itemInfo?.offset,
                        viewportStartOffset = layoutInfo.viewportStartOffset,
                        focusOffsetPx = focusOffsetPx,
                        featuredWidthPx = featuredWidthPx,
                    )
                    val tilt = (position * FEED_CAROUSEL_TILT_PER_POSITION)
                        .coerceIn(-FEED_CAROUSEL_MAX_TILT, FEED_CAROUSEL_MAX_TILT)
                    val focusProgress = (1f - abs(position)).coerceIn(0f, 1f)
                    HorizontalRailAnimatedVisibility(
                        animationKey = "updated_${headerItem.groupKey}",
                        index = index,
                        listState = listState,
                        scrollIntensity = scrollIntensity,
                        animationFactor = railAnimationFactor,
                        enableScrollLinkedAnimation = false,
                    ) { animatedModifier ->
                        FeedUpdatedContentCard(
                            item = headerItem,
                            width = feedUpdatedCardWidthForPosition(featuredWidth, position),
                            height = cardHeight,
                            featured = focusProgress >= 0.5f,
                            tilt = tilt,
                            badgesBottomRight = prefs.badgesBottomRight,
                            onClick = { coverBounds -> onItemClick(headerItem, coverBounds) },
                            modifier = animatedModifier,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FeedUpdatedContentCard(
    item: UpdatedContentHeaderItem,
    width: Dp,
    height: Dp,
    featured: Boolean,
    tilt: Float,
    badgesBottomRight: Set<String>,
    onClick: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = item.model
    val context = LocalContext.current
    val imageRequest = remember(
        context,
        model.manga.source.name,
        model.manga.url,
        model.manga.publicUrl,
        model.coverUrl,
    ) {
        val cacheKey = contentCoverCacheKey(model.manga, model.coverUrl)
        ImageRequest.Builder(context)
            .data(model.coverUrl)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .mangaExtra(model.manga)
            .crossfade(true)
            .build()
    }
    val badgeMetrics = remember(width) { contentCardBadgeMetricsFor(width) }
    val counterBadgeModel = remember(model, item.totalNewChapters) {
        model.asBadgeModel().copy(counter = item.totalNewChapters)
    }
    val coverBounds = rememberDeferredContentCoverBounds()
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val sharedElementKey = remember(item.groupKey, model.coverUrl, model.manga.source.name) {
        contentCoverSharedKey(model.manga.source.name, model.coverUrl.orEmpty(), instanceKey = "feed_updated_${item.groupKey}")
    }
    val onImageSuccess = remember(sharedElementKey) {
        { state: coil3.compose.AsyncImagePainter.State.Success ->
            HeroCoverSnapshotStore.put(sharedElementKey, state.result.image)
        }
    }
    val cardShape = remember(tilt) { FeedCarouselCardShape(tilt) }

    Box(
        modifier = modifier
            .width(width)
            .height(height)
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
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick(coverBounds.currentBounds()) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = model.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = onImageSuccess,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = if (featured) 0.82f else 0.74f),
                            ),
                        ),
                    ),
            )
        }

        ContentCardCornerBadges(
            badges = if (item.totalNewChapters > 0) setOf("counter") else emptySet(),
            item = counterBadgeModel,
            corner = Alignment.TopEnd,
            cardRadius = 12.dp,
            metrics = badgeMetrics,
            modifier = Modifier.align(Alignment.TopEnd),
        )
        if ("nsfw" in badgesBottomRight) {
            ContentCardCornerBadges(
                badges = badgesBottomRight,
                item = model.asBadgeModel(),
                corner = Alignment.BottomEnd,
                cardRadius = 12.dp,
                metrics = badgeMetrics,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(badgeMetrics.outerPadding),
            )
        } else if (model.manga.isNsfw()) {
            ContentCardNsfwBadge(
                metrics = badgeMetrics,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(badgeMetrics.outerPadding),
            )
        }

        val contentPadding = if (featured) 14.dp else 10.dp
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = contentPadding,
                    top = contentPadding,
                    end = contentPadding,
                    bottom = contentPadding + (height * abs(tilt)),
                ),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = model.title,
                style = if (featured) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.titleSmall
                },
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = if (featured) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.totalNewChapters > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.new_chapters,
                        item.totalNewChapters,
                        item.totalNewChapters,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.84f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun feedUpdatedFocusWidth(availableWidth: Dp, scale: Float): Dp {
    if (availableWidth == 0.dp) return 0.dp

    return (availableWidth * (0.38f * scale)).coerceAtMost(availableWidth)
}

private fun feedUpdatedCardWidthForPosition(
    focusWidth: Dp,
    position: Float,
): Dp {
    val distance = abs(position)
    val widthFraction = (
        FEED_CAROUSEL_MIN_WIDTH_FRACTION +
            ((1f - FEED_CAROUSEL_MIN_WIDTH_FRACTION) * FEED_CAROUSEL_WIDTH_DECAY.pow(distance))
        ).coerceIn(FEED_CAROUSEL_MIN_WIDTH_FRACTION, 1f)
    return focusWidth * widthFraction
}

private fun feedUpdatedCardPosition(
    itemOffset: Int?,
    viewportStartOffset: Int,
    focusOffsetPx: Float,
    featuredWidthPx: Float,
): Float {
    if (itemOffset == null || featuredWidthPx <= 0f) return 0f

    return ((itemOffset - viewportStartOffset).toFloat() - focusOffsetPx)
        .div(featuredWidthPx)
        .coerceIn(-FEED_CAROUSEL_MAX_DISTANCE, FEED_CAROUSEL_MAX_DISTANCE)
}

private class FeedCarouselCardShape(
    private val tilt: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val normalizedTilt = tilt.coerceIn(-FEED_CAROUSEL_MAX_TILT, FEED_CAROUSEL_MAX_TILT)
        val heightDelta = size.height * abs(normalizedTilt)
        val cornerRadius = with(density) { 14.dp.toPx() }
            .coerceAtMost(size.minDimension * 0.12f)
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
            quadraticTo(
                topRightCorner.x,
                topRightCorner.y,
                topRightOnRight.x,
                topRightOnRight.y,
            )
            lineTo(bottomRightOnRight.x, bottomRightOnRight.y)
            quadraticTo(
                bottomRightCorner.x,
                bottomRightCorner.y,
                bottomRightOnBottom.x,
                bottomRightOnBottom.y,
            )
            lineTo(bottomLeftOnBottom.x, bottomLeftOnBottom.y)
            quadraticTo(
                bottomLeftCorner.x,
                bottomLeftCorner.y,
                bottomLeftOnLeft.x,
                bottomLeftOnLeft.y,
            )
            lineTo(topLeftOnLeft.x, topLeftOnLeft.y)
            quadraticTo(
                topLeftCorner.x,
                topLeftCorner.y,
                topLeftOnTop.x,
                topLeftOnTop.y,
            )
            close()
        }
        return Outline.Generic(path)
    }
}

private fun normalizedDirection(from: Offset, to: Offset): Offset {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = sqrt((dx * dx) + (dy * dy))
    return if (length > 0f) {
        Offset(dx / length, dy / length)
    } else {
        Offset.Zero
    }
}

private fun Offset.offsetBy(direction: Offset, distance: Float): Offset = Offset(
    x = x + direction.x * distance,
    y = y + direction.y * distance,
)

private const val FEED_CAROUSEL_TILT_PER_POSITION = 0.07f
private const val FEED_CAROUSEL_MAX_TILT = 0.22f
private const val FEED_CAROUSEL_MAX_DISTANCE = 3f
private const val FEED_CAROUSEL_MIN_WIDTH_FRACTION = 0.15f
private const val FEED_CAROUSEL_WIDTH_DECAY = 0.50f
private const val FEED_GRID_CARD_HEIGHT_RATIO = 136f / 96f
