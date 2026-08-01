package org.skepsun.kototoro.scrobbling.common.ui.selector.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.compose.CompactContentCoverShape
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingFooter
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent
import org.skepsun.kototoro.scrobbling.common.ui.selector.ScrobblingSelectorViewModel
import org.skepsun.kototoro.scrobbling.common.ui.selector.model.ScrobblerHint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrobblingSelectorDialog(
	viewModel: ScrobblingSelectorViewModel,
	onDismissRequest: () -> Unit,
	onRetry: (Throwable) -> Unit,
) {
	var isSearchExpanded by remember { mutableStateOf(false) }
	val focusManager = LocalFocusManager.current

	BackHandler(enabled = isSearchExpanded) {
		isSearchExpanded = false
		focusManager.clearFocus()
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.surface),
	) {
		TopAppBar(
			title = { Text(stringResource(R.string.tracking)) },
			navigationIcon = {
				IconButton(onClick = onDismissRequest) {
					Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
				}
			},
			actions = {
				IconButton(onClick = { isSearchExpanded = !isSearchExpanded }) {
					Icon(
						imageVector = if (isSearchExpanded) Icons.Default.Close else Icons.Default.Search,
						contentDescription = stringResource(R.string.search),
					)
				}
			},
		)

		if (isSearchExpanded) {
			ScrobblerSearchField(
				onSearch = viewModel::search,
				onClose = {
					isSearchExpanded = false
					focusManager.clearFocus()
				},
			)
		}

		ScrobblerTabs(viewModel)
		ScrobblerListContent(
			viewModel = viewModel,
			onRetry = onRetry,
			onRequestSearch = { isSearchExpanded = true },
		)
	}
}

@Composable
private fun ScrobblerSearchField(
	onSearch: (String) -> Unit,
	onClose: () -> Unit,
) {
	var query by remember { mutableStateOf("") }
	val focusManager = LocalFocusManager.current
	val submit = {
		if (query.length >= 3) {
			onSearch(query)
			query = ""
			onClose()
			focusManager.clearFocus()
		}
	}

	OutlinedTextField(
		value = query,
		onValueChange = { query = it },
		modifier = Modifier
			.fillMaxWidth()
			.padding(
				start = dimensionResource(R.dimen.list_spacing_normal),
				end = dimensionResource(R.dimen.list_spacing_normal),
				bottom = dimensionResource(R.dimen.list_spacing_small),
			),
		placeholder = { Text(stringResource(R.string.search)) },
		trailingIcon = {
				IconButton(onClick = submit) {
					Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
				}
			},
		singleLine = true,
		keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
		keyboardActions = KeyboardActions(onSearch = { submit() }),
	)
}

@Composable
private fun ScrobblerTabs(viewModel: ScrobblingSelectorViewModel) {
	val selectedIndex by viewModel.selectedScrobblerIndex.collectAsStateWithLifecycle()
	val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
	val scrobblers = viewModel.availableScrobblers
	val selectedService = scrobblers.getOrNull(selectedIndex)?.scrobblerService
	val requiresLogin = selectedIndex in scrobblers.indices && !viewModel.isScrobblerAuthorized(selectedIndex)

	if (scrobblers.isNotEmpty()) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			ScrollableTabRow(
				selectedTabIndex = selectedIndex,
				modifier = Modifier.weight(1f),
				edgePadding = dimensionResource(R.dimen.list_spacing_normal),
				divider = {},
				containerColor = Color.Transparent,
			) {
				scrobblers.forEachIndexed { index, scrobbler ->
					val title = stringResource(scrobbler.scrobblerService.titleResId)
					Tab(
						selected = selectedIndex == index,
						onClick = { if (!isLoading) viewModel.setScrobblerIndex(index) },
						text = {
							Text(
								text = if (viewModel.isScrobblerAuthorized(index)) {
									title
								} else {
									stringResource(
										R.string.scrobbler_search_requires_login_label,
										title,
										stringResource(R.string.filter_need_login),
									)
								},
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						},
						icon = {
							Icon(
								painter = rememberSafePainter(scrobbler.scrobblerService.iconResId),
								contentDescription = null,
							)
						},
					)
				}
			}

			IconButton(
				onClick = viewModel::onDoneClick,
				enabled = !isLoading,
				modifier = Modifier.padding(end = dimensionResource(R.dimen.toolbar_button_margin)),
			) {
				Icon(
					painter = rememberSafePainter(
						if (requiresLogin) R.drawable.ic_lock else R.drawable.ic_check,
					),
					contentDescription = stringResource(if (requiresLogin) R.string.sign_in else R.string.done),
				)
			}
		}
	}

	if (requiresLogin && selectedService != null) {
		Text(
			text = stringResource(
				R.string.scrobbler_search_auth_hint,
				stringResource(selectedService.titleResId),
			),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(
				start = dimensionResource(R.dimen.list_spacing_normal),
				top = dimensionResource(R.dimen.list_spacing_small),
				end = dimensionResource(R.dimen.list_spacing_normal),
				bottom = dimensionResource(R.dimen.list_spacing_small),
			),
		)
	}
}

@Composable
private fun ScrobblerListContent(
	viewModel: ScrobblingSelectorViewModel,
	onRetry: (Throwable) -> Unit,
	onRequestSearch: () -> Unit,
) {
	val items by viewModel.content.collectAsStateWithLifecycle()
	val selectedItemId by viewModel.selectedItemId.collectAsStateWithLifecycle()
	val listState = rememberLazyListState()
	val selectedIndex by viewModel.selectedScrobblerIndex.collectAsStateWithLifecycle()
	val shouldLoadMore by remember {
		derivedStateOf {
			val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
			listState.layoutInfo.totalItemsCount > 0 && lastVisible >= listState.layoutInfo.totalItemsCount - 2
		}
	}

	LaunchedEffect(selectedIndex) {
		listState.scrollToItem(0)
	}
	LaunchedEffect(shouldLoadMore) {
		if (shouldLoadMore) {
			viewModel.loadNextPage()
		}
	}

	LazyColumn(
		state = listState,
		modifier = Modifier
			.fillMaxSize()
			.navigationBarsPadding(),
		contentPadding = PaddingValues(
			start = dimensionResource(R.dimen.list_spacing_normal),
			top = dimensionResource(R.dimen.list_spacing_normal),
			end = dimensionResource(R.dimen.list_spacing_normal),
			bottom = dimensionResource(R.dimen.list_spacing_normal),
		),
		verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.list_spacing_normal)),
	) {
		items(
			items = items,
			key = ::listItemKey,
			contentType = { it::class },
		) { item ->
			when (item) {
				is ScrobblerContent -> ScrobblerContentItem(
					item = item,
					isSelected = item.id == selectedItemId,
					onClick = { viewModel.selectItem(item.id) },
				)
				is ScrobblerHint -> ScrobblerHintItem(
					item = item,
					onRetry = { error -> error?.let(onRetry) ?: onRequestSearch() },
				)
				is LoadingState -> LoadingItem()
				is LoadingFooter -> LoadingFooterItem()
			}
		}
	}
}

@Composable
private fun ScrobblerContentItem(
	item: ScrobblerContent,
	isSelected: Boolean,
	onClick: () -> Unit,
) {
	val context = LocalContext.current
	val border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.clip(MaterialTheme.shapes.small)
			.clickable(onClick = onClick),
		shape = MaterialTheme.shapes.small,
		border = border,
		color = MaterialTheme.colorScheme.surface,
	) {
		Row(
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			AsyncImage(
				model = ImageRequest.Builder(context)
					.data(item.cover)
					.crossfade(true)
					.build(),
				contentDescription = null,
				contentScale = ContentScale.Crop,
				placeholder = rememberSafePainter(R.drawable.ic_placeholder),
				error = rememberSafePainter(R.drawable.ic_placeholder),
				modifier = Modifier
					.size(40.dp)
					.clip(CompactContentCoverShape),
			)
			Column(
				modifier = Modifier
					.weight(1f)
					.padding(start = 16.dp, end = 8.dp),
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = item.name,
						style = MaterialTheme.typography.titleSmall,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f, fill = false),
					)
					if (item.isBestMatch) {
						Icon(
							painter = rememberSafePainter(R.drawable.ic_star_small),
							contentDescription = null,
							modifier = Modifier.padding(start = 4.dp).size(16.dp),
							tint = MaterialTheme.colorScheme.primary,
						)
					}
				}
				item.altName?.takeIf(String::isNotBlank)?.let { altName ->
					Text(
						text = altName,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}

@Composable
private fun ScrobblerHintItem(
	item: ScrobblerHint,
	onRetry: (Throwable?) -> Unit,
) {
	val context = LocalContext.current
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(dimensionResource(R.dimen.margin_normal)),
		verticalAlignment = Alignment.Top,
	) {
		Icon(
			painter = rememberSafePainter(item.icon),
			contentDescription = null,
			modifier = Modifier.size(120.dp),
			tint = Color.Unspecified,
		)
		Column(
			modifier = Modifier
				.weight(1f)
				.padding(start = dimensionResource(R.dimen.margin_small)),
		) {
			Text(text = stringResource(item.textPrimary), style = MaterialTheme.typography.titleMedium)
			val secondary = item.error?.getDisplayMessage(context.resources)
				?: item.textSecondary.takeIf { it != 0 }?.let { context.getString(it) }
			secondary?.let {
				Text(
					text = it,
					style = MaterialTheme.typography.bodyMedium,
					color = if (item.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = dimensionResource(R.dimen.margin_small)),
				)
			}
			if (item.actionStringRes != 0) {
				FilledTonalButton(
					onClick = { onRetry(item.error) },
					modifier = Modifier.padding(top = dimensionResource(R.dimen.margin_small)),
				) {
					Text(stringResource(item.actionStringRes))
				}
			}
		}
	}
}

@Composable
private fun LoadingItem() {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 64.dp),
		contentAlignment = Alignment.Center,
	) {
		CircularProgressIndicator()
	}
}

@Composable
private fun LoadingFooterItem() {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(8.dp),
		contentAlignment = Alignment.Center,
	) {
		CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
	}
}

private fun listItemKey(item: ListModel): String = when (item) {
	is ScrobblerContent -> "content-${item.id}"
	is ScrobblerHint -> "hint-${item.textPrimary}"
	is LoadingState -> "loading"
	is LoadingFooter -> "loading-footer"
	else -> item::class.qualifiedName.orEmpty()
}

@Preview(showBackground = true)
@Composable
private fun ScrobblerContentItemPreview() {
	KototoroTheme {
		ScrobblerContentItem(
			item = ScrobblerContent(
				id = 1,
				name = "Sample title",
				altName = "Alternative title",
				cover = null,
				url = "https://example.com",
				isBestMatch = true,
			),
			isSelected = true,
			onClick = {},
		)
	}
}
