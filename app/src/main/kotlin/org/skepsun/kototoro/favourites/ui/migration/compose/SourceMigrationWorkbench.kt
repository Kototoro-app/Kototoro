package org.skepsun.kototoro.favourites.ui.migration.compose


import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.favourites.domain.MergeCandidateGroup
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeStage
import org.skepsun.kototoro.favourites.ui.migration.MigrationUiState
import java.util.Locale

@Composable
internal fun EntityWorkbenchSection(
    selectedStage: EntityOrganizeStage,
    rows: List<EntityWorkbenchRow>,
    uiState: MigrationUiState,
    viewState: EntityOrganizeWorkbenchViewState,
    workbenchDefaults: EntityOrganizeWorkbenchDefaults,
    onViewStateChange: (EntityOrganizeWorkbenchViewState) -> Unit,
    onToggleGroup: (String) -> Unit,
    onToggleReadingScopeGroup: (String) -> Unit,
    onSetGroupsSelected: (Set<String>, Boolean) -> Unit,
    onSetReadingScopeGroupsSelected: (Set<String>, Boolean) -> Unit,
    onToggleItem: (String, Long) -> Unit,
    onToggleTrackingPreview: (String) -> Unit,
    onToggleReadingPreview: (Long) -> Unit,
    onSelectRecommendedTracking: (Set<String>) -> Unit,
    onClearLowConfidenceTracking: (Set<String>) -> Unit,
    onClearTrackingSelections: (Set<String>) -> Unit,
    onAcceptReadingPreviews: (Set<Long>) -> Unit,
    onClearReadingPreviews: (Set<Long>) -> Unit,
    onSplitLocalProjection: (Long) -> Unit,
    onDetachLocalProjection: (Long) -> Unit,
) {
    val normalizedQuery = viewState.query.trim().lowercase(Locale.ROOT)
    val filteredRows = remember(
        rows,
        selectedStage,
        uiState.mergePreviewReady,
        uiState.trackingPreviewReady,
        uiState.selectedMergeItemsByGroup,
        uiState.selectedMergeGroupIds,
        uiState.selectedTrackingPreviewIds,
        uiState.acceptedReadingPreviewIds,
        normalizedQuery,
        viewState.showSelectedOnly,
        viewState.statusFilter,
        viewState.sortMode,
        viewState.stageFilters,
    ) {
        val matchedRows = rows.filter { row ->
            val matchesPreviewResult = when (selectedStage) {
                EntityOrganizeStage.MERGE -> !uiState.mergePreviewReady || row.isMergeCandidate
                EntityOrganizeStage.TRACKING -> !uiState.trackingPreviewReady ||
                    row.trackingCandidates.isNotEmpty() ||
                    row.existingTrackingBindings.isNotEmpty()
                EntityOrganizeStage.READING -> true
            }
            val manualMergeSelected = row.group.items.any { item ->
                item.mangaId in uiState.selectedMergeItemsByGroup[row.group.id].orEmpty()
            }
            val mergeSelected = row.isMergeSelected(uiState)
            val trackingSelected = row.hasTrackingSelected(uiState)
            val readingSelected = row.hasReadingSelected(uiState)
            val matchesSelection = !viewState.showSelectedOnly ||
                manualMergeSelected ||
                mergeSelected ||
                trackingSelected ||
                readingSelected
            val needsAction = row.needsAction(uiState)
            val matchesStatus = when (viewState.statusFilter) {
                WorkbenchStatusFilter.ALL -> true
                WorkbenchStatusFilter.ACTION_REQUIRED -> needsAction
                WorkbenchStatusFilter.SELECTED -> manualMergeSelected || mergeSelected || trackingSelected || readingSelected
                WorkbenchStatusFilter.UNSELECTED -> !manualMergeSelected && !mergeSelected && !trackingSelected && !readingSelected
            }
            val matchesQuery = normalizedQuery.isBlank() || buildString {
                append(row.group.title)
                append(' ')
                append(row.group.contentType.name)
                append(' ')
                row.group.items.forEach {
                    append(it.title)
                    append(' ')
                    append(it.displaySourceName)
                    append(' ')
                }
                row.trackingCandidates.forEach {
                    append(it.matchedTitle)
                    append(' ')
                    append(it.service.name)
                    append(' ')
                }
                row.existingTrackingBindings.forEach {
                    append(it.matchedTitle)
                    append(' ')
                    append(it.service.name)
                    append(' ')
                }
                row.readingCandidates.forEach {
                    append(it.targetSourceDisplayName)
                    append(' ')
                    append(it.matchedTitle)
                    append(' ')
                }
            }.lowercase(Locale.ROOT).contains(normalizedQuery)
            val matchesStageFilters = row.matchesStageFilters(
                uiState = uiState,
                filters = viewState.stageFilters,
            )
            matchesPreviewResult && matchesSelection && matchesStatus && matchesQuery && matchesStageFilters
        }
        sortWorkbenchRows(
            rows = matchedRows,
            uiState = uiState,
            sortMode = viewState.sortMode,
        )
    }
    val workbenchSummary = remember(
        rows,
        filteredRows,
        uiState.selectedManualMergeMangaIds,
        uiState.selectedMergeGroupIds,
        uiState.selectedTrackingPreviewIds,
        uiState.acceptedReadingPreviewIds,
    ) {
        buildWorkbenchSelectionSummary(
            rows = rows,
            filteredRows = filteredRows,
            uiState = uiState,
        )
    }
    val pagedRows = rememberPagedBrowsePage(
        items = filteredRows,
        pageSize = viewState.pageSize,
        currentPage = viewState.currentPage,
        onPageChanged = { page ->
            onViewStateChange(viewState.copy(currentPage = page))
        },
    )
    val pageResetKey = remember(
        normalizedQuery,
        viewState.showSelectedOnly,
        viewState.statusFilter,
        viewState.pageSize,
        viewState.stageFilters,
    ) {
        listOf(
            normalizedQuery,
            viewState.showSelectedOnly.toString(),
            viewState.statusFilter.name,
            viewState.pageSize.toString(),
            viewState.stageFilters.merge.sortedBy(WorkbenchStageState::name).joinToString(",") { it.name },
            viewState.stageFilters.tracking.sortedBy(WorkbenchStageState::name).joinToString(",") { it.name },
            viewState.stageFilters.reading.sortedBy(WorkbenchStageState::name).joinToString(",") { it.name },
        ).joinToString("|")
    }
    var lastPageResetKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(
        selectedStage,
        rows.size,
        filteredRows.size,
        pagedRows.page,
        pagedRows.items.size,
        uiState.mergePreviewReady,
        uiState.fuzzyMergeCandidatesEnabled,
        uiState.fuzzyMergeThresholdPercent,
        viewState.statusFilter,
        viewState.sortMode,
        viewState.showSelectedOnly,
    ) {
        if (selectedStage == EntityOrganizeStage.MERGE && uiState.mergePreviewReady) {
            Log.d(
                ENTITY_ORGANIZE_LOG_TAG,
                "EntityWorkbench: stage=$selectedStage, rows=${rows.size}, filtered=${filteredRows.size}, " +
                    "page=${pagedRows.page + 1}/${pagedRows.pageCount}, pageItems=${pagedRows.items.size}, " +
                    "fuzzyRows=${rows.count { it.group.isFuzzyMergeCandidate() }}, " +
                    "filteredFuzzy=${filteredRows.count { it.group.isFuzzyMergeCandidate() }}, " +
                    "pageFuzzy=${pagedRows.items.count { it.group.isFuzzyMergeCandidate() }}, " +
                    "statusFilter=${viewState.statusFilter}, sortMode=${viewState.sortMode}, " +
                    "showSelectedOnly=${viewState.showSelectedOnly}, " +
                    "threshold=${uiState.fuzzyMergeThresholdPercent}%",
            )
        }
    }
    LaunchedEffect(pageResetKey) {
        val previousKey = lastPageResetKey
        lastPageResetKey = pageResetKey
        if (previousKey != null && previousKey != pageResetKey && viewState.currentPage != 0) {
            onViewStateChange(viewState.copy(currentPage = 0))
        }
    }
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.entity_organize_workbench_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.entity_organize_workbench_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
                WorkbenchSelectionSummaryCard(
                    selectedStage = selectedStage,
                    summary = workbenchSummary,
                    hasMergePreviewSelection = uiState.mergePreviewReady,
                    hasTrackingPreviews = uiState.trackingPreviewReady,
                    onSelectAllRows = {},
                    onClearAllRows = {},
                    statusFilter = viewState.statusFilter,
                    onStatusFilterChange = { onViewStateChange(viewState.copy(statusFilter = it)) },
                    sortMode = viewState.sortMode,
                    onSortModeChange = { onViewStateChange(viewState.copy(sortMode = it)) },
                    stageFilters = viewState.stageFilters,
                    onStageFiltersChange = { onViewStateChange(viewState.copy(stageFilters = it)) },
                    showSelectedOnly = false,
                    onToggleSelectedOnly = {},
                    hasVisibleRows = pagedRows.items.isNotEmpty(),
                )
            EntityBrowseSection(
                query = viewState.query,
                onQueryChange = { onViewStateChange(viewState.copy(query = it)) },
                showSelectedOnly = false,
                onToggleSelectedOnly = {},
                visibleCount = filteredRows.size,
                totalCount = rows.size,
                pagedItems = pagedRows,
                pageSize = viewState.pageSize,
                onPageSizeChange = { onViewStateChange(viewState.copy(pageSize = it)) },
                onPageChange = { onViewStateChange(viewState.copy(currentPage = it)) },
                showSelectionToggle = false,
                emptyContent = {
                    Text(
                        text = stringResource(R.string.entity_organize_merge_candidates_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                tableToolbar = null,
            ) { visibleRows ->
                val visibleGroupIds = remember(visibleRows) {
                    visibleRows.mapTo(LinkedHashSet()) { it.group.id }
                }
                val visibleReadingIds = remember(visibleRows) {
                    visibleRows.flatMapTo(LinkedHashSet()) { row -> row.readingCandidates.map { it.mangaId } }
                }
                val checkableVisibleRows = remember(visibleRows, selectedStage, uiState) {
                    visibleRows.filter { it.isRowSelectionEnabled(selectedStage, uiState) }
                }
                val allVisibleSelected = checkableVisibleRows.isNotEmpty() &&
                    checkableVisibleRows.all { it.isRowSelectionChecked(selectedStage, uiState) }
                val onToggleSelectAll: (Boolean) -> Unit = { select ->
                    if (select) {
                        when (selectedStage) {
                            EntityOrganizeStage.MERGE -> {
                                if (!uiState.mergePreviewReady) onSetReadingScopeGroupsSelected(visibleGroupIds, true)
                                else onSetGroupsSelected(visibleGroupIds, true)
                            }
                            EntityOrganizeStage.TRACKING -> {
                                if (!uiState.trackingPreviewReady) onSetReadingScopeGroupsSelected(visibleGroupIds, true)
                                else onSelectRecommendedTracking(visibleGroupIds)
                            }
                            EntityOrganizeStage.READING -> onSetReadingScopeGroupsSelected(visibleGroupIds, true)
                        }
                    } else {
                        when (selectedStage) {
                            EntityOrganizeStage.MERGE -> {
                                if (!uiState.mergePreviewReady) onSetReadingScopeGroupsSelected(visibleGroupIds, false)
                                else onSetGroupsSelected(visibleGroupIds, false)
                            }
                            EntityOrganizeStage.TRACKING -> {
                                if (!uiState.trackingPreviewReady) onSetReadingScopeGroupsSelected(visibleGroupIds, false)
                                else onClearTrackingSelections(visibleGroupIds)
                            }
                            EntityOrganizeStage.READING -> {
                                onSetReadingScopeGroupsSelected(visibleGroupIds, false)
                                onClearReadingPreviews(visibleReadingIds)
                            }
                        }
                    }
                }
                EntityWorkbenchHeader(
                    allSelected = allVisibleSelected,
                    onToggleSelectAll = onToggleSelectAll,
                )
                visibleRows.forEach { row ->
                    EntityWorkbenchRowCard(
                        selectedStage = selectedStage,
                        row = row,
                        uiState = uiState,
                        onToggleGroup = onToggleGroup,
                        onToggleReadingScopeGroup = onToggleReadingScopeGroup,
                        onToggleItem = onToggleItem,
                        onToggleTrackingPreview = onToggleTrackingPreview,
                        onToggleReadingPreview = onToggleReadingPreview,
                        onSplitLocalProjection = onSplitLocalProjection,
                        onDetachLocalProjection = onDetachLocalProjection,
                    )
                }
            }
        }
    }
}

internal fun EntityWorkbenchRow.isMergeSelected(uiState: MigrationUiState): Boolean {
    return isMergeCandidate && group.id in uiState.selectedMergeGroupIds
}

internal fun EntityWorkbenchRow.hasTrackingSelected(uiState: MigrationUiState): Boolean {
    return trackingCandidates.any { it.previewId in uiState.selectedTrackingPreviewIds }
}

internal fun EntityWorkbenchRow.hasReadingSelected(uiState: MigrationUiState): Boolean {
    return readingCandidates.any { it.mangaId in uiState.acceptedReadingPreviewIds }
}

internal fun EntityWorkbenchRow.isRowSelectionChecked(
    selectedStage: EntityOrganizeStage,
    uiState: MigrationUiState,
): Boolean {
    val operationScopeIds = uiState.selectedContentIds.ifEmpty { uiState.manualMergeMangaIds }
    val isInOperationScope = group.mangaIds.any(operationScopeIds::contains)
    return when (selectedStage) {
        EntityOrganizeStage.MERGE -> if (uiState.mergePreviewReady) isMergeSelected(uiState) else isInOperationScope
        EntityOrganizeStage.TRACKING -> if (uiState.trackingPreviewReady) hasTrackingSelected(uiState) else isInOperationScope
        EntityOrganizeStage.READING -> isInOperationScope
    }
}

internal fun EntityWorkbenchRow.isRowSelectionEnabled(
    selectedStage: EntityOrganizeStage,
    uiState: MigrationUiState,
): Boolean {
    return when (selectedStage) {
        EntityOrganizeStage.MERGE -> !uiState.mergePreviewReady || isMergeCandidate
        EntityOrganizeStage.TRACKING -> !uiState.trackingPreviewReady || trackingCandidates.isNotEmpty()
        EntityOrganizeStage.READING -> true
    }
}

internal fun EntityWorkbenchRow.currentProjectionItem(): org.skepsun.kototoro.favourites.domain.MergeCandidateItem? {
    if (group.resolvedEntityId == null) {
        return null
    }
    return group.items.firstOrNull()
}

internal fun EntityWorkbenchRow.hasLowConfidenceTracking(): Boolean {
    return trackingCandidates.any { it.confidence.toPercentInt() < TRACKING_CONFIDENCE_WARNING_THRESHOLD }
}

internal fun EntityWorkbenchRow.stageSnapshot(uiState: MigrationUiState): WorkbenchRowStageSnapshot {
    val mergeState = when {
        group.isAlreadyMerged -> WorkbenchStageState.READY
        !isMergeCandidate -> WorkbenchStageState.EMPTY
        isMergeSelected(uiState) -> WorkbenchStageState.READY
        else -> WorkbenchStageState.MISSING
    }
    val trackingState = when {
        existingTrackingBindings.isNotEmpty() -> WorkbenchStageState.READY
        trackingCandidates.isEmpty() -> WorkbenchStageState.EMPTY
        !hasTrackingSelected(uiState) -> WorkbenchStageState.MISSING
        hasLowConfidenceTracking() -> WorkbenchStageState.WARNING
        else -> WorkbenchStageState.READY
    }
    val readingState = when {
        currentProjectionItem() != null -> WorkbenchStageState.READY
        readingCandidates.isEmpty() -> WorkbenchStageState.EMPTY
        !hasReadingSelected(uiState) -> WorkbenchStageState.MISSING
        else -> WorkbenchStageState.READY
    }
    return WorkbenchRowStageSnapshot(
        mergeState = mergeState,
        trackingState = trackingState,
        readingState = readingState,
    )
}

internal fun EntityWorkbenchRow.needsAction(uiState: MigrationUiState): Boolean {
    return (isMergeCandidate && !isMergeSelected(uiState)) ||
        (trackingCandidates.any() && !hasTrackingSelected(uiState)) ||
        hasLowConfidenceTracking() ||
        (readingCandidates.any() && !hasReadingSelected(uiState))
}

internal fun MergeCandidateGroup.isFuzzyMergeCandidate(): Boolean {
    return id.contains(":fuzzy:")
}

internal fun EntityWorkbenchRow.matchesStageFilters(
    uiState: MigrationUiState,
    filters: WorkbenchStageFilters,
): Boolean {
    val snapshot = stageSnapshot(uiState)
    return matchesStageFilter(snapshot.mergeState, filters.merge) &&
        matchesStageFilter(snapshot.trackingState, filters.tracking) &&
        matchesStageFilter(snapshot.readingState, filters.reading)
}

internal fun matchesStageFilter(
    state: WorkbenchStageState,
    filters: Set<WorkbenchStageState>,
): Boolean {
    return filters.isEmpty() || state in filters
}

internal fun buildWorkbenchSelectionSummary(
    rows: List<EntityWorkbenchRow>,
    filteredRows: List<EntityWorkbenchRow>,
    uiState: MigrationUiState,
): WorkbenchSelectionSummary {
    val operationScopeIds = uiState.selectedContentIds.ifEmpty { uiState.manualMergeMangaIds }
    return WorkbenchSelectionSummary(
        selectedScopeItems = operationScopeIds.size,
        selectedContents = uiState.manualMergeMangaIds.size,
        matchedGroups = filteredRows.size,
        selectedMergeGroups = rows.count { row -> row.isMergeSelected(uiState) },
        selectedTracking = uiState.selectedTrackingPreviewIds.size,
        selectedReading = uiState.acceptedReadingPreviewIds.size,
    )
}

internal fun sortWorkbenchRows(
    rows: List<EntityWorkbenchRow>,
    uiState: MigrationUiState,
    sortMode: WorkbenchSortMode,
): List<EntityWorkbenchRow> {
    return when (sortMode) {
        WorkbenchSortMode.ACTION_FIRST -> rows.sortedWith(
            compareByDescending<EntityWorkbenchRow> { it.needsAction(uiState) }
                .thenByDescending { it.hasTrackingSelected(uiState) || it.hasReadingSelected(uiState) || it.isMergeSelected(uiState) }
                .thenByDescending { it.group.matchScore }
                .thenByDescending { it.group.items.size }
                .thenBy { it.group.title.lowercase(Locale.ROOT) },
        )

        WorkbenchSortMode.MATCH_SCORE -> rows.sortedWith(
            compareByDescending<EntityWorkbenchRow> { it.group.matchScore }
                .thenByDescending { it.group.items.size }
                .thenBy { it.group.title.lowercase(Locale.ROOT) },
        )

        WorkbenchSortMode.PROJECTIONS -> rows.sortedWith(
            compareByDescending<EntityWorkbenchRow> { it.group.items.size }
                .thenByDescending { it.group.matchScore }
                .thenBy { it.group.title.lowercase(Locale.ROOT) },
        )

        WorkbenchSortMode.TITLE -> rows.sortedBy { it.group.title.lowercase(Locale.ROOT) }
    }
}

