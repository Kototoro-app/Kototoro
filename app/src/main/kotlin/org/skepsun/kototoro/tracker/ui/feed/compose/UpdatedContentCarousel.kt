package org.skepsun.kototoro.tracker.ui.feed.compose

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.HorizontalRailAnimatedVisibility
import org.skepsun.kototoro.core.ui.compose.ContentCoverShape
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.core.ui.compose.LocalSharedTransitionScope
import org.skepsun.kototoro.core.ui.compose.rememberRailAnimationFactor
import org.skepsun.kototoro.core.ui.compose.unclippedBoundsInWindow
import org.skepsun.kototoro.core.ui.compose.compactPosterRailCardStyle
import org.skepsun.kototoro.core.ui.compose.contentCoverCacheKey
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.core.util.ext.mangaExtra

import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.list.ui.compose.ContentCardCornerBadges
import org.skepsun.kototoro.list.ui.compose.ContentCardNsfwBadge
import org.skepsun.kototoro.list.ui.compose.asBadgeModel
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
import org.skepsun.kototoro.core.ui.compose.rememberHorizontalRailScrollIntensity
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeader
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeaderItem

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
	modifier: Modifier = Modifier
) {
	val posterStyle = remember(prefs.gridScale) { compactPosterRailCardStyle(prefs.gridScale) }
	val listState = rememberLazyListState()
	val scrollIntensity = rememberHorizontalRailScrollIntensity(listState)

	Column(modifier = modifier.fillMaxWidth()) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(start = 12.dp, top = 0.dp, end = 12.dp, bottom = 8.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween
			) {
			Text(
				text = stringResource(R.string.updates),
				style = MaterialTheme.typography.labelLarge,
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

		val railAnimationFactor = rememberRailAnimationFactor()
		LazyRow(
			state = listState,
			modifier = Modifier.fillMaxWidth(),
			contentPadding = PaddingValues(horizontal = 2.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			itemsIndexed(
				items = header.list,
				key = { _, item -> "updated_${item.groupKey}" },
				contentType = { _, _ -> "updated_card" },
			) { index, headerItem ->
				HorizontalRailAnimatedVisibility(
					animationKey = "updated_${headerItem.groupKey}",
					index = index,
					listState = listState,
					scrollIntensity = scrollIntensity,
					animationFactor = railAnimationFactor,
					enableScrollLinkedAnimation = false,
				) { animatedModifier ->
					FeedUpdatedPosterCard(
						item = headerItem,
						posterStyle = posterStyle,
						badgesBottomRight = prefs.badgesBottomRight,
						onClick = { coverBounds -> onItemClick(headerItem, coverBounds) },
						modifier = animatedModifier,
					)
				}
			}
		}
		
		Spacer(modifier = Modifier.height(8.dp))
	}
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FeedUpdatedPosterCard(
	item: UpdatedContentHeaderItem,
	posterStyle: org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle,
	badgesBottomRight: Set<String>,
	onClick: (Rect?) -> Unit,
	modifier: Modifier = Modifier,
) {
	val model = item.model
	val context = LocalContext.current
	val imageRequest = remember(context, model.manga.source.name, model.manga.url, model.manga.publicUrl, model.coverUrl) {
		val cacheKey = contentCoverCacheKey(model.manga, model.coverUrl)
		ImageRequest.Builder(context)
			.data(model.coverUrl)
			.memoryCacheKey(cacheKey)
			.diskCacheKey(cacheKey)
			.mangaExtra(model.manga)
			.crossfade(false)
			.build()
	}
	val badgeMetrics = remember(posterStyle.itemWidth) { contentCardBadgeMetricsFor(posterStyle.itemWidth) }
	val counterBadgeModel = remember(model, item.totalNewChapters) {
		model.asBadgeModel().copy(counter = item.totalNewChapters)
	}
	var coverBounds by remember(model.id) { mutableStateOf<Rect?>(null) }
	val sharedTransitionScope = LocalSharedTransitionScope.current
	val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current
	val sharedElementKey = remember(item.groupKey, model.coverUrl, model.manga.source.name) {
		contentCoverSharedKey(model.manga.source.name, model.coverUrl.orEmpty(), instanceKey = "feed_updated_${item.groupKey}")
	}

	Column(
		modifier = modifier
			.width(posterStyle.itemWidth)
			.clickable { onClick(coverBounds) },
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(posterStyle.posterHeight)
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
				.clip(ContentCoverShape)
				.background(MaterialTheme.colorScheme.surfaceVariant),
		) {
			AsyncImage(
				model = imageRequest,
				contentDescription = model.title,
				modifier = Modifier.fillMaxSize(),
				contentScale = ContentScale.Crop,
				onSuccess = { state ->
					HeroCoverSnapshotStore.put(sharedElementKey, state.result.image)
				},
			)
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
		}
		Text(
			text = model.title,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurface,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
}
