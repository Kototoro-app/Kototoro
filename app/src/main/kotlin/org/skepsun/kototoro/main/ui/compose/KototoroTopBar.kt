package org.skepsun.kototoro.main.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.CompactTopBarIconSize
import org.skepsun.kototoro.core.ui.compose.CompactTopBarItemSpacing
import org.skepsun.kototoro.core.ui.compose.CompactTopBarPillHeight
import org.skepsun.kototoro.core.ui.compose.CompactTopBarPillShape
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.GlassVisualTreatment
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem

private val CompactTopTabsRailHeight = 40.dp
private val CompactTopFilterRailHeight = 36.dp
private val CompactFilterRailChipHeight = 34.dp
data class KototoroTopBarMenuAction(
    val titleRes: Int,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KototoroTopBar(
    query: String,
    onSearchClick: () -> Unit = {},
    onOpenListOptions: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onSourceSettingsClick: () -> Unit = {},
    onManageSourcesClick: () -> Unit = onSourceSettingsClick,
    onTrackingAccountsClick: () -> Unit = {},
    isAppUpdateAvailable: Boolean = false,
    onAppUpdateClick: () -> Unit = {},
    isLanguagePresetFilterVisible: Boolean = false,
    languagePresetEntries: List<SourcePreset> = emptyList(),
    activeLanguagePresetId: Long = -1L,
    onLanguagePresetSelected: (Long) -> Unit = {},
    onManageLanguagePresets: () -> Unit = {},
    compactTabsState: CompactTabsTopBarOverrideState? = null,
    filterRailState: CompactFilterRailOverrideState? = null,
    selectedContentType: ContentType? = null,
    enabledContentTypes: Set<ContentType> = setOf(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO),
    isContentTypeFilterVisible: Boolean = true,
    onContentTypeSelected: (ContentType?) -> Unit = {},
    selectedSourceTags: Set<SourceTag> = emptySet(),
    sourceTagEntries: List<SourceTag> = SourceTag.quickFilterEntries,
    enabledSourceTags: Set<SourceTag> = sourceTagEntries.toSet(),
    isSourceTagFilterVisible: Boolean = true,
    onSourceTagFilterClick: (android.view.View?) -> Boolean = { false },
    onSourceTagSelected: (SourceTag?) -> Unit = {},
    supportsDisplayModeMenu: Boolean = false,
    currentListMode: ListMode = ListMode.GRID,
    onListModeSelected: (ListMode) -> Unit = {},
    supportsGridSizeSlider: Boolean = false,
    gridSize: Int = 100,
    onGridSizeChange: (Int) -> Unit = {},
    isBrowseTrackingRecommendationsEnabled: Boolean? = null,
    onBrowseTrackingRecommendationsChange: ((Boolean) -> Unit)? = null,
    isBrowseMoreTrackingRecommendationsEnabled: Boolean? = null,
    onBrowseMoreTrackingRecommendationsChange: ((Boolean) -> Unit)? = null,
    showSourceSettingsEntry: Boolean = false,
    contextualMenuActions: List<KototoroTopBarMenuAction> = emptyList(),
    isIncognitoModeEnabled: Boolean = false,
    onIncognitoToggle: () -> Unit = {},
    isCollapsedFullyTransparent: Boolean = false,
    forceCompactTabsExpanded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var isMoreMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var isLanguagePresetMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var showDisplayOptionsSheet by rememberSaveable { mutableStateOf(false) }
    var areCompactTabsExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingListMode by remember(showDisplayOptionsSheet) { mutableStateOf(currentListMode) }
    var pendingGridSize by remember(showDisplayOptionsSheet) { mutableIntStateOf(gridSize) }

    if (!showDisplayOptionsSheet) {
        pendingListMode = currentListMode
        pendingGridSize = gridSize
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val collapsedAlpha by animateFloatAsState(
        targetValue = if (isCollapsedFullyTransparent) 0f else 1f,
        label = "top_bar_alpha",
    )
    val showMoreActions = true
    val compactTabsExpanded = compactTabsState != null && (areCompactTabsExpanded || forceCompactTabsExpanded)
    val hidePrimaryControlsForTabs = compactTabsExpanded

    LaunchedEffect(compactTabsState) {
        if (compactTabsState == null) {
            areCompactTabsExpanded = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusBarPadding.calculateTopPadding())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CompactTopBarHorizontalPadding)
                .graphicsLayer { alpha = collapsedAlpha },
            horizontalArrangement = Arrangement.spacedBy(CompactTopBarItemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = !hidePrimaryControlsForTabs,
                enter = fadeIn() + expandHorizontally(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                GlassSurface(
                    shape = CompactTopBarPillShape,
                    style = GlassDefaults.topBarChromeStyle(),
                    visualTreatment = GlassVisualTreatment.TopBarPrototype,
                    componentRole = GlassComponentRole.TopBar,
                ) {
                    IconButton(
                        onClick = onSearchClick,
                        modifier = Modifier.size(CompactTopBarPillHeight),
                    ) {
                        Box(
                            modifier = Modifier.size(CompactTopBarIconSize),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.search),
                                modifier = Modifier.size(CompactTopBarIconSize),
                            )
                        }
                    }
                }
            }
            if (compactTabsState != null) {
                InlineCompactTopBarTabsRail(
                    state = compactTabsState,
                    modifier = Modifier.weight(1f, fill = true),
                    onExpandedChange = { areCompactTabsExpanded = it },
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            AnimatedVisibility(
                visible = !hidePrimaryControlsForTabs,
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
            ) {
                GlassSurface(
                    shape = CompactTopBarPillShape,
                    style = GlassDefaults.topBarChromeStyle(),
                    visualTreatment = GlassVisualTreatment.TopBarPrototype,
                    componentRole = GlassComponentRole.TopBar,
                ) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides CompactTopBarPillHeight) {
                        Row(
                            modifier = Modifier
                                .widthIn(min = CompactTopBarPillHeight)
                                .height(CompactTopBarPillHeight)
                                .padding(start = 2.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isContentTypeFilterVisible) {
                                SwipeableFilterChip(
                                    selectedType = selectedContentType,
                                    enabledTypes = enabledContentTypes,
                                    onTypeSelected = onContentTypeSelected,
                                    modifier = Modifier.zIndex(1f),
                                )
                            }
                            if (isSourceTagFilterVisible) {
                                SourceTagDropdown(
                                    selectedTags = selectedSourceTags,
                                    entries = sourceTagEntries,
                                    enabledTags = enabledSourceTags,
                                    onButtonClickIntercept = onSourceTagFilterClick,
                                    onTagSelected = onSourceTagSelected,
                                )
                            }
                            if (showMoreActions) {
                                Box {
                                    IconButton(
                                        onClick = { isMoreMenuExpanded = true },
                                        modifier = Modifier.size(CompactTopBarPillHeight),
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_more_vert),
                                            contentDescription = stringResource(R.string.more),
                                            modifier = Modifier.size(CompactTopBarIconSize),
                                        )
                                    }
                                    GlassDropdownMenu(
                                        expanded = isMoreMenuExpanded,
                                        onDismissRequest = { isMoreMenuExpanded = false },
                                        offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
                                        shape = CompactTopBarPillShape,
                                        style = GlassDefaults.subtleStyle(),
                                    ) {
                                    if (supportsDisplayModeMenu || supportsGridSizeSlider) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.display_options)) },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                showDisplayOptionsSheet = true
                                            },
                                        )

                                        HorizontalDivider()
                                    }
                                    if (isLanguagePresetFilterVisible) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.show_language_preset_filter)) },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                isLanguagePresetMenuExpanded = true
                                            },
                                        )
                                        HorizontalDivider()
                                    }
                                    if (showSourceSettingsEntry) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.extension_management)) },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                onManageSourcesClick()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.manage_sources)) },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                onSourceSettingsClick()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.tracking_accounts)) },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                onTrackingAccountsClick()
                                            },
                                        )
                                        HorizontalDivider()
                                    }
                                    contextualMenuActions.forEach { action ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(action.titleRes)) },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                action.onClick()
                                            },
                                        )
                                    }
                                    if (contextualMenuActions.isNotEmpty()) {
                                        HorizontalDivider()
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.settings)) },
                                        onClick = {
                                            isMoreMenuExpanded = false
                                            onSettingsClick()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.incognito_mode)) },
                                        trailingIcon = {
                                            Checkbox(
                                                checked = isIncognitoModeEnabled,
                                                onCheckedChange = null,
                                            )
                                        },
                                        onClick = {
                                            isMoreMenuExpanded = false
                                            onIncognitoToggle()
                                        },
                                    )
                                    }
                                }
                                GlassDropdownMenu(
                                    expanded = isLanguagePresetMenuExpanded,
                                    onDismissRequest = { isLanguagePresetMenuExpanded = false },
                                    offset = DpOffset(x = 0.dp, y = 4.dp),
                                    shape = CompactTopBarPillShape,
                                    style = GlassDefaults.subtleStyle(),
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.all)) },
                                        onClick = {
                                            onLanguagePresetSelected(-1L)
                                            isLanguagePresetMenuExpanded = false
                                        },
                                        leadingIcon = {
                                            Checkbox(
                                                checked = activeLanguagePresetId <= 0L,
                                                onCheckedChange = null,
                                            )
                                        },
                                    )
                                    languagePresetEntries.forEach { preset ->
                                        DropdownMenuItem(
                                            text = { Text(preset.title) },
                                            onClick = {
                                                onLanguagePresetSelected(preset.id)
                                                isLanguagePresetMenuExpanded = false
                                            },
                                            leadingIcon = {
                                                Checkbox(
                                                    checked = activeLanguagePresetId == preset.id,
                                                    onCheckedChange = null,
                                                )
                                            },
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.manage_language_presets)) },
                                        onClick = {
                                            isLanguagePresetMenuExpanded = false
                                            onManageLanguagePresets()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        filterRailState?.let { state ->
            CompactTopBarFilterRail(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
        if (showDisplayOptionsSheet && (supportsDisplayModeMenu || supportsGridSizeSlider || onBrowseTrackingRecommendationsChange != null)) {
            org.skepsun.kototoro.list.ui.compose.DisplayOptionsSheet(
                supportsDisplayModeMenu = supportsDisplayModeMenu,
                currentListMode = pendingListMode,
                onListModeSelected = {
                    pendingListMode = it
                    onListModeSelected(it)
                },
                supportsGridSizeSlider = supportsGridSizeSlider,
                gridSize = pendingGridSize,
                onGridSizeChange = {
                    pendingGridSize = it
                    onGridSizeChange(it)
                },
                extraContent = if (onBrowseTrackingRecommendationsChange != null && isBrowseTrackingRecommendationsEnabled != null) {
                    {
                        Column {
                            DisplayOptionsSwitchRow(
                                title = stringResource(R.string.browse_tracking_recommendations),
                                summary = stringResource(R.string.browse_tracking_recommendations_summary),
                                checked = isBrowseTrackingRecommendationsEnabled,
                                onCheckedChange = onBrowseTrackingRecommendationsChange,
                            )
                            if (
                                isBrowseTrackingRecommendationsEnabled &&
                                isBrowseMoreTrackingRecommendationsEnabled != null &&
                                onBrowseMoreTrackingRecommendationsChange != null
                            ) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                DisplayOptionsSwitchRow(
                                    title = stringResource(R.string.browse_more_tracking_recommendations),
                                    summary = stringResource(R.string.browse_more_tracking_recommendations_summary),
                                    checked = isBrowseMoreTrackingRecommendationsEnabled,
                                    onCheckedChange = onBrowseMoreTrackingRecommendationsChange,
                                )
                            }
                        }
                    }
                } else {
                    null
                },
                onDismissRequest = { showDisplayOptionsSheet = false },
            )
        }
    }
}

@Composable
fun CompactTopBarTabsRail(
    state: CompactTabsTopBarOverrideState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    EnsureItemFullyVisible(listState = listState, targetIndex = state.items.indexOfFirst { it.id == state.selectedItemId })
    GlassSurface(
        modifier = modifier,
        shape = CompactTopBarPillShape,
        style = GlassDefaults.topBarChromeStyle(),
        visualTreatment = GlassVisualTreatment.TopBarPrototype,
        componentRole = GlassComponentRole.TopBar,
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(CompactTopTabsRailHeight)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 1.dp),
        ) {
            items(items = state.items, key = { it.id }) { item ->
                val selected = item.id == state.selectedItemId
                Text(
                    text = item.title,
                    modifier = Modifier
                        .clickable { state.onItemSelected(item.id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun InlineCompactTopBarTabsRail(
    state: CompactTabsTopBarOverrideState,
    modifier: Modifier = Modifier,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var restoreRequest by remember { mutableIntStateOf(0) }
    var previousSelectedItemId by remember { mutableStateOf<Long?>(null) }
    val selectedIndex = state.items.indexOfFirst { it.id == state.selectedItemId }
    EnsureItemFullyVisible(listState = listState, targetIndex = selectedIndex)
    val isScrollInProgress = listState.isScrollInProgress
    LaunchedEffect(isScrollInProgress) {
        if (isScrollInProgress) {
            onExpandedChange(true)
        } else {
            delay(900)
            onExpandedChange(false)
        }
    }
    LaunchedEffect(restoreRequest) {
        if (restoreRequest <= 0) {
            return@LaunchedEffect
        }
        onExpandedChange(true)
        delay(1600)
        if (!listState.isScrollInProgress) {
            onExpandedChange(false)
        }
    }
    LaunchedEffect(state.selectedItemId, selectedIndex) {
        val previous = previousSelectedItemId
        previousSelectedItemId = state.selectedItemId
        if (previous == null || previous == state.selectedItemId) {
            return@LaunchedEffect
        }
        if (selectedIndex < 0) {
            return@LaunchedEffect
        }
        onExpandedChange(true)
        listState.animateScrollToItem(index = selectedIndex, scrollOffset = -with(density) { 24.dp.roundToPx() })
        delay(1600)
        if (!listState.isScrollInProgress) {
            onExpandedChange(false)
        }
    }
    GlassSurface(
        modifier = modifier,
        shape = CompactTopBarPillShape,
        style = GlassDefaults.topBarChromeStyle(),
        visualTreatment = GlassVisualTreatment.TopBarPrototype,
        componentRole = GlassComponentRole.TopBar,
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(CompactTopBarPillHeight)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 1.dp),
        ) {
            items(items = state.items, key = { it.id }) { item ->
                val selected = item.id == state.selectedItemId
                Text(
                    text = item.title,
                    modifier = Modifier
                        .clickable {
                            restoreRequest += 1
                            state.onItemSelected(item.id)
                        }
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun CompactTopBarFilterRail(
    state: CompactFilterRailOverrideState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val firstSelectedIndex = remember(state.items) {
        state.items.indexOfFirst { it.isSelected }
    }
    EnsureItemFullyVisible(listState = listState, targetIndex = firstSelectedIndex)
    LaunchedEffect(state.items, firstSelectedIndex) {
        if (firstSelectedIndex == 0 && listState.firstVisibleItemIndex > 0) {
            listState.animateScrollToItem(0)
        }
    }
    val visibleItemRange = remember(listState.layoutInfo) {
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val minVisible = visibleItems.minOfOrNull { it.index } ?: 0
        val maxVisible = visibleItems.maxOfOrNull { it.index } ?: -1
        (minVisible - 2).coerceAtLeast(0)..(maxVisible + 2).coerceAtLeast(-1)
    }
    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(CompactTopFilterRailHeight),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        items(
            items = state.items,
            key = { it.id },
        ) { item ->
            val itemIndex = remember(state.items, item.id) {
                state.items.indexOfFirst { it.id == item.id }
            }
            val shouldLoadIcon = itemIndex in visibleItemRange
            GlassSurface(
                shape = RoundedCornerShape(22.dp),
                style = GlassDefaults.topBarChromeStyle(),
                visualTreatment = GlassVisualTreatment.TopBarPrototype,
                componentRole = GlassComponentRole.TopBar,
            ) {
                Row(
                    modifier = Modifier
                        .clickable { item.onClick() }
                        .height(CompactFilterRailChipHeight)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item.source?.let { source ->
                        ContentSourceIcon(
                            source = source,
                            loadEnabled = shouldLoadIcon,
                            throttleNetworkLoad = shouldLoadIcon,
                            modifier = Modifier.size(16.dp),
                            contentDescription = null,
                        )
                    }
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (item.isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (item.isSelected) {
                            androidx.compose.ui.text.font.FontWeight.SemiBold
                        } else {
                            androidx.compose.ui.text.font.FontWeight.Normal
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun EnsureItemFullyVisible(
    listState: LazyListState,
    targetIndex: Int,
) {
    val density = LocalDensity.current
    val extraPaddingPx = with(density) { 8.dp.toPx() }
    LaunchedEffect(listState, targetIndex) {
        if (targetIndex < 0) return@LaunchedEffect
        repeat(2) {
            val layoutInfo = listState.layoutInfo
            val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            if (itemInfo == null) {
                listState.scrollToItem(targetIndex)
            } else {
                val viewportStart = layoutInfo.viewportStartOffset
                val viewportEnd = layoutInfo.viewportEndOffset
                val itemStart = itemInfo.offset
                val itemEnd = itemInfo.offset + itemInfo.size
                when {
                    itemStart < viewportStart -> listState.animateScrollBy(itemStart - viewportStart - extraPaddingPx)
                    itemEnd > viewportEnd -> listState.animateScrollBy(itemEnd - viewportEnd + extraPaddingPx)
                    else -> return@LaunchedEffect
                }
                return@LaunchedEffect
            }
        }
    }
}

@Composable
private fun DisplayOptionsSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
