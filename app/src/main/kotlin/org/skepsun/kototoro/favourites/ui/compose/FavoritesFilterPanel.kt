package org.skepsun.kototoro.favourites.ui.compose

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import org.skepsun.kototoro.core.ui.theme.popupMenuContainerColor
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.ui.compose.CompactFilterChip
import org.skepsun.kototoro.core.ui.compose.CompactFilterChipFlow
import org.skepsun.kototoro.core.ui.compose.FilterFacetChip
import org.skepsun.kototoro.core.ui.compose.FilterFacetDropdown
import org.skepsun.kototoro.core.ui.compose.FilterFacetOption
import org.skepsun.kototoro.core.ui.compose.FilterPanelGroup
import org.skepsun.kototoro.core.ui.compose.FilterPanelHeader
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.explore.ui.model.titleRes
import org.skepsun.kototoro.explore.ui.model.iconRes
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.ui.compose.buildChipLabel
import org.skepsun.kototoro.list.ui.compose.chipIcon
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.list.ui.model.QuickFilterGroup

/**
 * The favourites page's combined filter panel, rendered inside the top-bar "content source
 * type" filter popup. It mirrors the [org.skepsun.kototoro.main.ui.compose.SearchFilterSheet]
 * layout vocabulary (FilterPanelGroup + compact FilterChips) while staying a dropdown:
 *
 *  - a header whose reset clears the quick filter and source tags,
 *  - the same quick-filter groups/chips the inline tab bar exposes (reading status,
 *    publication status, content rating, work relations, downloaded),
 *  - one row of pickers for tags, sources and the content-source type (SourceTag),
 *  - toggle chips that show/hide the inline quick-filter bar and the shelf.
 *
 * [close] dismisses the popup (typically from the "Done" row).
 *
 * The group cards, header, switch rows, compact chips and facet dropdown come from `core-ui`'s shared
 * filter panel, which the desktop library drawer renders too; this file keeps the Android quick-filter
 * mapping, resources and the single-choice source-type menu.
 */
@Composable
fun FavoritesFilterPanelContent(
    quickFilter: QuickFilter?,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
    onResetFilters: () -> Unit,
    selectedSourceTags: Set<SourceTag>,
    sourceTagEntries: List<SourceTag>,
    enabledSourceTags: Set<SourceTag>,
    onSourceTagSelected: (SourceTag?) -> Unit,
    isInlineQuickFilterEnabled: Boolean,
    onInlineQuickFilterEnabledChange: (Boolean) -> Unit,
    isShelfEnabled: Boolean,
    onShelfEnabledChange: (Boolean) -> Unit,
    close: () -> Unit,
) {
    val context = LocalContext.current
    val entryPoint = remember(context.applicationContext) {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                BaseApp.BaseAppEntryPoint::class.java,
            )
        }.getOrNull()
    }

    val facetGroups = quickFilter?.groups.orEmpty().filter { it.isFacetDropdown() }
    val chipGroups = quickFilter?.groups.orEmpty().filterNot { it.isFacetDropdown() }
    val hasActiveFilter = selectedSourceTags.isNotEmpty() ||
        quickFilter?.groups.orEmpty().any { group -> group.items.any(ChipModel::isChecked) } ||
        quickFilter?.items.orEmpty().any(ChipModel::isChecked)

    Column(
        modifier = Modifier
            .widthIn(min = 300.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterPanelHeader(
            title = stringResource(R.string.filter),
            doneLabel = stringResource(R.string.done),
            onDone = close,
            resetLabel = stringResource(R.string.reset),
            onReset = onResetFilters,
            resetEnabled = hasActiveFilter,
        )

        chipGroups.forEach { group ->
            FilterPanelGroup(title = stringResource(group.titleResId), card = false) {
                QuickFilterItemChips(
                    chips = group.items,
                    context = context,
                    entryPoint = entryPoint,
                    onQuickFilterOptionClick = onQuickFilterOptionClick,
                )
            }
        }
        quickFilter?.items?.takeIf { it.isNotEmpty() }?.let { items ->
            FilterPanelGroup(card = false) {
                QuickFilterItemChips(
                    chips = items,
                    context = context,
                    entryPoint = entryPoint,
                    onQuickFilterOptionClick = onQuickFilterOptionClick,
                )
            }
        }

        // Tags, sources and source type are pickers whose chip already reads the facet's name, so they share one
        // untitled row instead of a titled group each.
        if (facetGroups.isNotEmpty() || sourceTagEntries.isNotEmpty()) {
            FilterPanelGroup(card = false) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    facetGroups.forEach { group ->
                        Box(modifier = Modifier.weight(1f)) {
                            QuickFilterGroupDropdown(
                                group = group,
                                context = context,
                                entryPoint = entryPoint,
                                onQuickFilterOptionClick = onQuickFilterOptionClick,
                            )
                        }
                    }
                    if (sourceTagEntries.isNotEmpty()) {
                        Box(modifier = Modifier.weight(1f)) {
                            SourceTagFilterDropdown(
                                selectedTags = selectedSourceTags,
                                entries = sourceTagEntries,
                                enabledTags = enabledSourceTags,
                                onTagSelected = onSourceTagSelected,
                            )
                        }
                    }
                }
            }
        }

        // What the page shows, not what it filters: kept last, below a rule, as two toggle chips.
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )
        FilterPanelGroup(card = false) {
            CompactFilterChipFlow {
                CompactFilterChip(
                    selected = isInlineQuickFilterEnabled,
                    onClick = { onInlineQuickFilterEnabledChange(!isInlineQuickFilterEnabled) },
                    label = stringResource(R.string.show_quick_filters),
                    icon = panelToggleCheck(isInlineQuickFilterEnabled),
                )
                CompactFilterChip(
                    selected = isShelfEnabled,
                    onClick = { onShelfEnabledChange(!isShelfEnabled) },
                    label = stringResource(R.string.favourites_shelf_toggle),
                    icon = panelToggleCheck(isShelfEnabled),
                )
            }
        }
    }
}

private fun panelToggleCheck(checked: Boolean): (@Composable () -> Unit)? = if (checked) {
    {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
    }
} else {
    null
}

private fun QuickFilterGroup.isFacetDropdown(): Boolean =
    items.any { it.data is ListFilterOption.Tag || it.data is ListFilterOption.Source }

/**
 * Ring shape for the chips and dropdown rows of this panel. M3 chips and menu items are both rounded
 * rectangles with a small corner radius, and neither draws anything for focus.
 */
private val FilterControlRingShape = RoundedCornerShape(8.dp)

@Composable
private fun QuickFilterGroupDropdown(
    group: QuickFilterGroup,
    context: Context,
    entryPoint: BaseApp.BaseAppEntryPoint?,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
) {
    val selectedItems = group.items.filter(ChipModel::isChecked)
    val label = when (selectedItems.size) {
        0 -> stringResource(group.titleResId)
        1 -> buildChipLabel(context, selectedItems.single(), entryPoint)
        else -> stringResource(
            R.string.filter_group_selected_count,
            stringResource(group.titleResId),
            selectedItems.size,
        )
    }
    val chipsByOption = group.items.mapNotNull { chip -> (chip.data as? ListFilterOption)?.let { it to chip } }.toMap()
    FilterFacetDropdown(
        label = label,
        options = chipsByOption.map { (option, chip) ->
            FilterFacetOption(option, buildChipLabel(context, chip, entryPoint), chip.isChecked)
        },
        onOptionClick = onQuickFilterOptionClick,
        resetLabel = stringResource(R.string.reset_filter),
        onReset = {
            selectedItems.forEach { chip ->
                (chip.data as? ListFilterOption)?.let(onQuickFilterOptionClick)
            }
        },
        optionIcon = { option ->
            Icon(
                painter = painterResource(if (option.checked) R.drawable.ic_check else chipsByOption.getValue(option.key).icon),
                contentDescription = null,
                tint = if (option.checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(group.iconResId),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        menuContainerColor = MaterialTheme.colorScheme.popupMenuContainerColor(),
    )
}

@Composable
private fun SourceTagFilterDropdown(
    selectedTags: Set<SourceTag>,
    entries: List<SourceTag>,
    enabledTags: Set<SourceTag>,
    onTagSelected: (SourceTag?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (selectedTags.size) {
        0 -> stringResource(R.string.source_type)
        1 -> stringResource(selectedTags.single().titleRes)
        else -> stringResource(
            R.string.filter_group_selected_count,
            stringResource(R.string.source_type),
            selectedTags.size,
        )
    }

    Box {
        FilterFacetChip(
            label = label,
            selected = selectedTags.isNotEmpty(),
            onClick = { expanded = true },
            leadingIcon = {
                Icon(
                    painter = rememberSafePainter(R.drawable.ic_filter_menu),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        DropdownMenu(
            containerColor = MaterialTheme.colorScheme.popupMenuContainerColor(),
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.all)) },
                onClick = {
                    expanded = false
                    onTagSelected(null)
                },
                modifier = Modifier.tvFocusable(shape = FilterControlRingShape, addFocusTarget = false),
                leadingIcon = {
                    if (selectedTags.isEmpty()) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
            entries.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(stringResource(tag.titleRes)) },
                    enabled = tag in enabledTags,
                    onClick = {
                        expanded = false
                        onTagSelected(tag)
                    },
                    modifier = Modifier.tvFocusable(
                        enabled = tag in enabledTags,
                        shape = FilterControlRingShape,
                        addFocusTarget = false,
                    ),
                    leadingIcon = {
                        Icon(
                            painter = rememberSafePainter(tag.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (tag in selectedTags) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    trailingIcon = {
                        if (tag in selectedTags) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickFilterItemChips(
    chips: List<ChipModel>,
    context: Context,
    entryPoint: BaseApp.BaseAppEntryPoint?,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
) {
    CompactFilterChipFlow {
        chips.forEach { chip ->
            val option = chip.data as? ListFilterOption
            CompactFilterChip(
                selected = chip.isChecked,
                enabled = option != null,
                onClick = { option?.let(onQuickFilterOptionClick) },
                label = buildChipLabel(context, chip, entryPoint),
                icon = chipIcon(chip),
            )
        }
    }
}
