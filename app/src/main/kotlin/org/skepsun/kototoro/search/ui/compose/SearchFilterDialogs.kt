package org.skepsun.kototoro.search.ui.compose


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.model.titleRes

import org.skepsun.kototoro.filter.ui.model.UiTagGroup
import org.skepsun.kototoro.filter.data.PersistableFilter.Companion.MAX_TITLE_LENGTH
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import java.util.Locale

private const val VISIBLE_TAG_LIMIT = 8
private const val SEARCH_TAG_LIMIT = 16

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
        FilterDropdownAnchor(
            label = selectedLabel,
            expanded = expanded,
            onClick = { onExpandedChange(!expanded) },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.widthIn(min = 180.dp, max = 280.dp),
        ) {
            sortOrders.forEach { item ->
                val selected = item == selectedSortOrder
                DropdownMenuItem(
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
internal fun LocaleFilterSection(
    locales: List<Locale?>,
    selectedLocale: Locale?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onLocaleChange: (Locale?) -> Unit,
) {
    val selectedLabel = localeLabel(selectedLocale)
    Box {
        FilterDropdownAnchor(
            label = selectedLabel,
            expanded = expanded,
            onClick = { onExpandedChange(!expanded) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.widthIn(min = 160.dp, max = 260.dp),
        ) {
            locales.forEach { locale ->
                val selected = locale == selectedLocale
                DropdownMenuItem(
                    text = { Text(localeLabel(locale)) },
                    onClick = {
                        onLocaleChange(locale)
                        onExpandedChange(false)
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(
                                if (selected) R.drawable.ic_check else R.drawable.ic_language,
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
private fun FilterDropdownAnchor(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (expanded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun localeLabel(locale: Locale?): String = if (locale == null) {
    stringResource(R.string.all)
} else {
    locale.getDisplayName(locale).ifBlank { locale.toLanguageTag() }
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
) {
    val visibleGroups = tagGroups.filter { it.tags.isNotEmpty() }
    if (visibleGroups.isEmpty()) return
    var query by rememberSaveable(title, excludeMode) { mutableStateOf("") }
    val singleGroup = visibleGroups.singleOrNull()
    FilterSection(
        title = title,
        iconRes = R.drawable.ic_tag,
        headerAction = singleGroup?.takeIf { it.tags.size > VISIBLE_TAG_LIMIT }?.let { group ->
            {
                TagGroupDropdown(
                    group = group,
                    excludeMode = excludeMode,
                    isTextInputTag = isTextInputTag,
                    textInputValue = textInputValue,
                    textInputLabel = textInputLabel,
                    onToggleTag = onToggleTag,
                    onTextInputTagClick = onTextInputTagClick,
                )
            }
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (visibleGroups.sumOf { it.tags.size } > VISIBLE_TAG_LIMIT) {
                val searchShape = RoundedCornerShape(12.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .background(MaterialTheme.colorScheme.surface, searchShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, searchShape)
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.search),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }
            }
            visibleGroups.forEach { group ->
                TagGroupContent(
                    group = group,
                    showTitle = singleGroup == null,
                    query = query,
                    excludeMode = excludeMode,
                    isTextInputTag = isTextInputTag,
                    textInputValue = textInputValue,
                    textInputLabel = textInputLabel,
                    onToggleTag = onToggleTag,
                    onTextInputTagClick = onTextInputTagClick,
                )
            }
        }
    }
}

@Composable
private fun TagGroupContent(
    group: UiTagGroup,
    showTitle: Boolean,
    query: String,
    excludeMode: Boolean,
    isTextInputTag: (ContentTag) -> Boolean,
    textInputValue: (ContentTag) -> String?,
    textInputLabel: (ContentTag) -> String,
    onToggleTag: (ContentTag, Boolean, Boolean) -> Unit,
    onTextInputTagClick: (ContentTag) -> Unit,
) {
    val orderedTags = remember(group, query) {
        (group.selected.toList() + group.tags.filterNot { it in group.selected }.sortedBy { it.title })
            .distinctBy { it.key }
            .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
    }
    val previewLimit = if (query.isBlank()) VISIBLE_TAG_LIMIT else SEARCH_TAG_LIMIT
    val visibleTags = remember(orderedTags, previewLimit) { orderedTags.take(previewLimit) }
    val canExpand = group.tags.size > VISIBLE_TAG_LIMIT

    Column(
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (showTitle && group.title.isNotBlank()) {
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
                    TagGroupDropdown(
                        group = group,
                        excludeMode = excludeMode,
                        isTextInputTag = isTextInputTag,
                        textInputValue = textInputValue,
                        textInputLabel = textInputLabel,
                        onToggleTag = onToggleTag,
                        onTextInputTagClick = onTextInputTagClick,
                    )
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
private fun TagGroupDropdown(
    group: UiTagGroup,
    excludeMode: Boolean,
    isTextInputTag: (ContentTag) -> Boolean,
    textInputValue: (ContentTag) -> String?,
    textInputLabel: (ContentTag) -> String,
    onToggleTag: (ContentTag, Boolean, Boolean) -> Unit,
    onTextInputTagClick: (ContentTag) -> Unit,
) {
    var expanded by rememberSaveable(group.title, excludeMode) { mutableStateOf(false) }
    val orderedTags = remember(group) {
        (group.selected.toList() + group.tags.filterNot { it in group.selected }.sortedBy { it.title })
            .distinctBy { it.key }
    }
    Box {
        IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.show_more),
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 220.dp, max = 320.dp)
                .heightIn(max = 360.dp),
        ) {
            orderedTags.forEach { tag ->
                val value = textInputValue(tag)
                val textInput = isTextInputTag(tag) || value != null
                val selected = if (textInput) !value.isNullOrBlank() else tag in group.selected
                DropdownMenuItem(
                    text = {
                        Text(
                            text = when {
                                textInput && !value.isNullOrBlank() -> "${textInputLabel(tag)}: $value"
                                textInput -> textInputLabel(tag)
                                else -> tag.title
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        if (textInput) {
                            expanded = false
                            onTextInputTagClick(tag)
                        } else {
                            onToggleTag(tag, !selected, excludeMode)
                        }
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(
                                if (selected) R.drawable.ic_check else R.drawable.ic_tag,
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
internal fun FilterSection(
    title: String,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                iconRes?.let {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(13.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(it),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                headerAction?.invoke()
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterChipFlow(
    content: @Composable () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
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
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            modifier = modifier.heightIn(min = 24.dp),
            shape = RoundedCornerShape(8.dp),
            label = {
                androidx.compose.material3.ProvideTextStyle(
                    MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
                ) {
                    label()
                }
            },
            leadingIcon = if (selected) {
                {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                    )
                }
            } else {
                null
            },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surface,
                labelColor = MaterialTheme.colorScheme.onSurface,
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.primary,
                selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
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
