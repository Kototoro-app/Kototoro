package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.focusGroup
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.compose.FilterPanelGroup
import org.skepsun.kototoro.core.ui.compose.StableAnchoredBottomSheet
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS
import org.skepsun.kototoro.search.domain.ALL_SOURCE_TYPES
import org.skepsun.kototoro.search.domain.SEARCH_CONTENT_KIND_OPTIONS
import org.skepsun.kototoro.search.domain.SOURCE_TYPE_OPTIONS
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchFilters
import org.skepsun.kototoro.search.domain.searchContentKindsFromNames
import org.skepsun.kototoro.search.domain.sourceTypesFromNames
import org.skepsun.kototoro.settings.sources.blacklist.GlobalTagBlacklistStatus

private val SearchFiltersSaver = listSaver<SearchFilters, Any>(
    save = {
        listOf(
            it.sourceTypes.joinToString(",") { type -> type.name },
            it.contentKinds.joinToString(",") { kind -> kind.name },
            it.pinnedOnly,
            it.hideEmpty,
            it.languagePresetId,
        )
    },
    restore = {
        SearchFilters(
            sourceTypes = sourceTypesFromNames((it[0] as String).split(',')) ?: ALL_SOURCE_TYPES,
            contentKinds = searchContentKindsFromNames((it[1] as String).split(',')) ?: ALL_SEARCH_CONTENT_KINDS,
            pinnedOnly = it[2] as Boolean,
            hideEmpty = it[3] as Boolean,
            languagePresetId = it[4] as Long,
        )
    },
)

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
    onApply: (SearchFilters) -> Unit,
    onManageLanguagePresets: (() -> Unit)? = null,
    onOpenGlobalTagBlacklist: () -> Unit = {},
    onDismissRequest: () -> Unit,
) {
    var draft by rememberSaveable(stateSaver = SearchFiltersSaver) {
        mutableStateOf(
            SearchFilters(sourceTypes, contentKinds, pinnedOnly, hideEmpty, activeLanguagePresetId ?: -1L).normalized(),
        )
    }
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
                    .navigationBarsPadding()
                    .then(if (LocalUiPresentationConfig.current.isTv) Modifier.focusGroup() else Modifier),
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
                                activePresetId = draft.languagePresetId,
                                onPresetSelected = { draft = draft.copy(languagePresetId = it) },
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
                                        selected = option.type in draft.sourceTypes,
                                        onClick = {
                                            draft = draft.copy(
                                                sourceTypes = draft.sourceTypes.toggleOrAll(option.type, ALL_SOURCE_TYPES),
                                            )
                                        },
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
                                        selected = option.kind in draft.contentKinds,
                                        onClick = {
                                            draft = draft.copy(
                                                contentKinds = draft.contentKinds.toggleOrAll(
                                                    option.kind, ALL_SEARCH_CONTENT_KINDS,
                                                ),
                                            )
                                        },
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
                                checked = draft.pinnedOnly,
                                onCheckedChange = { draft = draft.copy(pinnedOnly = it) },
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            )
                            SearchOptionSwitchRow(
                                title = stringResource(R.string.hide_empty_sources),
                                checked = draft.hideEmpty,
                                onCheckedChange = { draft = draft.copy(hideEmpty = it) },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { draft = SearchFilters() }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.reset_filter))
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Button(onClick = {
                        onApply(draft.normalized())
                        onDismissRequest()
                    }) {
                        Text(stringResource(R.string.apply))
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
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .tvFocusable(shape = RoundedCornerShape(10.dp), addFocusTarget = false),
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
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    val minimumHeight = 48.dp
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides minimumHeight) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            modifier = Modifier
                .heightIn(min = minimumHeight)
                .tvFocusable(shape = RoundedCornerShape(14.dp), addFocusTarget = false),
            label = {
                Text(
                    text = label,
                    style = if (isTvPresentation) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.labelSmall
                    },
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
            .tvFocusable(shape = RoundedCornerShape(12.dp), addFocusTarget = false)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
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
            onCheckedChange = null,
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
