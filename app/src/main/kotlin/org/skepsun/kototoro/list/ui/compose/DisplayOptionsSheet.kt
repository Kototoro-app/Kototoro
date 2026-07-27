package org.skepsun.kototoro.list.ui.compose

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuText

private const val DISPLAY_OPTIONS_SHEET_TAG = "DisplayOptionsSheet"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayOptionsSheet(
    supportsDisplayModeMenu: Boolean,
    currentListMode: ListMode,
    onListModeSelected: (ListMode) -> Unit,
    supportsGridSizeSlider: Boolean,
    gridSize: Int,
    onGridSizeChange: (Int) -> Unit,
    sortOrders: List<ListSortOrder> = emptyList(),
    selectedSortOrder: ListSortOrder? = null,
    onSortOrderSelected: (ListSortOrder) -> Unit = {},
    supportsGrouping: Boolean = false,
    isGroupingAvailable: Boolean = false,
    isGroupingEnabled: Boolean = false,
    onGroupingEnabledChange: (Boolean) -> Unit = {},
    extraContent: (@Composable () -> Unit)? = null,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(context.applicationContext) {
        org.skepsun.kototoro.core.prefs.AppSettings(context.applicationContext)
    }
    val showExtraInfo by settings.observeAsState(
        org.skepsun.kototoro.core.prefs.AppSettings.KEY_SHOW_EXTRA_INFO_ON_CARDS,
    ) { showExtraInfoOnCards }
    if (BuildConfig.DEBUG) {
        DisposableEffect(Unit) {
            Log.d(
                DISPLAY_OPTIONS_SHEET_TAG,
                "shown supportsDisplayModeMenu=$supportsDisplayModeMenu " +
                    "supportsGridSizeSlider=$supportsGridSizeSlider sortOrders=${sortOrders.size} " +
                    "supportsGrouping=$supportsGrouping extraContent=${extraContent != null} " +
                    "currentListMode=$currentListMode gridSize=$gridSize",
            )
            onDispose {
                Log.d(DISPLAY_OPTIONS_SHEET_TAG, "disposed")
            }
        }
        LaunchedEffect(sheetState.currentValue) {
            Log.d(DISPLAY_OPTIONS_SHEET_TAG, "sheetState currentValue=${sheetState.currentValue}")
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(0.dp),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        DebugBoundsBox(
            label = "modal_content_slot",
            modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    if (supportsDisplayModeMenu) {
                        Text(
                            text = stringResource(R.string.list_mode),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            DisplayModeChip(
                                iconRes = R.drawable.ic_list,
                                label = stringResource(R.string.list),
                                selected = currentListMode == ListMode.LIST,
                                onClick = { onListModeSelected(ListMode.LIST) },
                                modifier = Modifier.weight(1f)
                            )
                            DisplayModeChip(
                                iconRes = R.drawable.ic_list_detailed,
                                label = stringResource(R.string.details),
                                selected = currentListMode == ListMode.DETAILED_LIST,
                                onClick = { onListModeSelected(ListMode.DETAILED_LIST) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            DisplayModeChip(
                                iconRes = R.drawable.ic_grid,
                                label = stringResource(R.string.grid),
                                selected = currentListMode == ListMode.GRID,
                                onClick = { onListModeSelected(ListMode.GRID) },
                                modifier = Modifier.weight(1f)
                            )
                            DisplayModeChip(
                                iconRes = R.drawable.ic_grid,
                                label = stringResource(R.string.compact_grid),
                                selected = currentListMode == ListMode.COMPACT_GRID,
                                onClick = { onListModeSelected(ListMode.COMPACT_GRID) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (supportsGridSizeSlider) {
                        if (supportsDisplayModeMenu) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                        GridSizeSlider(
                            title = stringResource(R.string.grid_size),
                            value = gridSize,
                            onValueChange = onGridSizeChange,
                        )
                    }

                    if (sortOrders.isNotEmpty()) {
                        if (supportsDisplayModeMenu || supportsGridSizeSlider) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                        SortOrderSection(
                            sortOrders = sortOrders,
                            selectedSortOrder = selectedSortOrder,
                            onSortOrderSelected = onSortOrderSelected,
                        )
                    }

                    if (supportsGrouping) {
                        if (supportsDisplayModeMenu || supportsGridSizeSlider || sortOrders.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                        GroupingSection(
                            enabled = isGroupingEnabled,
                            available = isGroupingAvailable,
                            onEnabledChange = onGroupingEnabledChange,
                        )
                    }

                    if (supportsDisplayModeMenu || supportsGridSizeSlider || sortOrders.isNotEmpty() || supportsGrouping) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.show_extra_info_on_cards),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.show_extra_info_on_cards_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = showExtraInfo,
                            onCheckedChange = { settings.showExtraInfoOnCards = it },
                        )
                    }

                    extraContent?.let {
                        if (supportsDisplayModeMenu || supportsGridSizeSlider || sortOrders.isNotEmpty() || supportsGrouping) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                        it()
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugBoundsBox(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var lastBounds by remember { mutableStateOf<String?>(null) }
    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            if (!BuildConfig.DEBUG) return@onGloballyPositioned
            val bounds = coordinates.boundsInWindow()
            val message = "$label size=${coordinates.size.width}x${coordinates.size.height} " +
                "window=[${bounds.left},${bounds.top} - ${bounds.right},${bounds.bottom}]"
            if (message != lastBounds) {
                lastBounds = message
                Log.d(DISPLAY_OPTIONS_SHEET_TAG, message)
            }
        },
        content = content,
    )
}

@Composable
private fun SortOrderSection(
    sortOrders: List<ListSortOrder>,
    selectedSortOrder: ListSortOrder?,
    onSortOrderSelected: (ListSortOrder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOrder = selectedSortOrder ?: sortOrders.firstOrNull()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.sort_order),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            AssistChip(
                onClick = { expanded = true },
                label = {
                    Text(
                        text = selectedOrder?.let { stringResource(it.titleResId) }.orEmpty(),
                        maxLines = 1,
                    )
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_sort),
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more),
                        contentDescription = null,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    trailingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )
            GlassDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                sortOrders.forEach { order ->
                    CompactDropdownMenuItem(
                        text = { CompactDropdownMenuText(stringResource(order.titleResId)) },
                        onClick = {
                            expanded = false
                            onSortOrderSelected(order)
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(
                                    if (order == selectedOrder) R.drawable.ic_check else R.drawable.ic_sort,
                                ),
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupingSection(
    enabled: Boolean,
    available: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.group_by),
                style = MaterialTheme.typography.bodyMedium,
                color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            enabled = available,
            onCheckedChange = onEnabledChange,
        )
    }
}

@Composable
private fun DisplayModeChip(
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = {
            Icon(
                painter = painterResource(if (selected) R.drawable.ic_check else iconRes),
                contentDescription = null,
            )
        },
        modifier = modifier,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
            } else {
                Color.Transparent
            },
            labelColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun GridSizeSlider(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val currentValue = sliderValue.toInt().coerceIn(50, 150)

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$currentValue%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        KototoroSlider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                onValueChange(it.toInt().coerceIn(50, 150))
            },
            valueRange = 50f..150f,
            steps = 19,
        )
    }
}
