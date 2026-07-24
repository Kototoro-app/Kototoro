package org.skepsun.kototoro.favourites.ui.categories.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.favourites.domain.model.Cover
import org.skepsun.kototoro.favourites.ui.categories.adapter.AllCategoriesListModel
import org.skepsun.kototoro.favourites.ui.categories.adapter.CategoryListModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun FavouriteCategoriesScreen(
	items: List<ListModel>,
	selectedIds: Set<Long>,
	onSelectionChanged: (Set<Long>) -> Unit,
	onAdd: () -> Unit,
	onOpenAll: () -> Unit,
	onOpenCategory: (FavouriteCategory) -> Unit,
	onEditCategory: (FavouriteCategory) -> Unit,
	onShowAllChanged: (Boolean) -> Unit,
	onSetVisible: (Set<Long>, Boolean) -> Unit,
	onDelete: (Set<Long>) -> Unit,
	onSaveOrder: (List<ListModel>) -> Unit,
) {
	val localItems = remember { mutableStateListOf<ListModel>() }
	val listState = rememberLazyListState()
	var pendingDelete by remember { mutableStateOf(false) }

	LaunchedEffect(items) {
		localItems.clear()
		localItems.addAll(items)
	}
	BackHandler(enabled = selectedIds.isNotEmpty()) {
		onSelectionChanged(emptySet())
	}

	val selectedCategories = items.filterIsInstance<CategoryListModel>()
		.filter { it.category.id in selectedIds }
	val canShow = selectedCategories.isNotEmpty() && selectedCategories.all { !it.category.isVisibleInLibrary }
	val canHide = selectedCategories.isNotEmpty() && selectedCategories.all { it.category.isVisibleInLibrary }

	Scaffold(
		modifier = Modifier.fillMaxSize(),
		contentWindowInsets = WindowInsets(0, 0, 0, 0),
		topBar = {
			TopAppBar(
				modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)),
				title = {
					Text(
						if (selectedIds.isEmpty()) stringResource(R.string.manage_categories) else selectedIds.size.toString(),
					)
				},
				navigationIcon = {
					IconButton(onClick = {
						if (selectedIds.isEmpty()) onOpenAll() else onSelectionChanged(emptySet())
					}) {
						Icon(
							painter = painterResource(
								if (selectedIds.isEmpty()) R.drawable.ic_arrow_forward else R.drawable.ic_clear_all,
							),
							contentDescription = null,
						)
					}
				},
				actions = {
					if (canShow) {
						IconButton(onClick = {
							onSetVisible(selectedIds, true)
							onSelectionChanged(emptySet())
						}) {
							Icon(painterResource(R.drawable.ic_eye), stringResource(R.string.show))
						}
					} else if (canHide) {
						IconButton(onClick = {
							onSetVisible(selectedIds, false)
							onSelectionChanged(emptySet())
						}) {
							Icon(painterResource(R.drawable.ic_eye_off), stringResource(R.string.hide))
						}
					}
					if (selectedIds.isNotEmpty()) {
						IconButton(onClick = { pendingDelete = true }) {
							Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.remove))
						}
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
			)
		},
		floatingActionButton = {
				if (selectedIds.isEmpty()) {
					FloatingActionButton(
						onClick = onAdd,
						modifier = Modifier
							.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
							.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
					) {
						Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add_new_category))
					}
				}
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { contentPadding ->
		LazyColumn(
			state = listState,
			modifier = Modifier
				.fillMaxSize()
				.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
				.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
			contentPadding = PaddingValues(
				top = contentPadding.calculateTopPadding() + dimensionResource(R.dimen.list_spacing_normal),
				bottom = contentPadding.calculateBottomPadding() + dimensionResource(R.dimen.list_spacing_normal),
				start = dimensionResource(R.dimen.list_spacing_normal),
				end = dimensionResource(R.dimen.list_spacing_normal),
			),
			verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.list_spacing_normal)),
		) {
			itemsIndexed(
				items = localItems,
				key = { _, item -> itemKey(item) },
				contentType = { _, item -> item::class },
			) { index, item ->
				when (item) {
					LoadingState -> Unit
					is EmptyState -> EmptyCategoryState(item)
					is AllCategoriesListModel -> AllCategoriesRow(
						item = item,
						onClick = { if (selectedIds.isEmpty()) onOpenAll() },
						onVisibilityChanged = { onShowAllChanged(!item.isVisible) },
					)
					is CategoryListModel -> CategoryRow(
						item = item,
						isSelected = item.category.id in selectedIds,
						actionsEnabled = selectedIds.isEmpty() && item.isActionsEnabled,
						onClick = {
							if (selectedIds.isEmpty()) onOpenCategory(item.category) else toggleSelection(item.category.id, selectedIds, onSelectionChanged)
						},
						onLongClick = { toggleSelection(item.category.id, selectedIds, onSelectionChanged) },
						onEdit = {
							if (selectedIds.isEmpty()) onEditCategory(item.category)
							else toggleSelection(item.category.id, selectedIds, onSelectionChanged)
						},
						onMove = { targetIndex ->
							if (selectedIds.isEmpty() && moveItem(localItems, index, targetIndex)) {
								onSaveOrder(localItems.toList())
							}
						},
						listState = listState,
					)
				}
			}
		}
	}

	if (pendingDelete) {
		AlertDialog(
			onDismissRequest = { pendingDelete = false },
			title = { Text(stringResource(R.string.remove_category)) },
			text = { Text(stringResource(R.string.categories_delete_confirm)) },
			confirmButton = {
				TextButton(onClick = {
					pendingDelete = false
					onDelete(selectedIds)
					onSelectionChanged(emptySet())
				}) { Text(stringResource(R.string.remove)) }
			},
			dismissButton = {
				TextButton(onClick = { pendingDelete = false }) {
					Text(stringResource(android.R.string.cancel))
				}
			},
		)
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryRow(
	item: CategoryListModel,
	isSelected: Boolean,
	actionsEnabled: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onEdit: () -> Unit,
	onMove: (Int) -> Unit,
	listState: LazyListState,
) {
	val shape = RoundedCornerShape(dimensionResource(R.dimen.list_selector_corner))
	val rowModifier = Modifier
		.fillMaxWidth()
		.clip(shape)
		.background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
		.combinedClickable(onClick = onClick, onLongClick = onLongClick)
		.padding(start = dimensionResource(androidx.appcompat.R.dimen.abc_action_bar_content_inset_material), top = 4.dp, bottom = 4.dp)

	Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
		CoverStack(item.covers, Modifier.height(dimensionResource(R.dimen.category_covers_height)).aspectRatio(13f / 18f))
		Column(
			modifier = Modifier.weight(1f).padding(start = dimensionResource(R.dimen.margin_normal), end = 4.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(item.category.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = if (item.mangaCount == 0) stringResource(R.string.empty) else pluralStringResource(R.plurals.items, item.mangaCount, item.mangaCount),
					style = MaterialTheme.typography.bodySmall,
					maxLines = 1,
					modifier = Modifier.weight(1f, fill = false),
				)
				if (item.category.isTrackingEnabled) Icon(painterResource(R.drawable.ic_notification), stringResource(R.string.check_for_new_chapters), Modifier.padding(horizontal = 4.dp).size(16.dp))
				if (!item.category.isVisibleInLibrary) Icon(painterResource(R.drawable.ic_eye_off), stringResource(R.string.hide_from_main_screen), Modifier.size(16.dp))
			}
		}
		if (actionsEnabled) {
			IconButton(onClick = onEdit) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit)) }
			Icon(
				painter = painterResource(R.drawable.ic_reorder_handle),
				contentDescription = stringResource(R.string.reorder),
				modifier = Modifier
					.size(48.dp)
					.pointerInput(item.category.id) {
						var currentIndex = -1
						detectDragGestures(
							onDragStart = {
								currentIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey(item) }?.index ?: -1
							},
							onDrag = { change, dragAmount ->
								// The drag gesture is handled locally; no parent consumes this change.
								if (currentIndex < 0) return@detectDragGestures
								val center = (listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }?.offset ?: 0) + dragAmount.y.toInt()
								val target = listState.layoutInfo.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - center) }?.index ?: return@detectDragGestures
								if (target != currentIndex && target > 0) {
									onMove(target)
									currentIndex = target
								}
							},
						)
					}
			)
		}
	}
}

@Composable
private fun AllCategoriesRow(
	item: AllCategoriesListModel,
	onClick: () -> Unit,
	onVisibilityChanged: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick).padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		CoverStack(item.covers, Modifier.height(dimensionResource(R.dimen.category_covers_height)).aspectRatio(13f / 18f))
		Column(modifier = Modifier.weight(1f).padding(start = dimensionResource(R.dimen.margin_normal))) {
			Text(stringResource(R.string.all_favourites), style = MaterialTheme.typography.bodyLarge)
			Text(
				if (item.mangaCount == 0) stringResource(R.string.empty) else pluralStringResource(R.plurals.items, item.mangaCount, item.mangaCount),
				style = MaterialTheme.typography.bodySmall,
				modifier = Modifier.padding(top = 4.dp),
			)
		}
		if (item.isActionsEnabled) {
			IconButton(onClick = onVisibilityChanged) {
				Icon(painterResource(if (item.isVisible) R.drawable.ic_eye else R.drawable.ic_eye_off), stringResource(R.string.show_all))
			}
		}
	}
}

@Composable
private fun CoverStack(covers: List<Cover>, modifier: Modifier) {
	Box(modifier = modifier) {
		StackCover(
			cover = covers.getOrNull(2),
			modifier = Modifier.fillMaxSize().padding(start = 24.dp, bottom = 12.dp).alpha(0.6f),
		)
		StackCover(
			cover = covers.getOrNull(1),
			modifier = Modifier.fillMaxSize().padding(start = 12.dp).alpha(0.3f),
		)
		StackCover(
			cover = covers.getOrNull(0),
			modifier = Modifier.fillMaxSize().padding(top = 12.dp),
		)
	}
}

@Composable
private fun StackCover(cover: Cover?, modifier: Modifier) {
	if (cover == null) {
		return
	}
	val context = LocalContext.current
	val request = remember(cover.url, cover.source) {
		ImageRequest.Builder(context)
			.data(cover.url)
			.mangaSourceExtra(cover.mangaSource)
			.build()
	}
	AsyncImage(
		model = request,
		contentDescription = null,
		contentScale = ContentScale.Crop,
		modifier = modifier
			.clip(RoundedCornerShape(4.dp))
			.background(MaterialTheme.colorScheme.secondaryContainer),
	)
}

@Composable
private fun EmptyCategoryState(item: EmptyState) {
	Column(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.margin_normal)),
	) {
		Icon(painterResource(item.icon), null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
		Text(item.textPrimaryText?.toString() ?: stringResource(item.textPrimary), style = MaterialTheme.typography.titleLarge)
		if (item.textSecondary != 0 || item.textSecondaryText != null) {
			Text(item.textSecondaryText?.toString() ?: stringResource(item.textSecondary), style = MaterialTheme.typography.bodyMedium)
		}
	}
}

private fun toggleSelection(id: Long, selectedIds: Set<Long>, onSelectionChanged: (Set<Long>) -> Unit) {
	onSelectionChanged(if (id in selectedIds) selectedIds - id else selectedIds + id)
}

private fun moveItem(items: MutableList<ListModel>, from: Int, to: Int): Boolean {
	if (from == to || from !in items.indices || to !in items.indices || items[from] !is CategoryListModel || items[to] !is CategoryListModel) return false
	val item = items.removeAt(from)
	items.add(to, item)
	return true
}

private fun itemKey(item: ListModel): String = when (item) {
	LoadingState -> "loading"
	is EmptyState -> "empty"
	is AllCategoriesListModel -> "all"
	is CategoryListModel -> "category:${item.category.id}"
	else -> item.hashCode().toString()
}

@Preview(showBackground = true)
@Composable
private fun FavouriteCategoriesScreenPreview() {
		FavouriteCategoriesScreen(
			items = emptyList(),
			selectedIds = emptySet(),
			onSelectionChanged = {},
			onAdd = {},
			onOpenAll = {},
			onOpenCategory = {},
			onEditCategory = {},
			onShowAllChanged = {},
			onSetVisible = { _, _ -> },
			onDelete = {},
			onSaveOrder = {},
		)
}
