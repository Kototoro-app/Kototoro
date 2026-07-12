package org.skepsun.kototoro.details.ui.pager.bookmarks.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.details.ui.compose.state.CompactDetailsPaneAnchor
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneState
import org.skepsun.kototoro.details.ui.compose.state.rememberDetailsPaneNestedScrollConnection
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.list.ui.model.ListHeader

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BookmarkCard(
	bookmark: Bookmark,
	isSelected: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Card(
		modifier = modifier
			.fillMaxWidth()
			.aspectRatio(0.7f)
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
			AsyncImage(
				model = bookmark.toContentPage() ?: bookmark.imageUrl,
				contentDescription = "Bookmark Thumbnail",
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
			if (bookmark.percent > 0) {
				CircularProgressIndicator(
					progress = { bookmark.percent },
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(8.dp)
						.size(24.dp),
					color = MaterialTheme.colorScheme.primary,
					trackColor = Color.Black.copy(alpha = 0.5f),
				)
			}

			Surface(
				modifier = Modifier
					.align(Alignment.BottomStart)
					.padding(4.dp),
				shape = RoundedCornerShape(4.dp),
				color = Color.Black.copy(alpha = 0.6f),
			) {
				Text(
					text = "P.${bookmark.page + 1}",
					style = MaterialTheme.typography.labelSmall,
					color = Color.White,
					modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
				)
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
fun BookmarksScreen(
	items: List<org.skepsun.kototoro.list.ui.model.ListModel>,
	gridMinSize: Dp,
	selectedItemIds: Set<Long>,
	detailsPaneState: DetailsPaneState? = null,
	onItemClick: (Bookmark) -> Unit,
	onItemLongClick: (Bookmark) -> Unit,
	onSelectionActionClick: (Int) -> Unit,
	onClearSelection: () -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current

    val gridState = rememberLazyGridState()
    val activeDetailsPaneState by remember(detailsPaneState) {
        derivedStateOf {
            val state = detailsPaneState ?: return@derivedStateOf null
            if (state.anchor == CompactDetailsPaneAnchor.Full && gridState.canScrollBackward) {
                null
            } else {
                state
            }
        }
    }
    val paneNestedScrollConnection = rememberDetailsPaneNestedScrollConnection(
        state = activeDetailsPaneState,
        canChildScrollBackward = { gridState.canScrollBackward },
    )
    val paneNestedScrollModifier = remember(paneNestedScrollConnection) {
        if (paneNestedScrollConnection != null) {
            Modifier.nestedScroll(paneNestedScrollConnection)
        } else {
            Modifier
        }
    }
	Box(modifier = Modifier.fillMaxSize()) {
		if (items.isEmpty()) {
			Text(
				text = "No bookmarks",
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.align(Alignment.Center),
			)
		} else {
			LazyVerticalGrid(
                state = gridState,
				columns = GridCells.Adaptive(minSize = gridMinSize),
				contentPadding = PaddingValues(16.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
				modifier = Modifier
                    .fillMaxSize()
                    .then(paneNestedScrollModifier),
			) {
				items.forEach { item ->
					when (item) {
						is ListHeader -> {
							item(
								key = "header_${item.hashCode()}",
								span = { GridItemSpan(maxLineSpan) },
							) {
								val context = LocalContext.current
								Text(
									text = item.getText(context)?.toString().orEmpty(),
									style = MaterialTheme.typography.titleSmall,
									color = MaterialTheme.colorScheme.primary,
									modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
								)
							}
						}
						is Bookmark -> {
							item(key = item.pageId) {
								BookmarkCard(
									bookmark = item,
									isSelected = selectedItemIds.contains(item.pageId),
									onClick = {
                                        if (selectedItemIds.isNotEmpty()) {
                                            hapticFeedback.performSelectionHapticFeedback()
                                        }
                                        onItemClick(item)
                                    },
									onLongClick = { onItemLongClick(item) },
								)
							}
						}
						else -> {}
					}
				}
			}
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
						IconButton(onClick = { onSelectionActionClick(R.id.action_delete) }) {
							Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete")
						}
					}
				}
			}
		}
	}
}
