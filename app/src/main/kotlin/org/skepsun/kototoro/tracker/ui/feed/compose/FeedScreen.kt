package org.skepsun.kototoro.tracker.ui.feed.compose

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.core.ui.compose.KototoroPullToRefreshBox
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.tracker.ui.feed.model.FeedItem
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeader
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeaderItem
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
	contentPadding: PaddingValues = PaddingValues(0.dp),
	items: List<ListModel>,
	isRefreshing: Boolean,
	onRefresh: () -> Unit,
	onLoadMore: () -> Unit,
	onFeedOpened: () -> Unit = {},
	onFeedItemClick: (FeedItem, Rect?) -> Unit,
	onUpdatedContentItemClick: (UpdatedContentHeaderItem, Rect?) -> Unit,
	onUpdatedContentMoreClick: (UpdatedContentHeader) -> Unit,
	categories: List<FavouriteCategory>,
	selectedCategoryId: Long,
	onCategorySelected: (Long) -> Unit,
	onQuickFilterOptionClick: (ListFilterOption) -> Unit,
	showCategoryFilterInline: Boolean = true,
	modifier: Modifier = Modifier
) {
	val listState = rememberSaveable(saver = LazyListState.Saver) {
		LazyListState()
	}
	val context = LocalContext.current

	LaunchedEffect(Unit) {
		onFeedOpened()
	}
	val density = LocalDensity.current
	val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
	val carouselPrefs by settings.observeAsState(
		AppSettings.KEY_GRID_SIZE,
		AppSettings.KEY_BADGES_BOTTOM_RIGHT,
	) {
		UpdatedContentCarouselPrefs(
			gridScale = gridSize / 100f,
			badgesBottomRight = badgesBottomRight,
		)
	}
	
	// Trigger pagination threshold
	val shouldLoadMore by remember {
		derivedStateOf {
			val layoutInfo = listState.layoutInfo
			val totalVisibleItems = layoutInfo.visibleItemsInfo.size
			val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
			lastVisibleItemIndex >= layoutInfo.totalItemsCount - 4 && totalVisibleItems > 0
		}
	}

	LaunchedEffect(shouldLoadMore) {
		if (shouldLoadMore && !isRefreshing) {
			onLoadMore()
		}
	}
	val swipeThresholdPx = with(density) { 72.dp.toPx() }

	KototoroPullToRefreshBox(
		isRefreshing = isRefreshing,
		onRefresh = onRefresh,
		modifier = modifier
			.fillMaxSize()
			.pointerInput(categories, selectedCategoryId) {
				if (categories.size <= 1) {
					return@pointerInput
				}
				awaitEachGesture {
					awaitFirstDown(requireUnconsumed = false)
					var totalX = 0f
					var totalY = 0f
					var pointerStillDown = true
					while (pointerStillDown) {
						val event = awaitPointerEvent()
						val change = event.changes.firstOrNull() ?: break
						if (change.positionChangedIgnoreConsumed()) {
							val delta = change.positionChange()
							totalX += delta.x
							totalY += delta.y
						}
						pointerStillDown = event.changes.any {
							!it.changedToUpIgnoreConsumed() && it.pressed
						}
					}
					if (abs(totalX) < swipeThresholdPx || abs(totalX) <= abs(totalY) * 1.35f) {
						return@awaitEachGesture
					}
					val currentIndex = categories.indexOfFirst { it.id == selectedCategoryId }
					if (currentIndex == -1) {
						return@awaitEachGesture
					}
					val targetIndex = when {
						totalX < 0f -> (currentIndex + 1).coerceAtMost(categories.lastIndex)
						else -> (currentIndex - 1).coerceAtLeast(0)
					}
					if (targetIndex != currentIndex) {
						onCategorySelected(categories[targetIndex].id)
					}
				}
			},
		indicatorTopInset = contentPadding,
	) {
		LazyColumn(
			state = listState,
			contentPadding = PaddingValues(
				top = contentPadding.calculateTopPadding(),
				bottom = contentPadding.calculateBottomPadding(),
				start = 0.dp,
				end = 0.dp,
			),
			modifier = Modifier.fillMaxSize()
		) {

			itemsIndexed(
				items = items,
				key = { index, item ->
					when (item) {
						is QuickFilter -> "feed_filters"
						is FeedItem -> "feed_${item.id}"
						is UpdatedContentHeader -> "updates_header"
						is ListHeader -> "header_${item.getText(context)}_$index"
						is LoadingState -> "loading"
						is EmptyState -> "empty"
						else -> item.hashCode().toString()
					}
				},
				contentType = { _, item ->
					when (item) {
						is QuickFilter -> "feed_filter"
						is FeedItem -> "feed_item"
						is UpdatedContentHeader -> "updated_carousel"
						is ListHeader -> "list_header"
						else -> "feed_other"
					}
				},
			) { _, item ->
				when (item) {
					is QuickFilter -> {
						if (showCategoryFilterInline) {
							org.skepsun.kototoro.list.ui.compose.QuickFilterSection(
								quickFilter = item,
								onQuickFilterOptionClick = onQuickFilterOptionClick,
							)
						}
					}
					is FeedItem -> {
						FeedItemCard(
							item = item,
							onClick = { coverBounds -> onFeedItemClick(item, coverBounds) }
						)
					}
					is UpdatedContentHeader -> {
						// Here we render the horizontal carousel of updated contents
						UpdatedContentCarousel(
							header = item,
							prefs = carouselPrefs,
							onItemClick = onUpdatedContentItemClick,
							onMoreClick = { onUpdatedContentMoreClick(item) }
						)
					}
					is ListHeader -> {
						Text(
							text = item.getText(context)?.toString().orEmpty(),
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.onBackground,
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 16.dp, vertical = 12.dp)
						)
					}
					LoadingState -> {
						FeedLoadingState()
					}
					is EmptyState -> {
						FeedEmptyState(item)
					}
				}
			}
		}
	}
}

@Composable
private fun FeedLoadingState(
	modifier: Modifier = Modifier,
) {
	Box(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 24.dp, vertical = 32.dp),
		contentAlignment = Alignment.Center,
	) {
		KototoroLoadingIndicator()
	}
}

@Composable
private fun FeedEmptyState(
	item: EmptyState,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 24.dp, vertical = 32.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		if (item.icon != 0) {
			Icon(
				painter = painterResource(item.icon),
				contentDescription = null,
				modifier = Modifier.size(64.dp),
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Spacer(modifier = Modifier.height(12.dp))
		}
		val titleText = item.textPrimaryText?.toString()
		Text(
			text = titleText ?: stringResource(item.textPrimary),
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurface,
		)
		val subtitleText = item.textSecondaryText?.toString()
		if (item.textSecondary != 0 || !subtitleText.isNullOrBlank()) {
			Spacer(modifier = Modifier.height(8.dp))
			Text(
				text = subtitleText ?: stringResource(item.textSecondary),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}


