package org.skepsun.kototoro.search.ui.compose


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.compose.FilterPanelGroup
import org.skepsun.kototoro.core.ui.model.titleRes
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu

import org.skepsun.kototoro.filter.ui.model.UiTagGroup
import org.skepsun.kototoro.filter.data.PersistableFilter.Companion.MAX_TITLE_LENGTH
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder

@Composable
internal fun SaveFilterNameDialog(
    initialValue: String,
    existingNames: Set<String>,
    rejectExistingName: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val trimmed = value.trim()
    val hasError = trimmed.isEmpty() || (rejectExistingName && trimmed in existingNames)

    SearchInputDialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.save_filter),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(MAX_TITLE_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.filter_name)) },
                    isError = hasError,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                    ),
                )
                if (hasError) {
                    Text(
                        text = stringResource(R.string.invalid_value_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        actions = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
            TextButton(
                enabled = !hasError,
                onClick = { onConfirm(trimmed) },
            ) {
                Text(stringResource(R.string.save))
            }
        },
    )
}

@Composable
internal fun TextInputTagDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onClear: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }

    SearchInputDialogSurface(
        onDismissRequest = onDismissRequest,
        title = title,
        content = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(text = title) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                ),
            )
        },
        actions = {
            TextButton(onClick = onClear) {
                Text(text = stringResource(R.string.clear))
            }
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(android.R.string.cancel))
            }
            TextButton(onClick = { onConfirm(value) }) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
internal fun SortOrderFilterSection(
    sourceName: String,
    sortOrders: List<SortOrder>,
    selectedSortOrder: SortOrder?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
) {
    val selectedLabel = selectedSortOrder?.let { resolveSortOrderLabel(sourceName, it) }.orEmpty()
    Box {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            sortOrders.forEach { item ->
                val selected = item == selectedSortOrder
                CompactDropdownMenuItem(
                    text = { Text(resolveSortOrderLabel(sourceName, item)) },
                    onClick = {
                        onSortOrderChange(item)
                        onExpandedChange(false)
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(
                                if (selected) R.drawable.ic_check else R.drawable.ic_sort,
                            ),
                            contentDescription = null,
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun resolveSortOrderLabel(sourceName: String, order: SortOrder): String {
    return if (sourceName.startsWith("TRACKING_BANGUMI_")) {
        when (order) {
            SortOrder.RATING -> stringResource(R.string.sort_by_ranking)
            SortOrder.POPULARITY -> stringResource(R.string.sort_by_popularity_label)
            SortOrder.ADDED -> stringResource(R.string.sort_by_collection)
            SortOrder.NEWEST -> stringResource(R.string.sort_by_date_label)
            SortOrder.ALPHABETICAL -> stringResource(R.string.sort_by_name_label)
            else -> stringResource(order.titleRes)
        }
    } else {
        stringResource(order.titleRes)
    }
}

@Composable
internal fun TagGroupsSection(
    title: String,
    tagGroups: List<UiTagGroup>,
    excludeMode: Boolean,
    isTextInputTag: (ContentTag) -> Boolean,
    textInputValue: (ContentTag) -> String?,
    textInputLabel: (ContentTag) -> String,
    onToggleTag: (ContentTag, Boolean, Boolean) -> Unit,
    onTextInputTagClick: (ContentTag) -> Unit,
    onOpenTagCatalog: (String?, Boolean) -> Unit,
) {
    val visibleGroups = tagGroups.filter { it.tags.isNotEmpty() }
    if (visibleGroups.isEmpty()) return
    FilterSection(title = title) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            visibleGroups.forEach { group ->
                TagGroupContent(
                    group = group,
                    excludeMode = excludeMode,
                    isTextInputTag = isTextInputTag,
                    textInputValue = textInputValue,
                    textInputLabel = textInputLabel,
                    onToggleTag = onToggleTag,
                    onTextInputTagClick = onTextInputTagClick,
                    onOpenTagCatalog = onOpenTagCatalog,
                )
            }
        }
    }
}

@Composable
private fun TagGroupContent(
    group: UiTagGroup,
    excludeMode: Boolean,
    isTextInputTag: (ContentTag) -> Boolean,
    textInputValue: (ContentTag) -> String?,
    textInputLabel: (ContentTag) -> String,
    onToggleTag: (ContentTag, Boolean, Boolean) -> Unit,
    onTextInputTagClick: (ContentTag) -> Unit,
    onOpenTagCatalog: (String?, Boolean) -> Unit,
) {
    val orderedTags = remember(group) {
        (group.selected.toList() + group.tags.filterNot { it in group.selected }.sortedBy { it.title })
            .distinctBy { it.key }
    }
    val visibleTags = remember(orderedTags) { orderedTags.take(12) }
    val canExpand = orderedTags.size > visibleTags.size

    Column(
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (group.title.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (canExpand) {
                    IconButton(
                        onClick = { onOpenTagCatalog(group.title, excludeMode) },
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.show_more),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        FilterChipFlow {
            visibleTags.forEach { tag ->
                val value = textInputValue(tag)
                val textInput = isTextInputTag(tag) || value != null
                val selected = if (textInput) {
                    !value.isNullOrBlank()
                } else {
                    tag in group.selected
                }
                SearchPanelChip(
                    selected = selected,
                    onClick = {
                        if (textInput) {
                            onTextInputTagClick(tag)
                        } else {
                            onToggleTag(tag, !selected, excludeMode)
                        }
                    },
                    label = {
                        Text(
                            text = if (textInput && !value.isNullOrBlank()) {
                                "${textInputLabel(tag)}: $value"
                            } else if (textInput) {
                                textInputLabel(tag)
                            } else {
                                tag.title
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun FilterSection(
    title: String,
    content: @Composable () -> Unit,
) {
    FilterPanelGroup(title = title) {
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterChipFlow(
    content: @Composable () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
internal fun SearchPanelChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            modifier = modifier.heightIn(min = 36.dp),
            label = {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    label()
                }
            },
            leadingIcon = if (selected) {
                {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                null
            },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = Color.Transparent,
                labelColor = MaterialTheme.colorScheme.onSurface,
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f),
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
                },
            ),
        )
    }
}

internal fun ListMode.iconRes(): Int = when (this) {
    ListMode.LIST -> R.drawable.ic_list
    ListMode.DETAILED_LIST -> R.drawable.ic_list_detailed
    ListMode.GRID,
    ListMode.COMPACT_GRID -> R.drawable.ic_grid
}

