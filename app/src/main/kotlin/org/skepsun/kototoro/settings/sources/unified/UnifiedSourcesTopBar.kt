package org.skepsun.kototoro.settings.sources.unified


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.CompactTopBarItemSpacing
import org.skepsun.kototoro.settings.compose.SettingsAlertDialog
import org.skepsun.kototoro.settings.compose.SettingsCompactSearchField
import org.skepsun.kototoro.settings.compose.SettingsDialogActionButton
import org.skepsun.kototoro.settings.compose.SettingsTopBarIconButton
import org.skepsun.kototoro.settings.compose.SettingsTopBarSurface
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import androidx.compose.ui.tooling.preview.Preview
import org.skepsun.kototoro.core.ui.theme.KototoroTheme

@Composable
private fun ToolbarSearchIconButton(
	active: Boolean,
	onClick: () -> Unit,
) {
	Box(contentAlignment = Alignment.TopEnd) {
		IconButton(
			onClick = onClick,
			modifier = Modifier.size(40.dp),
		) {
			Icon(
				Icons.Filled.Search,
				contentDescription = stringResource(R.string.search),
				modifier = Modifier.size(20.dp),
				tint = if (active) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
		}
		if (active) {
			Surface(
				modifier = Modifier.size(8.dp),
				shape = RoundedCornerShape(4.dp),
				color = MaterialTheme.colorScheme.primary,
				contentColor = MaterialTheme.colorScheme.onPrimary,
			) {}
		}
	}
}

@Composable
private fun ToolbarFilterIconButton(
	iconRes: Int,
	activeCount: Int,
	contentDescription: String,
	onClick: () -> Unit,
) {
	Box(contentAlignment = Alignment.TopEnd) {
		IconButton(
			onClick = onClick,
			modifier = Modifier.size(40.dp),
		) {
			Icon(
				painter = painterResource(iconRes),
				contentDescription = contentDescription,
				modifier = Modifier.size(20.dp),
				tint = if (activeCount > 0) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
		}
		if (activeCount > 0) {
			Surface(
				shape = RoundedCornerShape(8.dp),
				color = MaterialTheme.colorScheme.primary,
				contentColor = MaterialTheme.colorScheme.onPrimary,
			) {
				Text(
					text = activeCount.coerceAtMost(9).toString(),
					modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
					style = MaterialTheme.typography.labelSmall,
				)
			}
		}
	}
}

@Composable
fun UnifiedSourcesSearchTopBar(
	readyState: UnifiedSourcesUiState.Ready?,
	onNavigateUp: () -> Unit,
	onSearchQueryChange: (String) -> Unit,
	onLanguageFilterClick: () -> Unit,
	onMoreFiltersClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	BackHandler(onBack = onNavigateUp)
	val tokens = LocalInterfaceStyleTokens.current
	SettingsTopBarSurface {
		Row(
			modifier = modifier
				.fillMaxWidth()
				.height(tokens.secondaryTopBarHeight),
			verticalAlignment = Alignment.CenterVertically,
		) {
			SettingsTopBarIconButton(onClick = onNavigateUp) {
				Icon(
					imageVector = Icons.AutoMirrored.Filled.ArrowBack,
					contentDescription = stringResource(android.R.string.cancel),
					modifier = Modifier.size(tokens.topBarIconSize),
				)
			}
			Spacer(modifier = Modifier.width(CompactTopBarItemSpacing))
			SettingsCompactSearchField(
				query = readyState?.filters?.query.orEmpty(),
				onQueryChange = onSearchQueryChange,
				modifier = Modifier.weight(1f),
				autofocus = true,
			)
			Spacer(modifier = Modifier.width(CompactTopBarItemSpacing))
			ToolbarFilterIconButton(
				iconRes = R.drawable.ic_language,
				activeCount = readyState?.filters?.languages?.size ?: 0,
				contentDescription = stringResource(R.string.filter_extensions_by_language),
				onClick = onLanguageFilterClick,
			)
			ToolbarFilterIconButton(
				iconRes = R.drawable.ic_filter_menu,
				activeCount = readyState?.filters?.otherFilterCount() ?: 0,
				contentDescription = stringResource(R.string.more_filters),
				onClick = onMoreFiltersClick,
			)
		}
	}
}

@Composable
fun UnifiedSourcesToolbarActions(
	readyState: UnifiedSourcesUiState.Ready?,
	onSearchClick: () -> Unit,
	onLanguageFilterClick: () -> Unit,
	onMoreFiltersClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.End,
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (readyState != null) {
			ToolbarSearchIconButton(
				active = readyState.filters.query.isNotBlank(),
				onClick = onSearchClick,
			)
			ToolbarFilterIconButton(
				iconRes = R.drawable.ic_language,
				activeCount = readyState.filters.languages.size,
				contentDescription = stringResource(R.string.filter_extensions_by_language),
				onClick = onLanguageFilterClick,
			)
			ToolbarFilterIconButton(
				iconRes = R.drawable.ic_filter_menu,
				activeCount = readyState.filters.otherFilterCount(),
				contentDescription = stringResource(R.string.more_filters),
				onClick = onMoreFiltersClick,
			)
		}
	}
}

@Composable
internal fun UnifiedFilterGroupDialog(
	title: String,
	onDismiss: () -> Unit,
	onClear: () -> Unit,
	content: @Composable () -> Unit,
) {
	SettingsAlertDialog(
		title = title,
		onDismissRequest = onDismiss,
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
				content()
			}
		},
		confirmButton = {
				SettingsDialogActionButton(
					text = stringResource(R.string.done),
					onClick = onDismiss,
				)
			},
			dismissButton = {
				SettingsDialogActionButton(
					text = stringResource(R.string.clear),
					onClick = onClear,
				)
			},
	)
}

@Preview(showBackground = true)
@Composable
private fun UnifiedSourcesSearchTopBarPreview() {
    KototoroTheme {
        UnifiedSourcesSearchTopBar(
            readyState = null,
            onNavigateUp = {},
            onSearchQueryChange = {},
            onLanguageFilterClick = {},
            onMoreFiltersClick = {},
        )
    }
}
