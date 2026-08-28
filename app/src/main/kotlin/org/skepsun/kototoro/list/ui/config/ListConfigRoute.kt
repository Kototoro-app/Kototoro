package org.skepsun.kototoro.list.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
    // SideEffect (not LaunchedEffect): re-assert the section on EVERY
    // recomposition so this panel can never write into another section's
    // prefs, even if the VM instance was reused from a previously open sheet.
    SideEffect {
        viewModel.initialize(section)
    }

    val listMode by viewModel.listModeState.collectAsStateWithLifecycle(initialValue = ListMode.GRID)
    val gridSize by viewModel.gridSizeState.collectAsStateWithLifecycle(initialValue = 100)
    val railRows by viewModel.railRowsState.collectAsStateWithLifecycle(initialValue = null)
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
        supportsGridSizeSlider = pendingListMode == ListMode.GRID ||
            pendingListMode == ListMode.COMPACT_GRID ||
            // Home rails keep the slider visible for every display style so
            // all three sections expose grid size from both entry points
            // (section header sheet and the home paged display options).
            section == ListConfigSection.HomeHistory ||
            section == ListConfigSection.HomeUpdates ||
            section == ListConfigSection.HomeRecommendations,
        gridSize = pendingGridSize,
        onGridSizeChange = {
            pendingGridSize = it
            viewModel.updateGridSize(it)
        },
        railRows = railRows,
        onRailRowsChange = railRows?.let { rows ->
            { value: Int -> viewModel.updateRailRows(value) }
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
