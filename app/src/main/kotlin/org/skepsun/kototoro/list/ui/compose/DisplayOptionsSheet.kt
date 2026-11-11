package org.skepsun.kototoro.list.ui.compose

import android.util.Log
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.compose.gridPosterCardStyle
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.adaptive.tvSliderDpad
import org.skepsun.kototoro.core.ui.adaptive.tvTakeInitialFocus
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.list.domain.ListSortOrder

private const val DISPLAY_OPTIONS_SHEET_TAG = "DisplayOptionsSheet"

/** Scrim of the display-options sheets: just enough to mark the sheet as modal, light enough to judge changes. */
internal val DisplayOptionsScrimColor = Color.Black.copy(alpha = 0.08f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayOptionsSheet(
    supportsDisplayModeMenu: Boolean,
    // Favourites only: lets the user silence the "sources may be broken" banner.
    supportsSourceHealthToggle: Boolean = false,
    currentListMode: ListMode,
    onListModeSelected: (ListMode) -> Unit,
    supportsGridSizeSlider: Boolean,
    gridSize: Int,
    onGridSizeChange: (Int) -> Unit,
    // Off for surfaces whose grid does not span the window (search's wide
    // split layout would make a window-width estimate visibly wrong).
    supportsGridColumnsHint: Boolean = true,
    sortOrders: List<ListSortOrder> = emptyList(),
    selectedSortOrder: ListSortOrder? = null,
    onSortOrderSelected: (ListSortOrder) -> Unit = {},
    supportsGrouping: Boolean = false,
    isGroupingAvailable: Boolean = false,
    isGroupingEnabled: Boolean = false,
    onGroupingEnabledChange: (Boolean) -> Unit = {},
    // Rows-per-page for the home list-mode rails; null hides the selector
    // (non-home sections don't page their rails).
    railRows: Int? = null,
    onRailRowsChange: ((Int) -> Unit)? = null,
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
    val showSourceHealth by settings.observeAsState(
        org.skepsun.kototoro.core.prefs.AppSettings.KEY_SOURCE_HEALTH_BANNER,
    ) { isSourceHealthBannerEnabled }
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
        // Every option applies live; a light scrim keeps the list behind readable while adjusting.
        scrimColor = DisplayOptionsScrimColor,
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
                // The sheet covers the page the shell backdrop samples, so a glass slider thumb here would show
                // that page through the sheet; draw the solid thumb instead.
                CompositionLocalProvider(LocalLiquidGlassBackdrop provides null) {
                    Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvTakeInitialFocus()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp)
                                .padding(bottom = 16.dp, top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SheetDragHandle(
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        // The extra-info toggle rides on the title row instead of taking a line of its own;
                        // the cards behind the sheet show what it does.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.display_options),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (supportsSourceHealthToggle) {
                                FilterChip(
                                    selected = showSourceHealth,
                                    onClick = { settings.isSourceHealthBannerEnabled = !showSourceHealth },
                                    label = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_alert_outline),
                                            contentDescription = stringResource(R.string.migration_health_banner_setting),
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    },
                                    modifier = Modifier.tvFocusable(shape = CircleShape, addFocusTarget = false),
                                )
                            }
                            FilterChip(
                                selected = showExtraInfo,
                                onClick = { settings.showExtraInfoOnCards = !showExtraInfo },
                                label = {
                                    Text(
                                        text = stringResource(R.string.show_extra_info_on_cards),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingIcon = if (showExtraInfo) {
                                    {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check),
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    }
                                } else null,
                                modifier = Modifier
                                    .widthIn(max = 200.dp)
                                    .tvFocusable(shape = CircleShape, addFocusTarget = false),
                            )
                        }

                        if (supportsDisplayModeMenu) {
                            DisplayModeSelector(
                                currentListMode = currentListMode,
                                onListModeSelected = onListModeSelected,
                            )
                        }

                        if (supportsGridSizeSlider) {
                            GridSizeSlider(
                                title = stringResource(R.string.grid_size),
                                value = gridSize,
                                onValueChange = onGridSizeChange,
                                columnsHint = resolveGridColumnsHint(
                                    enabled = supportsGridColumnsHint,
                                    listMode = currentListMode,
                                    gridSize = gridSize,
                                ),
                            )
                        }

                        if (railRows != null && onRailRowsChange != null &&
                            (currentListMode == ListMode.LIST || currentListMode == ListMode.DETAILED_LIST)
                        ) {
                            RailRowsSelector(
                                railRows = railRows,
                                onRailRowsChange = onRailRowsChange,
                            )
                        }

                        if (sortOrders.isNotEmpty()) {
                            SortOrderSection(
                                sortOrders = sortOrders,
                                selectedSortOrder = selectedSortOrder,
                                onSortOrderSelected = onSortOrderSelected,
                            )
                        }

                        if (supportsGrouping) {
                            GroupingSection(
                                enabled = isGroupingEnabled,
                                available = isGroupingAvailable,
                                onEnabledChange = onGroupingEnabledChange,
                            )
                        }

                        extraContent?.let {
                            it()
                        }
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
    SortOrderControl(
        sortOrders = sortOrders,
        selectedSortOrder = selectedSortOrder,
        onSortOrderSelected = onSortOrderSelected,
        modifier = Modifier.fillMaxWidth(),
    )
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
            modifier = Modifier.tvFocusable(
                enabled = available,
                shape = CircleShape,
                addFocusTarget = false,
            ),
        )
    }
}

/** Android's resources and TV focus around the shared mode buttons (core-ui DisplayModeOptions). */
@Composable
internal fun DisplayModeSelector(
    currentListMode: ListMode,
    onListModeSelected: (ListMode) -> Unit,
) {
    val labels = androidx.compose.ui.platform.LocalContext.current.resources.getStringArray(R.array.list_modes)
    DisplayModeOptions(
        currentListMode = currentListMode,
        onListModeSelected = onListModeSelected,
        label = { labels[it.ordinal] },
        // Unselected modes show only their icon, so each mode needs one of its own.
        icon = { mode ->
            painterResource(
                when (mode) {
                    ListMode.LIST -> R.drawable.ic_list
                    ListMode.DETAILED_LIST -> R.drawable.ic_list_detailed
                    ListMode.GRID -> R.drawable.ic_grid
                    ListMode.COMPACT_GRID -> R.drawable.ic_grid_compact
                    ListMode.MOVIE_GRID -> R.drawable.ic_content_video
                    ListMode.COMPACT_MOVIE_GRID -> R.drawable.ic_movie_compact
                },
            )
        },
        chipModifier = Modifier.tvFocusable(shape = CircleShape, addFocusTarget = false),
    )
}

/** Android's D-pad handling around the shared slider (core-ui GridSizeOption). */
@Composable
internal fun GridSizeSlider(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    columnsHint: String? = null,
) {
    GridSizeOption(
        title = title,
        value = value,
        onValueChange = onValueChange,
        columnsHint = columnsHint,
        sliderModifier = { current, commit ->
            Modifier.tvSliderDpad(value = current, valueRange = GridSizeRange.first.toFloat()..GridSizeRange.last.toFloat(),
                onValueChange = commit, steps = GridSizeSliderSteps)
        },
    )
}

/**
 * Approximate poster-column count the grid below the sheet would show at the
 * current scale. The real grid measures its own width inside
 * [KototoroContentListScreen]; the sheet only sees the window container, so the
 * resource wording stays "about".
 */
@Composable
private fun resolveGridColumnsHint(
    enabled: Boolean,
    listMode: ListMode,
    gridSize: Int,
): String? {
    if (!enabled) return null
    if (!listMode.isGrid) return null
    val density = LocalDensity.current
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    if (windowWidthPx <= 0) return null
    val windowWidth = with(density) { windowWidthPx.toDp() }
    val availableWidth = (windowWidth - GridHorizontalPadding * 2).coerceAtLeast(48.dp)
    val columns = resolveGridColumnCount(
        availableWidth = availableWidth,
        targetItemWidth = listMode.gridPosterCardStyle(gridSize / 100f).itemWidth,
        spacing = GridSpacing,
    )
    return stringResource(R.string.grid_size_columns_hint, columns)
}
