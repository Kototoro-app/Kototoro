package org.skepsun.kototoro.widget.shelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.widget.shelf.model.CategoryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShelfWidgetConfigScreen(
	categories: List<CategoryItem>,
	hasBackground: Boolean,
	onBackgroundChanged: (Boolean) -> Unit,
	onCategorySelected: (Long) -> Unit,
	onNavigateUp: () -> Unit,
	onDone: () -> Unit,
) {
	Scaffold(
		modifier = Modifier.fillMaxSize(),
		containerColor = MaterialTheme.colorScheme.background,
		contentWindowInsets = WindowInsets(0, 0, 0, 0),
		topBar = {
			TopAppBar(
				modifier = Modifier.windowInsetsPadding(
					WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
				),
				title = { Text(stringResource(R.string.manga_shelf)) },
				navigationIcon = {
					IconButton(onClick = onNavigateUp) {
						Icon(
							imageVector = Icons.Default.Close,
							contentDescription = stringResource(R.string.close),
						)
					}
				},
				actions = {
					Button(
						onClick = onDone,
						modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.toolbar_button_margin)),
					) {
						Text(stringResource(R.string.done))
					}
				},
				windowInsets = WindowInsets.statusBars,
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
				),
			)
		},
	) { contentPadding ->
		LazyColumn(
			modifier = Modifier
				.fillMaxSize()
				.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
				.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
			contentPadding = PaddingValues(
				top = contentPadding.calculateTopPadding(),
				bottom = contentPadding.calculateBottomPadding() + dimensionResource(R.dimen.list_spacing_normal),
			),
		) {
			item(key = "background") {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(
							top = dimensionResource(R.dimen.list_spacing_large),
							start = dimensionResource(R.dimen.screen_padding),
							end = dimensionResource(R.dimen.screen_padding),
						)
						.toggleable(
							value = hasBackground,
							onValueChange = onBackgroundChanged,
							role = Role.Switch,
						),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(
						text = stringResource(R.string.background),
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.weight(1f),
					)
					Switch(
						checked = hasBackground,
						onCheckedChange = null,
					)
				}
			}
			item(key = "categories-title") {
				Text(
					text = stringResource(R.string.favourites_categories),
					style = MaterialTheme.typography.titleSmall,
					maxLines = 1,
					overflow = TextOverflow.Clip,
					modifier = Modifier
						.fillMaxWidth()
						.padding(
							start = dimensionResource(R.dimen.screen_padding),
							top = dimensionResource(R.dimen.list_spacing_small),
							end = dimensionResource(R.dimen.screen_padding),
						),
				)
			}
			item(key = "categories-spacing") {
				Spacer(modifier = Modifier.height(dimensionResource(R.dimen.list_spacing_normal)))
			}
			items(
				items = categories,
				key = { it.id },
			) { category ->
				CategoryRow(
					item = category,
					onClick = { onCategorySelected(category.id) },
				)
			}
		}
	}
}

@Composable
private fun CategoryRow(
	item: CategoryItem,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.selectable(
				selected = item.isSelected,
				onClick = onClick,
				role = Role.RadioButton,
			)
			.padding(horizontal = dimensionResource(R.dimen.screen_padding)),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = item.name ?: stringResource(R.string.all_favourites),
			style = MaterialTheme.typography.bodyLarge,
			modifier = Modifier.weight(1f),
		)
		RadioButton(
			selected = item.isSelected,
			onClick = null,
		)
	}
}

@Preview(showBackground = true)
@Composable
private fun ShelfWidgetConfigScreenPreview() {
	KototoroTheme {
		ShelfWidgetConfigScreen(
			categories = listOf(
				CategoryItem(0L, null, true),
				CategoryItem(1L, "Reading", false),
			),
			hasBackground = true,
			onBackgroundChanged = {},
			onCategorySelected = {},
			onNavigateUp = {},
			onDone = {},
		)
	}
}
