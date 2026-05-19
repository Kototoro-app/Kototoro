package org.skepsun.kototoro.main.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem

private val CollapsedSearchBarHeight = 48.dp
private val CompactTopBarActionSize = 40.dp
private val CompactTopBarIconSize = 20.dp

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
    modifier: Modifier = Modifier,
) {
    var isMoreMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var showDisplayOptionsSheet by rememberSaveable { mutableStateOf(false) }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val collapsedAlpha by animateFloatAsState(
        targetValue = if (isCollapsedFullyTransparent) 0f else 1f,
        label = "top_bar_alpha",
    )
    val showMoreActions = true

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusBarPadding.calculateTopPadding())
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .graphicsLayer { alpha = collapsedAlpha },
            shape = RoundedCornerShape(28.dp),
            style = GlassDefaults.subtleStyle(),
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides CompactTopBarActionSize) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CollapsedSearchBarHeight)
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(CollapsedSearchBarHeight)
                            .clickable(onClick = onSearchClick),
                        verticalAlignment = Alignment.CenterVertically,
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
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = query.ifBlank { stringResource(R.string.search_content) },
                            color = if (query.isBlank()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isLanguagePresetFilterVisible) {
                        LanguagePresetDropdownButton(
                            presets = languagePresetEntries,
                            activePresetId = activeLanguagePresetId,
                            onPresetSelected = onLanguagePresetSelected,
                            onManagePresets = onManageLanguagePresets,
                        )
                    }
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
                                modifier = Modifier.size(CompactTopBarActionSize),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_more_vert),
                                    contentDescription = stringResource(R.string.more),
                                    modifier = Modifier.size(CompactTopBarIconSize),
                                )
                            }
                            DropdownMenu(
                                expanded = isMoreMenuExpanded,
                                onDismissRequest = { isMoreMenuExpanded = false },
                                shape = MaterialTheme.shapes.extraSmall,
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp,
                                offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
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
                    }
                }
            }
        }
        if (showDisplayOptionsSheet && (supportsDisplayModeMenu || supportsGridSizeSlider || onBrowseTrackingRecommendationsChange != null)) {
            org.skepsun.kototoro.list.ui.compose.DisplayOptionsSheet(
                supportsDisplayModeMenu = supportsDisplayModeMenu,
                currentListMode = currentListMode,
                onListModeSelected = onListModeSelected,
                supportsGridSizeSlider = supportsGridSizeSlider,
                gridSize = gridSize,
                onGridSizeChange = onGridSizeChange,
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

@Composable
private fun LanguagePresetDropdownButton(
    presets: List<SourcePreset>,
    activePresetId: Long,
    onPresetSelected: (Long) -> Unit,
    onManagePresets: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(CompactTopBarActionSize),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_language),
                contentDescription = stringResource(R.string.show_language_preset_filter),
                modifier = Modifier.size(CompactTopBarIconSize),
                tint = if (activePresetId > 0L) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(x = 0.dp, y = 4.dp),
            shape = MaterialTheme.shapes.extraSmall,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        )
 {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.all)) },
                onClick = {
                    onPresetSelected(-1L)
                    expanded = false
                },
                leadingIcon = {
                    Checkbox(
                        checked = activePresetId <= 0L,
                        onCheckedChange = null,
                    )
                },
            )
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.title) },
                    onClick = {
                        onPresetSelected(preset.id)
                        expanded = false
                    },
                    leadingIcon = {
                        Checkbox(
                            checked = activePresetId == preset.id,
                            onCheckedChange = null,
                        )
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.manage_language_presets)) },
                onClick = {
                    expanded = false
                    onManagePresets()
                },
            )
        }
    }
}
