package org.skepsun.kototoro.favourites.ui.migration.compose


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getStableIdentityKey
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeStage
import org.skepsun.kototoro.favourites.domain.ReadingSourcePreview
import org.skepsun.kototoro.favourites.domain.ReadingSourcePreviewAction
import org.skepsun.kototoro.favourites.ui.migration.MigrationUiState
import org.skepsun.kototoro.parsers.model.ContentSource

@Composable
internal fun TargetSourcesSection(
    uiState: MigrationUiState,
    onToggleTargetSource: (ContentSource) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onRemove: (String) -> Unit,
    onContentTypeToggle: (BrowseGroupTab) -> Unit,
    onSourceTagToggle: (SourceTag) -> Unit,
    onPreview: () -> Unit,
) {
    val stagePlan = uiState.stagePlan(EntityOrganizeStage.READING)
    var showSelector by remember { mutableStateOf(false) }
    val enabled = !uiState.isExecuting
    val context = androidx.compose.ui.platform.LocalContext.current
    val targetSourceTagSummary = uiState.toSourceTagFilter.takeIf { it.isNotEmpty() }
        ?.joinToString { context.getString(it.titleRes) }
        ?: context.getString(R.string.filter_all)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.entity_organize_reading_targets_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterDropdown(
                label = stringResource(R.string.content_type_filter),
                summary = uiState.toContentTypeFilter.takeIf { it.isNotEmpty() }?.joinToString { contentTypeLabel(context, it) }
                    ?: stringResource(R.string.filter_all),
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                listOf(BrowseGroupTab.Content, BrowseGroupTab.Novel, BrowseGroupTab.Video).forEach { tab ->
                    DropdownMenuItem(
                        text = { Text(stringResource(tab.titleRes)) },
                        onClick = { onContentTypeToggle(tab) },
                        trailingIcon = if (tab in uiState.toContentTypeFilter) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }

            FilterDropdown(
                label = stringResource(R.string.source_type_filter),
                summary = targetSourceTagSummary,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                SourceTag.quickFilterEntries.forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(stringResource(tag.titleRes)) },
                        onClick = { onSourceTagToggle(tag) },
                        trailingIcon = if (tag in uiState.toSourceTagFilter) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { if (enabled) showSelector = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && uiState.toFilteredSources.isNotEmpty(),
        ) {
            Text(stringResource(R.string.migration_select_target_sources))
        }

        if (uiState.selectedTargetSources.isEmpty()) {
            Text(
                text = stringResource(R.string.migration_no_target_sources_selected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            uiState.selectedTargetSources.forEachIndexed { index, source ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}. ${source.getEntityOrganizeDisplayTitle(androidx.compose.ui.platform.LocalContext.current)}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(
                        onClick = { onMoveUp(source.getStableIdentityKey()) },
                        enabled = enabled && index > 0,
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = null)
                    }
                    IconButton(
                        onClick = { onMoveDown(source.getStableIdentityKey()) },
                        enabled = enabled && index < uiState.selectedTargetSources.lastIndex,
                    ) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = null)
                    }
                    IconButton(
                        onClick = { onRemove(source.getStableIdentityKey()) },
                        enabled = enabled,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            }
            OutlinedButton(
                onClick = onPreview,
                modifier = Modifier.fillMaxWidth(),
                enabled = stagePlan.canPreview,
            ) {
                Text(stringResource(R.string.entity_organize_reading_preview))
            }
        }
        Text(
            text = stringResource(R.string.entity_organize_stage_panel_workbench_hint_reading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StageFeedbackText(uiState, EntityOrganizeStage.READING)
    }

    if (showSelector) {
        MultiSourceSelectorDialog(
            title = stringResource(R.string.migration_select_target_sources),
            sources = uiState.toFilteredSources,
            selectedSourceKeys = uiState.selectedTargetSources.map { it.getStableIdentityKey() }.toSet(),
            onToggle = onToggleTargetSource,
            onDismiss = { showSelector = false },
        )
    }
}

@Composable
internal fun <T> EntityBrowseSection(
    query: String,
    onQueryChange: (String) -> Unit,
    showSelectedOnly: Boolean,
    onToggleSelectedOnly: () -> Unit,
    visibleCount: Int,
    totalCount: Int,
    pagedItems: EntityBrowsePage<T>,
    pageSize: Int,
    onPageSizeChange: (Int) -> Unit,
    onPageChange: (Int) -> Unit,
    extraToggleLabel: String? = null,
    onExtraToggle: (() -> Unit)? = null,
    emptyContent: @Composable (() -> Unit)? = null,
    showSelectionToggle: Boolean = true,
    tableToolbar: @Composable ((List<T>) -> Unit)? = null,
    content: @Composable (List<T>) -> Unit,
) {
    SectionFilterBar(
        query = query,
        onQueryChange = onQueryChange,
        showSelectedOnly = showSelectedOnly,
        onToggleSelectedOnly = onToggleSelectedOnly,
        extraToggleLabel = extraToggleLabel,
        onExtraToggle = onExtraToggle,
        showSelectionToggle = showSelectionToggle,
    )
    if (totalCount == 0) {
        emptyContent?.invoke()
        return
    }
    if (visibleCount == 0) {
        Text(
            text = stringResource(R.string.entity_organize_filtered_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    PagedBrowseToolbar(
        visibleCount = visibleCount,
        totalCount = totalCount,
        page = pagedItems.page,
        pageCount = pagedItems.pageCount,
        pageSize = pageSize,
        onPageSizeChange = onPageSizeChange,
        onPageChange = onPageChange,
    )
    tableToolbar?.invoke(pagedItems.items)
    content(pagedItems.items)
}

@Composable
private fun SectionFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    showSelectedOnly: Boolean,
    onToggleSelectedOnly: () -> Unit,
    extraToggleLabel: String? = null,
    onExtraToggle: (() -> Unit)? = null,
    showSelectionToggle: Boolean = true,
) {
    val hasActionRow = showSelectionToggle || (extraToggleLabel != null && onExtraToggle != null)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchPillTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.entity_organize_filter_placeholder),
        )
        if (hasActionRow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showSelectionToggle) {
                    OutlinedButton(
                        onClick = onToggleSelectedOnly,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(
                                if (showSelectedOnly) {
                                    R.string.entity_organize_show_all
                                } else {
                                    R.string.entity_organize_show_selected_only
                                },
                            ),
                        )
                    }
                }
                if (extraToggleLabel != null && onExtraToggle != null) {
                    OutlinedButton(
                        onClick = onExtraToggle,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(extraToggleLabel)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchPillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)),
        tonalElevation = 2.dp,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.padding(8.dp).size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                    if (value.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onValueChange("") },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.padding(8.dp).size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun PagedBrowseToolbar(
    visibleCount: Int,
    totalCount: Int,
    page: Int,
    pageCount: Int,
    pageSize: Int,
    onPageSizeChange: (Int) -> Unit,
    onPageChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = stringResource(R.string.entity_organize_page_size_value, pageSize),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    ENTITY_ORGANIZE_PAGE_SIZES.forEach { size ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.entity_organize_page_size_value, size)) },
                            onClick = {
                                onPageSizeChange(size)
                                expanded = false
                            },
                            trailingIcon = if (size == pageSize) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { onPageChange(page - 1) },
                        enabled = page > 0,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(16.dp))
                    }
                    Text(
                        text = stringResource(R.string.entity_organize_page_indicator, page + 1, pageCount),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    IconButton(
                        onClick = { onPageChange(page + 1) },
                        enabled = page + 1 < pageCount,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
@Composable
internal fun <T> rememberPagedBrowsePage(
    items: List<T>,
    pageSize: Int,
    currentPage: Int,
    onPageChanged: (Int) -> Unit,
): EntityBrowsePage<T> {
    val safePageSize = pageSize.coerceAtLeast(1)
    val pageCount = remember(items.size, safePageSize) {
        maxOf(1, (items.size + safePageSize - 1) / safePageSize)
    }
    val safePage = currentPage.coerceIn(0, pageCount - 1)
    LaunchedEffect(safePage) {
        if (safePage != currentPage) {
            onPageChanged(safePage)
        }
    }
    val visibleItems = remember(items, safePageSize, safePage) {
        val fromIndex = safePage * safePageSize
        val toIndex = minOf(items.size, fromIndex + safePageSize)
        if (fromIndex >= items.size) emptyList() else items.subList(fromIndex, toIndex)
    }
    return EntityBrowsePage(
        items = visibleItems,
        page = safePage,
        pageCount = pageCount,
    )
}

@Composable
private fun ReadingPreviewCard(
    preview: ReadingSourcePreview,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = preview.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = preview.targetSourceDisplayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = when (preview.action) {
                        ReadingSourcePreviewAction.ACTIVATE_EXISTING ->
                            stringResource(R.string.entity_organize_reading_action_activate_existing_detail)
                        ReadingSourcePreviewAction.ATTACH_NEW ->
                            stringResource(R.string.entity_organize_reading_action_attach_new_detail)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = preview.matchedTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun FilterDropdown(
    label: String,
    summary: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    menuItems: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        CompactDropdownButton(
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            label = label,
            value = summary,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 4.dp),
        ) {
            menuItems()
        }
    }
}

@Composable
internal fun ConcurrencyDropdown(
    value: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(1, 2, 3, 5, 8, 10)
    Box(modifier = modifier) {
        CompactDropdownButton(
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.source_migration_concurrency_label),
            value = value.toString(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 4.dp),
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text("$opt") },
                    onClick = { onSelect(opt); expanded = false },
                    trailingIcon = if (opt == value) {
                        { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun CompactDropdownButton(
    onClick: () -> Unit,
    enabled: Boolean,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (enabled) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f)
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (enabled) 0.82f else 0.52f),
            ) {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.padding(6.dp).size(16.dp),
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
            }
        }
    }
}

@Composable
internal fun buildWorkbenchEntityMeta(row: EntityWorkbenchRow): String {
    val typeName = row.group.contentType.name
    val projectionCount = row.group.mangaIds.size
    val entityId = row.group.resolvedEntityId
    return when {
        entityId != null -> "$typeName · E#$entityId · $projectionCount"
        else -> "$typeName · $projectionCount"
    }
}

@Composable
internal fun buildWorkbenchEntityDetail(row: EntityWorkbenchRow): String {
    return when {
        row.isMergeCandidate -> stringResource(
            if (row.group.isExactMatch) {
                R.string.entity_organize_merge_candidates_exact
            } else {
                R.string.entity_organize_merge_candidates_fuzzy
            },
            row.group.matchScore.toPercentInt(),
        )

        row.group.isAlreadyMerged -> stringResource(R.string.entity_organize_workbench_entity_archived)
        row.group.resolvedEntityId != null -> stringResource(R.string.entity_organize_workbench_entity_local)
        else -> stringResource(R.string.entity_organize_workbench_entity_unarchived)
    }
}

