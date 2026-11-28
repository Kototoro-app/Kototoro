package org.skepsun.kototoro.main.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.buttons.Button
import org.skepsun.kototoro.core.ui.buttons.TextButton
import org.skepsun.kototoro.core.ui.compose.KototoroSheetSurface
import org.skepsun.kototoro.core.ui.compose.SheetDragHandle
import org.skepsun.kototoro.core.ui.compose.StableAnchoredBottomSheet
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.glass.GlassPrefs
import org.skepsun.kototoro.core.ui.glass.LocalGlassPrefs
import org.skepsun.kototoro.core.ui.text.LocalUiStrings
import org.skepsun.kototoro.core.ui.text.rememberAndroidUiStrings
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyleTokens
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.theme.tokens
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS
import org.skepsun.kototoro.search.domain.ALL_SOURCE_TYPES
import org.skepsun.kototoro.search.domain.SEARCH_CONTENT_KIND_OPTIONS
import org.skepsun.kototoro.search.domain.SOURCE_TYPE_OPTIONS
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchFilters
import org.skepsun.kototoro.search.domain.searchContentKindsFromNames
import org.skepsun.kototoro.search.domain.sourceTypesFromNames

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

/** Compact control height: tall enough for a remote's focus ring, tight enough not to waste a row. */
private val FilterControlMinHeight = 44.dp

/** The unselected shape of a source-type chip; a selected one morphs into a pill. */
private val SourceTypeChipShape = RoundedCornerShape(12.dp)

/**
 * The search filter panel.
 *
 * The sheet hugs its content (up to [SearchFilterSheetMaxHeightFraction] of the host) instead of filling the screen, so
 * the docked footer sits right under the last group rather than below a block of dead space, and the header is pushed
 * below the status bar instead of sliding under it.
 *
 * [resolvedLanguagePresetId] is what the language row highlights while the draft still follows the global browsing
 * preset ([SearchFilters.PRESET_FOLLOW_GLOBAL]); tapping any preset pins it for search only.
 *
 * [temporaryScope] marks the panel as the detail page's reading-source scope, which is deliberately not persisted.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun SearchFilterSheet(
    sourceTypes: Set<SourceType>,
    contentKinds: Set<SearchContentKind>,
    pinnedOnly: Boolean,
    hideEmpty: Boolean,
    languagePresets: List<SourcePreset> = emptyList(),
    activeLanguagePresetId: Long? = null,
    resolvedLanguagePresetId: Long? = null,
    blacklistedTagCount: Int = 0,
    onApply: (SearchFilters) -> Unit,
    onManageLanguagePresets: (() -> Unit)? = null,
    // Null when the host has nowhere to send the viewer: the row then shows the status only, instead
    // of a Manage button that looks active and does nothing (and that a remote focus ring would land on).
    onOpenGlobalTagBlacklist: (() -> Unit)? = null,
    temporaryScope: Boolean = false,
    onDismissRequest: () -> Unit,
) {
    val defaultFilters = SearchFilters()
    var draft by rememberSaveable(stateSaver = SearchFiltersSaver) {
        mutableStateOf(
            SearchFilters(
                sourceTypes = sourceTypes,
                contentKinds = contentKinds,
                pinnedOnly = pinnedOnly,
                hideEmpty = hideEmpty,
                languagePresetId = activeLanguagePresetId ?: SearchFilters.PRESET_FOLLOW_GLOBAL,
            ).normalized(),
        )
    }

    StableAnchoredBottomSheet(
        onDismissRequest = onDismissRequest,
        shape = RectangleShape,
        containerColor = Color.Transparent,
        dragHandle = null,
    ) { sheetDragModifier ->
        SearchFilterSheetScaffold(
            draft = draft,
            onDraftChange = { draft = it },
            showLanguageSection = activeLanguagePresetId != null ||
                languagePresets.isNotEmpty() ||
                onManageLanguagePresets != null,
            resolvedLanguagePresetId = resolvedLanguagePresetId,
            temporaryScope = temporaryScope,
            languagePresets = languagePresets,
            blacklistedTagCount = blacklistedTagCount,
            onManageLanguagePresets = onManageLanguagePresets,
            onOpenGlobalTagBlacklist = onOpenGlobalTagBlacklist,
            dragModifier = sheetDragModifier,
            onReset = { draft = defaultFilters },
            onCancel = onDismissRequest,
            onApply = {
                onApply(draft.normalized())
                onDismissRequest()
            },
        )
    }
}

/**
 * The card itself: scrim-free, dialog-free and state-hoisted, so Compose Preview and the headless
 * screenshot renderer can draw the exact panel the app shows without a Window.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchFilterSheetScaffold(
    draft: SearchFilters,
    onDraftChange: (SearchFilters) -> Unit,
    showLanguageSection: Boolean,
    resolvedLanguagePresetId: Long?,
    temporaryScope: Boolean,
    languagePresets: List<SourcePreset>,
    blacklistedTagCount: Int,
    onManageLanguagePresets: (() -> Unit)?,
    onOpenGlobalTagBlacklist: (() -> Unit)?,
    dragModifier: Modifier,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    val highlightedPresetId = if (draft.languagePresetId == SearchFilters.PRESET_FOLLOW_GLOBAL) {
        resolvedLanguagePresetId ?: SearchFilters.PRESET_ALL
    } else {
        draft.languagePresetId
    }
    val activeCount = draft.countActiveFilters()
    val isDefault = draft == SearchFilters()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sheetMaxHeight = maxHeight * SearchFilterSheetMaxHeightFraction
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The sheet is expanded up to 88% of the host, so the header has to stay clear of the status bar
                // once it grows; the card itself then hugs the bottom, right above the navigation bar.
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            KototoroSheetSurface(
                // Content-hugging: the card is only as tall as the groups it holds, so the docked footer sits
                // right under them instead of below a screenful of empty sheet.
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = sheetMaxHeight),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SheetDragHandle(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .then(dragModifier),
                    )
                    FilterSheetHeader(
                        activeCount = activeCount,
                        showTemporaryScope = temporaryScope,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // fill = false lets the group column stop at its content, so a short panel does not
                            // stretch and a long one still scrolls inside the bounded height above.
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ContentKindSection(
                            selected = draft.contentKinds,
                            onToggle = { kind ->
                                onDraftChange(
                                    draft.copy(
                                        contentKinds = draft.contentKinds.toggleOrAll(kind, ALL_SEARCH_CONTENT_KINDS),
                                    ),
                                )
                            },
                        )
                        if (showLanguageSection) {
                            LanguagePresetSection(
                                presets = languagePresets,
                                highlightedPresetId = highlightedPresetId,
                                onPresetSelected = { presetId ->
                                    onDraftChange(draft.copy(languagePresetId = presetId))
                                },
                                onManagePresets = onManageLanguagePresets,
                            )
                        }
                        SourceTypeSection(
                            selected = draft.sourceTypes,
                            onToggle = { type ->
                                onDraftChange(
                                    draft.copy(sourceTypes = draft.sourceTypes.toggleOrAll(type, ALL_SOURCE_TYPES)),
                                )
                            },
                            onSelectAll = { onDraftChange(draft.copy(sourceTypes = ALL_SOURCE_TYPES)) },
                        )
                        OptionsSection(
                            draft = draft,
                            blacklistedTagCount = blacklistedTagCount,
                            onDraftChange = onDraftChange,
                            onOpenGlobalTagBlacklist = onOpenGlobalTagBlacklist,
                        )
                    }
                    FilterSheetFooter(
                        resetEnabled = !isDefault,
                        onReset = onReset,
                        onCancel = onCancel,
                        onApply = onApply,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FilterSheetHeader(
    activeCount: Int,
    showTemporaryScope: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.filter),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        if (activeCount > 0) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = activeCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        } else {
            Text(
                text = stringResource(R.string.search_filter_default),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showTemporaryScope) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.search_filter_temporary_scope),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContentKindSection(
    selected: Set<SearchContentKind>,
    onToggle: (SearchContentKind) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title = stringResource(R.string.type))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            SEARCH_CONTENT_KIND_OPTIONS.forEachIndexed { index, option ->
                val checked = option.kind in selected
                ToggleButton(
                    checked = checked,
                    onCheckedChange = { onToggle(option.kind) },
                    shapes = when (index) {
                        0 -> if (SEARCH_CONTENT_KIND_OPTIONS.size == 1) {
                            singleToggleShapes()
                        } else {
                            ButtonGroupDefaults.connectedLeadingButtonShapes()
                        }
                        SEARCH_CONTENT_KIND_OPTIONS.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    colors = filterToggleColors(),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = FilterControlMinHeight)
                        .tvFocusable(shape = CircleShape, addFocusTarget = false),
                ) {
                    Icon(
                        painter = painterResource(option.kind.iconRes()),
                        contentDescription = null,
                        modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                    )
                    Text(
                        text = stringResource(option.titleRes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = ToggleButtonDefaults.IconSpacing),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LanguagePresetSection(
    presets: List<SourcePreset>,
    highlightedPresetId: Long,
    onPresetSelected: (Long) -> Unit,
    onManagePresets: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(
            title = stringResource(R.string.show_language_preset_filter),
            action = onManagePresets?.let { manage ->
                {
                    ManagePill(
                        onClick = manage,
                        modifier = Modifier.tvFocusable(shape = CircleShape, addFocusTarget = false),
                    )
                }
            },
        )
        val entries = listOf(SearchFilters.PRESET_ALL to stringResource(R.string.all)) +
            presets.map { preset -> preset.id to preset.title }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            entries.forEachIndexed { index, (presetId, title) ->
                ToggleButton(
                    checked = highlightedPresetId == presetId,
                    onCheckedChange = { onPresetSelected(presetId) },
                    shapes = when {
                        entries.size == 1 -> singleToggleShapes()
                        index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        index == entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    colors = filterToggleColors(),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier
                        .heightIn(min = FilterControlMinHeight)
                        .tvFocusable(shape = CircleShape, addFocusTarget = false),
                ) {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SourceTypeSection(
    selected: Set<SourceType>,
    onToggle: (SourceType) -> Unit,
    onSelectAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(
            title = stringResource(R.string.source_type),
            subtitle = stringResource(
                R.string.search_filter_selected_of_total,
                selected.size,
                SOURCE_TYPE_OPTIONS.size,
            ),
            action = if (selected.size < SOURCE_TYPE_OPTIONS.size) {
                {
                    ManagePill(
                        onClick = onSelectAll,
                        label = stringResource(R.string.select_all),
                        modifier = Modifier.tvFocusable(shape = CircleShape, addFocusTarget = false),
                    )
                }
            } else {
                null
            },
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SOURCE_TYPE_OPTIONS.forEach { option ->
                val checked = option.type in selected
                ToggleButton(
                    checked = checked,
                    onCheckedChange = { onToggle(option.type) },
                    shapes = ToggleButtonShapes(SourceTypeChipShape, SourceTypeChipShape, CircleShape),
                    colors = filterToggleColors(),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .heightIn(min = FilterControlMinHeight)
                        .tvFocusable(shape = CircleShape, addFocusTarget = false),
                ) {
                    AnimatedVisibility(
                        visible = checked,
                        enter = expandHorizontally() + fadeIn(),
                        exit = shrinkHorizontally() + fadeOut(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier
                                .size(ToggleButtonDefaults.IconSize)
                                .padding(end = ToggleButtonDefaults.IconSpacing),
                        )
                    }
                    Icon(
                        painter = rememberSafePainter(option.iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                    )
                    Text(
                        text = stringResource(option.titleRes),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = ToggleButtonDefaults.IconSpacing),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OptionsSection(
    draft: SearchFilters,
    blacklistedTagCount: Int,
    onDraftChange: (SearchFilters) -> Unit,
    onOpenGlobalTagBlacklist: (() -> Unit)?,
) {
    val showBlacklistRow = onOpenGlobalTagBlacklist != null
    val rowCount = if (showBlacklistRow) 3 else 2
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        OptionSwitchRow(
            iconRes = R.drawable.ic_pin_small,
            title = stringResource(R.string.pinned_sources_only),
            subtitle = stringResource(R.string.search_filter_pinned_only_hint),
            checked = draft.pinnedOnly,
            onCheckedChange = { onDraftChange(draft.copy(pinnedOnly = it)) },
            shape = optionRowShape(0, rowCount),
        )
        OptionSwitchRow(
            iconRes = R.drawable.ic_filter_menu,
            title = stringResource(R.string.hide_empty_sources),
            subtitle = stringResource(R.string.search_filter_hide_empty_hint),
            checked = draft.hideEmpty,
            onCheckedChange = { onDraftChange(draft.copy(hideEmpty = it)) },
            shape = optionRowShape(1, rowCount),
        )
        if (showBlacklistRow && onOpenGlobalTagBlacklist != null) {
            Surface(
                shape = optionRowShape(2, rowCount),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tag),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.blacklisted_tags),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = if (blacklistedTagCount > 0) {
                                stringResource(R.string.global_tag_blacklist_active, blacklistedTagCount)
                            } else {
                                stringResource(R.string.global_tag_blacklist_inactive)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ManagePill(
                        onClick = onOpenGlobalTagBlacklist,
                        modifier = Modifier.tvFocusable(shape = CircleShape, addFocusTarget = false),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OptionSwitchRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: androidx.compose.ui.graphics.Shape,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .tvFocusable(shape = shape, addFocusTarget = false)
                .heightIn(min = 56.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = {
                    Icon(
                        imageVector = if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        }
    }
}

@Composable
private fun FilterSheetFooter(
    resetEnabled: Boolean,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onReset,
            enabled = resetEnabled,
            modifier = Modifier.tvFocusable(enabled = resetEnabled, shape = CircleShape, addFocusTarget = false),
        ) {
            Text(stringResource(R.string.reset))
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(
            onClick = onCancel,
            modifier = Modifier.tvFocusable(shape = CircleShape, addFocusTarget = false),
        ) {
            Text(stringResource(android.R.string.cancel))
        }
        Button(
            onClick = onApply,
            modifier = Modifier.tvFocusable(shape = CircleShape, addFocusTarget = false),
        ) {
            Text(stringResource(R.string.apply))
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun ManagePill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.manage),
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/** `All` alone reads as a lonely circle, so a one-entry group keeps a padded pill instead of the connected shapes. */
private fun singleToggleShapes(): ToggleButtonShapes = ToggleButtonShapes(CircleShape, CircleShape, CircleShape)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun filterToggleColors() = ToggleButtonDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/** Outer rows round by 20.dp, the seam between two rows by 6.dp: one card visually split into rows. */
private fun optionRowShape(index: Int, count: Int): androidx.compose.ui.graphics.Shape {
    val outer = 20.dp
    val inner = 6.dp
    return when {
        count <= 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
        index == count - 1 -> RoundedCornerShape(
            topStart = inner,
            topEnd = inner,
            bottomStart = outer,
            bottomEnd = outer,
        )
        else -> RoundedCornerShape(inner)
    }
}

private fun SearchFilters.countActiveFilters(): Int {
    var count = 0
    if (sourceTypes != ALL_SOURCE_TYPES) count++
    if (contentKinds != ALL_SEARCH_CONTENT_KINDS) count++
    if (pinnedOnly) count++
    if (hideEmpty) count++
    if (languagePresetId != SearchFilters.PRESET_FOLLOW_GLOBAL) count++
    return count
}

private fun SearchContentKind.iconRes(): Int = when (this) {
    SearchContentKind.MANGA -> R.drawable.ic_content_manga
    SearchContentKind.NOVEL -> R.drawable.ic_content_novel
    SearchContentKind.VIDEO -> R.drawable.ic_content_video
}

/** How much of the host the panel may take before its body starts scrolling. */
private const val SearchFilterSheetMaxHeightFraction = 0.88f

internal fun <T> Set<T>.toggleOrAll(item: T, allItems: Set<T>): Set<T> {
    val updated = toMutableSet().apply {
        if (!add(item)) {
            remove(item)
        }
    }
    return updated.ifEmpty { allItems }
}

/**
 * Shared theme for the panel previews.
 *
 * The real screen runs [org.skepsun.kototoro.core.ui.theme.KototoroTheme], which reads Hilt-backed
 * [org.skepsun.kototoro.core.prefs.AppSettings] and a Google-font loader; previews and the headless
 * screenshot renderer cannot provide either, so this reproduces the same composition locals
 * (expressive style, glass prefs, UI strings) around a plain [MaterialExpressiveTheme].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchFilterPreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    val style = InterfaceStyle.MATERIAL_3_EXPRESSIVE
    CompositionLocalProvider(
        LocalInterfaceStyle provides style,
        LocalInterfaceStyleTokens provides style.tokens(),
        LocalMaterialExpressiveComponentsEnabled provides true,
        LocalUiStrings provides rememberAndroidUiStrings(),
        LocalGlassPrefs provides GlassPrefs(
            isGlassEffectEnabled = true,
            isReducedVisualEffectsEnabled = false,
            immersiveStrengthPercent = 65,
        ),
    ) {
        MaterialExpressiveTheme(
            colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
            motionScheme = MotionScheme.expressive(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                content()
            }
        }
    }
}

private val PreviewLanguagePresets = listOf(
    SourcePreset(
        id = 1L,
        title = "中文源",
        languages = setOf("zh"),
        sources = emptySet(),
        createdAt = 0L,
        sortKey = 0,
    ),
    SourcePreset(
        id = 2L,
        title = "English",
        languages = setOf("en"),
        sources = emptySet(),
        createdAt = 0L,
        sortKey = 1,
    ),
)

/**
 * State-hoisted host used by every [SearchFilterSheet] preview and by the headless renderer.
 *
 * [temporaryScope] = true renders the detail page's reading-source scope, which is when the panel
 * shows the "This work only" badge.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun SearchFilterSheetPreview(
    dark: Boolean = false,
    temporaryScope: Boolean = false,
) {
    SearchFilterPreviewTheme(dark = dark) {
        var draft by remember {
            mutableStateOf(
                if (temporaryScope) {
                    SearchFilters(languagePresetId = 1L)
                } else {
                    SearchFilters(
                        sourceTypes = setOf(SourceType.NATIVE, SourceType.MIHON, SourceType.CLOUDSTREAM),
                        contentKinds = setOf(SearchContentKind.MANGA),
                        pinnedOnly = true,
                        hideEmpty = false,
                        languagePresetId = 1L,
                    )
                },
            )
        }
        SearchFilterSheetScaffold(
            draft = draft,
            onDraftChange = { draft = it },
            showLanguageSection = true,
            resolvedLanguagePresetId = 1L,
            temporaryScope = temporaryScope,
            languagePresets = PreviewLanguagePresets,
            blacklistedTagCount = 3,
            onManageLanguagePresets = {},
            onOpenGlobalTagBlacklist = {},
            dragModifier = Modifier,
            onReset = { draft = SearchFilters() },
            onCancel = {},
            onApply = {},
        )
    }
}

@Preview(name = "Search filter · light", widthDp = 412, heightDp = 915, locale = "zh-rCN")
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchFilterSheetLightPreview() = SearchFilterSheetPreview()

@Preview(name = "Search filter · dark", widthDp = 412, heightDp = 915, locale = "zh-rCN")
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchFilterSheetDarkPreview() = SearchFilterSheetPreview(dark = true)

@Preview(name = "Search filter · detail scope", widthDp = 412, heightDp = 915, locale = "zh-rCN")
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchFilterSheetDetailScopePreview() = SearchFilterSheetPreview(temporaryScope = true)
