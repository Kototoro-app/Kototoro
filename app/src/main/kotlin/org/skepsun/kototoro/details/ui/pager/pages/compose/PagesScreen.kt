package org.skepsun.kototoro.details.ui.pager.pages.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.ui.compose.VerticalScrollbar
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.details.ui.compose.state.CompactDetailsPaneAnchor
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneState
import org.skepsun.kototoro.details.ui.compose.state.rememberDetailsPaneNestedScrollConnection
import org.skepsun.kototoro.details.ui.pager.pages.PageThumbnail
import org.skepsun.kototoro.details.ui.pager.pages.PageThumbnailPlaceholder
import org.skepsun.kototoro.list.ui.model.ListHeader
import kotlinx.coroutines.flow.distinctUntilChanged

private const val PageThumbnailAspectRatioMin = 0.35f
private const val PageThumbnailAspectRatioMax = 1f

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PageThumbnailCard(
	thumbnail: PageThumbnail,
	isSelected: Boolean,
	fitPreview: Boolean,
	aspectRatio: Float,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Card(
		modifier = modifier
			.fillMaxWidth()
			.aspectRatio(aspectRatio)
			.combinedClickable(
				onClick = onClick,
				onLongClick = onLongClick,
			),
		shape = RoundedCornerShape(8.dp),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceVariant,
		),
		border = if (isSelected) BorderStroke(4.dp, MaterialTheme.colorScheme.primary) else null,
	) {
		Box(modifier = Modifier.fillMaxSize()) {
			val context = LocalContext.current
			val model = remember(thumbnail.page) {
				ImageRequest.Builder(context)
					.data(thumbnail.page.toContentPage())
					.mangaSourceExtra(thumbnail.page.source)
					.build()
			}
			AsyncImage(
				model = model,
				contentDescription = "Page Thumbnail",
				contentScale = if (fitPreview) ContentScale.Fit else ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)

			Surface(
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(6.dp),
				shape = RoundedCornerShape(4.dp),
				color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
			) {
				Text(
					text = "${thumbnail.number}",
					style = MaterialTheme.typography.labelSmall,
					modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
				)
			}

			if (thumbnail.isCurrent) {
				Surface(
					modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
					shape = RoundedCornerShape(4.dp),
					color = MaterialTheme.colorScheme.primaryContainer,
				) {
					Icon(
						painter = painterResource(id = R.drawable.ic_current_chapter),
						contentDescription = "Current Page",
						tint = MaterialTheme.colorScheme.onPrimaryContainer,
						modifier = Modifier.size(16.dp).padding(2.dp),
					)
				}
			}

			if (isSelected) {
				Surface(
					modifier = Modifier.fillMaxSize(),
					color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
				) {
					Icon(
						imageVector = Icons.Default.CheckCircle,
						contentDescription = "Selected",
						tint = MaterialTheme.colorScheme.primaryContainer,
						modifier = Modifier
							.align(Alignment.Center)
							.size(48.dp),
					)
				}
			}
		}
	}
}

@Composable
fun PageThumbnailPlaceholderCard(
	aspectRatio: Float,
	modifier: Modifier = Modifier,
) {
	Card(
		modifier = modifier
			.fillMaxWidth()
			.aspectRatio(aspectRatio),
		shape = RoundedCornerShape(8.dp),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
		),
		border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
	) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				painter = painterResource(id = R.drawable.ic_images),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
				modifier = Modifier.size(32.dp),
			)
		}
	}
}

@Composable
fun PagesScreen(
	items: List<org.skepsun.kototoro.list.ui.model.ListModel>,
	gridColumns: Int,
	selectedItemIds: Set<Long>,
	fitPreview: Boolean = false,
	thumbnailAspectRatio: Float = 0.7f,
	emptyMessageResId: Int?,
	isLoading: Boolean,
	detailsPaneState: DetailsPaneState? = null,
	onLoadPrevious: () -> Unit = {},
	onLoadNext: () -> Unit = {},
	onVisiblePlaceholder: (Long) -> Unit = {},
	onItemClick: (PageThumbnail) -> Unit,
	onItemLongClick: (PageThumbnail) -> Unit,
	onSelectionActionClick: (Int) -> Unit,
	onClearSelection: () -> Unit,
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val listState = rememberLazyGridState()
    val activeDetailsPaneState by remember(detailsPaneState) {
        derivedStateOf {
            val state = detailsPaneState ?: return@derivedStateOf null
            if (state.anchor == CompactDetailsPaneAnchor.Full && listState.canScrollBackward) {
                null
            } else {
                state
            }
        }
    }
    val paneNestedScrollConnection = rememberDetailsPaneNestedScrollConnection(
        state = activeDetailsPaneState,
        canChildScrollBackward = { listState.canScrollBackward },
    )
    val paneNestedScrollModifier = remember(paneNestedScrollConnection) {
        if (paneNestedScrollConnection != null) {
            Modifier.nestedScroll(paneNestedScrollConnection)
        } else {
            Modifier
        }
    }
	val cardAspectRatio = thumbnailAspectRatio.coerceIn(PageThumbnailAspectRatioMin, PageThumbnailAspectRatioMax)
	Box(modifier = Modifier.fillMaxSize()) {
		if (isLoading) {
			CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
		} else if (items.isEmpty()) {
            emptyMessageResId?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
            }
		} else {
			LaunchedEffect(items) {
				val currentIndex = items.indexOfFirst { item ->
                    item is PageThumbnail && item.isCurrent
                }
				if (currentIndex >= 0 && listState.firstVisibleItemIndex == 0) {
					listState.scrollToItem(currentIndex)
				}
			}

			LaunchedEffect(items, isLoading) {
				snapshotFlow {
					if (isLoading) {
						VisibleLoadRequest()
					} else {
						val layoutInfo = listState.layoutInfo
						val visibleItems = layoutInfo.visibleItemsInfo
						val firstIndex = visibleItems.firstOrNull()?.index ?: return@snapshotFlow VisibleLoadRequest()
						val lastIndex = visibleItems.lastOrNull()?.index ?: return@snapshotFlow VisibleLoadRequest()
						val placeholderChapterId = visibleItems
							.asSequence()
							.mapNotNull { info -> items.getOrNull(info.index) as? PageThumbnailPlaceholder }
							.firstOrNull()
							?.chapterId
						VisibleLoadRequest(
							loadPrevious = firstIndex <= LOAD_MORE_THRESHOLD,
							loadNext = lastIndex >= layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD - 1,
							placeholderChapterId = placeholderChapterId,
						)
					}
				}.distinctUntilChanged().collect { request ->
					if (request.loadPrevious) {
						onLoadPrevious()
					}
					if (request.loadNext) {
						onLoadNext()
					}
					if (request.placeholderChapterId != null) {
						onVisiblePlaceholder(request.placeholderChapterId)
					}
				}
			}

			LazyVerticalGrid(
				state = listState,
				columns = GridCells.Fixed(gridColumns.coerceIn(2, 6)),
				contentPadding = PaddingValues(16.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
				modifier = Modifier
                    .fillMaxSize()
                    .then(paneNestedScrollModifier),
			) {
                items(
                    count = items.size,
                    key = { index ->
                        when (val item = items[index]) {
                            is PageThumbnail -> "page_${item.page.id}"
                            is PageThumbnailPlaceholder -> "placeholder_${item.chapterId}"
                            is ListHeader -> "header_${item.getText(context)}_$index"
                            else -> "item_${item::class.java.simpleName}_$index"
                        }
                    },
                    span = { index ->
                        if (items[index] is ListHeader) {
                            GridItemSpan(maxLineSpan)
                        } else {
                            GridItemSpan(1)
                        }
                    },
                ) { index ->
                    when (val item = items[index]) {
                        is PageThumbnail -> {
                            PageThumbnailCard(
                                thumbnail = item,
                                isSelected = selectedItemIds.contains(item.page.id),
                                fitPreview = fitPreview,
                                aspectRatio = cardAspectRatio,
                                onClick = {
                                    if (selectedItemIds.isNotEmpty()) {
                                        hapticFeedback.performSelectionHapticFeedback()
                                    }
                                    onItemClick(item)
                                },
                                onLongClick = { onItemLongClick(item) },
                            )
                        }

                        is PageThumbnailPlaceholder -> {
                            PageThumbnailPlaceholderCard(aspectRatio = cardAspectRatio)
                        }

                        is ListHeader -> {
                            Text(
                                text = item.getText(context)?.toString().orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
            }
            VerticalScrollbar(
                state = listState,
                labelProvider = { index -> "${index + 1}" },
            )
		}

		androidx.compose.animation.AnimatedVisibility(
			visible = selectedItemIds.isNotEmpty(),
			enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
			exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
			modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
		) {
			Surface(
				shape = RoundedCornerShape(16.dp),
				color = MaterialTheme.colorScheme.inverseSurface,
				contentColor = MaterialTheme.colorScheme.inverseOnSurface,
				modifier = Modifier.padding(16.dp).windowInsetsPadding(WindowInsets.safeDrawing),
			) {
				Row(
					modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						IconButton(onClick = onClearSelection) {
							Icon(imageVector = Icons.Default.Close, contentDescription = "Clear")
						}
						Text(
							text = "${selectedItemIds.size}",
							style = MaterialTheme.typography.titleMedium,
							modifier = Modifier.padding(start = 8.dp),
						)
					}
					Row {
						IconButton(onClick = { onSelectionActionClick(R.id.action_save) }) {
							Icon(painter = painterResource(id = R.drawable.ic_save_ok), contentDescription = "Save Page")
						}
					}
				}
			}
		}
	}
}

fun pagePreviewGridColumns(gridScale: Float): Int {
	return when {
		gridScale <= 0.6f -> 6
		gridScale <= 0.8f -> 5
		gridScale < 1f -> 4
		gridScale <= 1.2f -> 3
		else -> 2
	}
}

private data class VisibleLoadRequest(
	val loadPrevious: Boolean = false,
	val loadNext: Boolean = false,
	val placeholderChapterId: Long? = null,
)

private const val LOAD_MORE_THRESHOLD = 6
