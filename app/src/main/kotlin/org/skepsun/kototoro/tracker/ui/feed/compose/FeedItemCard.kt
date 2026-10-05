package org.skepsun.kototoro.tracker.ui.feed.compose

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import org.skepsun.kototoro.core.ui.compose.FastScrollTouchWidth
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.rememberDeferredContentCoverBounds
import org.skepsun.kototoro.core.ui.feed.FeedContinueAction
import org.skepsun.kototoro.core.ui.feed.FeedCoverWidth
import org.skepsun.kototoro.core.ui.feed.FeedTimelineCard
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.list.ui.compose.ContentCardNsfwBadge
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
import org.skepsun.kototoro.tracker.ui.feed.model.FeedItem

/** Android's feed card: the shared timeline card ([FeedTimelineCard]) with Coil covers and shared transitions. */
@OptIn(ExperimentalSharedTransitionApi::class)
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
    val badgeMetrics = remember { contentCardBadgeMetricsFor(FeedCoverWidth) }
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
    val chapterText = if (item.count > 0) {
        pluralStringResource(id = R.plurals.new_chapters, count = item.count, item.count)
    } else {
        pluralStringResource(id = R.plurals.old_chapters_in_total, count = item.totalChapters, item.totalChapters)
    }
    val continueLabel = stringResource(R.string.continue_reading)
    val continueIcon = painterResource(id = R.drawable.ic_read)
    FeedTimelineCard(
        title = item.title,
        chapterText = chapterText,
        isNew = item.isNew,
        onClick = { onClick(coverBounds.currentBounds()) },
        modifier = modifier,
        screenPadding = AppLayoutTokens.screenHorizontalPadding,
        coverModifier = Modifier
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
        isSelected = isSelected,
        selectedIcon = painterResource(id = R.drawable.ic_check),
        timelineLabel = timelineLabel,
        onLongClick = onLongClick,
        continueAction = onContinueReading?.let { FeedContinueAction(continueIcon, continueLabel, it) },
        // The feed scrollbar owns a [FastScrollTouchWidth]-wide drag strip on the trailing edge and turns taps
        // inside it into drags, so keep the button fully outside it.
        continueEndInset = FastScrollTouchWidth - FeedCardEndPadding,
        cover = {
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
        },
    )
}

/** The shared card's trailing padding. */
private val FeedCardEndPadding = 16.dp
