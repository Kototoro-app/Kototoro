package org.skepsun.kototoro.favourites.ui.compose

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import org.skepsun.kototoro.core.ui.theme.popupMenuContainerColor
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.BaseApp
import org.skepsun.kototoro.core.ui.compose.FilterPanelGroup
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.explore.ui.model.SourceTag
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
 *  - a switch to show/hide the inline quick-filter tab bar at the top of the list,
 *  - the same quick-filter groups/chips the inline tab bar exposes (reading status,
 *    publication status, content rating, work relations, downloaded, sources, tags),
 *  - the original content-source-type (SourceTag) section kept as one option,
 *  - a reset action that clears the quick filter and source tags.
 *
 * [close] dismisses the popup (typically from the "Done" row).
 */
@OptIn(ExperimentalLayoutApi::class)
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

    Column(
        modifier = Modifier
            .widthIn(min = 300.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.filter),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = close) {
                Text(stringResource(R.string.done))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        FilterPanelGroup {
            FilterPanelSwitchRow(
                title = stringResource(R.string.show_quick_filters),
                checked = isInlineQuickFilterEnabled,
                onCheckedChange = onInlineQuickFilterEnabledChange,
            )
        }

        quickFilter?.let { filter ->
            filter.groups.forEach { group ->
                FilterPanelGroup(title = stringResource(group.titleResId)) {
                    if (group.isFacetDropdown()) {
                        QuickFilterGroupDropdown(
                            group = group,
                            context = context,
                            entryPoint = entryPoint,
                            onQuickFilterOptionClick = onQuickFilterOptionClick,
                        )
                    } else {
                        QuickFilterItemChips(
                            chips = group.items,
                            context = context,
                            entryPoint = entryPoint,
                            onQuickFilterOptionClick = onQuickFilterOptionClick,
                        )
                    }
                }
            }
            if (filter.items.isNotEmpty()) {
                FilterPanelGroup {
                    QuickFilterItemChips(
                        chips = filter.items,
                        context = context,
                        entryPoint = entryPoint,
                        onQuickFilterOptionClick = onQuickFilterOptionClick,
                    )
                }
            }
        }

        if (sourceTagEntries.isNotEmpty()) {
            FilterPanelGroup(title = stringResource(R.string.source_type)) {
                SourceTagFilterDropdown(
                    selectedTags = selectedSourceTags,
                    entries = sourceTagEntries,
                    enabledTags = enabledSourceTags,
                    onTagSelected = onSourceTagSelected,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            TextButton(onClick = onResetFilters) {
                Text(stringResource(R.string.reset_filter))
            }
        }
    }
}

private fun QuickFilterGroup.isFacetDropdown(): Boolean =
    items.any { it.data is ListFilterOption.Tag || it.data is ListFilterOption.Source }

@Composable
private fun QuickFilterGroupDropdown(
    group: QuickFilterGroup,
    context: Context,
    entryPoint: BaseApp.BaseAppEntryPoint?,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
) {
    var expanded by remember(group.key) { mutableStateOf(false) }
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

    Box {
        FilterChip(
            selected = selectedItems.isNotEmpty(),
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
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
            label = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            },
        )
        DropdownMenu(
            containerColor = MaterialTheme.colorScheme.popupMenuContainerColor(),
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            group.items.forEach { chip ->
                val option = chip.data as? ListFilterOption ?: return@forEach
                DropdownMenuItem(
                    text = {
                        Text(
                            text = buildChipLabel(context, chip, entryPoint),
                            maxLines = 1,
                        )
                    },
                    onClick = { onQuickFilterOptionClick(option) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(
                                if (chip.isChecked) R.drawable.ic_check else chip.icon,
                            ),
                            contentDescription = null,
                            tint = if (chip.isChecked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reset_filter)) },
                onClick = {
                    selectedItems.forEach { chip ->
                        (chip.data as? ListFilterOption)?.let(onQuickFilterOptionClick)
                    }
                },
                enabled = selectedItems.isNotEmpty(),
            )
        }
    }
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
        FilterChip(
            selected = selectedTags.isNotEmpty(),
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
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
            label = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickFilterItemChips(
    chips: List<ChipModel>,
    context: Context,
    entryPoint: BaseApp.BaseAppEntryPoint?,
    onQuickFilterOptionClick: (ListFilterOption) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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

@Composable
private fun CompactFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 28.dp) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 28.dp),
            leadingIcon = icon,
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
private fun FilterPanelSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .heightIn(min = 44.dp),
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
