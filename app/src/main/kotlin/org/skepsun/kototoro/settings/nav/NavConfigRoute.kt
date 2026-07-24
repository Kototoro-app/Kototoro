package org.skepsun.kototoro.settings.nav

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.settings.SettingsActivity
import org.skepsun.kototoro.settings.compose.NavConfigScreen

@Composable
fun NavConfigRoute(
	viewModel: NavConfigViewModel,
	modifier: Modifier = Modifier,
) {
	val configuredItems = viewModel.configuredItems.collectAsStateWithLifecycle().value
	val availableItems = viewModel.availableItems.collectAsStateWithLifecycle().value
	val canShowAddAction = viewModel.canShowAddAction.collectAsStateWithLifecycle().value
	val canAddAction = viewModel.canAddAction.collectAsStateWithLifecycle().value

	NavConfigScreen(
		configuredItems = configuredItems,
		availableItems = availableItems,
		canShowAddAction = canShowAddAction,
		canAddAction = canAddAction,
		onAddItem = viewModel::addItem,
		onRemoveItem = viewModel::removeItem,
		onMoveUp = viewModel::moveUp,
		onMoveDown = viewModel::moveDown,
		modifier = modifier,
	)
}
