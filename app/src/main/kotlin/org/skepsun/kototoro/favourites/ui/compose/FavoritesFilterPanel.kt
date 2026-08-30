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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
                    QuickFilterItemChips(
                        chips = group.items,
                        context = context,
                        entryPoint = entryPoint,
                        onQuickFilterOptionClick = onQuickFilterOptionClick,
                    )
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CompactFilterChip(
                        selected = selectedSourceTags.isEmpty(),
                        onClick = { onSourceTagSelected(null) },
                        label = stringResource(R.string.all),
                    )
                    sourceTagEntries.forEach { tag ->
                        CompactFilterChip(
                            selected = tag in selectedSourceTags,
                            enabled = tag in enabledSourceTags,
                            onClick = { onSourceTagSelected(tag) },
                            label = stringResource(tag.titleRes),
                            icon = {
                                Icon(
                                    // Same safe loader the default source-tag dropdown uses;
                                    // painterResource rejects some of these drawable types.
                                    painter = rememberSafePainter(tag.iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
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
