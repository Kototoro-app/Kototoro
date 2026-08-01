package org.skepsun.kototoro.discover.ui.compose

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
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
import org.skepsun.kototoro.core.ui.compose.compactPosterRailCardStyle
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.ui.compose.contentCoverSharedKey
import org.skepsun.kototoro.core.ui.compose.HeroCoverSnapshotStore
import org.skepsun.kototoro.list.ui.compose.ContentCardCornerBadges
import org.skepsun.kototoro.list.ui.compose.ContentCardNsfwBadge
import org.skepsun.kototoro.list.ui.compose.asBadgeModel
import org.skepsun.kototoro.list.ui.compose.contentCardBadgeMetricsFor
import org.skepsun.kototoro.core.ui.compose.rememberHorizontalRailScrollIntensity
import org.skepsun.kototoro.discover.ui.model.DiscoverCarouselRow
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.buildInfoText

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiscoverCarousel(
	row: DiscoverCarouselRow,
	gridScale: Float,
	badgesBottomRight: Set<String>,
	onItemClick: (ContentListModel, Rect?, String?) -> Unit,
	onMoreClick: (org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCategory) -> Unit,
	modifier: Modifier = Modifier
) {
	val listState = rememberLazyListState()
	val posterStyle = remember(gridScale) { compactPosterRailCardStyle(gridScale) }
	val scrollIntensity = rememberHorizontalRailScrollIntensity(listState)

	Column(modifier = modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween
		) {
			Text(
				text = stringResource(row.category.nameResId),
				style = MaterialTheme.typography.titleMedium
			)
			Text(
				text = stringResource(R.string.more),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.primary,
				modifier = Modifier.clickable { onMoreClick(row.category) }
			)
		}

		val railAnimationFactor = rememberRailAnimationFactor()
		LazyRow(
			state = listState,
			contentPadding = PaddingValues(horizontal = 16.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp)
		) {
			itemsIndexed(
				items = row.items,
				key = { _, item -> "carousel_${row.category.id}_${(item as? ContentListModel)?.manga?.id ?: item.hashCode()}" },
				contentType = { _, _ -> "discover_card" },
			) { index, contentModel ->
				(contentModel as? ContentListModel)?.let { model ->
					HorizontalRailAnimatedVisibility(
						animationKey = "discover_${row.category.id}_${model.id}",
						index = index,
						listState = listState,
						scrollIntensity = scrollIntensity,
						animationFactor = railAnimationFactor,
						enableScrollLinkedAnimation = false,
					) { animatedModifier ->
						val sharedElementKey = remember(row.category.id, model.id, model.coverUrl, model.manga.source.name) {
							contentCoverSharedKey(
								model.manga.source.name,
								model.coverUrl.orEmpty(),
								instanceKey = "discover_carousel_${row.category.id}_${model.id}",
							)
						}
						DiscoverPosterCard(
							model = model,
							posterStyle = posterStyle,
							badgesBottomRight = badgesBottomRight,
							sharedElementKey = sharedElementKey,
							onClick = { onItemClick(model, null, sharedElementKey) },
							modifier = animatedModifier,
						)
					}
				}
			}
		}

		Spacer(modifier = Modifier.height(16.dp))
	}
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun DiscoverPosterCard(
	model: ContentListModel,
	posterStyle: org.skepsun.kototoro.core.ui.compose.CompactPosterCardStyle,
	badgesBottomRight: Set<String>,
	sharedElementKey: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val imageRequest = remember(model.id, model.coverUrl) {
		ImageRequest.Builder(context)
			.data(model.coverUrl)
			.crossfade(true)
			.build()
	}
	val badgeMetrics = remember(posterStyle.itemWidth) { contentCardBadgeMetricsFor(posterStyle.itemWidth) }
	val sharedTransitionScope = LocalSharedTransitionScope.current
	val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current

	Column(
		modifier = modifier
			.width(posterStyle.itemWidth)
			.clickable(onClick = onClick),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(posterStyle.posterHeight)
				.then(
					if (sharedTransitionScope != null && animatedVisibilityScope != null) {
						with(sharedTransitionScope) {
							Modifier.sharedElement(
								rememberSharedContentState(key = sharedElementKey),
								animatedVisibilityScope = animatedVisibilityScope,
							)
						}
					} else {
						Modifier
					},
				)
				.clip(ContentCoverShape)
				.background(
					color = MaterialTheme.colorScheme.surfaceVariant,
					shape = ContentCoverShape,
				),
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = model.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onSuccess = { state ->
                    HeroCoverSnapshotStore.put(sharedElementKey, state.result.image)
                },
            )
            model.scoreText?.takeIf { it.isNotBlank() }?.let { scoreText ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                ) {
                    Text(
                        text = scoreText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
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
		val infoText = remember(model.manga.state, model.manga.chapters?.size, model.manga.tags, model.scoreText, context) {
			model.buildInfoText(context)
		}
		infoText?.takeIf { it.isNotBlank() }?.let { info ->
			Text(
				text = info,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}
