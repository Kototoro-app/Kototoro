package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.compose.FilterPanelGroup
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
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
        dragHandle = null,
    ) {
        KototoroSheetSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SheetDragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
                Text(
                    text = stringResource(R.string.filter),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
                            FilterPanelGroup(title = stringResource(R.string.show_language_preset_filter)) {
                                LanguagePresetSection(
                                    presets = languagePresets,
                                    activePresetId = activeLanguagePresetId ?: -1L,
                                    onPresetSelected = onLanguagePresetSelected,
                                    onManagePresets = onManageLanguagePresets,
                                )
                            }
                        }
                    }
                    item {
                        FilterPanelGroup(title = stringResource(R.string.source_type)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SOURCE_TYPE_OPTIONS.forEach { option ->
                                    FilterChip(
                                        selected = option.type in sourceTypes,
                                        onClick = { onSourceTypeToggle(option.type) },
                                        label = {
                                            Text(
                                                text = stringResource(option.titleRes),
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                    item {
                        FilterPanelGroup(title = stringResource(R.string.type)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SEARCH_CONTENT_KIND_OPTIONS.forEach { option ->
                                    FilterChip(
                                        selected = option.kind in contentKinds,
                                        onClick = { onContentKindToggle(option.kind) },
                                        label = {
                                            Text(
                                                text = stringResource(option.titleRes),
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        },
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
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = activePresetId <= 0L,
            onClick = { onPresetSelected(-1L) },
            label = { Text(stringResource(R.string.all)) },
        )
        presets.forEach { preset ->
            FilterChip(
                selected = activePresetId == preset.id,
                onClick = { onPresetSelected(preset.id) },
                label = { Text(preset.title) },
            )
        }
    }
    if (onManagePresets != null) {
        FilledTonalButton(
            onClick = onManagePresets,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_language),
                contentDescription = null,
            )
            Text(
                text = stringResource(R.string.manage_language_presets),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
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
            .heightIn(min = 52.dp)
            .padding(vertical = 6.dp),
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
