package org.skepsun.kototoro.favourites.ui.migration.compose


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeStage
import org.skepsun.kototoro.favourites.ui.migration.MigrationUiState
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.core.prefs.TrackingMetadataSourceStrategy

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
internal fun StageConfigCard(
    selectedStage: EntityOrganizeStage,
    rowCount: Int,
    workbenchRows: List<EntityWorkbenchRow>,
    onStageSelected: (EntityOrganizeStage) -> Unit,
    uiState: MigrationUiState,
    onTrackingMetadataStrategyChange: (TrackingMetadataSourceStrategy) -> Unit,
    onFuzzyMergeCandidatesEnabledChange: (Boolean) -> Unit,
    onFuzzyMergeThresholdPercentChange: (Int) -> Unit,
    onFuzzyTrackingCandidatesEnabledChange: (Boolean) -> Unit,
    onFuzzyTrackingThresholdPercentChange: (Int) -> Unit,
    onToggleTrackingService: (ScrobblerService) -> Unit,
    onMoveTrackingServiceUp: (ScrobblerService) -> Unit,
    onMoveTrackingServiceDown: (ScrobblerService) -> Unit,
    onPreviewMerge: () -> Unit,
    onClearManualMergeSelections: () -> Unit,
    onManualMergeSelected: () -> Unit,
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
    val plans = remember(uiState, workbenchRows) {
        EntityOrganizeStage.entries.map { stage ->
            val plan = uiState.stagePlan(stage)
            val count = stageTabCount(
                stage = stage,
                rows = workbenchRows,
                uiState = uiState,
                plan = plan,
            )
            plan to count
        }
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
                        plans.forEach { (plan, count) ->
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
                                        text = "${count.accepted}/${count.total}",
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
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.entity_organize_merge_fuzzy_toggle),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = stringResource(R.string.entity_organize_merge_fuzzy_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = uiState.fuzzyMergeCandidatesEnabled,
                                    onCheckedChange = onFuzzyMergeCandidatesEnabledChange,
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.entity_organize_merge_fuzzy_threshold,
                                    uiState.fuzzyMergeThresholdPercent,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            KototoroSlider(
                                value = uiState.fuzzyMergeThresholdPercent.toFloat(),
                                onValueChange = {
                                    onFuzzyMergeThresholdPercentChange(it.toInt())
                                },
                                valueRange = 80f..100f,
                                steps = 19,
                                enabled = uiState.fuzzyMergeCandidatesEnabled,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onPreviewMerge,
                        enabled = mergePlan.canPreview,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                    ) {
                        ButtonLabel(stringResource(R.string.entity_organize_merge_preview))
                    }
                    Text(
                        text = stringResource(R.string.entity_organize_manual_merge_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onClearManualMergeSelections,
                            enabled = uiState.manualMergeMangaIds.isNotEmpty() && !uiState.isExecuting,
                            modifier = Modifier
                                .weight(0.42f)
                                .heightIn(min = 44.dp),
                        ) {
                            ButtonLabel(stringResource(R.string.entity_organize_manual_merge_clear))
                        }
                        OutlinedButton(
                            onClick = onManualMergeSelected,
                            enabled = uiState.manualMergeMangaIds.size >= 2 && !uiState.isExecuting,
                            modifier = Modifier
                                .weight(0.58f)
                                .heightIn(min = 44.dp),
                        ) {
                            ButtonLabel(
                                stringResource(
                                    R.string.entity_organize_manual_merge_execute,
                                    uiState.manualMergeMangaIds.size,
                                ),
                            )
                        }
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
                        onFuzzyTrackingCandidatesEnabledChange = onFuzzyTrackingCandidatesEnabledChange,
                        onFuzzyTrackingThresholdPercentChange = onFuzzyTrackingThresholdPercentChange,
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

