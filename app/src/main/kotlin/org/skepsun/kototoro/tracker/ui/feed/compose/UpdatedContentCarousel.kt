package org.skepsun.kototoro.tracker.ui.feed.compose

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.HorizontalRailAnimatedVisibility
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.rememberDeferredContentCoverBounds
import org.skepsun.kototoro.core.ui.compose.rememberHorizontalRailScrollIntensity
import org.skepsun.kototoro.core.ui.compose.rememberRailAnimationFactor
import org.skepsun.kototoro.core.ui.feed.UpdatedContentCarousel as SharedUpdatedContentCarousel
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.list.ui.compose.ContentCardCornerBadges
import org.skepsun.kototoro.list.ui.compose.ContentCardNsfwBadge
import org.skepsun.kototoro.list.ui.compose.asBadgeModel
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeader
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeaderItem

@Immutable
data class UpdatedContentCarouselPrefs(
    val gridScale: Float,
    val badgesBottomRight: Set<String>,
)

/** Android's carousel: the shared layout with Coil covers, card badges, rail animation and shared transitions. */
@OptIn(ExperimentalSharedTransitionApi::class)
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
    val railAnimationFactor = rememberRailAnimationFactor()
    val context = LocalContext.current
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
    val boundsByKey = remember { HashMap<Long, () -> Rect?>() }
    SharedUpdatedContentCarousel(
        items = header.list,
        key = { "updated_${it.groupKey}" },
        title = { it.model.title },
        newChapters = { it.totalNewChapters },
        newChaptersLabel = { count -> pluralStringResourceCompat(count) },
        headerTitle = stringResource(R.string.updates),
        moreLabel = stringResource(R.string.more),
        onItemClick = { item -> onItemClick(item, boundsByKey[item.groupKey]?.invoke()) },
        onMoreClick = onMoreClick,
        modifier = modifier,
        screenPadding = AppLayoutTokens.screenHorizontalPadding,
        gridScale = prefs.gridScale,
        listState = listState,
        itemWrapper = { index, item, content ->
            HorizontalRailAnimatedVisibility(
                animationKey = "updated_${item.groupKey}",
                index = index,
                listState = listState,
                scrollIntensity = scrollIntensity,
                animationFactor = railAnimationFactor,
                enableScrollLinkedAnimation = false,
            ) { animatedModifier ->
                val coverBounds = rememberDeferredContentCoverBounds()
                SideEffect { boundsByKey[item.groupKey] = { coverBounds.currentBounds() } }
                val sharedElementKey = remember(item.groupKey, item.model.coverUrl, item.model.manga.source.name) {
                    contentCoverSharedKey(
                        item.model.manga.source.name,
                        item.model.coverUrl.orEmpty(),
                        instanceKey = "feed_updated_${item.groupKey}",
                    )
                }
                content(
                    animatedModifier
                        .onGloballyPositioned { coordinates -> coverBounds.updateCoordinates(coordinates) }
                        .then(
                            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                with(sharedTransitionScope) {
                                    Modifier.sharedElement(
                                        rememberSharedContentState(key = sharedElementKey),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                    )
                                }
                            } else Modifier,
                        ),
                )
            }
        },
        cover = { item, _ ->
            val model = item.model
            val imageRequest = remember(context, model.manga.source.name, model.manga.url, model.manga.publicUrl, model.coverUrl) {
                val cacheKey = contentCoverCacheKey(model.manga, model.coverUrl)
                ImageRequest.Builder(context)
                    .data(model.coverUrl)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .mangaExtra(model.manga)
                    .crossfade(true)
                    .build()
            }
            val sharedElementKey = remember(item.groupKey, model.coverUrl, model.manga.source.name) {
                contentCoverSharedKey(model.manga.source.name, model.coverUrl.orEmpty(), instanceKey = "feed_updated_${item.groupKey}")
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = model.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { state -> HeroCoverSnapshotStore.put(sharedElementKey, state.result.image) },
            )
        },
        badges = { item, card ->
            val model = item.model
            val badgeMetrics = remember(card.width) { contentCardBadgeMetricsFor(card.width) }
            val counterBadgeModel = remember(model, item.totalNewChapters) {
                model.asBadgeModel().copy(counter = item.totalNewChapters)
            }
            ContentCardCornerBadges(
                badges = if (item.totalNewChapters > 0) setOf("counter") else emptySet(),
                item = counterBadgeModel,
                corner = Alignment.TopEnd,
                cardRadius = 12.dp,
                metrics = badgeMetrics,
                modifier = Modifier.align(Alignment.TopEnd),
            )
            if ("nsfw" in prefs.badgesBottomRight) {
                ContentCardCornerBadges(
                    badges = prefs.badgesBottomRight,
                    item = model.asBadgeModel(),
                    corner = Alignment.BottomEnd,
                    cardRadius = 12.dp,
                    metrics = badgeMetrics,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(badgeMetrics.outerPadding),
                )
            } else if (model.manga.isNsfw()) {
                ContentCardNsfwBadge(
                    metrics = badgeMetrics,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(badgeMetrics.outerPadding),
                )
            }
        },
    )
}

@Composable
private fun pluralStringResourceCompat(count: Int): String = pluralStringResource(R.plurals.new_chapters, count, count)
