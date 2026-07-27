package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.NavItem
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.settings.nav.model.NavItemConfigModel

@Composable
fun NavConfigScreen(
	configuredItems: List<NavItemConfigModel>,
	availableItems: List<NavItem>,
	canShowAddAction: Boolean,
	canAddAction: Boolean,
	onAddItem: (NavItem) -> Unit,
	onRemoveItem: (NavItem) -> Unit,
	onMoveUp: (Int) -> Unit,
	onMoveDown: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	var isAddDialogVisible by remember { mutableStateOf(false) }

	Surface(
		modifier = modifier.fillMaxSize(),
		color = MaterialTheme.colorScheme.background,
	) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
		LazyColumn(state = listState,
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(
				start = SettingsContentHorizontalPadding,
				end = SettingsContentHorizontalPadding,
				top = 20.dp,
				bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
			),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			item(key = "nav_config") {
				SettingsPreferenceSection(title = stringResource(R.string.main_screen_sections)) {
					configuredItems.forEachIndexed { index, item ->
						NavConfigPreferenceRow(
							item = item,
							canMoveUp = index > 0,
							canMoveDown = index < configuredItems.lastIndex,
							onMoveUp = { onMoveUp(index) },
							onMoveDown = { onMoveDown(index) },
							onRemove = { onRemoveItem(item.item) },
						)
						if (index != configuredItems.lastIndex || canShowAddAction) {
							SettingsSectionDivider()
						}
					}
					if (canShowAddAction) {
						SettingsActionPreference(
							title = stringResource(if (canAddAction) R.string.add else R.string.items_limit_exceeded),
							iconRes = R.drawable.ic_add,
							enabled = canAddAction,
							showChevron = false,
							onClick = { isAddDialogVisible = true },
						)
					}
				}
			}
		}
	}

	if (isAddDialogVisible) {
		SettingsAlertDialog(
			onDismissRequest = { isAddDialogVisible = false },
			title = stringResource(R.string.add),
			text = {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
				LazyColumn(state = listState,
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 360.dp),
				) {
					itemsIndexed(
						items = availableItems,
						key = { _, item -> item.name },
					) { index, item ->
						SettingsActionPreference(
							title = stringResource(item.title),
							iconRes = item.icon,
							showChevron = false,
							onClick = {
								onAddItem(item)
								isAddDialogVisible = false
							},
						)
						if (index != availableItems.lastIndex) {
							SettingsSectionDivider()
						}
					}
				}
			},
			confirmButton = {},
			dismissButton = {
				SettingsDialogActionButton(
					text = stringResource(android.R.string.cancel),
					onClick = { isAddDialogVisible = false },
				)
			},
		)
	}
}

@Composable
private fun NavConfigPreferenceRow(
	item: NavItemConfigModel,
	canMoveUp: Boolean,
	canMoveDown: Boolean,
	onMoveUp: () -> Unit,
	onMoveDown: () -> Unit,
	onRemove: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 20.dp, vertical = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(
			painter = rememberSafePainter(item.item.icon),
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Spacer(modifier = Modifier.size(16.dp))
		androidx.compose.foundation.layout.Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Text(
				text = stringResource(item.item.title),
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (item.disabledHintResId != 0) {
				Text(
					text = stringResource(item.disabledHintResId),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		IconButton(onClick = onMoveUp, enabled = canMoveUp) {
			Icon(
				imageVector = Icons.Filled.KeyboardArrowUp,
				contentDescription = stringResource(R.string.move_up),
			)
		}
		IconButton(onClick = onMoveDown, enabled = canMoveDown) {
			Icon(
				imageVector = Icons.Filled.KeyboardArrowDown,
				contentDescription = stringResource(R.string.move_down),
			)
		}
		IconButton(onClick = onRemove) {
			Icon(
				imageVector = Icons.Filled.Delete,
				contentDescription = stringResource(R.string.remove),
			)
		}
	}
}
