package org.skepsun.kototoro.favourites.ui.migration.compose


import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeDatasetStatus
import org.skepsun.kototoro.favourites.ui.migration.EntityOrganizeDatasetBridge
import org.skepsun.kototoro.favourites.ui.migration.MigrationUiState

@Composable
internal fun HeaderSection(
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
internal fun DatasetBridgeCard(
    selectedBridge: EntityOrganizeDatasetBridge,
    animeStatus: EntityOrganizeDatasetStatus,
    mangaBakaStatus: EntityOrganizeDatasetStatus,
    onBridgeSelected: (EntityOrganizeDatasetBridge) -> Unit,
    onRefreshAnime: () -> Unit,
    onUpdateAnime: () -> Unit,
    onDeleteAnime: () -> Unit,
    onRefreshMangaBaka: () -> Unit,
    onUpdateMangaBaka: () -> Unit,
    onDeleteMangaBaka: () -> Unit,
    onBuildMangaBakaIndex: () -> Unit,
) {
    val status = when (selectedBridge) {
        EntityOrganizeDatasetBridge.ANIME_OFFLINE -> animeStatus
        EntityOrganizeDatasetBridge.MANGABAKA -> mangaBakaStatus
    }
    val canDeleteDataset = !status.isLoading &&
        (status.isInstalled || status.hasSearchIndex || status.version != null || status.searchIndexVersion != null)
    val onDeleteDataset = when (selectedBridge) {
        EntityOrganizeDatasetBridge.ANIME_OFFLINE -> onDeleteAnime
        EntityOrganizeDatasetBridge.MANGABAKA -> onDeleteMangaBaka
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
                FilledIconButton(
                    onClick = onDeleteDataset,
                    enabled = canDeleteDataset,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(18.dp),
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

