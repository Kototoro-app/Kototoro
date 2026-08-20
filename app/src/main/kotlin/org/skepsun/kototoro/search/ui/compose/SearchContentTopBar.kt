package org.skepsun.kototoro.search.ui.compose


import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.resolveTopImmersiveAlpha
import org.skepsun.kototoro.core.ui.glass.rememberGlassPrefsOrFallback

import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.parsers.model.ContentTag

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchContentTopBar(
    searchMode: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    onSearchSubmit: () -> Unit,
    focusRequester: FocusRequester,
    sourceTitle: String,
    activeQuery: String?,
    currentSortLabel: String,
    isFilterApplied: Boolean,
    quickFilter: QuickFilter?,
    contentItems: List<ContentListModel>,
    selectedTags: Set<ContentTag>,
    availableTags: List<ContentTag>,
    listMode: ListMode,
    gridSize: Int,
    topActionsHeight: Dp,
    collapseOffsetPx: Float,
    isRandomLoading: Boolean,
    activeSpaceId: SpaceId?,
    onBackClick: () -> Unit,
    onSpaceSwitcherClick: () -> Unit,
    onRandomClick: () -> Unit,
    onFilterClick: () -> Unit,
    onResetFilterClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onListModeChange: (ListMode) -> Unit,
    onGridSizeChange: (Int) -> Unit,
    onClearActiveQuery: () -> Unit,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
    onToggleTag: (ContentTag, Boolean) -> Unit,
) {
    val extractedTags = remember(contentItems, selectedTags, availableTags) {
        buildSourcePinnedTags(
            contentItems = contentItems,
            selectedTags = selectedTags,
            availableTags = availableTags,
        )
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topActionsHeightPx = with(density) { topActionsHeight.toPx() }
    val topActionsCollapsedPx = collapseOffsetPx.coerceIn(0f, topActionsHeightPx)
    val topActionsVisibleHeight = with(density) { (topActionsHeightPx - topActionsCollapsedPx).coerceAtLeast(0f).toDp() }
    val compactTopBarAlpha = if (topActionsHeightPx == 0f) 1f else {
        ((topActionsHeightPx - topActionsCollapsedPx) / topActionsHeightPx).coerceIn(0f, 1f)
    }
    val topGradientAlpha = resolveTopImmersiveAlpha(
        contentScrollAlpha = (1f - compactTopBarAlpha).coerceIn(0f, 1f),
        chromeAlpha = compactTopBarAlpha,
    )
    val statusBarPadding = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues()
    val statusBarTopPadding = statusBarPadding.calculateTopPadding()
    val glassPrefs = rememberGlassPrefsOrFallback()
    val immersiveStrength = (glassPrefs.immersiveStrengthPercent.coerceIn(0, 100)) / 100f
    val isDarkTheme = isSystemInDarkTheme()
    val immersiveBaseColor = if (isDarkTheme) Color.Black else Color.White
    val immersiveTopColors = listOf(
        immersiveBaseColor.copy(alpha = lerpFloat(0.72f, 0.98f, immersiveStrength)),
        immersiveBaseColor.copy(alpha = lerpFloat(0.56f, 0.82f, immersiveStrength)),
        immersiveBaseColor.copy(alpha = lerpFloat(0.32f, 0.52f, immersiveStrength)),
        immersiveBaseColor.copy(alpha = lerpFloat(0.12f, 0.22f, immersiveStrength)),
        Color.Transparent,
    )

    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(statusBarTopPadding + topActionsHeight + 6.dp)
                .graphicsLayer { alpha = topGradientAlpha }
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to immersiveTopColors[0],
                            0.38f to immersiveTopColors[1],
                            0.72f to immersiveTopColors[2],
                            0.92f to immersiveTopColors[3],
                            1f to immersiveTopColors[4],
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusBarTopPadding),
        ) {
            if (searchMode) {
                SearchInputRow(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    onClose = onSearchClose,
                    onSubmit = onSearchSubmit,
                    focusRequester = focusRequester,
                )
            } else {
                CollapsingBarSlot(
                    visibleHeight = topActionsVisibleHeight,
                    fullHeight = topActionsHeight,
                ) {
                    var showDisplayOptionsSheet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

                    SourceListTopActionsRow(
                        sourceTitle = sourceTitle,
                        currentSortLabel = currentSortLabel,
                        topBarAlpha = compactTopBarAlpha,
                        listMode = listMode,
                        gridSize = gridSize,
                        isFilterApplied = isFilterApplied,
                        isRandomLoading = isRandomLoading,
                        activeSpaceId = activeSpaceId,
                        onBackClick = onBackClick,
                        onSpaceSwitcherClick = onSpaceSwitcherClick,
                        onSearchClick = onSearchOpen,
                        onRandomClick = onRandomClick,
                        onFilterClick = onFilterClick,
                        onResetFilterClick = onResetFilterClick,
                        onSettingsClick = onSettingsClick,
                        onListModeChange = onListModeChange,
                        onGridSizeChange = onGridSizeChange,
                        onShowDisplayOptionsSheet = { showDisplayOptionsSheet = true }
                    )

                    if (showDisplayOptionsSheet) {
                        org.skepsun.kototoro.list.ui.compose.DisplayOptionsSheet(
                            supportsDisplayModeMenu = true,
                            currentListMode = listMode,
                            onListModeSelected = onListModeChange,
                            supportsGridSizeSlider = true,
                            gridSize = gridSize,
                            onGridSizeChange = onGridSizeChange,
                            onDismissRequest = { showDisplayOptionsSheet = false },
                        )
                    }
                }
            }

            if (!searchMode) {
                if (quickFilter != null) {
                    QuickFilterPinnedRow(
                        quickFilter = quickFilter,
                        activeQuery = activeQuery,
                        onClearActiveQuery = onClearActiveQuery,
                        onQuickFilterOptionClick = onQuickFilterOptionClick,
                    )
                } else {
                    SourceTagsPinnedRow(
                        tags = extractedTags,
                        selectedTags = selectedTags,
                        activeQuery = activeQuery,
                        onClearActiveQuery = onClearActiveQuery,
                        onToggleTag = onToggleTag,
                    )
                }
            }
        }
    }
}

