package org.skepsun.kototoro.main.ui.compose


import androidx.compose.animation.AnimatedVisibility
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.explore.ui.compose.ExploreSelectionTopBar
import org.skepsun.kototoro.explore.ui.compose.ExploreSourceSelectionTopBarState
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.parsers.model.ContentType
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import org.skepsun.kototoro.main.ui.compose.CompactFilterRailOverrideState
import org.skepsun.kototoro.main.ui.compose.CompactTabsTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.LayeredTopBarOverrideState
import org.skepsun.kototoro.list.domain.ListSortOrder

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun BoxScope.MainTopChrome(
    effectiveTopBarOverrideState: TopBarOverrideState?,
    isLandscapeNavigation: Boolean,
    isLayeredSurface: Boolean,
    chromeSharedTransitionScope: SharedTransitionScope?,
    heroTransitionInProgress: Boolean,
    isDetailsChromeTransitionPending: Boolean,
    visibleStartInsetDp: androidx.compose.ui.unit.Dp,
    effectiveTopBarOffset: Float,
    chromeAlpha: Float,
    onTopBarHeightMeasured: (Int) -> Unit,
    query: String,
    titleRes: Int?,
    onSearchClick: () -> Unit,
    onOpenListOptions: () -> Unit,
    onSettingsClick: () -> Unit,
    onHelpClick: () -> Unit,
    onSourceSettingsClick: () -> Unit,
    onManageSourcesClick: () -> Unit,
    onTrackingAccountsClick: () -> Unit,
    isAppUpdateAvailable: Boolean,
    onAppUpdateClick: () -> Unit,
    isIncognitoModeEnabled: Boolean,
    onIncognitoToggle: () -> Unit,
    isLanguagePresetFilterVisible: Boolean,
    languagePresetEntries: List<SourcePreset>,
    activeLanguagePresetId: Long,
    onLanguagePresetSelected: (Long) -> Unit,
    onManageLanguagePresets: () -> Unit,
    topTabsOverrideState: CompactTabsTopBarOverrideState?,
    topFilterRailOverrideState: CompactFilterRailOverrideState?,
    selectedContentType: ContentType?,
    enabledContentTypes: Set<ContentType>,
    isContentTypeFilterVisible: Boolean,
    onContentTypeSelected: (ContentType?) -> Unit,
    selectedSourceTags: Set<SourceTag>,
    sourceTagEntries: List<SourceTag>,
    enabledSourceTags: Set<SourceTag>,
    isSourceTagFilterVisible: Boolean,
    onSourceTagFilterClick: (android.view.View?) -> Boolean,
    onSourceTagSelected: (SourceTag?) -> Unit,
    supportsDisplayModeMenu: Boolean,
    currentListMode: ListMode,
    onListModeSelected: (ListMode) -> Unit,
    supportsGridSizeSlider: Boolean,
    gridSize: Int,
    onGridSizeChange: (Int) -> Unit,
    isBrowseTrackingRecommendationsEnabled: Boolean?,
    onBrowseTrackingRecommendationsChange: ((Boolean) -> Unit)?,
    isBrowseMoreTrackingRecommendationsEnabled: Boolean?,
    onBrowseMoreTrackingRecommendationsChange: ((Boolean) -> Unit)?,
    showSourceSettingsEntry: Boolean,
    contextualMenuActions: List<KototoroTopBarMenuAction>,
    forceCompactTabsExpanded: Boolean,
    effectiveCompactTabsTopBarOffset: Float,
    sortOrders: List<org.skepsun.kototoro.list.domain.ListSortOrder> = emptyList(),
    selectedSortOrder: org.skepsun.kototoro.list.domain.ListSortOrder? = null,
    onSortOrderSelected: (org.skepsun.kototoro.list.domain.ListSortOrder) -> Unit = {},
    displayOptionsExtraContent: (@Composable (() -> Unit) -> Unit)? = null,
) {
    val topChromeModifier = Modifier
        .align(if (isLandscapeNavigation) Alignment.TopStart else Alignment.TopCenter)
        .then(if (isLandscapeNavigation) Modifier.fillMaxWidth() else Modifier)
        .renderChromeInSharedTransitionOverlay(
            sharedTransitionScope = chromeSharedTransitionScope,
            zIndexInOverlay = 2f,
            renderInOverlay = {
                heroTransitionInProgress || isDetailsChromeTransitionPending
            },
        )
        .padding(start = visibleStartInsetDp)
        .offset { androidx.compose.ui.unit.IntOffset(0, effectiveTopBarOffset.toInt()) }
        .graphicsLayer { alpha = chromeAlpha }
        .onGloballyPositioned { coords -> onTopBarHeightMeasured(coords.size.height) }

    if (effectiveTopBarOverrideState != null && effectiveTopBarOverrideState !is CompactTabsTopBarOverrideState) {
        MainSelectionTopChrome(
            effectiveTopBarOverrideState = effectiveTopBarOverrideState,
            modifier = topChromeModifier,
        )
    } else {
        val compactTabsOffsetModifier = Modifier.offset {
            androidx.compose.ui.unit.IntOffset(
                0,
                (effectiveCompactTabsTopBarOffset - effectiveTopBarOffset).toInt(),
            )
        }
        val topContent: @Composable () -> Unit = {
            KototoroTopBar(
                query = query,
                titleRes = titleRes,
                onSearchClick = onSearchClick,
                onOpenListOptions = onOpenListOptions,
                onSettingsClick = onSettingsClick,
                onHelpClick = onHelpClick,
                onSourceSettingsClick = onSourceSettingsClick,
                onManageSourcesClick = onManageSourcesClick,
                onTrackingAccountsClick = onTrackingAccountsClick,
                isAppUpdateAvailable = isAppUpdateAvailable,
                onAppUpdateClick = onAppUpdateClick,
                isIncognitoModeEnabled = isIncognitoModeEnabled,
                onIncognitoToggle = onIncognitoToggle,
                isLanguagePresetFilterVisible = isLanguagePresetFilterVisible,
                languagePresetEntries = languagePresetEntries,
                activeLanguagePresetId = activeLanguagePresetId,
                onLanguagePresetSelected = onLanguagePresetSelected,
                onManageLanguagePresets = onManageLanguagePresets,
                compactTabsState = topTabsOverrideState,
                filterRailState = topFilterRailOverrideState,
                selectedContentType = selectedContentType,
                enabledContentTypes = enabledContentTypes,
                isContentTypeFilterVisible = isContentTypeFilterVisible,
                onContentTypeSelected = onContentTypeSelected,
                selectedSourceTags = selectedSourceTags,
                sourceTagEntries = sourceTagEntries,
                enabledSourceTags = enabledSourceTags,
                isSourceTagFilterVisible = isSourceTagFilterVisible,
                onSourceTagFilterClick = onSourceTagFilterClick,
                onSourceTagSelected = onSourceTagSelected,
                supportsDisplayModeMenu = supportsDisplayModeMenu,
                currentListMode = currentListMode,
                onListModeSelected = onListModeSelected,
                supportsGridSizeSlider = supportsGridSizeSlider,
                gridSize = gridSize,
                onGridSizeChange = onGridSizeChange,
                isBrowseTrackingRecommendationsEnabled = isBrowseTrackingRecommendationsEnabled,
                onBrowseTrackingRecommendationsChange = onBrowseTrackingRecommendationsChange,
                isBrowseMoreTrackingRecommendationsEnabled = isBrowseMoreTrackingRecommendationsEnabled,
                onBrowseMoreTrackingRecommendationsChange = onBrowseMoreTrackingRecommendationsChange,
                showSourceSettingsEntry = showSourceSettingsEntry,
                contextualMenuActions = contextualMenuActions,
                forceCompactTabsExpanded = forceCompactTabsExpanded,
                sortOrders = sortOrders,
                selectedSortOrder = selectedSortOrder,
                onSortOrderSelected = onSortOrderSelected,
                displayOptionsExtraContent = displayOptionsExtraContent,
                modifier = if (isLayeredSurface) {
                    compactTabsOffsetModifier
                } else {
                    topChromeModifier.then(compactTabsOffsetModifier)
                },
            )
        }
        if (isLayeredSurface) {
            Surface(
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = 4.dp,
                modifier = topChromeModifier,
            ) {
                topContent()
            }
        } else {
            topContent()
        }
    }
}

@Composable
internal fun FeedDisplayOptionsContent(
    showAllUpdates: Boolean,
    onShowAllUpdatesChanged: (Boolean) -> Unit,
    feedLimit: Int,
    onFeedLimitChanged: (Int) -> Unit,
    onFeedRefresh: () -> Unit,
) {
    val jumps = remember { listOf(50, 100, 200, 500, 1000, 2000) }
    val limitIndex = remember(feedLimit) { jumps.indexOf(feedLimit).coerceAtLeast(0) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.show_all_updates),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = showAllUpdates,
                onCheckedChange = onShowAllUpdatesChanged,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.feed_visible_entries),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = feedLimit.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            KototoroSlider(
                value = limitIndex.toFloat(),
                onValueChange = { index ->
                    onFeedLimitChanged(jumps[index.roundToInt()])
                },
                valueRange = 0f..(jumps.size - 1).toFloat(),
                steps = jumps.size - 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
        AnimatedVisibility(visible = showAllUpdates) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.feed_behavior_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onFeedRefresh,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(text = stringResource(R.string.trigger_update_now))
                }
            }
        }
    }
}

@Composable
private fun MainSelectionTopChrome(
    effectiveTopBarOverrideState: TopBarOverrideState,
    modifier: Modifier = Modifier,
) {
    when (effectiveTopBarOverrideState) {
        is ExploreSourceSelectionTopBarState -> {
            ExploreSelectionTopBar(
                selectedCount = effectiveTopBarOverrideState.selectedCount,
                isSingleSelection = effectiveTopBarOverrideState.isSingleSelection,
                canPin = effectiveTopBarOverrideState.canPin,
                canUnpin = effectiveTopBarOverrideState.canUnpin,
                canDisable = effectiveTopBarOverrideState.canDisable,
                canDelete = effectiveTopBarOverrideState.canDelete,
                markEmptyTitleRes = effectiveTopBarOverrideState.markEmptyTitleRes,
                onClearSelection = effectiveTopBarOverrideState.onClearSelection,
                onSettings = effectiveTopBarOverrideState.onSettings,
                onDisable = effectiveTopBarOverrideState.onDisable,
                onDelete = effectiveTopBarOverrideState.onDelete,
                onShortcut = effectiveTopBarOverrideState.onShortcut,
                onPin = effectiveTopBarOverrideState.onPin,
                onUnpin = effectiveTopBarOverrideState.onUnpin,
                onToggleEmptyAvailability = effectiveTopBarOverrideState.onToggleEmptyAvailability,
                modifier = modifier,
            )
        }

        is ContentSelectionTopBarOverrideState -> {
            org.skepsun.kototoro.list.ui.compose.KototoroSelectionTopBar(
                selectedCount = effectiveTopBarOverrideState.selectedCount,
                isAllNonLocal = effectiveTopBarOverrideState.isAllNonLocal,
                isSingleSelection = effectiveTopBarOverrideState.isSingleSelection,
                showRemoveOption = effectiveTopBarOverrideState.showRemoveOption,
                supportedActions = effectiveTopBarOverrideState.supportedActions,
                allPinned = effectiveTopBarOverrideState.allPinned,
                preferredInlineActions = effectiveTopBarOverrideState.preferredInlineActions,
                removeActionIconRes = effectiveTopBarOverrideState.removeActionIconRes,
                removeActionTitleRes = effectiveTopBarOverrideState.removeActionTitleRes,
                fixActionTitleRes = effectiveTopBarOverrideState.fixActionTitleRes,
                onClearSelection = effectiveTopBarOverrideState.onClearSelection,
                onActionClick = effectiveTopBarOverrideState.onActionClick,
                modifier = modifier,
            )
        }

        is CompactTabsTopBarOverrideState -> Unit
        is LayeredTopBarOverrideState -> Unit
    }
}

