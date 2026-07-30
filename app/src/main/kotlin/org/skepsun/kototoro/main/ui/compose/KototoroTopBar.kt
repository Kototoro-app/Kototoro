package org.skepsun.kototoro.main.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.skepsun.kototoro.core.prefs.BackgroundStyle
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import kotlinx.coroutines.delay
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.ui.compose.CompactTopBarItemSpacing
import org.skepsun.kototoro.core.ui.compose.CompactTopBarPillHeight
import org.skepsun.kototoro.core.ui.compose.CompactTopBarPillShape
import org.skepsun.kototoro.core.ui.compose.ContentSourceIcon
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.glassContainerShadow
import org.skepsun.kototoro.core.ui.theme.LocalBackgroundStyle
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem

private val CompactTopTabsRailVisualHeight = 40.dp
private val CompactTopFilterRailVisualHeight = 36.dp
data class KototoroTopBarMenuAction(
    val titleRes: Int,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KototoroTopBar(
    query: String,
    titleRes: Int? = null,
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
    sortOrders: List<ListSortOrder> = emptyList(),
    selectedSortOrder: ListSortOrder? = null,
    onSortOrderSelected: (ListSortOrder) -> Unit = {},
    displayOptionsExtraContent: (@Composable (() -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var isMoreMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var isLanguagePresetMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var topBarMenuAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    var showDisplayOptionsSheet by rememberSaveable { mutableStateOf(false) }
    var areCompactTabsExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingListMode by remember(showDisplayOptionsSheet) { mutableStateOf(currentListMode) }
    var pendingGridSize by remember(showDisplayOptionsSheet) { mutableIntStateOf(gridSize) }

    if (!showDisplayOptionsSheet) {
        pendingListMode = currentListMode
        pendingGridSize = gridSize
    }

    val statusBarPadding = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues()
    val tokens = LocalInterfaceStyleTokens.current
    val topBarControlHeight = tokens.topBarButtonSize
    val topBarIconSize = tokens.topBarIconSize
    val collapsedAlpha by animateFloatAsState(
        targetValue = if (isCollapsedFullyTransparent) 0f else 1f,
        label = "top_bar_alpha",
    )
    val showMoreActions = true
    val compactTabsExpanded = compactTabsState != null && (areCompactTabsExpanded || forceCompactTabsExpanded)
    val hidePrimaryControlsForTabs = compactTabsExpanded
    val topBarTitle = titleRes?.let { stringResource(it) }

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
                .height(tokens.mainTopBarHeight)
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
                TopBarControlSurface {
                    IconButton(
                        onClick = onSearchClick,
                        modifier = Modifier.size(topBarControlHeight),
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.search),
                            modifier = Modifier.size(topBarIconSize),
                        )
                    }
                }
            }
            if (!topBarTitle.isNullOrBlank() && !hidePrimaryControlsForTabs) {
                val maxWidth = if (compactTabsState != null) 72.dp else 128.dp
                Text(
                    text = topBarTitle,
                    modifier = Modifier.widthIn(max = maxWidth),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (compactTabsState != null) {
                InlineCompactTopBarTabsRail(
                    state = compactTabsState,
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .widthIn(max = if (hidePrimaryControlsForTabs) Dp.Unspecified else 196.dp),
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
                TopBarControlSurface {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides topBarControlHeight) {
                        Row(
                            modifier = Modifier
                                .widthIn(min = topBarControlHeight)
                                .height(topBarControlHeight)
                                .padding(start = 2.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (isContentTypeFilterVisible) {
                                SwipeableFilterChip(
                                    selectedType = selectedContentType,
                                    enabledTypes = enabledContentTypes,
                                    onTypeSelected = onContentTypeSelected,
                                    controlSize = topBarControlHeight,
                                    iconSize = topBarIconSize,
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
                                    buttonSize = topBarControlHeight,
                                    iconSize = topBarIconSize,
                                )
                            }
                            if (showMoreActions) {
                                Box(
                                    modifier = Modifier.onGloballyPositioned {
                                        topBarMenuAnchorBounds = it.boundsInRoot()
                                    },
                                ) {
                                    IconButton(
                                        onClick = { isMoreMenuExpanded = true },
                                        modifier = Modifier.size(topBarControlHeight),
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_more_vert),
                                            contentDescription = stringResource(R.string.more),
                                            modifier = Modifier.size(topBarIconSize),
                                        )
                                    }
                                    GlassDropdownMenu(
                                        expanded = isMoreMenuExpanded,
                                        onDismissRequest = { isMoreMenuExpanded = false },
                                        offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
                                        alignToAnchorEnd = true,
                                        useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
                                        anchorBounds = topBarMenuAnchorBounds,
                                        shape = CompactTopBarPillShape,
                                        style = GlassDefaults.subtleStyle(),
                                    ) {
                                    if (supportsDisplayModeMenu || supportsGridSizeSlider || displayOptionsExtraContent != null) {
                                        CompactDropdownMenuItem(
                                            text = { CompactDropdownMenuText(stringResource(R.string.display_options)) },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                showDisplayOptionsSheet = true
                                            },
                                        )

                                        CompactDropdownMenuDivider()
                                    }
                                    if (isLanguagePresetFilterVisible) {
                                        CompactDropdownMenuItem(
                                            text = { CompactDropdownMenuText(stringResource(R.string.show_language_preset_filter)) },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                isLanguagePresetMenuExpanded = true
                                            },
                                        )
                                        CompactDropdownMenuDivider()
                                    }
                                    if (showSourceSettingsEntry) {
                                        CompactDropdownMenuItem(
                                            text = { CompactDropdownMenuText(stringResource(R.string.extension_management)) },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                onManageSourcesClick()
                                            },
                                        )
                                        CompactDropdownMenuItem(
                                            text = { CompactDropdownMenuText(stringResource(R.string.manage_sources)) },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                onSourceSettingsClick()
                                            },
                                        )
                                        CompactDropdownMenuItem(
                                            text = { CompactDropdownMenuText(stringResource(R.string.tracking_accounts)) },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                onTrackingAccountsClick()
                                            },
                                        )
                                        CompactDropdownMenuDivider()
                                    }
                                    contextualMenuActions.forEach { action ->
                                        CompactDropdownMenuItem(
                                            text = { CompactDropdownMenuText(stringResource(action.titleRes)) },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                action.onClick()
                                            },
                                        )
                                    }
                                    if (contextualMenuActions.isNotEmpty()) {
                                        CompactDropdownMenuDivider()
                                    }
                                    CompactDropdownMenuItem(
                                        text = { CompactDropdownMenuText(stringResource(R.string.settings)) },
                                        onClick = {
                                            isMoreMenuExpanded = false
                                            onSettingsClick()
                                        },
                                    )
                                    CompactDropdownMenuItem(
                                        text = { CompactDropdownMenuText(stringResource(R.string.incognito_mode)) },
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
                                    alignToAnchorEnd = true,
                                    useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
                                    anchorBounds = topBarMenuAnchorBounds,
                                    shape = CompactTopBarPillShape,
                                    style = GlassDefaults.subtleStyle(),
                                ) {
                                    CompactDropdownMenuItem(
                                        text = { CompactDropdownMenuText(stringResource(R.string.all)) },
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
                                        CompactDropdownMenuItem(
                                            text = { CompactDropdownMenuText(preset.title) },
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
                                    CompactDropdownMenuDivider()
                                    CompactDropdownMenuItem(
                                        text = { CompactDropdownMenuText(stringResource(R.string.manage_language_presets)) },
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
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (
            showDisplayOptionsSheet &&
            (
                supportsDisplayModeMenu ||
                    supportsGridSizeSlider ||
                    onBrowseTrackingRecommendationsChange != null ||
                    sortOrders.isNotEmpty() ||
                    displayOptionsExtraContent != null
                )
        ) {
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
                sortOrders = sortOrders,
                selectedSortOrder = selectedSortOrder,
                onSortOrderSelected = onSortOrderSelected,
                extraContent = if (
                    displayOptionsExtraContent != null ||
                    (onBrowseTrackingRecommendationsChange != null && isBrowseTrackingRecommendationsEnabled != null)
                ) {
                    {
                        Column {
                            if (onBrowseTrackingRecommendationsChange != null && isBrowseTrackingRecommendationsEnabled != null) {
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
                            displayOptionsExtraContent?.let { content ->
                                if (onBrowseTrackingRecommendationsChange != null && isBrowseTrackingRecommendationsEnabled != null) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                }
                                content { showDisplayOptionsSheet = false }
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
internal fun TopBarControlSurface(
    allowBackdrop: Boolean = true,
    fallbackContainerColor: Color? = null,
    shadowElevation: Dp = GlassDefaults.navigationShadowElevation,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val shape = if (expressive) RoundedCornerShape(999.dp) else CompactTopBarPillShape
    val style = if (expressive) {
            GlassDefaults.topBarChromeStyle().copy(
                containerAlpha = 0.90f,
                borderAlpha = 0.24f,
                shadowElevation = shadowElevation,
            )
        } else {
            GlassDefaults.topBarChromeStyle().copy(shadowElevation = shadowElevation)
    }
    val backdrop = LocalLiquidGlassBackdrop.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val isArtworkBackground = LocalBackgroundStyle.current == BackgroundStyle.DYNAMIC_ARTWORK_BLUR
    val useBackdrop = allowBackdrop &&
        isIosStyle &&
        backdrop != null
    if (useBackdrop) {
        GlassSurface(
            modifier = modifier,
            shape = shape,
            style = style,
            componentRole = GlassComponentRole.TopBar,
            content = content,
        )
    } else if (fallbackContainerColor != null) {
        val effectiveFallbackContainerColor = if (!isIosStyle && isArtworkBackground) {
            fallbackContainerColor.copy(alpha = 1f)
        } else {
            fallbackContainerColor
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Box(
                modifier = modifier
                    .glassContainerShadow(shape, shadowElevation)
                    .background(effectiveFallbackContainerColor, shape),
                content = content,
            )
        }
    } else {
        GlassSurface(
            modifier = modifier,
            shape = shape,
            style = style,
            componentRole = GlassComponentRole.TopBar,
            content = content,
        )
    }
}

@Composable
fun CompactTopBarTabsRail(
    state: CompactTabsTopBarOverrideState,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalInterfaceStyleTokens.current
    val listState = rememberLazyListState()
    EnsureItemFullyVisible(listState = listState, targetIndex = state.items.indexOfFirst { it.id == state.selectedItemId })
    Box(
        modifier = modifier.height(tokens.minimumTouchTarget),
    ) {
        TopBarControlSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(CompactTopTabsRailVisualHeight)
                .align(Alignment.Center),
        ) {}
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.minimumTouchTarget)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 1.dp),
        ) {
            items(items = state.items, key = { it.id }) { item ->
                val selected = item.id == state.selectedItemId
                Box(
                    modifier = Modifier
                        .height(tokens.minimumTouchTarget)
                        .clickable { state.onItemSelected(item.id) }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        fontWeight = if (selected) {
                            androidx.compose.ui.text.font.FontWeight.SemiBold
                        } else {
                            androidx.compose.ui.text.font.FontWeight.Normal
                        },
                    )
                }
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
    val tokens = LocalInterfaceStyleTokens.current
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
    Box(
        modifier = modifier.height(tokens.minimumTouchTarget),
    ) {
        TopBarControlSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.topBarButtonSize)
                .align(Alignment.Center),
        ) {}
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.minimumTouchTarget)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 1.dp),
        ) {
            items(items = state.items, key = { it.id }) { item ->
                val selected = item.id == state.selectedItemId
                Box(
                    modifier = Modifier
                        .height(tokens.minimumTouchTarget)
                        .clickable {
                            restoreRequest += 1
                            state.onItemSelected(item.id)
                        }
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        fontWeight = if (selected) {
                            androidx.compose.ui.text.font.FontWeight.SemiBold
                        } else {
                            androidx.compose.ui.text.font.FontWeight.Normal
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun CompactTopBarFilterRail(
    state: CompactFilterRailOverrideState,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalInterfaceStyleTokens.current
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(tokens.minimumTouchTarget),
    ) {
        TopBarControlSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(CompactTopFilterRailVisualHeight)
                .align(Alignment.BottomCenter),
        ) {}
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(tokens.minimumTouchTarget),
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
                Box(
                    modifier = Modifier
                        .height(tokens.minimumTouchTarget)
                        .clickable { item.onClick() }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Row(
                        modifier = Modifier.height(CompactTopFilterRailVisualHeight),
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
