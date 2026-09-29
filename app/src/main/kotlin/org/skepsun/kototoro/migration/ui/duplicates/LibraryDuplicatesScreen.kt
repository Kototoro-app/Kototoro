package org.skepsun.kototoro.migration.ui.duplicates

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.migration.domain.DuplicateGroupEntry
import org.skepsun.kototoro.migration.ui.migrationTitle
import org.skepsun.kototoro.migration.ui.rememberCoverRequest
import org.skepsun.kototoro.settings.compose.SettingsTopBarScaffold

@Composable
fun LibraryDuplicatesScreen(
    state: LibraryDuplicatesState,
    onNavigateUp: () -> Unit,
    onOpen: (Long) -> Unit,
    onSelectKeep: (String, Long) -> Unit,
    onMerge: (String) -> Unit,
    onIgnore: (String) -> Unit,
    onRequestMergeAll: () -> Unit,
    onConfirmMergeAll: () -> Unit,
    onDismissMergeAll: () -> Unit,
) {
    SettingsTopBarScaffold(title = stringResource(R.string.library_duplicates_title), onNavigateUp = onNavigateUp) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.groups.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.mergedCount > 0) {
                            pluralStringResource(R.plurals.library_duplicates_merged, state.mergedCount, state.mergedCount)
                        } else {
                            stringResource(R.string.library_duplicates_none)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text(
                        stringResource(R.string.library_duplicates_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.groups, key = { it.group.key }) { ui ->
                            GroupCard(
                                ui = ui,
                                enabled = !state.isMergingAll,
                                onOpen = onOpen,
                                onSelectKeep = { onSelectKeep(ui.group.key, it) },
                                onMerge = { onMerge(ui.group.key) },
                                onIgnore = { onIgnore(ui.group.key) },
                            )
                        }
                    }
                    Surface(tonalElevation = 3.dp) {
                        Button(
                            onClick = onRequestMergeAll,
                            enabled = !state.isMergingAll,
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                        ) {
                            if (state.isMergingAll) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text(pluralStringResource(R.plurals.library_duplicates_merge_all, state.groups.size, state.groups.size))
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.confirmMergeAll) {
        AlertDialog(
            onDismissRequest = onDismissMergeAll,
            title = { Text(pluralStringResource(R.plurals.library_duplicates_merge_all, state.groups.size, state.groups.size)) },
            text = { Text(stringResource(R.string.library_duplicates_merge_all_message)) },
            confirmButton = { TextButton(onClick = onConfirmMergeAll) { Text(stringResource(R.string.library_duplicates_merge)) } },
            dismissButton = { TextButton(onClick = onDismissMergeAll) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun GroupCard(
    ui: DuplicateGroupUi,
    enabled: Boolean,
    onOpen: (Long) -> Unit,
    onSelectKeep: (Long) -> Unit,
    onMerge: () -> Unit,
    onIgnore: () -> Unit,
) {
    val keep = ui.group.entries.first { it.row.id == ui.keepId }.row
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                keep.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                pluralStringResource(R.plurals.library_duplicates_entries, ui.group.entries.size, ui.group.entries.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(ui.group.entries, key = { it.row.id }) { entry ->
                    EntryCard(
                        entry = entry,
                        selected = entry.row.id == ui.keepId,
                        onClick = { if (enabled && !ui.isMerging) onSelectKeep(entry.row.id) },
                        onOpen = { onOpen(entry.row.id) },
                    )
                }
            }
            ui.error?.let {
                Text(
                    stringResource(R.string.migration_item_failed, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onIgnore, enabled = enabled && !ui.isMerging) {
                    Text(stringResource(R.string.library_duplicates_not_duplicates))
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onMerge, enabled = enabled && !ui.isMerging) {
                    if (ui.isMerging) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.library_duplicates_merge))
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(entry: DuplicateGroupEntry, selected: Boolean, onClick: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val row = entry.row
    val source = ContentSource(row.source)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.width(128.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(8.dp)) {
            Box {
                AsyncImage(
                    model = rememberCoverRequest(row.coverUrl, source),
                    contentDescription = row.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(13f / 18f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpen),
                )
                val badge = when {
                    selected -> stringResource(R.string.library_duplicates_keep)
                    entry.sourceBroken -> stringResource(R.string.duplicate_source_broken)
                    else -> null
                }
                if (badge != null) {
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.padding(4.dp),
                    ) {
                        Text(
                            badge,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Text(
                source.migrationTitle(context),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (row.knownChapters > 0) {
                Text(
                    stringResource(R.string.library_duplicates_chapters, row.knownChapters),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                readingLabel(row),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val percent = (row.historyPercent ?: 0f).coerceIn(0f, 1f)
            if (percent > 0f) {
                LinearProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

@Composable
internal fun readingLabel(row: org.skepsun.kototoro.migration.data.LibraryRow): String {
    val chapter = row.historyChapterNumber
    val percent = row.historyPercent ?: 0f
    return when {
        chapter != null -> stringResource(
            R.string.duplicate_read_to,
            if (chapter % 1f == 0f) chapter.toInt().toString() else chapter.toString(),
        )
        percent > 0f -> stringResource(R.string.duplicate_read_percent, (percent.coerceIn(0f, 1f) * 100).toInt())
        else -> stringResource(R.string.duplicate_unread)
    }
}
