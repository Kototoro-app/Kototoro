package org.skepsun.kototoro.favourites.ui.migration.compose


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeStage
import org.skepsun.kototoro.favourites.domain.TrackingBindingMatchKind
import org.skepsun.kototoro.favourites.domain.TrackingBindingPreview
import org.skepsun.kototoro.favourites.ui.migration.MigrationUiState
import org.skepsun.kototoro.favourites.ui.migration.isExecutableMergeCandidate
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.core.prefs.TrackingMetadataSourceStrategy
import java.util.Locale

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
    var pageSize by rememberSaveable { mutableStateOf(ENTITY_ORGANIZE_PAGE_SIZES[1]) }
    var currentPage by rememberSaveable { mutableStateOf(0) }
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val filteredGroups = remember(
        uiState.mergeCandidateGroups,
        uiState.selectedMergeGroupIds,
        uiState.selectedMergeItemsByGroup,
        normalizedQuery,
        showSelectedOnly,
    ) {
        uiState.mergeCandidateGroups.filter { group ->
            val isMergeCandidate = group.isExecutableMergeCandidate()
            val matchesSelection = !showSelectedOnly || group.id in uiState.selectedMergeGroupIds
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
            isMergeCandidate && matchesSelection && matchesQuery
        }
    }
    val mergeCandidateCount = remember(uiState.mergeCandidateGroups) {
        uiState.mergeCandidateGroups.count { it.isExecutableMergeCandidate() }
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
                visibleCount = filteredGroups.size,
                totalCount = mergeCandidateCount,
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
internal fun TrackingBindingSection(
    uiState: MigrationUiState,
    onTrackingMetadataStrategyChange: (TrackingMetadataSourceStrategy) -> Unit,
    onFuzzyTrackingCandidatesEnabledChange: (Boolean) -> Unit,
    onFuzzyTrackingThresholdPercentChange: (Int) -> Unit,
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
                                text = stringResource(R.string.entity_organize_tracking_fuzzy_toggle),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.entity_organize_tracking_fuzzy_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = uiState.fuzzyTrackingCandidatesEnabled,
                            onCheckedChange = onFuzzyTrackingCandidatesEnabledChange,
                            enabled = !uiState.isExecuting,
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.entity_organize_tracking_fuzzy_threshold,
                            uiState.fuzzyTrackingThresholdPercent,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    KototoroSlider(
                        value = uiState.fuzzyTrackingThresholdPercent.toFloat(),
                        onValueChange = {
                            onFuzzyTrackingThresholdPercentChange(it.toInt())
                        },
                        valueRange = 80f..100f,
                        steps = 19,
                        enabled = uiState.fuzzyTrackingCandidatesEnabled && !uiState.isExecuting,
                    )
                }
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
internal fun SourceFilterSection(
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

