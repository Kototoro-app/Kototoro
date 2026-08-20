package org.skepsun.kototoro.favourites.ui.migration.compose


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.favourites.domain.MergeCandidateGroup
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeStage
import org.skepsun.kototoro.favourites.ui.migration.MigrationUiState
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeStagePlan
import org.skepsun.kototoro.favourites.ui.migration.isExecutableMergeCandidate
import org.skepsun.kototoro.parsers.model.ContentSource

@Composable
internal fun WorkbenchSelectionSummaryCard(
    selectedStage: EntityOrganizeStage,
    summary: WorkbenchSelectionSummary,
    hasMergePreviewSelection: Boolean,
    hasTrackingPreviews: Boolean,
    statusFilter: WorkbenchStatusFilter,
    onStatusFilterChange: (WorkbenchStatusFilter) -> Unit,
    sortMode: WorkbenchSortMode,
    onSortModeChange: (WorkbenchSortMode) -> Unit,
    stageFilters: WorkbenchStageFilters,
    onStageFiltersChange: (WorkbenchStageFilters) -> Unit,
    showSelectedOnly: Boolean,
    onToggleSelectedOnly: () -> Unit,
    onSelectAllRows: () -> Unit,
    onClearAllRows: () -> Unit,
    hasVisibleRows: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterDropdown(
                    label = stringResource(R.string.entity_organize_workbench_status_filter),
                    summary = stringResource(
                        when (statusFilter) {
                            WorkbenchStatusFilter.ALL -> R.string.entity_organize_workbench_filter_all
                            WorkbenchStatusFilter.ACTION_REQUIRED -> R.string.entity_organize_workbench_filter_action_required
                            WorkbenchStatusFilter.SELECTED -> R.string.entity_organize_workbench_filter_selected
                            WorkbenchStatusFilter.UNSELECTED -> R.string.entity_organize_workbench_filter_unselected
                        },
                    ),
                    enabled = true,
                    modifier = Modifier.weight(1f),
                ) {
                    WorkbenchStatusFilter.entries.forEach { filter ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        when (filter) {
                                            WorkbenchStatusFilter.ALL -> R.string.entity_organize_workbench_filter_all
                                            WorkbenchStatusFilter.ACTION_REQUIRED -> R.string.entity_organize_workbench_filter_action_required
                                            WorkbenchStatusFilter.SELECTED -> R.string.entity_organize_workbench_filter_selected
                                            WorkbenchStatusFilter.UNSELECTED -> R.string.entity_organize_workbench_filter_unselected
                                        },
                                    ),
                                )
                            },
                            onClick = { onStatusFilterChange(filter) },
                            trailingIcon = if (filter == statusFilter) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
                FilterDropdown(
                    label = stringResource(R.string.entity_organize_workbench_sort_label),
                    summary = stringResource(
                        when (sortMode) {
                            WorkbenchSortMode.ACTION_FIRST -> R.string.entity_organize_workbench_sort_action_first
                            WorkbenchSortMode.MATCH_SCORE -> R.string.entity_organize_workbench_sort_match_score
                            WorkbenchSortMode.PROJECTIONS -> R.string.entity_organize_workbench_sort_projections
                            WorkbenchSortMode.TITLE -> R.string.entity_organize_workbench_sort_title
                        },
                    ),
                    enabled = true,
                    modifier = Modifier.weight(1f),
                ) {
                    WorkbenchSortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        when (mode) {
                                            WorkbenchSortMode.ACTION_FIRST -> R.string.entity_organize_workbench_sort_action_first
                                            WorkbenchSortMode.MATCH_SCORE -> R.string.entity_organize_workbench_sort_match_score
                                            WorkbenchSortMode.PROJECTIONS -> R.string.entity_organize_workbench_sort_projections
                                            WorkbenchSortMode.TITLE -> R.string.entity_organize_workbench_sort_title
                                        },
                                    ),
                                )
                            },
                            onClick = { onSortModeChange(mode) },
                            trailingIcon = if (mode == sortMode) {
                                { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
                FilterDropdown(
                    label = stringResource(R.string.advanced),
                    summary = workbenchAdvancedFilterSummary(stageFilters),
                    enabled = true,
                    modifier = Modifier.weight(1f),
                ) {
                    StageFilterSection(
                        title = stringResource(R.string.entity_organize_merge_title),
                        selected = stageFilters.merge,
                        onSelectedChange = { merge ->
                            onStageFiltersChange(stageFilters.copy(merge = merge))
                        },
                    )
                    HorizontalDivider()
                    StageFilterSection(
                        title = stringResource(R.string.entity_organize_tracking_title),
                        selected = stageFilters.tracking,
                        onSelectedChange = { tracking ->
                            onStageFiltersChange(stageFilters.copy(tracking = tracking))
                        },
                    )
                    HorizontalDivider()
                    StageFilterSection(
                        title = stringResource(R.string.entity_organize_reading_title),
                        selected = stageFilters.reading,
                        onSelectedChange = { reading ->
                            onStageFiltersChange(stageFilters.copy(reading = reading))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.entity_organize_workbench_stage_filter_clear)) },
                        onClick = {
                            onStageFiltersChange(
                                WorkbenchStageFilters(
                                    merge = emptySet(),
                                    tracking = emptySet(),
                                    reading = emptySet(),
                                ),
                            )
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onSelectAllRows,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp),
                ) {
                    ButtonLabel(
                        stringResource(
                            when (selectedStage) {
                                EntityOrganizeStage.MERGE -> if (!hasMergePreviewSelection) {
                                    R.string.entity_organize_workbench_select_all_scope
                                } else {
                                    R.string.entity_organize_workbench_select_all_merge_groups
                                }
                                EntityOrganizeStage.TRACKING -> if (!hasTrackingPreviews) {
                                    R.string.entity_organize_workbench_select_all_scope
                                } else {
                                    R.string.entity_organize_workbench_select_all
                                }
                                EntityOrganizeStage.READING -> R.string.entity_organize_workbench_select_all
                            },
                        ),
                    )
                }
                OutlinedButton(
                    onClick = onClearAllRows,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp),
                ) {
                    ButtonLabel(
                        stringResource(
                            when (selectedStage) {
                                EntityOrganizeStage.MERGE -> if (!hasMergePreviewSelection) {
                                    R.string.entity_organize_workbench_clear_all_scope
                                } else {
                                    R.string.entity_organize_workbench_clear_all_merge_groups
                                }
                                EntityOrganizeStage.TRACKING -> if (!hasTrackingPreviews) {
                                    R.string.entity_organize_workbench_clear_all_scope
                                } else {
                                    R.string.entity_organize_workbench_clear_all
                                }
                                EntityOrganizeStage.READING -> R.string.entity_organize_workbench_clear_all
                            },
                        ),
                    )
                }
                OutlinedButton(
                    onClick = onToggleSelectedOnly,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp),
                ) {
                    ButtonLabel(
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
            if (!hasVisibleRows) {
                Text(
                    text = stringResource(R.string.entity_organize_filtered_empty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun WorkbenchMetricChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun StageStateChip(
    title: String,
    state: WorkbenchStageState,
    modifier: Modifier = Modifier,
) {
    val (containerColor, contentColor, stateText) = when (state) {
        WorkbenchStageState.EMPTY -> Triple(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.entity_organize_workbench_stage_empty),
        )

        WorkbenchStageState.MISSING -> Triple(
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.onErrorContainer,
            stringResource(R.string.entity_organize_workbench_stage_missing),
        )

        WorkbenchStageState.WARNING -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.onTertiaryContainer,
            stringResource(R.string.entity_organize_workbench_stage_warning),
        )

        WorkbenchStageState.READY -> Triple(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.onSecondaryContainer,
            stringResource(R.string.entity_organize_workbench_stage_ready),
        )
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stateText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun StageFilterDropdown(
    label: String,
    selected: Set<WorkbenchStageState>,
    onSelectedChange: (Set<WorkbenchStageState>) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterDropdown(
        label = label,
        summary = when {
            selected.isEmpty() -> stringResource(R.string.entity_organize_workbench_stage_filter_all)
            selected.size == 1 -> stringResource(stageStateLabelRes(selected.first()))
            else -> stringResource(R.string.entity_organize_workbench_stage_filter_multi, selected.size)
        },
        enabled = true,
        modifier = modifier,
    ) {
        WorkbenchStageState.entries.forEach { state ->
            val checked = state in selected
            DropdownMenuItem(
                text = { Text(stringResource(stageStateLabelRes(state))) },
                onClick = {
                    val next = if (checked) {
                        selected - state
                    } else {
                        selected + state
                    }
                    onSelectedChange(next)
                },
                trailingIcon = if (checked) {
                    { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                } else {
                    null
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.entity_organize_workbench_stage_filter_clear)) },
            onClick = { onSelectedChange(emptySet()) },
        )
    }
}

@Composable
private fun StageFilterSection(
    title: String,
    selected: Set<WorkbenchStageState>,
    onSelectedChange: (Set<WorkbenchStageState>) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WorkbenchStageState.entries.forEach { state ->
                val checked = state in selected
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable {
                            val next = if (checked) selected - state else selected + state
                            onSelectedChange(next)
                        },
                    shape = RoundedCornerShape(999.dp),
                    color = if (checked) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (checked) {
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (checked) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Text(
                            text = stringResource(stageStateLabelRes(state)),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (checked) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun workbenchAdvancedFilterSummary(stageFilters: WorkbenchStageFilters): String {
    val totalSelected = stageFilters.merge.size + stageFilters.tracking.size + stageFilters.reading.size
    return when {
        totalSelected == 0 -> stringResource(R.string.entity_organize_workbench_stage_filter_all)
        totalSelected == 1 -> stringResource(R.string.entity_organize_workbench_stage_filter_multi, totalSelected)
        else -> stringResource(R.string.entity_organize_workbench_stage_filter_multi, totalSelected)
    }
}

@Composable
private fun WorkbenchTableToolbar(
    selectedStage: EntityOrganizeStage,
    onSelectVisibleGroups: () -> Unit,
    onClearVisibleMergeSelections: () -> Unit,
    onSelectVisibleReadingScope: () -> Unit,
    onClearVisibleReadingScope: () -> Unit,
    onSelectRecommendedTracking: () -> Unit,
    onClearLowConfidenceTracking: () -> Unit,
    onClearTrackingSelections: () -> Unit,
    onAcceptReadingPreviews: () -> Unit,
    onClearReadingPreviews: () -> Unit,
    hasVisibleMerge: Boolean,
    hasVisibleTracking: Boolean,
    hasVisibleReading: Boolean,
) {
    val primaryLabel: String
    val secondaryLabel: String
    val primaryEnabled: Boolean
    val secondaryEnabled: Boolean
    val primaryAction: () -> Unit
    val secondaryAction: () -> Unit
    val tertiaryLabel: String?
    val tertiaryEnabled: Boolean
    val tertiaryAction: (() -> Unit)?

    when (selectedStage) {
        EntityOrganizeStage.MERGE -> {
            primaryLabel = stringResource(R.string.entity_organize_workbench_select_visible)
            secondaryLabel = stringResource(R.string.entity_organize_workbench_clear_visible)
            primaryEnabled = hasVisibleMerge
            secondaryEnabled = hasVisibleMerge
            primaryAction = onSelectVisibleGroups
            secondaryAction = onClearVisibleMergeSelections
            tertiaryLabel = null
            tertiaryEnabled = false
            tertiaryAction = null
        }

        EntityOrganizeStage.TRACKING -> {
            primaryLabel = stringResource(R.string.entity_organize_workbench_apply_recommended)
            secondaryLabel = stringResource(R.string.entity_organize_workbench_clear_low_confidence)
            primaryEnabled = hasVisibleTracking
            secondaryEnabled = hasVisibleTracking
            primaryAction = onSelectRecommendedTracking
            secondaryAction = onClearLowConfidenceTracking
            tertiaryLabel = stringResource(R.string.entity_organize_workbench_clear_visible)
            tertiaryEnabled = hasVisibleTracking
            tertiaryAction = onClearTrackingSelections
        }

        EntityOrganizeStage.READING -> {
            primaryLabel = stringResource(R.string.entity_organize_workbench_select_visible)
            secondaryLabel = stringResource(R.string.entity_organize_workbench_clear_visible)
            primaryEnabled = true
            secondaryEnabled = true
            primaryAction = onSelectVisibleReadingScope
            secondaryAction = onClearVisibleReadingScope
            tertiaryLabel = if (hasVisibleReading) {
                stringResource(R.string.entity_organize_workbench_accept_reading)
            } else {
                stringResource(R.string.entity_organize_workbench_clear_reading)
            }
            tertiaryEnabled = true
            tertiaryAction = if (hasVisibleReading) onAcceptReadingPreviews else onClearReadingPreviews
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = primaryAction,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp),
            enabled = primaryEnabled,
        ) {
            ButtonLabel(primaryLabel)
        }
        OutlinedButton(
            onClick = secondaryAction,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp),
            enabled = secondaryEnabled,
        ) {
            ButtonLabel(secondaryLabel)
        }
        if (tertiaryLabel != null && tertiaryAction != null) {
            OutlinedButton(
                onClick = tertiaryAction,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp),
                enabled = tertiaryEnabled,
            ) {
                ButtonLabel(tertiaryLabel)
            }
        }
    }
}

@Composable
internal fun InlineStatusBadge(
    text: String,
    state: WorkbenchStageState,
) {
    val (containerColor, contentColor) = when (state) {
        WorkbenchStageState.EMPTY -> Pair(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        WorkbenchStageState.MISSING -> Pair(
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.onErrorContainer,
        )

        WorkbenchStageState.WARNING -> Pair(
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.onTertiaryContainer,
        )

        WorkbenchStageState.READY -> Pair(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
        )
    }
}

internal fun stageStateLabelRes(state: WorkbenchStageState): Int {
    return when (state) {
        WorkbenchStageState.EMPTY -> R.string.entity_organize_workbench_stage_empty
        WorkbenchStageState.MISSING -> R.string.entity_organize_workbench_stage_missing
        WorkbenchStageState.WARNING -> R.string.entity_organize_workbench_stage_warning
        WorkbenchStageState.READY -> R.string.entity_organize_workbench_stage_ready
    }
}

internal fun buildEntityWorkbenchRows(
    uiState: MigrationUiState,
): List<EntityWorkbenchRow> {
    val candidateGroups = uiState.mergeCandidateGroups
    val groupedMangaIds = candidateGroups.flatMapTo(LinkedHashSet()) { it.mangaIds }
    val workGroups = uiState.organizableWorks
        .filter { work -> work.projections.any { projection -> projection.mangaId !in groupedMangaIds } }
        .map(::workToWorkbenchGroup)
    val groups = candidateGroups + workGroups
    return groups.map { group ->
        EntityWorkbenchRow(
            group = group,
            existingTrackingBindings = uiState.existingTrackingPreviews.filter { it.groupId == group.id },
            trackingCandidates = uiState.trackingPreviews.filter { it.groupId == group.id },
            readingCandidates = uiState.readingSourcePreviews.filter { preview ->
                preview.mangaId in group.mangaIds
            },
            isMergeCandidate = group.isExecutableMergeCandidate(),
        )
    }
}

private fun workToWorkbenchGroup(
    work: org.skepsun.kototoro.favourites.domain.OrganizableWork,
): MergeCandidateGroup {
    return MergeCandidateGroup(
        id = "work:${work.entityId}",
        title = work.title,
        normalizedTitle = work.title.lowercase(),
        contentType = work.projections.firstOrNull()?.source?.let { sourceName ->
            org.skepsun.kototoro.core.model.ContentSource(sourceName).contentType
        } ?: org.skepsun.kototoro.parsers.model.ContentType.MANGA,
        mangaIds = work.projections.mapTo(LinkedHashSet()) { it.mangaId },
        items = work.projections.map { projection ->
            org.skepsun.kototoro.favourites.domain.MergeCandidateItem(
                mangaId = projection.mangaId,
                title = projection.title,
                normalizedTitle = projection.title.lowercase(),
                sourceName = projection.source,
                coverUrl = null,
                score = 1f,
            )
        },
        matchScore = 1f,
        isExactMatch = true,
        resolvedEntityId = work.entityId,
        isAlreadyMerged = work.projections.size >= 2,
    )
}

internal fun stageTabCount(
    stage: EntityOrganizeStage,
    rows: List<EntityWorkbenchRow>,
    uiState: MigrationUiState,
    plan: EntityOrganizeStagePlan,
): StageTabCount {
    if (rows.isEmpty()) {
        return StageTabCount(accepted = plan.acceptedCount, total = plan.previewCount)
    }
    val operationScopeIds = uiState.selectedContentIds.ifEmpty { uiState.manualMergeMangaIds }
    val scopedRows = if (operationScopeIds.isEmpty()) {
        rows
    } else {
        rows.filter { row -> row.group.mangaIds.any(operationScopeIds::contains) }
    }
    return when (stage) {
        EntityOrganizeStage.MERGE -> if (uiState.mergePreviewReady) {
            StageTabCount(
                accepted = plan.acceptedCount,
                total = plan.previewCount,
            )
        } else {
            StageTabCount(
                accepted = scopedRows.count { row -> row.group.mangaIds.any(operationScopeIds::contains) },
                total = scopedRows.size,
            )
        }

        EntityOrganizeStage.TRACKING -> if (uiState.trackingPreviewReady) {
            StageTabCount(
                accepted = plan.acceptedCount,
                total = plan.previewCount,
            )
        } else {
            StageTabCount(
                accepted = scopedRows.count { row -> row.group.mangaIds.any(operationScopeIds::contains) },
                total = scopedRows.size,
            )
        }

        EntityOrganizeStage.READING -> if (uiState.readingSourcePreviews.isNotEmpty()) {
            StageTabCount(
                accepted = plan.acceptedCount,
                total = plan.previewCount,
            )
        } else {
            StageTabCount(
                accepted = scopedRows.count { row -> row.group.mangaIds.any(operationScopeIds::contains) },
                total = scopedRows.size,
            )
        }
    }
}

internal fun resolveStageWorkbenchViewState(
    selectedStage: EntityOrganizeStage,
    current: EntityOrganizeWorkbenchViewState,
): EntityOrganizeWorkbenchViewState {
    return current
}

