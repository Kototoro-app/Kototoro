package org.skepsun.kototoro.favourites.ui.migration.compose

import android.content.Context
import android.text.format.Formatter
import coil3.compose.AsyncImage
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getOriginLabel
import org.skepsun.kototoro.core.model.getStableIdentityKey
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.data.toContent
import org.skepsun.kototoro.favourites.domain.MergeCandidateGroup
import org.skepsun.kototoro.favourites.domain.MigrationProgress
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeStage
import org.skepsun.kototoro.favourites.domain.TrackingBindingMatchKind
import org.skepsun.kototoro.favourites.domain.TrackingBindingPreview
import org.skepsun.kototoro.favourites.domain.ReadingSourcePreview
import org.skepsun.kototoro.favourites.domain.ReadingSourcePreviewAction
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeDatasetStatus
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeDatasetBridge
import org.skepsun.kototoro.favourites.ui.migration.MigrationUiState
import org.skepsun.kototoro.favourites.ui.migration.SourceMigrationViewModel
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.core.prefs.TrackingMetadataSourceStrategy
import java.util.Locale

private const val TRACKING_CONFIDENCE_WARNING_THRESHOLD = 85
private val ENTITY_ORGANIZE_PAGE_SIZES = listOf(8, 16, 24, 40)
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

private fun Float.toPercentInt(): Int = (coerceIn(0f, 1f) * 100f).toInt()

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

private data class EntityOrganizeStageSpec(
    val stage: EntityOrganizeStage,
    val titleRes: Int,
    val subtitleRes: Int,
    val placeholderRes: Int? = null,
    val icon: ImageVector,
)

private data class EntityBrowsePage<T>(
    val items: List<T>,
    val page: Int,
    val pageCount: Int,
)

internal data class EntityWorkbenchRow(
    val group: MergeCandidateGroup,
    val trackingCandidates: List<TrackingBindingPreview>,
    val readingCandidates: List<ReadingSourcePreview>,
    val isMergeCandidate: Boolean,
)

internal data class WorkbenchSelectionSummary(
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

private data class WorkbenchColumnWidths(
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
        uiState.trackingPreviews,
        uiState.readingSourcePreviews,
    ) {
        buildEntityWorkbenchRows(uiState)
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 10.dp, vertical = 8.dp),
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
                DatasetBridgeCard(
                    selectedBridge = selectedDatasetBridge,
                    animeStatus = uiState.animeDatasetStatus,
                    mangaBakaStatus = uiState.mangaBakaDatasetStatus,
                    onBridgeSelected = { selectedDatasetBridge = it },
                    onRefreshAnime = viewModel::refreshAnimeDatasetStatus,
                    onUpdateAnime = viewModel::updateAnimeDataset,
                    onRefreshMangaBaka = viewModel::refreshMangaBakaDatasetStatus,
                    onUpdateMangaBaka = viewModel::updateMangaBakaDataset,
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
                )
            }

            item {
                StageConfigCard(
                    selectedStage = selectedStage,
                    rowCount = workbenchRows.size,
                    onStageSelected = { stage ->
                        selectedStage = stage
                    },
                    uiState = uiState,
                    onTrackingMetadataStrategyChange = viewModel::setTrackingMetadataSourceStrategy,
                    onToggleTrackingService = viewModel::toggleTrackingService,
                    onMoveTrackingServiceUp = viewModel::moveTrackingServiceUp,
                    onMoveTrackingServiceDown = viewModel::moveTrackingServiceDown,
                    onPreviewMerge = viewModel::previewMergeCandidates,
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
}

@Composable
private fun HeaderSection(
    uiState: MigrationUiState,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.entity_organize_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.entity_organize_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { if (!uiState.isExecuting) onDismiss() }) {
            Icon(Icons.Default.Close, contentDescription = null)
        }
    }
}

@Composable
private fun DatasetBridgeCard(
    selectedBridge: EntityOrganizeDatasetBridge,
    animeStatus: EntityOrganizeDatasetStatus,
    mangaBakaStatus: EntityOrganizeDatasetStatus,
    onBridgeSelected: (EntityOrganizeDatasetBridge) -> Unit,
    onRefreshAnime: () -> Unit,
    onUpdateAnime: () -> Unit,
    onRefreshMangaBaka: () -> Unit,
    onUpdateMangaBaka: () -> Unit,
    onBuildMangaBakaIndex: () -> Unit,
) {
    val status = when (selectedBridge) {
        EntityOrganizeDatasetBridge.ANIME_OFFLINE -> animeStatus
        EntityOrganizeDatasetBridge.MANGABAKA -> mangaBakaStatus
    }
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(
                            when (selectedBridge) {
                                EntityOrganizeDatasetBridge.ANIME_OFFLINE -> R.string.entity_organize_dataset_title
                                EntityOrganizeDatasetBridge.MANGABAKA -> R.string.entity_organize_dataset_mangabaka_title
                            },
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = status.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        EntityOrganizeDatasetBridge.ANIME_OFFLINE to stringResource(R.string.entity_organize_dataset_title),
                        EntityOrganizeDatasetBridge.MANGABAKA to stringResource(R.string.entity_organize_dataset_mangabaka_title),
                    ).forEach { (bridge, label) ->
                        val selected = bridge == selectedBridge
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onBridgeSelected(bridge) },
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                            },
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DatasetMetaChip(
                    label = stringResource(R.string.entity_organize_dataset_version),
                    value = status.version ?: stringResource(R.string.entity_organize_dataset_not_installed_short),
                    modifier = Modifier.weight(1f),
                )
                DatasetMetaChip(
                    label = stringResource(R.string.entity_organize_dataset_size),
                    value = if (status.sizeBytes > 0L) {
                        Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, status.sizeBytes)
                    } else {
                        "0 B"
                    },
                    modifier = Modifier.weight(1f),
                )
                DatasetMetaChip(
                    label = stringResource(R.string.entity_organize_dataset_entries),
                    value = status.entryCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            if (selectedBridge == EntityOrganizeDatasetBridge.MANGABAKA) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DatasetMetaChip(
                        label = stringResource(R.string.entity_organize_dataset_index),
                        value = when {
                            status.hasSearchIndex -> {
                                stringResource(
                                    R.string.entity_organize_dataset_index_ready,
                                    status.searchIndexEntries,
                                )
                            }
                            status.isInstalled -> stringResource(R.string.entity_organize_dataset_index_missing)
                            else -> stringResource(R.string.entity_organize_dataset_not_installed_short)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    DatasetMetaChip(
                        label = stringResource(R.string.entity_organize_dataset_index_version),
                        value = status.searchIndexVersion ?: stringResource(R.string.entity_organize_dataset_not_installed_short),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (status.isLoading && (status.downloadProgress != null || status.totalBytes > 0L || status.downloadedBytes > 0L)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LinearProgressIndicator(
                        progress = {
                            status.downloadProgress ?: 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = if (status.progressIsCount) {
                            if (status.totalBytes > 0L) {
                                "${status.downloadedBytes} / ${status.totalBytes}"
                            } else {
                                status.downloadedBytes.toString()
                            }
                        } else if (status.totalBytes > 0L) {
                            "${Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, status.downloadedBytes)} / " +
                                Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, status.totalBytes)
                        } else {
                            Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, status.downloadedBytes)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedBridge == EntityOrganizeDatasetBridge.MANGABAKA) {
                    OutlinedButton(
                        onClick = onRefreshMangaBaka,
                        enabled = !status.isLoading,
                        modifier = Modifier.weight(0.26f),
                    ) {
                        ButtonLabel(stringResource(R.string.entity_organize_dataset_refresh))
                    }
                    Button(
                        onClick = onUpdateMangaBaka,
                        enabled = !status.isLoading && (!status.isInstalled || status.hasUpdate),
                        modifier = Modifier.weight(0.37f),
                    ) {
                        ButtonLabel(
                            if (status.hasUpdate) {
                                stringResource(
                                    R.string.entity_organize_dataset_update_available,
                                    status.latestVersion ?: "",
                                )
                            } else {
                                stringResource(R.string.entity_organize_dataset_update)
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = onBuildMangaBakaIndex,
                        enabled = !status.isLoading && status.isInstalled,
                        modifier = Modifier.weight(0.37f),
                    ) {
                        ButtonLabel(
                            if (status.hasSearchIndex) {
                                stringResource(R.string.entity_organize_dataset_index_rebuild)
                            } else {
                                stringResource(R.string.entity_organize_dataset_index_build)
                            },
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onRefreshAnime,
                        enabled = !status.isLoading,
                        modifier = Modifier.weight(0.38f),
                    ) {
                        ButtonLabel(stringResource(R.string.entity_organize_dataset_refresh))
                    }
                    Button(
                        onClick = onUpdateAnime,
                        enabled = !status.isLoading && (!status.isInstalled || status.hasUpdate),
                        modifier = Modifier.weight(0.62f),
                    ) {
                        ButtonLabel(
                            if (status.hasUpdate) {
                                stringResource(
                                    R.string.entity_organize_dataset_update_available,
                                    status.latestVersion ?: "",
                                )
                            } else {
                                stringResource(R.string.entity_organize_dataset_update)
                            },
                        )
                    }
                }
            }
        }
    }
}

internal fun resolveEntityOrganizeEntryMode(
    selectedCount: Int,
): EntityOrganizeEntryMode {
    return if (selectedCount > 0) {
        EntityOrganizeEntryMode.MANUAL_SELECTION
    } else {
        EntityOrganizeEntryMode.ALL_FAVORITES
    }
}

internal fun resolveEntityOrganizeWorkbenchDefaults(
    entryMode: EntityOrganizeEntryMode,
): EntityOrganizeWorkbenchDefaults {
    return when (entryMode) {
        EntityOrganizeEntryMode.MANUAL_SELECTION -> EntityOrganizeWorkbenchDefaults(
            statusFilter = WorkbenchStatusFilter.SELECTED,
            sortMode = WorkbenchSortMode.MATCH_SCORE,
        )

        EntityOrganizeEntryMode.ALL_FAVORITES -> EntityOrganizeWorkbenchDefaults(
            statusFilter = WorkbenchStatusFilter.ALL,
            sortMode = WorkbenchSortMode.ACTION_FIRST,
        )
    }
}

@Composable
fun EntityOrganizePageIntroCard(
    selectedCount: Int,
    modifier: Modifier = Modifier,
) {
    val entryMode = remember(selectedCount) {
        resolveEntityOrganizeEntryMode(selectedCount)
    }
    val workbenchDefaults = remember(entryMode) {
        resolveEntityOrganizeWorkbenchDefaults(entryMode)
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactInfoChip(
                label = stringResource(R.string.entity_organize_entry_scope_label),
                value = stringResource(
                    when (entryMode) {
                        EntityOrganizeEntryMode.MANUAL_SELECTION -> R.string.entity_organize_entry_scope_manual_value
                        EntityOrganizeEntryMode.ALL_FAVORITES -> R.string.entity_organize_entry_scope_all_value
                    },
                ),
                modifier = Modifier.weight(1f),
            )
            CompactInfoChip(
                label = stringResource(R.string.entity_organize_entry_count_label),
                value = if (selectedCount > 0) selectedCount.toString() else stringResource(R.string.entity_organize_entry_count_all),
                modifier = Modifier.weight(1f),
            )
            CompactInfoChip(
                label = stringResource(R.string.entity_organize_entry_default_view_hint),
                value = stringResource(
                    when (workbenchDefaults.sortMode) {
                        WorkbenchSortMode.ACTION_FIRST -> R.string.entity_organize_workbench_sort_action_first
                        WorkbenchSortMode.MATCH_SCORE -> R.string.entity_organize_workbench_sort_match_score
                        WorkbenchSortMode.PROJECTIONS -> R.string.entity_organize_workbench_sort_projections
                        WorkbenchSortMode.TITLE -> R.string.entity_organize_workbench_sort_title
                    },
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
@Composable
private fun StageConfigCard(
    selectedStage: EntityOrganizeStage,
    rowCount: Int,
    onStageSelected: (EntityOrganizeStage) -> Unit,
    uiState: MigrationUiState,
    onTrackingMetadataStrategyChange: (TrackingMetadataSourceStrategy) -> Unit,
    onToggleTrackingService: (ScrobblerService) -> Unit,
    onMoveTrackingServiceUp: (ScrobblerService) -> Unit,
    onMoveTrackingServiceDown: (ScrobblerService) -> Unit,
    onPreviewMerge: () -> Unit,
    onPreviewTracking: () -> Unit,
    onSelectFromSource: (ContentSource?) -> Unit,
    onToggleFromContentType: (BrowseGroupTab) -> Unit,
    onToggleFromSourceTag: (SourceTag) -> Unit,
    onToggleTargetSource: (ContentSource) -> Unit,
    onMoveTargetSourceUp: (String) -> Unit,
    onMoveTargetSourceDown: (String) -> Unit,
    onRemoveTargetSource: (String) -> Unit,
    onToggleToContentType: (BrowseGroupTab) -> Unit,
    onToggleToSourceTag: (SourceTag) -> Unit,
    onPreviewReading: () -> Unit,
    onExecuteMerge: () -> Unit,
    onExecuteTracking: () -> Unit,
    onExecuteReading: () -> Unit,
    onCancel: () -> Unit,
    concurrency: Int,
    onConcurrencyChange: (Int) -> Unit,
) {
    val spec = stageSpec(selectedStage)
    val plans = remember(uiState) {
        EntityOrganizeStage.entries.map(uiState::stagePlan)
    }
    val mergePlan = uiState.stagePlan(EntityOrganizeStage.MERGE)
    val trackingPlan = uiState.stagePlan(EntityOrganizeStage.TRACKING)
    val readingPlan = uiState.stagePlan(EntityOrganizeStage.READING)
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        plans.forEach { plan ->
                            val selected = selectedStage == plan.stage
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable { onStageSelected(plan.stage) },
                                shape = RoundedCornerShape(18.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                },
                                tonalElevation = if (selected) 1.dp else 0.dp,
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = stageShortLabel(plan.stage),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    Text(
                                        text = "${plan.acceptedCount}/${plan.previewCount}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) {
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
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = spec.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(spec.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(spec.subtitleRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            spec.placeholderRes?.let { placeholderRes ->
                Text(
                    text = stringResource(placeholderRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.entity_organize_stage_scope_compact, rowCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            when (selectedStage) {
                EntityOrganizeStage.MERGE -> {
                    Text(
                        text = stringResource(R.string.entity_organize_stage_panel_workbench_hint_merge),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onPreviewMerge,
                        enabled = mergePlan.canPreview,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                    ) {
                        ButtonLabel(stringResource(R.string.entity_organize_merge_preview))
                    }
                    StageFeedbackText(uiState, EntityOrganizeStage.MERGE)
                }

                EntityOrganizeStage.TRACKING -> {
                    uiState.trackingProgress?.let { progress ->
                        ExecutionProgressSection(
                            progress = progress,
                            activeLabel = stringResource(R.string.entity_organize_tracking_execute),
                            finishedLabel = stringResource(R.string.entity_organize_tracking_execute),
                        )
                    }
                    TrackingBindingSection(
                        uiState = uiState,
                        onTrackingMetadataStrategyChange = onTrackingMetadataStrategyChange,
                        onToggle = onToggleTrackingService,
                        onMoveUp = onMoveTrackingServiceUp,
                        onMoveDown = onMoveTrackingServiceDown,
                        onPreview = onPreviewTracking,
                    )
                }

                EntityOrganizeStage.READING -> {
                    uiState.migrationProgress?.let { progress ->
                        ExecutionProgressSection(
                            progress = progress,
                            activeLabel = stringResource(R.string.entity_organize_reading_preview_active),
                            finishedLabel = stringResource(R.string.entity_organize_reading_preview_finished),
                        )
                    }
                    if (!uiState.hasManualSelection) {
                        SourceFilterSection(
                            label = stringResource(R.string.entity_organize_scope_title),
                            sources = uiState.fromFilteredSources,
                            selectedSource = uiState.selectedFromSource,
                            onSourceSelected = onSelectFromSource,
                            contentTypeFilter = uiState.fromContentTypeFilter,
                            onContentTypeToggle = onToggleFromContentType,
                            sourceTagFilter = uiState.fromSourceTagFilter,
                            onSourceTagToggle = onToggleFromSourceTag,
                            enabled = !uiState.isExecuting,
                        )
                    }
                    TargetSourcesSection(
                        uiState = uiState,
                        onToggleTargetSource = onToggleTargetSource,
                        onMoveUp = onMoveTargetSourceUp,
                        onMoveDown = onMoveTargetSourceDown,
                        onRemove = onRemoveTargetSource,
                        onContentTypeToggle = onToggleToContentType,
                        onSourceTagToggle = onToggleToSourceTag,
                        onPreview = onPreviewReading,
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConcurrencyDropdown(
                    value = concurrency,
                    onSelect = onConcurrencyChange,
                    enabled = !uiState.isExecuting,
                    modifier = Modifier.weight(0.42f),
                )
                StageExecuteButton(
                    text = stringResource(
                        when (selectedStage) {
                            EntityOrganizeStage.MERGE -> R.string.entity_organize_merge_execute
                            EntityOrganizeStage.TRACKING -> R.string.entity_organize_tracking_execute
                            EntityOrganizeStage.READING -> R.string.entity_organize_reading_execute
                        },
                    ),
                    enabled = when (selectedStage) {
                        EntityOrganizeStage.MERGE -> mergePlan.canExecute && !uiState.isExecuting
                        EntityOrganizeStage.TRACKING -> trackingPlan.canExecute && !uiState.isExecuting
                        EntityOrganizeStage.READING -> readingPlan.canExecute && !uiState.isExecuting
                    },
                    onClick = when (selectedStage) {
                        EntityOrganizeStage.MERGE -> onExecuteMerge
                        EntityOrganizeStage.TRACKING -> onExecuteTracking
                        EntityOrganizeStage.READING -> onExecuteReading
                    },
                    modifier = Modifier.weight(0.58f),
                )
            }
            if (uiState.isExecuting) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.source_migration_cancel))
                }
            }
        }
    }
}
@Composable
private fun EntityWorkbenchSection(
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
) {
    val normalizedQuery = viewState.query.trim().lowercase(Locale.ROOT)
    val filteredRows = remember(
        rows,
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
            val mergeSelected = row.isMergeSelected(uiState)
            val trackingSelected = row.hasTrackingSelected(uiState)
            val readingSelected = row.hasReadingSelected(uiState)
            val matchesSelection = !viewState.showSelectedOnly || mergeSelected || trackingSelected || readingSelected
            val needsAction = row.needsAction(uiState)
            val matchesStatus = when (viewState.statusFilter) {
                WorkbenchStatusFilter.ALL -> true
                WorkbenchStatusFilter.ACTION_REQUIRED -> needsAction
                WorkbenchStatusFilter.SELECTED -> mergeSelected || trackingSelected || readingSelected
                WorkbenchStatusFilter.UNSELECTED -> !mergeSelected && !trackingSelected && !readingSelected
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
            matchesSelection && matchesStatus && matchesQuery && matchesStageFilters
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
    LaunchedEffect(
        normalizedQuery,
        viewState.showSelectedOnly,
        viewState.statusFilter,
        viewState.pageSize,
        viewState.stageFilters,
    ) {
        onViewStateChange(viewState.copy(currentPage = 0))
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
                    statusFilter = viewState.statusFilter,
                    onStatusFilterChange = { onViewStateChange(viewState.copy(statusFilter = it)) },
                    sortMode = viewState.sortMode,
                    onSortModeChange = { onViewStateChange(viewState.copy(sortMode = it)) },
                    stageFilters = viewState.stageFilters,
                    onStageFiltersChange = { onViewStateChange(viewState.copy(stageFilters = it)) },
                    showSelectedOnly = viewState.showSelectedOnly,
                    onToggleSelectedOnly = {
                        onViewStateChange(viewState.copy(showSelectedOnly = !viewState.showSelectedOnly))
                    },
                    onSelectAllRows = {
                        val allGroupIds = filteredRows.mapTo(LinkedHashSet()) { it.group.id }
                        val allReadingScopeGroupIds = filteredRows.mapTo(LinkedHashSet()) { it.group.id }
                        when (selectedStage) {
                            EntityOrganizeStage.MERGE -> onSetGroupsSelected(allGroupIds, true)
                            EntityOrganizeStage.TRACKING -> onSelectRecommendedTracking(allGroupIds)
                            EntityOrganizeStage.READING -> onSetReadingScopeGroupsSelected(allReadingScopeGroupIds, true)
                        }
                    },
                    onClearAllRows = {
                        val allGroupIds = filteredRows.mapTo(LinkedHashSet()) { it.group.id }
                        val allReadingIds = filteredRows.flatMapTo(LinkedHashSet()) { row -> row.readingCandidates.map { it.mangaId } }
                        when (selectedStage) {
                            EntityOrganizeStage.MERGE -> onSetGroupsSelected(allGroupIds, false)
                            EntityOrganizeStage.TRACKING -> onClearTrackingSelections(allGroupIds)
                            EntityOrganizeStage.READING -> {
                                onSetReadingScopeGroupsSelected(allGroupIds, false)
                                onClearReadingPreviews(allReadingIds)
                            }
                        }
                    },
                    hasVisibleRows = pagedRows.items.isNotEmpty(),
                )
            EntityBrowseSection(
                query = viewState.query,
                onQueryChange = { onViewStateChange(viewState.copy(query = it)) },
                showSelectedOnly = viewState.showSelectedOnly,
                onToggleSelectedOnly = {
                    onViewStateChange(viewState.copy(showSelectedOnly = !viewState.showSelectedOnly))
                },
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
                tableToolbar = { visibleRows ->
                    val visibleGroupIds = visibleRows.mapTo(LinkedHashSet()) { it.group.id }
                    val visibleReadingIds = visibleRows
                        .flatMapTo(LinkedHashSet()) { row -> row.readingCandidates.map { it.mangaId } }
                    WorkbenchTableToolbar(
                        selectedStage = selectedStage,
                        onSelectVisibleGroups = { onSetGroupsSelected(visibleGroupIds, true) },
                        onClearVisibleMergeSelections = { onSetGroupsSelected(visibleGroupIds, false) },
                        onSelectVisibleReadingScope = { onSetReadingScopeGroupsSelected(visibleGroupIds, true) },
                        onClearVisibleReadingScope = { onSetReadingScopeGroupsSelected(visibleGroupIds, false) },
                        onSelectRecommendedTracking = { onSelectRecommendedTracking(visibleGroupIds) },
                        onClearLowConfidenceTracking = { onClearLowConfidenceTracking(visibleGroupIds) },
                        onClearTrackingSelections = { onClearTrackingSelections(visibleGroupIds) },
                        onAcceptReadingPreviews = { onAcceptReadingPreviews(visibleReadingIds) },
                        onClearReadingPreviews = { onClearReadingPreviews(visibleReadingIds) },
                        hasVisibleMerge = visibleRows.any { it.isMergeCandidate },
                        hasVisibleTracking = visibleRows.any { it.trackingCandidates.isNotEmpty() },
                        hasVisibleReading = visibleRows.any { it.readingCandidates.isNotEmpty() },
                    )
                },
            ) { visibleRows ->
                EntityWorkbenchHeader()
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
    return WorkbenchSelectionSummary(
        selectedContents = uiState.selectedContentIds.size,
        matchedGroups = filteredRows.size,
        selectedMergeGroups = uiState.selectedMergeGroupIds.size,
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

@Composable
private fun WorkbenchSelectionSummaryCard(
    selectedStage: EntityOrganizeStage,
    summary: WorkbenchSelectionSummary,
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
            ) {
                WorkbenchMetricChip(
                    label = stringResource(R.string.entity_organize_workbench_metric_selected_contents),
                    value = summary.selectedContents.toString(),
                    modifier = Modifier.weight(1f),
                )
                WorkbenchMetricChip(
                    label = stringResource(R.string.entity_organize_workbench_metric_matched_groups),
                    value = summary.matchedGroups.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WorkbenchMetricChip(
                    label = stringResource(R.string.entity_organize_workbench_metric_selected_groups),
                    value = summary.selectedMergeGroups.toString(),
                    modifier = Modifier.weight(1f),
                )
                WorkbenchMetricChip(
                    label = stringResource(R.string.entity_organize_tracking_title),
                    value = summary.selectedTracking.toString(),
                    modifier = Modifier.weight(1f),
                )
                WorkbenchMetricChip(
                    label = stringResource(R.string.entity_organize_reading_title),
                    value = summary.selectedReading.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
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
                    ButtonLabel(stringResource(R.string.entity_organize_workbench_select_all))
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
                                EntityOrganizeStage.MERGE -> R.string.entity_organize_workbench_clear_all
                                EntityOrganizeStage.TRACKING -> R.string.entity_organize_workbench_clear_all
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
private fun WorkbenchMetricChip(
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
private fun InlineStatusBadge(
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

private fun stageStateLabelRes(state: WorkbenchStageState): Int {
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
    return uiState.mergeCandidateGroups.map { group ->
        EntityWorkbenchRow(
            group = group,
            trackingCandidates = uiState.trackingPreviews.filter { it.groupId == group.id },
            readingCandidates = uiState.readingSourcePreviews.filter { preview ->
                preview.mangaId in group.mangaIds
            },
            isMergeCandidate = group.mangaIds.size >= 2 && !group.isAlreadyMerged,
        )
    }
}

internal fun resolveStageWorkbenchViewState(
    selectedStage: EntityOrganizeStage,
    current: EntityOrganizeWorkbenchViewState,
): EntityOrganizeWorkbenchViewState {
    return current
}

@Composable
private fun EntityWorkbenchHeader() {
    val widths = workbenchColumnWidths()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .width(IntrinsicSize.Max)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkbenchHeaderCell(
                text = stringResource(R.string.entity_organize_workbench_entity_column),
                width = widths.entity,
            )
            WorkbenchDivider()
            WorkbenchHeaderCell(
                text = stringResource(R.string.entity_organize_workbench_members_column),
                width = widths.members,
            )
            WorkbenchDivider()
            WorkbenchHeaderCell(
                text = stringResource(R.string.entity_organize_tracking_title),
                width = widths.tracking,
            )
            WorkbenchDivider()
            WorkbenchHeaderCell(
                text = stringResource(R.string.entity_organize_reading_title),
                width = widths.reading,
            )
        }
    }
}

@Composable
private fun EntityWorkbenchRowCard(
    selectedStage: EntityOrganizeStage,
    row: EntityWorkbenchRow,
    uiState: MigrationUiState,
    onToggleGroup: (String) -> Unit,
    onToggleReadingScopeGroup: (String) -> Unit,
    onToggleItem: (String, Long) -> Unit,
    onToggleTrackingPreview: (String) -> Unit,
    onToggleReadingPreview: (Long) -> Unit,
) {
    val widths = workbenchColumnWidths()
    var expanded by rememberSaveable(row.group.id) { mutableStateOf(false) }
    val snapshot = row.stageSnapshot(uiState)
    val mergeSelected = row.isMergeSelected(uiState)
    val selectedTrackingId = row.trackingCandidates
        .firstOrNull { it.previewId in uiState.selectedTrackingPreviewIds }
        ?.previewId
    val recommendedTracking = row.trackingCandidates.maxByOrNull { it.confidence }
    val visibleMembers = if (expanded) row.group.items else row.group.items.take(3)
    val entityMeta = buildWorkbenchEntityMeta(row)
    val entityDetail = buildWorkbenchEntityDetail(row)
    val rowChecked = when (selectedStage) {
        EntityOrganizeStage.MERGE -> mergeSelected
        EntityOrganizeStage.TRACKING -> selectedTrackingId != null
        EntityOrganizeStage.READING -> row.group.mangaIds.any(uiState.selectedContentIds::contains)
    }
    val rowEnabled = when (selectedStage) {
        EntityOrganizeStage.MERGE -> row.isMergeCandidate
        EntityOrganizeStage.TRACKING -> row.trackingCandidates.isNotEmpty()
        EntityOrganizeStage.READING -> true
    }
    val onToggleRowSelection = {
        when (selectedStage) {
            EntityOrganizeStage.MERGE -> onToggleGroup(row.group.id)
            EntityOrganizeStage.TRACKING -> {
                val target = selectedTrackingId ?: recommendedTracking?.previewId
                if (target != null) {
                    onToggleTrackingPreview(target)
                }
            }
            EntityOrganizeStage.READING -> {
                onToggleReadingScopeGroup(row.group.id)
            }
        }
    }
    val rowContainerColor = if (rowChecked) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.36f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
    }
    val rowBorderColor = if (rowChecked) {
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.58f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
    }
    val titleColor = if (rowChecked) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = rowContainerColor,
        border = BorderStroke(
            1.dp,
            rowBorderColor,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.width(widths.entity)) {
                    Row(
                        modifier = Modifier.toggleable(
                            value = rowChecked,
                            enabled = rowEnabled,
                            role = Role.Checkbox,
                            onValueChange = { onToggleRowSelection() },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Checkbox(
                            checked = rowChecked,
                            onCheckedChange = null,
                            enabled = rowEnabled,
                        )
                        Column {
                            Text(
                                text = row.group.title,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = titleColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = entityMeta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = entityDetail,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (row.isMergeCandidate) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                InlineStatusBadge(
                                    text = stringResource(stageStateLabelRes(snapshot.mergeState)),
                                    state = snapshot.mergeState,
                                )
                                InlineStatusBadge(
                                    text = stringResource(stageStateLabelRes(snapshot.trackingState)),
                                    state = snapshot.trackingState,
                                )
                                InlineStatusBadge(
                                    text = stringResource(stageStateLabelRes(snapshot.readingState)),
                                    state = snapshot.readingState,
                                )
                            }
                        }
                    }
                }
                WorkbenchDivider(fillHeight = true)
                Column(
                    modifier = Modifier.width(widths.members),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    visibleMembers.forEach { item ->
                        val itemChecked = item.mangaId in uiState.selectedMergeItemsByGroup[row.group.id].orEmpty()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleItem(row.group.id, item.mangaId) },
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Checkbox(
                                checked = itemChecked,
                                onCheckedChange = { onToggleItem(row.group.id, item.mangaId) },
                                modifier = Modifier.size(18.dp),
                            )
                            MemberCoverThumb(
                                title = item.title,
                                coverUrl = item.coverUrl,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${item.displaySourceName} · ${item.score.toPercentInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (row.group.items.size > 3) {
                        OutlinedButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(
                                    if (expanded) {
                                        R.string.entity_organize_workbench_collapse_members
                                    } else {
                                        R.string.entity_organize_workbench_expand_members
                                    },
                                    row.group.items.size,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                WorkbenchDivider(fillHeight = true)
                Column(
                    modifier = Modifier.width(widths.tracking),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (row.trackingCandidates.isEmpty()) {
                        Text(
                            text = stringResource(R.string.entity_organize_workbench_tracking_empty),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        row.trackingCandidates.take(if (expanded) 3 else 1).forEach { preview ->
                            val checked = preview.previewId == selectedTrackingId
                            val confidencePercent = preview.confidence.toPercentInt()
                            CompactSelectCard(
                                checked = checked,
                                prefix = "#${row.trackingCandidates.indexOf(preview) + 1}",
                                title = stringResource(preview.service.titleResId),
                                subtitle = preview.matchedTitle,
                                meta = "$confidencePercent%",
                                emphasized = preview.previewId == recommendedTracking?.previewId,
                                badge = when {
                                    checked -> stringResource(R.string.entity_organize_workbench_status_selected)
                                    confidencePercent < TRACKING_CONFIDENCE_WARNING_THRESHOLD ->
                                        stringResource(R.string.entity_organize_workbench_status_low_confidence)
                                    preview.previewId == recommendedTracking?.previewId ->
                                        stringResource(R.string.entity_organize_workbench_status_recommended)
                                    else -> null
                                },
                                tone = when {
                                    checked -> CompactSelectTone.Selected
                                    confidencePercent < TRACKING_CONFIDENCE_WARNING_THRESHOLD -> CompactSelectTone.Warning
                                    preview.previewId == recommendedTracking?.previewId -> CompactSelectTone.Recommended
                                    else -> CompactSelectTone.Neutral
                                },
                                onToggle = { onToggleTrackingPreview(preview.previewId) },
                            )
                        }
                    }
                }
                WorkbenchDivider(fillHeight = true)
                Column(
                    modifier = Modifier.width(widths.reading),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (row.readingCandidates.isEmpty()) {
                        val currentProjection = row.currentProjectionItem()
                        if (currentProjection != null) {
                            ProjectionSummaryCard(
                                title = currentProjection.displaySourceName,
                                subtitle = currentProjection.title,
                                meta = stringResource(
                                    R.string.favourites_entity_current_projection,
                                    currentProjection.displaySourceName,
                                ),
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.entity_organize_workbench_reading_empty),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        row.readingCandidates.take(if (expanded) 3 else 2).forEach { preview ->
                            val checked = preview.mangaId in uiState.acceptedReadingPreviewIds
                            CompactSelectCard(
                                checked = checked,
                                prefix = "#${row.readingCandidates.indexOf(preview) + 1}",
                                title = preview.targetSourceDisplayName,
                                subtitle = preview.matchedTitle,
                                meta = preview.title,
                                emphasized = checked,
                                badge = if (checked) {
                                    when (preview.action) {
                                        ReadingSourcePreviewAction.ACTIVATE_EXISTING ->
                                            stringResource(R.string.entity_organize_reading_action_activate_existing)
                                        ReadingSourcePreviewAction.ATTACH_NEW ->
                                            stringResource(R.string.entity_organize_reading_action_attach_new)
                                    }
                                } else {
                                    when (preview.action) {
                                        ReadingSourcePreviewAction.ACTIVATE_EXISTING ->
                                            stringResource(R.string.entity_organize_reading_action_candidate_activate)
                                        ReadingSourcePreviewAction.ATTACH_NEW ->
                                            stringResource(R.string.entity_organize_reading_action_candidate_attach)
                                    }
                                },
                                tone = if (checked) {
                                    CompactSelectTone.Selected
                                } else {
                                    CompactSelectTone.Neutral
                                },
                                onToggle = { onToggleReadingPreview(preview.mangaId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class CompactSelectTone {
    Neutral,
    Recommended,
    Selected,
    Warning,
}

@Composable
private fun ProjectionSummaryCard(
    title: String,
    subtitle: String,
    meta: String,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.details_current_projection),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CompactSelectCard(
    checked: Boolean,
    prefix: String? = null,
    title: String,
    subtitle: String,
    meta: String,
    emphasized: Boolean,
    badge: String? = null,
    tone: CompactSelectTone = CompactSelectTone.Neutral,
    onToggle: () -> Unit,
) {
    val containerColor = when (tone) {
        CompactSelectTone.Neutral -> MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        CompactSelectTone.Recommended -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.44f)
        CompactSelectTone.Selected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.82f)
        CompactSelectTone.Warning -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    }
    val accentColor = when (tone) {
        CompactSelectTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        CompactSelectTone.Recommended -> MaterialTheme.colorScheme.primary
        CompactSelectTone.Selected -> MaterialTheme.colorScheme.secondary
        CompactSelectTone.Warning -> MaterialTheme.colorScheme.error
    }
    val borderColor = when (tone) {
        CompactSelectTone.Neutral -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
        CompactSelectTone.Recommended -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        CompactSelectTone.Selected -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)
        CompactSelectTone.Warning -> MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
    }
    val titleColor = when (tone) {
        CompactSelectTone.Selected -> MaterialTheme.colorScheme.onSecondaryContainer
        CompactSelectTone.Warning -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    prefix?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    badge?.let {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = accentColor.copy(alpha = 0.14f),
                        ) {
                            Text(
                                text = it,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = accentColor,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (emphasized) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WorkbenchHeaderCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun WorkbenchDivider(
    fillHeight: Boolean = false,
) {
    Surface(
        modifier = if (fillHeight) {
            Modifier
                .fillMaxHeight()
                .width(1.dp)
        } else {
            Modifier
                .height(18.dp)
                .width(1.dp)
        },
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        content = {},
    )
}

private fun workbenchColumnWidths(): WorkbenchColumnWidths = WorkbenchColumnWidths(
    entity = 148.dp,
    members = 198.dp,
    tracking = 132.dp,
    reading = 144.dp,
)

@Composable
private fun MemberCoverThumb(
    title: String,
    coverUrl: String?,
) {
    val shape = RoundedCornerShape(8.dp)
    if (!coverUrl.isNullOrBlank()) {
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            modifier = Modifier
                .size(width = 28.dp, height = 40.dp)
                .clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            modifier = Modifier
                .size(width = 28.dp, height = 40.dp),
            shape = shape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = title.take(1).uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun MergeCandidateSection(
    uiState: MigrationUiState,
    onToggleGroup: (String) -> Unit,
    onToggleItem: (String, Long) -> Unit,
    onMerge: () -> Unit,
) {
    val stagePlan = uiState.stagePlan(EntityOrganizeStage.MERGE)
    var query by rememberSaveable { mutableStateOf("") }
    var showSelectedOnly by rememberSaveable { mutableStateOf(false) }
    var showFuzzyGroups by rememberSaveable { mutableStateOf(false) }
    var pageSize by rememberSaveable { mutableStateOf(ENTITY_ORGANIZE_PAGE_SIZES[1]) }
    var currentPage by rememberSaveable { mutableStateOf(0) }
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val filteredGroups = remember(
        uiState.mergeCandidateGroups,
        uiState.selectedMergeGroupIds,
        uiState.selectedMergeItemsByGroup,
        normalizedQuery,
        showSelectedOnly,
        showFuzzyGroups,
    ) {
        uiState.mergeCandidateGroups.filter { group ->
            val matchesType = showFuzzyGroups || group.isExactMatch
            val matchesSelection = !showSelectedOnly ||
                group.id in uiState.selectedMergeGroupIds ||
                uiState.selectedMergeItemsByGroup[group.id].orEmpty().isNotEmpty()
            val matchesQuery = normalizedQuery.isBlank() || buildString {
                append(group.title)
                append(' ')
                append(group.contentType.name)
                append(' ')
                group.items.forEach { item ->
                    append(item.title)
                    append(' ')
                    append(item.displaySourceName)
                    append(' ')
                }
            }.lowercase(Locale.ROOT).contains(normalizedQuery)
            matchesType && matchesSelection && matchesQuery
        }
    }
    val pagedGroups = rememberPagedBrowsePage(
        items = filteredGroups,
        pageSize = pageSize,
        currentPage = currentPage,
        onPageChanged = { currentPage = it },
    )
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.entity_organize_merge_candidates_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.entity_organize_merge_candidates_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EntityBrowseSection(
                query = query,
                onQueryChange = { query = it },
                showSelectedOnly = showSelectedOnly,
                onToggleSelectedOnly = { showSelectedOnly = !showSelectedOnly },
                extraToggleLabel = stringResource(
                    if (showFuzzyGroups) {
                        R.string.entity_organize_hide_fuzzy
                    } else {
                        R.string.entity_organize_show_fuzzy
                    },
                ),
                onExtraToggle = { showFuzzyGroups = !showFuzzyGroups },
                visibleCount = filteredGroups.size,
                totalCount = uiState.mergeCandidateGroups.size,
                pagedItems = pagedGroups,
                pageSize = pageSize,
                onPageSizeChange = { pageSize = it },
                onPageChange = { currentPage = it },
                emptyContent = {
                    Text(
                        text = stringResource(R.string.entity_organize_merge_candidates_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) { visibleGroups ->
                visibleGroups.forEach { group ->
                    val checked = group.id in uiState.selectedMergeGroupIds
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleGroup(group.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggleGroup(group.id) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = group.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.entity_organize_merge_candidates_meta,
                                        group.mangaIds.size,
                                        group.contentType.name,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                        text = stringResource(
                                            if (group.isExactMatch) {
                                                R.string.entity_organize_merge_candidates_exact
                                            } else {
                                                R.string.entity_organize_merge_candidates_fuzzy
                                            },
                                            group.matchScore.toPercentInt(),
                                        ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (group.isExactMatch) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.tertiary
                                    },
                                )
                                Spacer(Modifier.height(6.dp))
                                group.items.forEach { item ->
                                    val itemChecked = item.mangaId in uiState.selectedMergeItemsByGroup[group.id].orEmpty()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onToggleItem(group.id, item.mangaId) }
                                            .padding(top = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = itemChecked,
                                            onCheckedChange = { onToggleItem(group.id, item.mangaId) },
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Text(
                                                text = "${item.displaySourceName} · ${item.score.toPercentInt()}%",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onMerge,
                enabled = stagePlan.canExecute,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.entity_organize_merge_execute))
            }
            StageFeedbackText(uiState, EntityOrganizeStage.MERGE)
        }
    }
}

@Composable
private fun TrackingBindingSection(
    uiState: MigrationUiState,
    onTrackingMetadataStrategyChange: (TrackingMetadataSourceStrategy) -> Unit,
    onToggle: (ScrobblerService) -> Unit,
    onMoveUp: (ScrobblerService) -> Unit,
    onMoveDown: (ScrobblerService) -> Unit,
    onPreview: () -> Unit,
) {
    val stagePlan = uiState.stagePlan(EntityOrganizeStage.TRACKING)
    var showSelector by remember { mutableStateOf(false) }
    OutlinedCard(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.entity_organize_tracking_priority_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.entity_organize_tracking_priority_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.entity_organize_tracking_strategy_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TrackingStrategyOption(
                    label = stringResource(R.string.entity_organize_tracking_strategy_local_first),
                    description = stringResource(R.string.entity_organize_tracking_strategy_local_first_summary),
                    selected = uiState.trackingMetadataSourceStrategy == TrackingMetadataSourceStrategy.LOCAL_THEN_API,
                    onClick = { onTrackingMetadataStrategyChange(TrackingMetadataSourceStrategy.LOCAL_THEN_API) },
                )
                TrackingStrategyOption(
                    label = stringResource(R.string.entity_organize_tracking_strategy_api_first),
                    description = stringResource(R.string.entity_organize_tracking_strategy_api_first_summary),
                    selected = uiState.trackingMetadataSourceStrategy == TrackingMetadataSourceStrategy.API_THEN_LOCAL,
                    onClick = { onTrackingMetadataStrategyChange(TrackingMetadataSourceStrategy.API_THEN_LOCAL) },
                )
                TrackingStrategyOption(
                    label = stringResource(R.string.entity_organize_tracking_strategy_local_only),
                    description = stringResource(R.string.entity_organize_tracking_strategy_local_only_summary),
                    selected = uiState.trackingMetadataSourceStrategy == TrackingMetadataSourceStrategy.LOCAL_ONLY,
                    onClick = { onTrackingMetadataStrategyChange(TrackingMetadataSourceStrategy.LOCAL_ONLY) },
                )
                TrackingStrategyOption(
                    label = stringResource(R.string.entity_organize_tracking_strategy_api_only),
                    description = stringResource(R.string.entity_organize_tracking_strategy_api_only_summary),
                    selected = uiState.trackingMetadataSourceStrategy == TrackingMetadataSourceStrategy.API_ONLY,
                    onClick = { onTrackingMetadataStrategyChange(TrackingMetadataSourceStrategy.API_ONLY) },
                )
            }
            OutlinedButton(
                onClick = { showSelector = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isExecuting && uiState.availableTrackingServices.isNotEmpty(),
            ) {
                Text(stringResource(R.string.entity_organize_tracking_select))
            }
            if (uiState.selectedTrackingServices.isEmpty()) {
                Text(
                    text = stringResource(R.string.entity_organize_tracking_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                uiState.selectedTrackingServices.forEachIndexed { index, service ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}. ${stringResource(service.titleResId)}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(
                                onClick = { onMoveUp(service) },
                                enabled = !uiState.isExecuting && index > 0,
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = null)
                            }
                            IconButton(
                                onClick = { onMoveDown(service) },
                                enabled = !uiState.isExecuting && index < uiState.selectedTrackingServices.lastIndex,
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = null)
                            }
                            IconButton(
                                onClick = { onToggle(service) },
                                enabled = !uiState.isExecuting,
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = onPreview,
                    enabled = stagePlan.canPreview,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                ) {
                    ButtonLabel(stringResource(R.string.entity_organize_tracking_preview))
                }
            }
            Text(
                text = stringResource(R.string.entity_organize_stage_panel_workbench_hint_tracking),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StageFeedbackText(uiState, EntityOrganizeStage.TRACKING)
        }
    }
    if (showSelector) {
        TrackingServiceSelectorDialog(
            services = uiState.availableTrackingServices,
            selectedServices = uiState.selectedTrackingServices.toSet(),
            onToggle = onToggle,
            onDismiss = { showSelector = false },
        )
    }
}

@Composable
private fun TrackingStrategyOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrackingPreviewCard(
    preview: TrackingBindingPreview,
    checked: Boolean,
    isRecommended: Boolean,
    onToggle: () -> Unit,
) {
    val confidencePercent = preview.confidence.toPercentInt()
    val isLowConfidence = confidencePercent < TRACKING_CONFIDENCE_WARNING_THRESHOLD
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (isLowConfidence) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = preview.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isRecommended) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.entity_organize_tracking_recommended),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Text(
                    text = "${stringResource(preview.service.titleResId)} · ${preview.contentTypeName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isLowConfidence) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                    text = preview.matchedTitle,
                    style = MaterialTheme.typography.bodyMedium,
                )
                preview.matchedAltTitle?.takeIf { it.isNotBlank() }?.let { altTitle ->
                    Text(
                        text = altTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = buildString {
                        append(
                            stringResource(
                                when (preview.matchedBy) {
                                    TrackingBindingMatchKind.ANIME_DATASET -> R.string.entity_organize_tracking_match_dataset
                                    TrackingBindingMatchKind.AGGREGATE_API -> R.string.entity_organize_tracking_match_api
                                    TrackingBindingMatchKind.ONLINE_SEARCH -> R.string.entity_organize_tracking_match_online
                                    TrackingBindingMatchKind.EXISTING_BINDING -> R.string.entity_organize_tracking_match_existing
                                },
                            ),
                        )
                        append(" · ")
                        append("$confidencePercent%")
                        preview.year?.let {
                            append(" · ")
                            append(it)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isLowConfidence) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (isLowConfidence) {
                    Text(
                        text = stringResource(R.string.entity_organize_tracking_low_confidence_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else if (isRecommended) {
                    Text(
                        text = stringResource(R.string.entity_organize_tracking_recommended_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                preview.url?.let { url ->
                    Text(
                        text = url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackingPreviewGroupCard(
    groupTitle: String,
    previews: List<TrackingBindingPreview>,
    selectedPreviewIds: Set<String>,
    onTogglePreview: (String) -> Unit,
) {
    val recommendedPreviewId = previews.maxByOrNull { it.confidence }?.previewId
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = groupTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        previews.forEach { preview ->
            TrackingPreviewCard(
                preview = preview,
                checked = preview.previewId in selectedPreviewIds,
                isRecommended = preview.previewId == recommendedPreviewId,
                onToggle = { onTogglePreview(preview.previewId) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceFilterSection(
    label: String,
    sources: List<ContentSource>,
    selectedSource: ContentSource?,
    onSourceSelected: (ContentSource?) -> Unit,
    contentTypeFilter: Set<BrowseGroupTab>,
    onContentTypeToggle: (BrowseGroupTab) -> Unit,
    sourceTagFilter: Set<SourceTag>,
    onSourceTagToggle: (SourceTag) -> Unit,
    enabled: Boolean,
) {
    var showSelector by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val sourceTagSummary = sourceTagFilter.takeIf { it.isNotEmpty() }
        ?.joinToString { context.getString(it.titleRes) }
        ?: context.getString(R.string.filter_all)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (enabled && sources.isNotEmpty()) Modifier.clickable { showSelector = true } else Modifier),
        ) {
            OutlinedTextField(
                value = selectedSource?.getEntityOrganizeDisplayTitle(context) ?: "",
                onValueChange = {},
                readOnly = true,
                enabled = false,
                placeholder = {
                    Text(
                        if (sources.isEmpty()) stringResource(R.string.migration_no_sources)
                        else stringResource(R.string.migration_select_source),
                    )
                },
                trailingIcon = {
                    if (selectedSource != null && enabled) {
                        IconButton(onClick = { onSourceSelected(null) }) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterDropdown(
                label = stringResource(R.string.content_type_filter),
                summary = contentTypeFilter.takeIf { it.isNotEmpty() }?.joinToString { contentTypeLabel(context, it) }
                    ?: stringResource(R.string.filter_all),
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                listOf(BrowseGroupTab.Content, BrowseGroupTab.Novel, BrowseGroupTab.Video).forEach { tab ->
                    DropdownMenuItem(
                        text = { Text(stringResource(tab.titleRes)) },
                        onClick = { onContentTypeToggle(tab) },
                        trailingIcon = if (tab in contentTypeFilter) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }

            FilterDropdown(
                label = stringResource(R.string.source_type_filter),
                summary = sourceTagSummary,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                SourceTag.quickFilterEntries.forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(stringResource(tag.titleRes)) },
                        onClick = { onSourceTagToggle(tag) },
                        trailingIcon = if (tag in sourceTagFilter) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    if (showSelector) {
        SourceSelectorDialog(
            title = label,
            sources = sources,
            onSelect = {
                onSourceSelected(it)
                showSelector = false
            },
            onDismiss = { showSelector = false },
        )
    }
}

@Composable
private fun TargetSourcesSection(
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
private fun <T> EntityBrowseSection(
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
            Text(
                text = stringResource(R.string.entity_organize_visible_count, visibleCount, totalCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
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
private fun <T> rememberPagedBrowsePage(
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
private fun FilterDropdown(
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
private fun ConcurrencyDropdown(
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
private fun buildWorkbenchEntityMeta(row: EntityWorkbenchRow): String {
    val typeName = row.group.contentType.name
    val projectionCount = row.group.mangaIds.size
    val entityId = row.group.resolvedEntityId
    return when {
        entityId != null -> "$typeName · E#$entityId · $projectionCount"
        else -> "$typeName · $projectionCount"
    }
}

@Composable
private fun buildWorkbenchEntityDetail(row: EntityWorkbenchRow): String {
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

internal data class SourceSearchEntry(
    val stableKey: String,
    val source: ContentSource,
    val displayTitle: String,
    val normalizedName: String,
    val normalizedTitle: String,
)

internal fun buildSourceEntryKey(
    source: ContentSource,
    displayTitle: String,
    index: Int,
): String = "${source.name}:${displayTitle}:${source::class.java.name}:$index"

private fun stageSpec(stage: EntityOrganizeStage): EntityOrganizeStageSpec = when (stage) {
    EntityOrganizeStage.MERGE -> EntityOrganizeStageSpec(
        stage = stage,
        titleRes = R.string.entity_organize_merge_title,
        subtitleRes = R.string.entity_organize_merge_subtitle,
        placeholderRes = R.string.entity_organize_merge_placeholder,
        icon = Icons.Default.MergeType,
    )

    EntityOrganizeStage.TRACKING -> EntityOrganizeStageSpec(
        stage = stage,
        titleRes = R.string.entity_organize_tracking_title,
        subtitleRes = R.string.entity_organize_tracking_subtitle,
        placeholderRes = R.string.entity_organize_tracking_placeholder,
        icon = Icons.Default.Link,
    )

    EntityOrganizeStage.READING -> EntityOrganizeStageSpec(
        stage = stage,
        titleRes = R.string.entity_organize_reading_title,
        subtitleRes = R.string.entity_organize_reading_subtitle,
        icon = Icons.Default.PlaylistAddCheck,
    )
}

@Composable
private fun stageShortLabel(stage: EntityOrganizeStage): String = stringResource(
    when (stage) {
        EntityOrganizeStage.MERGE -> R.string.entity_organize_stage_short_merge
        EntityOrganizeStage.TRACKING -> R.string.entity_organize_stage_short_tracking
        EntityOrganizeStage.READING -> R.string.entity_organize_stage_short_reading
    },
)




private fun contentTypeLabel(context: Context, tab: BrowseGroupTab): String = when (tab) {
    BrowseGroupTab.Content -> context.getString(R.string.content_type_manga)
    BrowseGroupTab.Novel -> context.getString(R.string.content_type_novel)
    BrowseGroupTab.Video -> context.getString(R.string.content_type_video)
    else -> tab.id
}

@Composable
private fun MigrationProgressSection(
    uiState: MigrationUiState,
    selectedStage: EntityOrganizeStage,
) {
    val progress = uiState.migrationProgress ?: return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        ExecutionProgressSection(
            progress = progress,
            activeLabel = stringResource(
                if (selectedStage == EntityOrganizeStage.READING) {
                    R.string.entity_organize_reading_preview_active
                } else {
                    R.string.source_migration_start
                },
            ),
            finishedLabel = stringResource(
                if (selectedStage == EntityOrganizeStage.READING) {
                    R.string.entity_organize_reading_preview_finished
                } else {
                    R.string.source_migration_start
                },
            ),
        )
    }
}

@Composable
private fun ExecutionProgressSection(
    progress: MigrationProgress,
    activeLabel: String,
    finishedLabel: String,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { (progress.completed + progress.failed + progress.notFound).toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WorkbenchMetricChip(
                    label = activeLabel,
                    value = "${progress.completed + progress.failed + progress.notFound}/${progress.total}",
                    modifier = Modifier.weight(1f),
                )
                WorkbenchMetricChip(
                    label = stringResource(R.string.migration_status_success),
                    value = progress.completed.toString(),
                    modifier = Modifier.weight(1f),
                )
                WorkbenchMetricChip(
                    label = stringResource(R.string.migration_status_reused),
                    value = progress.reused.toString(),
                    modifier = Modifier.weight(1f),
                )
                WorkbenchMetricChip(
                    label = stringResource(R.string.migration_status_attached),
                    value = progress.attached.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WorkbenchMetricChip(
                    label = stringResource(R.string.migration_status_failed),
                    value = progress.failed.toString(),
                    modifier = Modifier.weight(1f),
                )
                WorkbenchMetricChip(
                    label = stringResource(R.string.migration_status_not_found),
                    value = progress.notFound.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            progress.currentItem?.let { currentItem ->
                Text(
                    text = stringResource(R.string.migration_status_active, currentItem.title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress.isFinished) {
                Text(
                    text = "$finishedLabel: " + stringResource(
                        R.string.migration_completed_summary,
                        progress.completed,
                        progress.reused,
                        progress.attached,
                        progress.failed,
                        progress.notFound,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
