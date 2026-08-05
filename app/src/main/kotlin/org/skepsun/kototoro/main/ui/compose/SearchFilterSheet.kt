package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.compose.FilterPanelGroup
import org.skepsun.kototoro.core.ui.compose.StableAnchoredBottomSheet
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS
import org.skepsun.kototoro.search.domain.ALL_SOURCE_TYPES
import org.skepsun.kototoro.search.domain.SEARCH_CONTENT_KIND_OPTIONS
import org.skepsun.kototoro.search.domain.SOURCE_TYPE_OPTIONS
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.settings.sources.blacklist.GlobalTagBlacklistStatus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchFilterSheet(
    sourceTypes: Set<SourceType>,
    contentKinds: Set<SearchContentKind>,
    pinnedOnly: Boolean,
    hideEmpty: Boolean,
    languagePresets: List<SourcePreset> = emptyList(),
    activeLanguagePresetId: Long? = null,
    blacklistedTagCount: Int = 0,
    onSourceTypeToggle: (SourceType) -> Unit,
    onContentKindToggle: (SearchContentKind) -> Unit,
    onPinnedOnlyChange: (Boolean) -> Unit,
    onHideEmptyChange: (Boolean) -> Unit,
    onLanguagePresetSelected: (Long) -> Unit = {},
    onManageLanguagePresets: (() -> Unit)? = null,
    onOpenGlobalTagBlacklist: () -> Unit = {},
    onDismissRequest: () -> Unit,
) {
    StableAnchoredBottomSheet(
        onDismissRequest = onDismissRequest,
        shape = RectangleShape,
        containerColor = Color.Transparent,
        dragHandle = null,
    ) { sheetDragModifier ->
        KototoroSheetSurface(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                SheetDragHandle(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .then(sheetDragModifier),
                )
                Text(
                    text = stringResource(R.string.filter),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        GlobalTagBlacklistStatus(
                            blacklistedTagCount = blacklistedTagCount,
                            onClick = onOpenGlobalTagBlacklist,
                        )
                    }
                    if (
                        activeLanguagePresetId != null ||
                        languagePresets.isNotEmpty() ||
                        onManageLanguagePresets != null
                    ) {
                        item {
                            LanguagePresetSection(
                                presets = languagePresets,
                                activePresetId = activeLanguagePresetId ?: -1L,
                                onPresetSelected = onLanguagePresetSelected,
                                onManagePresets = onManageLanguagePresets,
                            )
                        }
                    }
                    item {
                        FilterPanelGroup(title = stringResource(R.string.source_type)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                SOURCE_TYPE_OPTIONS.forEach { option ->
                                    CompactSearchFilterChip(
                                        selected = option.type in sourceTypes,
                                        onClick = { onSourceTypeToggle(option.type) },
                                        label = stringResource(option.titleRes),
                                    )
                                }
                            }
                        }
                    }
                    item {
                        FilterPanelGroup(title = stringResource(R.string.type)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                SEARCH_CONTENT_KIND_OPTIONS.forEach { option ->
                                    CompactSearchFilterChip(
                                        selected = option.kind in contentKinds,
                                        onClick = { onContentKindToggle(option.kind) },
                                        label = stringResource(option.titleRes),
                                    )
                                }
                            }
                        }
                    }
                    item {
                        FilterPanelGroup {
                            SearchOptionSwitchRow(
                                title = stringResource(R.string.pinned_sources_only),
                                checked = pinnedOnly,
                                onCheckedChange = onPinnedOnlyChange,
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            )
                            SearchOptionSwitchRow(
                                title = stringResource(R.string.hide_empty_sources),
                                checked = hideEmpty,
                                onCheckedChange = onHideEmptyChange,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguagePresetSection(
    presets: List<SourcePreset>,
    activePresetId: Long,
    onPresetSelected: (Long) -> Unit,
    onManagePresets: (() -> Unit)?,
) {
    FilterPanelGroup {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.show_language_preset_filter),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            if (onManagePresets != null) {
                TextButton(
                    onClick = onManagePresets,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.manage))
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompactSearchFilterChip(
                selected = activePresetId <= 0L,
                onClick = { onPresetSelected(-1L) },
                label = stringResource(R.string.all),
            )
            presets.forEach { preset ->
                CompactSearchFilterChip(
                    selected = activePresetId == preset.id,
                    onClick = { onPresetSelected(preset.id) },
                    label = preset.title,
                )
            }
        }
    }
}

@Composable
private fun CompactSearchFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 28.dp) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.heightIn(min = 28.dp),
            label = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
    }
}

@Composable
private fun SearchOptionSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

internal fun <T> Set<T>.toggleOrAll(item: T, allItems: Set<T>): Set<T> {
    val updated = toMutableSet().apply {
        if (!add(item)) {
            remove(item)
        }
    }
    return updated.ifEmpty { allItems }
}
