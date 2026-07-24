package org.skepsun.kototoro.settings.tracker.categories

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.list.domain.ListSortOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackerCategoriesConfigRoute(
	onDismissRequest: () -> Unit,
	viewModel: TrackerCategoriesConfigViewModel = hiltViewModel(),
) {
	val categories by viewModel.content.collectAsStateWithLifecycle()
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetState = sheetState,
		modifier = Modifier.fillMaxHeight(),
	) {
		KototoroTheme {
			TrackerCategoriesConfigContent(
				categories = categories,
				onCategoryClick = viewModel::toggleItem,
			)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackerCategoriesConfigContent(
	categories: List<FavouriteCategory>,
	onCategoryClick: (FavouriteCategory) -> Unit,
	modifier: Modifier = Modifier,
) {
	Scaffold(
		modifier = modifier.fillMaxSize(),
		containerColor = MaterialTheme.colorScheme.background,
		contentWindowInsets = WindowInsets(0, 0, 0, 0),
		topBar = {
			TopAppBar(
				title = { Text(text = stringResource(R.string.favourites_categories)) },
				windowInsets = WindowInsets(0, 0, 0, 0),
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
				),
			)
		},
	) { contentPadding ->
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				top = contentPadding.calculateTopPadding(),
				bottom = contentPadding.calculateBottomPadding(),
			),
		) {
			items(
				items = categories,
				key = { it.id },
				contentType = { "tracker_category" },
			) { category ->
				TrackerCategoryRow(
					category = category,
					onClick = { onCategoryClick(category) },
				)
			}
		}
	}
}

@Composable
private fun TrackerCategoryRow(
	category: FavouriteCategory,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = dimensionResource(R.dimen.chapter_list_item_height))
			.toggleable(
				value = category.isTrackingEnabled,
				role = Role.Checkbox,
				onValueChange = { onClick() },
			)
			.padding(horizontal = dimensionResource(R.dimen.screen_padding)),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = category.title,
			style = MaterialTheme.typography.bodyLarge,
			modifier = Modifier.weight(1f),
		)
		Checkbox(
			checked = category.isTrackingEnabled,
			onCheckedChange = null,
		)
	}
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun TrackerCategoriesConfigContentPreview() {
	KototoroTheme {
		TrackerCategoriesConfigContent(
			categories = listOf(
				FavouriteCategory(
					id = 1L,
					title = "Reading",
					sortKey = 0,
					order = ListSortOrder.NEWEST,
					createdAt = Instant.EPOCH,
					isTrackingEnabled = true,
					isVisibleInLibrary = true,
				),
				FavouriteCategory(
					id = 2L,
					title = "Plan to read",
					sortKey = 1,
					order = ListSortOrder.NEWEST,
					createdAt = Instant.EPOCH,
					isTrackingEnabled = false,
					isVisibleInLibrary = true,
				),
			),
			onCategoryClick = {},
		)
	}
}
