package org.skepsun.kototoro.favourites.ui.migration.compose


import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.favourites.domain.MigrationProgress
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeStage
import org.skepsun.kototoro.favourites.ui.migration.MigrationUiState
import org.skepsun.kototoro.parsers.model.ContentSource

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

internal fun stageSpec(stage: EntityOrganizeStage): EntityOrganizeStageSpec = when (stage) {
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
internal fun stageShortLabel(stage: EntityOrganizeStage): String = stringResource(
    when (stage) {
        EntityOrganizeStage.MERGE -> R.string.entity_organize_stage_short_merge
        EntityOrganizeStage.TRACKING -> R.string.entity_organize_stage_short_tracking
        EntityOrganizeStage.READING -> R.string.entity_organize_stage_short_reading
    },
)




internal fun contentTypeLabel(context: Context, tab: BrowseGroupTab): String = when (tab) {
    BrowseGroupTab.Content -> context.getString(R.string.content_type_manga)
    BrowseGroupTab.Novel -> context.getString(R.string.content_type_novel)
    BrowseGroupTab.Video -> context.getString(R.string.content_type_video)
    else -> tab.id
}

@Composable
internal fun MigrationProgressSection(
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
internal fun ExecutionProgressSection(
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

