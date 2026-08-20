package org.skepsun.kototoro.favourites.ui.migration.compose


import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getOriginLabel
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.favourites.domain.MergeCandidateGroup
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeStage
import org.skepsun.kototoro.favourites.domain.TrackingBindingPreview
import org.skepsun.kototoro.favourites.domain.ReadingSourcePreview
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeDatasetBridge
import org.skepsun.kototoro.favourites.ui.migration.SourceMigrationViewModel
import org.skepsun.kototoro.parsers.model.ContentSource

internal const val TRACKING_CONFIDENCE_WARNING_THRESHOLD = 85
internal const val ENTITY_ORGANIZE_LOG_TAG = "MergeFavoriteEntities"
internal val ENTITY_ORGANIZE_PAGE_SIZES = listOf(8, 16, 24, 40)
private val ENTITY_ORGANIZE_NON_JAR_ORIGINS = setOf(
    "Mihon",
    "Aniyomi",
    "IReader",
    "Cloudstream",
    "Legado",
    "TVBox",
    "JS",
    "JSON",
)

internal fun Float.toPercentInt(): Int = (coerceIn(0f, 1f) * 100f).toInt()

internal fun ContentSource.getEntityOrganizeDisplayTitle(context: Context): String {
    val title = getTitle(context)
    val originLabel = getOriginLabel(context)?.takeIf { it.isNotBlank() } ?: return title
    val shouldAppendOrigin = originLabel in ENTITY_ORGANIZE_NON_JAR_ORIGINS ||
        originLabel == context.getString(R.string.external_source)
    if (!shouldAppendOrigin) {
        return title
    }
    val currentLanguage = context.resources.configuration.locales.get(0)?.language.orEmpty()
    val (open, close) = if (currentLanguage == "zh") "（" to "）" else "(" to ")"
    return "$title $open$originLabel$close"
}

internal data class EntityOrganizeStageSpec(
    val stage: EntityOrganizeStage,
    val titleRes: Int,
    val subtitleRes: Int,
    val placeholderRes: Int? = null,
    val icon: ImageVector,
)

internal data class EntityBrowsePage<T>(
    val items: List<T>,
    val page: Int,
    val pageCount: Int,
)

internal data class StageTabCount(
    val accepted: Int,
    val total: Int,
)

internal data class EntityWorkbenchRow(
    val group: MergeCandidateGroup,
    val existingTrackingBindings: List<TrackingBindingPreview>,
    val trackingCandidates: List<TrackingBindingPreview>,
    val readingCandidates: List<ReadingSourcePreview>,
    val isMergeCandidate: Boolean,
)

internal data class WorkbenchSelectionSummary(
    val selectedScopeItems: Int,
    val selectedContents: Int,
    val matchedGroups: Int,
    val selectedMergeGroups: Int,
    val selectedTracking: Int,
    val selectedReading: Int,
)

internal data class WorkbenchRowStageSnapshot(
    val mergeState: WorkbenchStageState,
    val trackingState: WorkbenchStageState,
    val readingState: WorkbenchStageState,
)

internal enum class WorkbenchStatusFilter {
    ALL,
    ACTION_REQUIRED,
    SELECTED,
    UNSELECTED,
}

internal enum class WorkbenchSortMode {
    ACTION_FIRST,
    MATCH_SCORE,
    PROJECTIONS,
    TITLE,
}

internal enum class WorkbenchStageState {
    EMPTY,
    MISSING,
    WARNING,
    READY,
}

internal data class WorkbenchStageFilters(
    val merge: Set<WorkbenchStageState> = emptySet(),
    val tracking: Set<WorkbenchStageState> = emptySet(),
    val reading: Set<WorkbenchStageState> = emptySet(),
)

internal data class WorkbenchColumnWidths(
    val entity: androidx.compose.ui.unit.Dp,
    val members: androidx.compose.ui.unit.Dp,
    val tracking: androidx.compose.ui.unit.Dp,
    val reading: androidx.compose.ui.unit.Dp,
)

internal enum class EntityOrganizeEntryMode {
    MANUAL_SELECTION,
    ALL_FAVORITES,
}

internal data class EntityOrganizeWorkbenchDefaults(
    val statusFilter: WorkbenchStatusFilter,
    val sortMode: WorkbenchSortMode,
)

internal data class EntityOrganizeWorkbenchViewState(
    val query: String = "",
    val showSelectedOnly: Boolean = false,
    val pageSize: Int = ENTITY_ORGANIZE_PAGE_SIZES[0],
    val currentPage: Int = 0,
    val statusFilter: WorkbenchStatusFilter,
    val sortMode: WorkbenchSortMode,
    val stageFilters: WorkbenchStageFilters = WorkbenchStageFilters(),
)

private val workbenchViewStateSaver: Saver<EntityOrganizeWorkbenchViewState, Any> = listSaver(
    save = {
        listOf(
            it.query,
            it.showSelectedOnly,
            it.pageSize,
            it.currentPage,
            it.statusFilter.name,
            it.sortMode.name,
            it.stageFilters.merge.map(WorkbenchStageState::name),
            it.stageFilters.tracking.map(WorkbenchStageState::name),
            it.stageFilters.reading.map(WorkbenchStageState::name),
        )
    },
    restore = {
        EntityOrganizeWorkbenchViewState(
            query = it[0] as String,
            showSelectedOnly = it[1] as Boolean,
            pageSize = it[2] as Int,
            currentPage = it[3] as Int,
            statusFilter = WorkbenchStatusFilter.valueOf(it[4] as String),
            sortMode = WorkbenchSortMode.valueOf(it[5] as String),
            stageFilters = WorkbenchStageFilters(
                merge = (it[6] as List<*>)
                    .filterIsInstance<String>()
                    .mapTo(linkedSetOf(), WorkbenchStageState::valueOf),
                tracking = (it[7] as List<*>)
                    .filterIsInstance<String>()
                    .mapTo(linkedSetOf(), WorkbenchStageState::valueOf),
                reading = (it[8] as List<*>)
                    .filterIsInstance<String>()
                    .mapTo(linkedSetOf(), WorkbenchStageState::valueOf),
            ),
        )
    },
)

@Composable
fun SourceMigrationPanel(
    initialSelectedContentIds: Set<Long>,
    onDismiss: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    showHeader: Boolean = true,
    leadingContent: @Composable (() -> Unit)? = null,
    viewModel: SourceMigrationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedStage by rememberSaveable { mutableStateOf(EntityOrganizeStage.MERGE) }
    var selectedDatasetBridge by rememberSaveable { mutableStateOf(EntityOrganizeDatasetBridge.ANIME_OFFLINE) }
    var showEntityResetConfirm by rememberSaveable { mutableStateOf(false) }
    val entryMode = remember(initialSelectedContentIds.size) {
        resolveEntityOrganizeEntryMode(initialSelectedContentIds.size)
    }
    val workbenchDefaults = remember(entryMode) {
        resolveEntityOrganizeWorkbenchDefaults(entryMode)
    }
    var workbenchViewState by rememberSaveable(entryMode, stateSaver = workbenchViewStateSaver) {
        mutableStateOf(
            resolveStageWorkbenchViewState(
                selectedStage = selectedStage,
                current = EntityOrganizeWorkbenchViewState(
                    statusFilter = workbenchDefaults.statusFilter,
                    sortMode = workbenchDefaults.sortMode,
                ),
            ),
        )
    }

    LaunchedEffect(initialSelectedContentIds) {
        viewModel.setSelectedContentIds(initialSelectedContentIds)
    }

    val workbenchRows = remember(
        uiState.mergeCandidateGroups,
        uiState.organizableWorks,
        uiState.existingTrackingPreviews,
        uiState.trackingPreviews,
        uiState.readingSourcePreviews,
    ) {
        buildEntityWorkbenchRows(uiState)
    }
    val fuzzyMergeGroupCount = remember(uiState.mergeCandidateGroups) {
        uiState.mergeCandidateGroups.count { it.isFuzzyMergeCandidate() }
    }
    LaunchedEffect(
        selectedStage,
        uiState.mergePreviewReady,
        uiState.fuzzyMergeCandidatesEnabled,
        fuzzyMergeGroupCount,
    ) {
        if (
            selectedStage == EntityOrganizeStage.MERGE &&
            uiState.mergePreviewReady &&
            uiState.fuzzyMergeCandidatesEnabled &&
            fuzzyMergeGroupCount > 0 &&
            workbenchViewState.statusFilter == WorkbenchStatusFilter.SELECTED
        ) {
            Log.d(
                ENTITY_ORGANIZE_LOG_TAG,
                "EntityWorkbench: fuzzy preview visible by switching statusFilter SELECTED -> ALL, " +
                    "fuzzyGroups=$fuzzyMergeGroupCount",
            )
            workbenchViewState = workbenchViewState.copy(
                statusFilter = WorkbenchStatusFilter.ALL,
                currentPage = 0,
            )
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        val layoutDirection = LocalLayoutDirection.current
        val listContentPadding = PaddingValues(
            start = contentPadding.calculateLeftPadding(layoutDirection) + 10.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            end = contentPadding.calculateRightPadding(layoutDirection) + 10.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = listContentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showHeader) {
                item {
                    HeaderSection(
                        uiState = uiState,
                        onDismiss = onDismiss,
                    )
                }
            }

            if (leadingContent != null) {
                item {
                    leadingContent()
                }
            }

            item {
                EntityOrganizeScopeSummary(
                    uiState = uiState,
                    selectedCount = initialSelectedContentIds.size,
                )
            }

            if ((uiState.repairReport?.mixedWorkContentTypeEntityCount ?: 0) > 0) {
                item {
                    MixedWorkContentTypesRepairCard(
                        uiState = uiState,
                        onRepairClick = viewModel::repairMixedWorkContentTypeEntities,
                    )
                }
            }

            if ((uiState.repairReport?.danglingWorkProjectionAnchorCount ?: 0) > 0) {
                item {
                    DanglingWorkAnchorsRepairCard(
                        uiState = uiState,
                        onRepairClick = viewModel::repairDanglingWorkProjectionAnchors,
                    )
                }
            }

            if ((uiState.repairReport?.workEntityMissingSyncIdCount ?: 0) > 0) {
                item {
                    MissingWorkSyncIdsRepairCard(
                        uiState = uiState,
                        onRepairClick = viewModel::repairWorkEntitiesMissingSyncId,
                    )
                }
            }

            item {
                EntityIdentityResetCard(
                    uiState = uiState,
                    onResetClick = { showEntityResetConfirm = true },
                    onConfirmResultClick = viewModel::confirmEntityResetResult,
                )
            }

            item {
                DatasetBridgeCard(
                    selectedBridge = selectedDatasetBridge,
                    animeStatus = uiState.animeDatasetStatus,
                    mangaBakaStatus = uiState.mangaBakaDatasetStatus,
                    onBridgeSelected = { selectedDatasetBridge = it },
                    onRefreshAnime = viewModel::refreshAnimeDatasetStatus,
                    onUpdateAnime = viewModel::updateAnimeDataset,
                    onDeleteAnime = viewModel::deleteAnimeDataset,
                    onRefreshMangaBaka = viewModel::refreshMangaBakaDatasetStatus,
                    onUpdateMangaBaka = viewModel::updateMangaBakaDataset,
                    onDeleteMangaBaka = viewModel::deleteMangaBakaDataset,
                    onBuildMangaBakaIndex = viewModel::buildMangaBakaSearchIndex,
                )
            }

            if (uiState.isExecuting || uiState.migrationProgress?.isFinished == true) {
                item {
                    MigrationProgressSection(
                        uiState = uiState,
                        selectedStage = selectedStage,
                    )
                }
            }

            item {
                EntityWorkbenchSection(
                    selectedStage = selectedStage,
                    rows = workbenchRows,
                    uiState = uiState,
                    viewState = workbenchViewState,
                    workbenchDefaults = workbenchDefaults,
                    onViewStateChange = { workbenchViewState = it },
                    onToggleGroup = viewModel::toggleMergeGroup,
                    onToggleReadingScopeGroup = viewModel::toggleReadingScopeGroup,
                    onSetGroupsSelected = viewModel::setMergeGroupsSelected,
                    onSetReadingScopeGroupsSelected = viewModel::setReadingScopeGroupsSelected,
                    onToggleItem = viewModel::toggleMergeItem,
                    onToggleTrackingPreview = viewModel::toggleTrackingPreview,
                    onToggleReadingPreview = viewModel::toggleReadingPreview,
                    onSelectRecommendedTracking = viewModel::selectRecommendedTrackingPreviews,
                    onClearLowConfidenceTracking = viewModel::clearLowConfidenceTrackingSelections,
                    onClearTrackingSelections = viewModel::clearTrackingSelections,
                    onAcceptReadingPreviews = viewModel::acceptReadingPreviews,
                    onClearReadingPreviews = viewModel::clearReadingPreviews,
                    onSplitLocalProjection = viewModel::splitLocalWorkProjection,
                    onDetachLocalProjection = viewModel::detachLocalWorkProjection,
                )
            }

            item {
                StageConfigCard(
                    selectedStage = selectedStage,
                    rowCount = workbenchRows.size,
                    workbenchRows = workbenchRows,
                    onStageSelected = { stage ->
                        selectedStage = stage
                    },
                    uiState = uiState,
                    onTrackingMetadataStrategyChange = viewModel::setTrackingMetadataSourceStrategy,
                    onFuzzyMergeCandidatesEnabledChange = viewModel::setFuzzyMergeCandidatesEnabled,
                    onFuzzyMergeThresholdPercentChange = viewModel::setFuzzyMergeThresholdPercent,
                    onFuzzyTrackingCandidatesEnabledChange = viewModel::setFuzzyTrackingCandidatesEnabled,
                    onFuzzyTrackingThresholdPercentChange = viewModel::setFuzzyTrackingThresholdPercent,
                    onToggleTrackingService = viewModel::toggleTrackingService,
                    onMoveTrackingServiceUp = viewModel::moveTrackingServiceUp,
                    onMoveTrackingServiceDown = viewModel::moveTrackingServiceDown,
                    onPreviewMerge = viewModel::previewMergeCandidates,
                    onClearManualMergeSelections = viewModel::clearManualMergeSelections,
                    onManualMergeSelected = viewModel::manualMergeSelectedWorks,
                    onPreviewTracking = viewModel::previewSelectedTracking,
                    onSelectFromSource = viewModel::selectFromSource,
                    onToggleFromContentType = viewModel::toggleFromContentType,
                    onToggleFromSourceTag = viewModel::toggleFromSourceTag,
                    onToggleTargetSource = viewModel::toggleTargetSource,
                    onMoveTargetSourceUp = viewModel::moveTargetSourceUp,
                    onMoveTargetSourceDown = viewModel::moveTargetSourceDown,
                    onRemoveTargetSource = viewModel::removeTargetSource,
                    onToggleToContentType = viewModel::toggleToContentType,
                    onToggleToSourceTag = viewModel::toggleToSourceTag,
                    onPreviewReading = viewModel::previewReadingSources,
                    onExecuteMerge = viewModel::mergeSelectedEntities,
                    onExecuteTracking = viewModel::bindSelectedTracking,
                    onExecuteReading = viewModel::startMigration,
                    onCancel = viewModel::cancelMigration,
                    concurrency = uiState.concurrency,
                    onConcurrencyChange = viewModel::setConcurrency,
                )
            }
        }
    }
    if (showEntityResetConfirm) {
        AlertDialog(
            onDismissRequest = {
                if (!uiState.isEntityResetRunning) {
                    showEntityResetConfirm = false
                }
            },
            icon = {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
            },
            title = {
                Text(stringResource(R.string.entity_organize_reset_title))
            },
            text = {
                Text(stringResource(R.string.entity_organize_reset_confirm))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEntityResetConfirm = false
                        viewModel.resetEntityIdentities()
                    },
                    enabled = !uiState.isEntityResetRunning,
                ) {
                    Text(stringResource(R.string.entity_organize_reset_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEntityResetConfirm = false },
                    enabled = !uiState.isEntityResetRunning,
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

