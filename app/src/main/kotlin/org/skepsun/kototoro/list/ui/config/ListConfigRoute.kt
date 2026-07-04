package org.skepsun.kototoro.list.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.ui.compose.DisplayOptionsSheet

@Composable
fun ListConfigRoute(
    section: ListConfigSection,
    onDismissRequest: () -> Unit,
    viewModel: ListConfigViewModel = hiltViewModel(key = "list-config-${section.hashCode()}"),
) {
    LaunchedEffect(section) {
        viewModel.initialize(section)
    }

	val listMode by viewModel.listModeState.collectAsStateWithLifecycle(initialValue = ListMode.GRID)
	val gridSize by viewModel.gridSizeState.collectAsStateWithLifecycle(initialValue = 100)
	val sortOrders by viewModel.sortOrdersState.collectAsStateWithLifecycle(initialValue = emptyList())
	val selectedSortOrder by viewModel.selectedSortOrderState.collectAsStateWithLifecycle(initialValue = null)
	val supportsGrouping by viewModel.supportsGroupingState.collectAsStateWithLifecycle(initialValue = false)
	val isGroupingAvailable by viewModel.isGroupingAvailableState.collectAsStateWithLifecycle(initialValue = false)
	val isGroupingEnabled by viewModel.isGroupingEnabledState.collectAsStateWithLifecycle(initialValue = false)

	var pendingListMode by remember(section) { mutableStateOf(listMode) }
	var pendingGridSize by remember(section) { mutableIntStateOf(gridSize) }
	var pendingSelectedSortOrder by remember(section) { mutableStateOf(selectedSortOrder) }
	var pendingGroupingEnabled by remember(section) { mutableStateOf(isGroupingEnabled) }

	LaunchedEffect(listMode) {
		pendingListMode = listMode
	}
	LaunchedEffect(gridSize) {
		pendingGridSize = gridSize
	}
	LaunchedEffect(selectedSortOrder) {
		pendingSelectedSortOrder = selectedSortOrder
	}
	LaunchedEffect(isGroupingEnabled) {
		pendingGroupingEnabled = isGroupingEnabled
	}

	val effectiveGroupingAvailable = when (section) {
		ListConfigSection.History -> pendingSelectedSortOrder?.isGroupingSupported() == true
		ListConfigSection.Updated -> true
		else -> isGroupingAvailable
	}

    DisplayOptionsSheet(
        supportsDisplayModeMenu = true,
        currentListMode = pendingListMode,
        onListModeSelected = {
			pendingListMode = it
			viewModel.updateListMode(it)
		},
        supportsGridSizeSlider = pendingListMode == ListMode.GRID || pendingListMode == ListMode.COMPACT_GRID,
        gridSize = pendingGridSize,
        onGridSizeChange = {
			pendingGridSize = it
			viewModel.updateGridSize(it)
		},
        sortOrders = sortOrders,
        selectedSortOrder = pendingSelectedSortOrder,
        onSortOrderSelected = { order: ListSortOrder ->
			pendingSelectedSortOrder = order
			viewModel.setSortOrder(order)
		},
        supportsGrouping = supportsGrouping,
        isGroupingAvailable = effectiveGroupingAvailable,
        isGroupingEnabled = pendingGroupingEnabled,
        onGroupingEnabledChange = {
			pendingGroupingEnabled = it
			viewModel.updateGroupingEnabled(it)
		},
        onDismissRequest = onDismissRequest,
    )
}
