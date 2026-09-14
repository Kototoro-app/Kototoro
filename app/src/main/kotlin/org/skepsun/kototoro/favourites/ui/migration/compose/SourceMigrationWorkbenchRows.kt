package org.skepsun.kototoro.favourites.ui.migration.compose


import coil3.compose.AsyncImage
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeStage
import org.skepsun.kototoro.favourites.domain.ReadingSourcePreviewAction
import org.skepsun.kototoro.favourites.domain.TrackingBindingPreview
import org.skepsun.kototoro.favourites.ui.migration.MigrationUiState
import org.skepsun.kototoro.parsers.model.ContentType
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun EntityWorkbenchHeader(
    allSelected: Boolean,
    onToggleSelectAll: (Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 640.dp) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.toggleable(
                            value = allSelected,
                            role = Role.Checkbox,
                            onValueChange = onToggleSelectAll,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = null,
                        )
                        Text(
                            text = stringResource(R.string.select_all),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        } else {
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
                    WorkbenchSelectableHeaderCell(
                        text = stringResource(R.string.entity_organize_workbench_entity_column),
                        checked = allSelected,
                        onCheckedChange = onToggleSelectAll,
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
    }
}

@Composable
private fun WorkbenchSelectableHeaderCell(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    width: androidx.compose.ui.unit.Dp,
) {
    Row(
        modifier = Modifier
            .width(width)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun EntityWorkbenchRowCard(
    selectedStage: EntityOrganizeStage,
    row: EntityWorkbenchRow,
    uiState: MigrationUiState,
    onToggleGroup: (String) -> Unit,
    onToggleReadingScopeGroup: (String) -> Unit,
    onToggleItem: (String, Long) -> Unit,
    onToggleTrackingPreview: (String) -> Unit,
    onToggleReadingPreview: (Long) -> Unit,
    onSplitLocalProjection: (Long) -> Unit,
    onDetachLocalProjection: (Long) -> Unit,
    onRepairDuplicateProjections: ((Long?) -> Unit)? = null,
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
    var pendingSplitMemberId by rememberSaveable(row.group.id) { mutableStateOf<Long?>(null) }
    var pendingDetachMemberId by rememberSaveable(row.group.id) { mutableStateOf<Long?>(null) }
    val entityMeta = buildWorkbenchEntityMeta(row)
    val entityDetail = buildWorkbenchEntityDetail(row)
    val hasMergePreviewSelection = uiState.mergePreviewReady
    val hasTrackingPreviewSelection = uiState.trackingPreviewReady
    val rowChecked = row.isRowSelectionChecked(selectedStage, uiState)
    val rowEnabled = row.isRowSelectionEnabled(selectedStage, uiState)
    val onToggleRowSelection = {
        when (selectedStage) {
            EntityOrganizeStage.MERGE -> {
                if (hasMergePreviewSelection) {
                    onToggleGroup(row.group.id)
                } else {
                    onToggleReadingScopeGroup(row.group.id)
                }
            }
            EntityOrganizeStage.TRACKING -> {
                if (hasTrackingPreviewSelection) {
                    val target = selectedTrackingId ?: recommendedTracking?.previewId
                    if (target != null) {
                        onToggleTrackingPreview(target)
                    }
                } else {
                    onToggleReadingScopeGroup(row.group.id)
                }
            }
            EntityOrganizeStage.READING -> {
                if (uiState.readingSourcePreviews.isNotEmpty()) {
                    val candidate = row.readingCandidates.firstOrNull { it.mangaId in uiState.acceptedReadingPreviewIds }
                        ?: row.readingCandidates.firstOrNull()
                    if (candidate != null) {
                        onToggleReadingPreview(candidate.mangaId)
                    }
                } else {
                    onToggleReadingScopeGroup(row.group.id)
                }
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
    pendingSplitMemberId?.let { mangaId ->
        AlertDialog(
            onDismissRequest = { pendingSplitMemberId = null },
            title = { Text(stringResource(R.string.entity_organize_repair_split_member)) },
            text = { Text(stringResource(R.string.entity_organize_repair_split_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSplitMemberId = null
                        onSplitLocalProjection(mangaId)
                    },
                ) {
                    Text(stringResource(R.string.entity_organize_repair_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSplitMemberId = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    pendingDetachMemberId?.let { mangaId ->
        AlertDialog(
            onDismissRequest = { pendingDetachMemberId = null },
            title = { Text(stringResource(R.string.entity_organize_repair_detach_member)) },
            text = { Text(stringResource(R.string.entity_organize_repair_detach_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDetachMemberId = null
                        onDetachLocalProjection(mangaId)
                    },
                ) {
                    Text(stringResource(R.string.entity_organize_repair_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDetachMemberId = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = rowContainerColor,
        border = BorderStroke(
            1.dp,
            rowBorderColor,
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isCompact = maxWidth < 640.dp
            if (isCompact) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Header Row: Checkbox, Title, Badges, StageMiniDots, Details Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Checkbox(
                            checked = rowChecked,
                            onCheckedChange = { onToggleRowSelection() },
                            enabled = rowEnabled,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = row.group.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = titleColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                ContentTypeBadge(row.group.contentType)
                                if (row.group.matchScore > 0f) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    ) {
                                        Text(
                                            text = "${row.group.matchScore.toPercentInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = entityMeta,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (row.duplicateProjectionCount > 0) {
                                    InlineStatusBadge(
                                        text = stringResource(
                                            R.string.entity_organize_duplicate_projections_badge,
                                            row.duplicateProjectionCount + 1,
                                        ),
                                        state = WorkbenchStageState.WARNING,
                                    )
                                    if (onRepairDuplicateProjections != null) {
                                        Text(
                                            text = stringResource(R.string.entity_organize_clean_duplicate_projections_action),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable(enabled = !uiState.isExecuting) {
                                                onRepairDuplicateProjections(row.group.resolvedEntityId)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            StageMiniDots(snapshot)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.clickable { expanded = !expanded },
                            ) {
                                Text(
                                    text = stringResource(
                                        if (expanded) R.string.collapse else R.string.details,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }

                    // Stage-Aware Body
                    if (!expanded) {
                        when (selectedStage) {
                            EntityOrganizeStage.MERGE -> {
                                WorkbenchMembersColumn(
                                    row = row,
                                    visibleMembers = visibleMembers,
                                    expanded = expanded,
                                    uiState = uiState,
                                    onToggleItem = onToggleItem,
                                    onToggleExpand = { expanded = !expanded },
                                    onSplitClick = { pendingSplitMemberId = it },
                                    onDetachClick = { pendingDetachMemberId = it },
                                )
                            }
                            EntityOrganizeStage.TRACKING -> {
                                WorkbenchTrackingColumn(
                                    row = row,
                                    expanded = expanded,
                                    selectedTrackingId = selectedTrackingId,
                                    recommendedTracking = recommendedTracking,
                                    onToggleTrackingPreview = onToggleTrackingPreview,
                                )
                            }
                            EntityOrganizeStage.READING -> {
                                WorkbenchReadingColumn(
                                    row = row,
                                    expanded = expanded,
                                    uiState = uiState,
                                    onToggleReadingPreview = onToggleReadingPreview,
                                )
                            }
                        }
                    } else {
                        // Expanded view showing all details compactly
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.entity_organize_workbench_members_column) + " (${row.group.items.size})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            WorkbenchMembersColumn(
                                row = row,
                                visibleMembers = row.group.items,
                                expanded = true,
                                uiState = uiState,
                                onToggleItem = onToggleItem,
                                onToggleExpand = { expanded = !expanded },
                                onSplitClick = { pendingSplitMemberId = it },
                                onDetachClick = { pendingDetachMemberId = it },
                            )
                            if (row.existingTrackingBindings.isNotEmpty() || row.trackingCandidates.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.entity_organize_tracking_title),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                WorkbenchTrackingColumn(
                                    row = row,
                                    expanded = true,
                                    selectedTrackingId = selectedTrackingId,
                                    recommendedTracking = recommendedTracking,
                                    onToggleTrackingPreview = onToggleTrackingPreview,
                                )
                            }
                            if (row.readingCandidates.isNotEmpty() || row.currentProjectionItem() != null) {
                                Text(
                                    text = stringResource(R.string.entity_organize_reading_title),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                WorkbenchReadingColumn(
                                    row = row,
                                    expanded = true,
                                    uiState = uiState,
                                    onToggleReadingPreview = onToggleReadingPreview,
                                )
                            }
                        }
                    }
                }
            } else {
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
                        Box(modifier = Modifier.width(widths.entity)) {
                            WorkbenchEntityInfoColumn(
                                row = row,
                                titleColor = titleColor,
                                entityMeta = entityMeta,
                                entityDetail = entityDetail,
                                snapshot = snapshot,
                                rowChecked = rowChecked,
                                rowEnabled = rowEnabled,
                                onToggleRowSelection = onToggleRowSelection,
                                onRepairDuplicateProjections = onRepairDuplicateProjections,
                                isExecuting = uiState.isExecuting,
                            )
                        }
                        WorkbenchDivider(fillHeight = true)
                        Box(modifier = Modifier.width(widths.members)) {
                            WorkbenchMembersColumn(
                                row = row,
                                visibleMembers = visibleMembers,
                                expanded = expanded,
                                uiState = uiState,
                                onToggleItem = onToggleItem,
                                onToggleExpand = { expanded = !expanded },
                                onSplitClick = { pendingSplitMemberId = it },
                                onDetachClick = { pendingDetachMemberId = it },
                            )
                        }
                        WorkbenchDivider(fillHeight = true)
                        Box(modifier = Modifier.width(widths.tracking)) {
                            WorkbenchTrackingColumn(
                                row = row,
                                expanded = expanded,
                                selectedTrackingId = selectedTrackingId,
                                recommendedTracking = recommendedTracking,
                                onToggleTrackingPreview = onToggleTrackingPreview,
                            )
                        }
                        WorkbenchDivider(fillHeight = true)
                        Box(modifier = Modifier.width(widths.reading)) {
                            WorkbenchReadingColumn(
                                row = row,
                                expanded = expanded,
                                uiState = uiState,
                                onToggleReadingPreview = onToggleReadingPreview,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkbenchEntityInfoColumn(
    row: EntityWorkbenchRow,
    titleColor: androidx.compose.ui.graphics.Color,
    entityMeta: String,
    entityDetail: String,
    snapshot: WorkbenchRowStageSnapshot,
    rowChecked: Boolean,
    rowEnabled: Boolean,
    onToggleRowSelection: () -> Unit,
    onRepairDuplicateProjections: ((Long?) -> Unit)?,
    isExecuting: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.toggleable(
            value = rowChecked,
            enabled = rowEnabled,
            role = Role.Checkbox,
            onValueChange = { onToggleRowSelection() },
        ),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(
            checked = rowChecked,
            onCheckedChange = null,
            enabled = rowEnabled,
        )
        Column(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
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
            if (row.duplicateProjectionCount > 0) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InlineStatusBadge(
                        text = stringResource(
                            R.string.entity_organize_duplicate_projections_badge,
                            row.duplicateProjectionCount + 1,
                        ),
                        state = WorkbenchStageState.WARNING,
                    )
                    if (onRepairDuplicateProjections != null) {
                        TextButton(
                            onClick = { onRepairDuplicateProjections(row.group.resolvedEntityId) },
                            enabled = !isExecuting,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.entity_organize_clean_duplicate_projections_action),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkbenchMembersColumn(
    row: EntityWorkbenchRow,
    visibleMembers: List<org.skepsun.kototoro.favourites.domain.MergeCandidateItem>,
    expanded: Boolean,
    uiState: MigrationUiState,
    onToggleItem: (String, Long) -> Unit,
    onToggleExpand: () -> Unit,
    onSplitClick: (Long) -> Unit,
    onDetachClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        visibleMembers.forEach { item ->
            val itemChecked = item.mangaId in uiState.selectedMergeItemsByGroup[row.group.id].orEmpty()
            val isSuspectMismerged = item.mangaId in uiState.suspectMismergedLocalMangaIds
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
                    if (isSuspectMismerged) {
                        InlineStatusBadge(
                            text = stringResource(R.string.entity_organize_repair_suspect_mismerged),
                            state = WorkbenchStageState.WARNING,
                        )
                    }
                    if (!row.isMergeCandidate) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(
                                onClick = { onSplitClick(item.mangaId) },
                                enabled = !uiState.isExecuting,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.entity_organize_repair_split_member),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            TextButton(
                                onClick = { onDetachClick(item.mangaId) },
                                enabled = !uiState.isExecuting,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.entity_organize_repair_detach_member),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (row.group.items.size > 3) {
            TextButton(
                onClick = onToggleExpand,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
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
}

@Composable
private fun WorkbenchTrackingColumn(
    row: EntityWorkbenchRow,
    expanded: Boolean,
    selectedTrackingId: String?,
    recommendedTracking: TrackingBindingPreview?,
    onToggleTrackingPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (row.existingTrackingBindings.isNotEmpty()) {
            row.existingTrackingBindings.take(if (expanded) 3 else 1).forEach { preview ->
                CompactSelectCard(
                    checked = false,
                    prefix = stringResource(R.string.entity_organize_tracking_match_existing),
                    title = stringResource(preview.service.titleResId),
                    subtitle = preview.matchedTitle,
                    meta = preview.remoteId.toString(),
                    emphasized = false,
                    badge = stringResource(R.string.entity_organize_tracking_match_existing),
                    tone = CompactSelectTone.Recommended,
                    enabled = false,
                    onToggle = {},
                )
            }
        }
        if (row.trackingCandidates.isEmpty() && row.existingTrackingBindings.isEmpty()) {
            Text(
                text = stringResource(R.string.entity_organize_workbench_tracking_empty),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (row.trackingCandidates.isNotEmpty()) {
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
}

@Composable
private fun WorkbenchReadingColumn(
    row: EntityWorkbenchRow,
    expanded: Boolean,
    uiState: MigrationUiState,
    onToggleReadingPreview: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
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
    enabled: Boolean = true,
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
                .clickable(enabled = enabled, onClick = onToggle)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = enabled,
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
    entity = 176.dp,
    members = 198.dp,
    tracking = 132.dp,
    reading = 144.dp,
)

@Composable
private fun MemberCoverThumb(
    title: String,
    coverUrl: String?,
) {
    val shape = RoundedCornerShape(6.dp)
    if (!coverUrl.isNullOrBlank()) {
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            modifier = Modifier
                .size(width = 24.dp, height = 34.dp)
                .clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            modifier = Modifier
                .size(width = 24.dp, height = 34.dp),
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
private fun ContentTypeBadge(
    contentType: ContentType?,
    modifier: Modifier = Modifier,
) {
    if (contentType == null) return
    val (label, containerColor, contentColor) = when (contentType) {
        ContentType.MANGA,
        ContentType.MANHWA,
        ContentType.MANHUA,
        ContentType.COMICS,
        ContentType.ONE_SHOT,
        ContentType.DOUJINSHI ->
            Triple(
                stringResource(R.string.manga),
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                MaterialTheme.colorScheme.primary,
            )
        ContentType.NOVEL ->
            Triple(
                stringResource(R.string.novel),
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                MaterialTheme.colorScheme.secondary,
            )
        ContentType.VIDEO ->
            Triple(
                stringResource(R.string.video),
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                MaterialTheme.colorScheme.tertiary,
            )
        ContentType.HENTAI_MANGA,
        ContentType.HENTAI_NOVEL,
        ContentType.HENTAI_VIDEO ->
            Triple(
                "18+",
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                MaterialTheme.colorScheme.error,
            )
        else ->
            Triple(
                contentType.name,
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = containerColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun StageMiniDots(
    snapshot: WorkbenchRowStageSnapshot,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageMiniDot(
            char = stringResource(R.string.entity_organize_stage_short_merge).take(1).uppercase(),
            state = snapshot.mergeState,
        )
        StageMiniDot(
            char = stringResource(R.string.entity_organize_stage_short_tracking).take(1).uppercase(),
            state = snapshot.trackingState,
        )
        StageMiniDot(
            char = stringResource(R.string.entity_organize_stage_short_reading).take(1).uppercase(),
            state = snapshot.readingState,
        )
    }
}

@Composable
private fun StageMiniDot(
    char: String,
    state: WorkbenchStageState,
) {
    val (bgColor, textColor) = when (state) {
        WorkbenchStageState.READY -> Pair(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        WorkbenchStageState.WARNING -> Pair(
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        WorkbenchStageState.MISSING -> Pair(
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
            MaterialTheme.colorScheme.onErrorContainer,
        )
        WorkbenchStageState.EMPTY -> Pair(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = bgColor,
    ) {
        Text(
            text = char,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = textColor,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }
}

