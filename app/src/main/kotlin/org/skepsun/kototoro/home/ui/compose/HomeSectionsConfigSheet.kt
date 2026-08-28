package org.skepsun.kototoro.home.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.list.ui.compose.DisplayModeChip
import org.skepsun.kototoro.list.ui.compose.GridSizeSlider
import org.skepsun.kototoro.list.ui.compose.RailRowsSelector

/**
 * Paged display-options sheet for the three home rails. Each tab configures
 * one rail (history / updates / recommendations) independently: list mode,
 * poster grid size and — for list modes — rows per rail page.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeSectionsConfigRoute(
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val prefs by settings.observeAsState(
        AppSettings.KEY_HOME_SECTION_LIST_MODE_HISTORY,
        AppSettings.KEY_HOME_SECTION_LIST_MODE_UPDATES,
        AppSettings.KEY_HOME_SECTION_LIST_MODE_RECOMMENDATIONS,
        AppSettings.KEY_HOME_SECTION_GRID_SIZE_HISTORY,
        AppSettings.KEY_HOME_SECTION_GRID_SIZE_UPDATES,
        AppSettings.KEY_HOME_SECTION_GRID_SIZE_RECOMMENDATIONS,
        AppSettings.KEY_HOME_SECTION_RAIL_ROWS_HISTORY,
        AppSettings.KEY_HOME_SECTION_RAIL_ROWS_UPDATES,
        AppSettings.KEY_HOME_SECTION_RAIL_ROWS_RECOMMENDATIONS,
    ) {
        HomeSectionsConfigPrefs(
            historyListMode = homeSectionListModeHistory,
            updatesListMode = homeSectionListModeUpdates,
            recommendationsListMode = homeSectionListModeRecommendations,
            historyGridSize = homeSectionGridSizeHistory,
            updatesGridSize = homeSectionGridSizeUpdates,
            recommendationsGridSize = homeSectionGridSizeRecommendations,
            historyRailRows = homeSectionRailRowsHistory,
            updatesRailRows = homeSectionRailRowsUpdates,
            recommendationsRailRows = homeSectionRailRowsRecommendations,
        )
    }
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tabTitles = listOf(
        stringResource(R.string.recent_history),
        stringResource(R.string.home_recent_updates),
        stringResource(R.string.suggestions),
    )
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = null,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        KototoroSheetSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = GlassDefaults.prominentStyle().copy(
                containerAlpha = 0.8f,
                minimumContainerAlpha = 0.6f,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SheetDragHandle(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Text(
                    text = stringResource(R.string.display_options),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = {
                                Text(
                                    text = title,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
                when (selectedTabIndex) {
                    0 -> HomeSectionConfigContent(
                        listMode = prefs.historyListMode,
                        gridSize = prefs.historyGridSize,
                        railRows = prefs.historyRailRows,
                        onListModeSelected = { settings.homeSectionListModeHistory = it },
                        onGridSizeChange = { settings.homeSectionGridSizeHistory = it },
                        onRailRowsChange = { settings.homeSectionRailRowsHistory = it },
                    )

                    1 -> HomeSectionConfigContent(
                        listMode = prefs.updatesListMode,
                        gridSize = prefs.updatesGridSize,
                        railRows = prefs.updatesRailRows,
                        onListModeSelected = { settings.homeSectionListModeUpdates = it },
                        onGridSizeChange = { settings.homeSectionGridSizeUpdates = it },
                        onRailRowsChange = { settings.homeSectionRailRowsUpdates = it },
                    )

                    else -> HomeSectionConfigContent(
                        listMode = prefs.recommendationsListMode,
                        gridSize = prefs.recommendationsGridSize,
                        railRows = prefs.recommendationsRailRows,
                        onListModeSelected = { settings.homeSectionListModeRecommendations = it },
                        onGridSizeChange = { settings.homeSectionGridSizeRecommendations = it },
                        onRailRowsChange = { settings.homeSectionRailRowsRecommendations = it },
                    )
                }
            }
        }
    }
}

@Immutable
private data class HomeSectionsConfigPrefs(
    val historyListMode: ListMode,
    val updatesListMode: ListMode,
    val recommendationsListMode: ListMode,
    val historyGridSize: Int,
    val updatesGridSize: Int,
    val recommendationsGridSize: Int,
    val historyRailRows: Int,
    val updatesRailRows: Int,
    val recommendationsRailRows: Int,
)

@Composable
private fun HomeSectionConfigContent(
    listMode: ListMode,
    gridSize: Int,
    railRows: Int,
    onListModeSelected: (ListMode) -> Unit,
    onGridSizeChange: (Int) -> Unit,
    onRailRowsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.list_mode),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DisplayModeChip(
                iconRes = R.drawable.ic_list,
                label = stringResource(R.string.list),
                selected = listMode == ListMode.LIST,
                onClick = { onListModeSelected(ListMode.LIST) },
                modifier = Modifier.weight(1f),
            )
            DisplayModeChip(
                iconRes = R.drawable.ic_list_detailed,
                label = stringResource(R.string.details),
                selected = listMode == ListMode.DETAILED_LIST,
                onClick = { onListModeSelected(ListMode.DETAILED_LIST) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DisplayModeChip(
                iconRes = R.drawable.ic_grid,
                label = stringResource(R.string.grid),
                selected = listMode == ListMode.GRID,
                onClick = { onListModeSelected(ListMode.GRID) },
                modifier = Modifier.weight(1f),
            )
            DisplayModeChip(
                iconRes = R.drawable.ic_grid,
                label = stringResource(R.string.compact_grid),
                selected = listMode == ListMode.COMPACT_GRID,
                onClick = { onListModeSelected(ListMode.COMPACT_GRID) },
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        GridSizeSlider(
            title = stringResource(R.string.grid_size),
            value = gridSize,
            onValueChange = onGridSizeChange,
        )

        if (listMode == ListMode.LIST || listMode == ListMode.DETAILED_LIST) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            RailRowsSelector(
                railRows = railRows,
                onRailRowsChange = onRailRowsChange,
            )
        }
    }
}

