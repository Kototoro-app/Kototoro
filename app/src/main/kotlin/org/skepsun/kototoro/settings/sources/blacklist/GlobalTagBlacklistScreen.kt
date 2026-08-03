package org.skepsun.kototoro.settings.sources.blacklist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GlobalTagBlacklistScreen(
	state: GlobalTagBlacklistUiState,
	query: String,
	onQueryChange: (String) -> Unit,
	onAddQuery: () -> Unit,
	onToggleTag: (String) -> Unit,
	onClear: () -> Unit,
	onNavigateUp: () -> Unit,
) {
	var aliasDetailsTag by remember { mutableStateOf<GlobalTagBlacklistItem?>(null) }
	var queryValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
		mutableStateOf(TextFieldValue(query, selection = TextRange(query.length)))
	}
	LaunchedEffect(query) {
		if (query != queryValue.text) {
			queryValue = TextFieldValue(query, selection = TextRange(query.length))
		}
	}
	Scaffold(
		modifier = Modifier.fillMaxSize(),
		containerColor = MaterialTheme.colorScheme.background,
		contentWindowInsets = WindowInsets(0, 0, 0, 0),
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.blacklisted_tags)) },
				navigationIcon = {
					IconButton(onClick = onNavigateUp) {
						Icon(
							imageVector = Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = stringResource(R.string.back),
						)
					}
				},
				actions = {
					if (state.selectedTags.isNotEmpty()) {
						TextButton(onClick = onClear) {
							Text(stringResource(R.string.clear))
						}
					}
				},
				windowInsets = WindowInsets.statusBars,
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
				),
			)
		},
	) { contentPadding ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(contentPadding)
				.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
				.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
		) {
			OutlinedTextField(
				value = queryValue,
				onValueChange = { value ->
					queryValue = value
					onQueryChange(value.text)
				},
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 12.dp),
				placeholder = { Text(stringResource(R.string.search)) },
				leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
				trailingIcon = {
					if (query.isNotEmpty()) {
						IconButton(onClick = {
							queryValue = TextFieldValue("")
							onQueryChange("")
						}) {
							Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear))
						}
					}
				},
				singleLine = true,
			)

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 16.dp, vertical = 4.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = stringResource(R.string.available),
					style = MaterialTheme.typography.titleSmall,
				)
				Text(
					text = stringResource(R.string.selected_count, state.selectedTags.size),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			if (query.isNotBlank()) {
				TextButton(
					onClick = onAddQuery,
					modifier = Modifier.padding(horizontal = 12.dp),
				) {
					Text(stringResource(R.string.add_tag_pattern, query.trim()))
				}
			}

			if (state.tags.isEmpty() && state.selectedItems.isEmpty()) {
				Text(
					text = stringResource(R.string.nothing_found),
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(24.dp),
				)
			} else {
				LazyVerticalGrid(
					columns = GridCells.Adaptive(minSize = 104.dp),
					contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalArrangement = Arrangement.spacedBy(4.dp),
					modifier = Modifier.fillMaxSize(),
				) {
					if (state.selectedItems.isNotEmpty()) {
						item(
							key = "selected_tags",
							span = { GridItemSpan(maxLineSpan) },
						) {
							Text(
								text = stringResource(R.string.selected_count, state.selectedItems.size),
								style = MaterialTheme.typography.titleSmall,
								color = MaterialTheme.colorScheme.primary,
								modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
							)
						}
						items(
							items = state.selectedItems,
							key = { tag -> "selected:${tag.key}" },
						) { tag ->
							BlacklistTagChip(
								tag = tag,
								selected = true,
								onClick = { onToggleTag(tag.key) },
								onLongClick = { aliasDetailsTag = tag },
							)
						}
					}
					state.tags.groupBy(GlobalTagBlacklistItem::category).forEach { (category, tags) ->
						item(
							key = "category:${category.id}",
							span = { GridItemSpan(maxLineSpan) },
						) {
							Text(
								text = category.displayName(),
								style = MaterialTheme.typography.titleSmall,
								color = MaterialTheme.colorScheme.primary,
								modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
							)
						}
						items(
							items = tags,
							key = GlobalTagBlacklistItem::key,
						) { tag ->
							BlacklistTagChip(
								tag = tag,
								selected = false,
								onClick = { onToggleTag(tag.key) },
								onLongClick = { aliasDetailsTag = tag },
							)
						}
					}
				}
			}
		}
	}

	aliasDetailsTag?.let { tag ->
		SettingsAlertDialog(
			title = stringResource(R.string.aliases_for_tag, tag.label),
			onDismissRequest = { aliasDetailsTag = null },
			confirmButton = {
				SettingsDialogActionButton(
					text = stringResource(R.string.close),
					onClick = { aliasDetailsTag = null },
				)
			},
			text = {
				SelectionContainer {
					Text(
						text = tag.aliases.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n")
							?: stringResource(R.string.no_aliases),
						style = MaterialTheme.typography.bodyMedium,
					)
				}
			},
		)
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlacklistTagChip(
	tag: GlobalTagBlacklistItem,
	selected: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	val showAliasesLabel = stringResource(R.string.show_aliases)
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 48.dp)
			.semantics { this.selected = selected }
			.combinedClickable(
				role = Role.Checkbox,
				onClick = onClick,
				onLongClickLabel = showAliasesLabel,
				onLongClick = onLongClick,
			),
		shape = MaterialTheme.shapes.small,
		color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
		contentColor = if (selected) {
			MaterialTheme.colorScheme.onSecondaryContainer
		} else {
			MaterialTheme.colorScheme.onSurfaceVariant
		},
		border = BorderStroke(
			width = 1.dp,
			color = if (selected) {
				MaterialTheme.colorScheme.secondaryContainer
			} else {
				MaterialTheme.colorScheme.outlineVariant
			},
		),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Column {
				Text(
					text = tag.label,
					style = MaterialTheme.typography.labelLarge,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				if (tag.aliases.isNotEmpty()) {
					Text(
						text = tag.aliases.joinToString(" · "),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			if (selected) {
				Icon(
					imageVector = Icons.Default.Clear,
					contentDescription = stringResource(R.string.remove),
					modifier = Modifier.size(FilterChipDefaults.IconSize),
				)
			}
		}
	}
}
