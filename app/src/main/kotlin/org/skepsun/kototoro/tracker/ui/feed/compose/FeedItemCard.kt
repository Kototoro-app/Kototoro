package org.skepsun.kototoro.tracker.ui.feed.compose

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.CompactContentCoverShape
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.ui.compose.unclippedBoundsInWindow
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
    onLongClick: (() -> Unit)? = null,
) {
    var coverBounds by remember(item.id) { mutableStateOf<Rect?>(null) }
    val badgeMetrics = remember { contentCardBadgeMetricsFor(40.dp) }
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                } else {
                    Color.Transparent
                },
            )
            .combinedClickable(
                onClick = { onClick(coverBounds) },
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .onGloballyPositioned { coordinates ->
                    coverBounds = coordinates.unclippedBoundsInWindow()
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
                .clip(CompactContentCoverShape)
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onSuccess = { state ->
                    HeroCoverSnapshotStore.put(sharedElementKey, state.result.image)
                },
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
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                        .padding(4.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.isNew) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
            Text(
                text = chapterText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
