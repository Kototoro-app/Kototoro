package org.skepsun.kototoro.migration.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.migration.domain.MatchCandidate
import org.skepsun.kototoro.migration.domain.MigrationMode
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.settings.compose.SettingsTopBarScaffold

@Composable
fun MigrationListScreen(
    state: MigrationListState,
    onNavigateUp: () -> Unit,
    onFilter: (MigrationFilter) -> Unit,
    onSkip: (Long) -> Unit,
    onSelectCandidate: (Long, MatchCandidate) -> Unit,
    onManualSearch: (Long, String) -> Unit,
    onMigrateNow: (Long) -> Unit,
    onOpenOriginal: (Content) -> Unit,
    onRequestMigrate: (MigrationMode) -> Unit,
    onConfirmMigrate: (MigrationMode) -> Unit,
    onCancelMigrate: () -> Unit,
    onDismissDialog: () -> Unit,
    onAbandon: () -> Unit,
) {
    var sheetItemId by remember { mutableStateOf<Long?>(null) }
    val title = if (state.isMatching) {
        stringResource(R.string.migration_title_progress, state.settledCount, state.items.size)
    } else {
        stringResource(R.string.migration_title)
    }
    SettingsTopBarScaffold(title = title, onNavigateUp = onNavigateUp) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.isMatching && state.items.isNotEmpty()) {
                LinearProgressIndicator(
                    progress = { state.settledCount.toFloat() / state.items.size },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FilterRow(state, onFilter)
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(state.visibleItems, key = { it.origin.id }) { item ->
                    MigrationRow(
                        item = item,
                        onClick = { sheetItemId = item.origin.id },
                        onSkip = { onSkip(item.origin.id) },
                        onMigrateNow = { onMigrateNow(item.origin.id) },
                        onOpenOriginal = { onOpenOriginal(item.origin) },
                    )
                    HorizontalDivider()
                }
            }
            BottomBar(state.readyCount, onRequestMigrate)
        }
    }

    sheetItemId?.let { id ->
        val item = state.items.firstOrNull { it.origin.id == id }
        if (item == null) {
            sheetItemId = null
        } else {
            MigrationCandidatesSheet(
                item = item,
                onSelect = { candidate ->
                    onSelectCandidate(id, candidate)
                    sheetItemId = null
                },
                onSearch = { query -> onManualSearch(id, query) },
                onDismiss = { sheetItemId = null },
            )
        }
    }

    MigrationDialogs(state.dialog, onConfirmMigrate, onCancelMigrate, onDismissDialog, onAbandon)
}

@Composable
private fun FilterRow(state: MigrationListState, onFilter: (MigrationFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(MigrationFilter.entries) { filter ->
            val label = when (filter) {
                MigrationFilter.ALL -> R.string.migration_filter_all
                MigrationFilter.MATCHED -> R.string.migration_filter_matched
                MigrationFilter.NOT_FOUND -> R.string.migration_filter_not_found
                MigrationFilter.FEWER_CHAPTERS -> R.string.migration_filter_fewer
            }
            FilterChip(
                selected = state.filter == filter,
                onClick = { onFilter(filter) },
                label = { Text(stringResource(label, state.count(filter))) },
            )
        }
    }
}

@Composable
private fun MigrationRow(
    item: MigrationItemState,
    onClick: () -> Unit,
    onSkip: () -> Unit,
    onMigrateNow: () -> Unit,
    onOpenOriginal: () -> Unit,
) {
    val context = LocalContext.current
    val matched = item.target != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = (item.target ?: item.origin).coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = if (matched) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
            modifier = Modifier
                .width(48.dp)
                .aspectRatio(13f / 18f)
                .clip(RoundedCornerShape(8.dp))
                .alpha(if (matched) 1f else 0.5f),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val newTitle = item.target?.title?.takeIf { it != item.origin.title }
            Text(
                text = if (newTitle != null) "${item.origin.title} → $newTitle" else item.origin.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.migration_chapters, item.origin.source.getTitle(context), item.originChapters) + " → ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                StatusText(item)
            }
            val hint = when {
                item.status == MigrationItemStatus.NOT_FOUND -> stringResource(R.string.migration_tap_to_search)
                item.otherCandidatesCount > 0 -> stringResource(R.string.migration_more_candidates, item.otherCandidatesCount)
                else -> null
            }
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        RowMenu(item, onSkip, onMigrateNow, onOpenOriginal)
    }
}

@Composable
private fun RowScope.StatusText(item: MigrationItemState) {
    val context = LocalContext.current
    when (item.status) {
        MigrationItemStatus.WAITING -> SecondaryText(stringResource(R.string.migration_status_waiting))
        MigrationItemStatus.SEARCHING, MigrationItemStatus.MIGRATING -> {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(4.dp))
            SecondaryText(stringResource(R.string.migration_status_searching))
        }
        MigrationItemStatus.NOT_FOUND -> Text(
            stringResource(R.string.migration_item_not_found),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        MigrationItemStatus.FAILED -> Text(
            stringResource(R.string.migration_item_failed, item.failure.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MigrationItemStatus.MATCHED -> {
            val target = item.target ?: return
            Text(
                stringResource(R.string.migration_chapters, target.source.getTitle(context), item.targetChapters ?: 0),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            item.chapterDelta?.let { DeltaBadge(it) }
        }
    }
}

@Composable
private fun SecondaryText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DeltaBadge(delta: Int) {
    val (bg, fg) = when {
        delta > 0 -> Color(0xFFDFF5E6) to Color(0xFF1D7A3E)
        delta < 0 -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(start = 4.dp)) {
        Text(
            text = if (delta > 0) "+$delta" else if (delta < 0) "−${-delta}" else "±0",
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun RowMenu(item: MigrationItemState, onSkip: () -> Unit, onMigrateNow: () -> Unit, onOpenOriginal: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.migration_action_skip)) },
                onClick = { expanded = false; onSkip() },
            )
            if (item.status == MigrationItemStatus.MATCHED) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.migration_action_migrate_now)) },
                    onClick = { expanded = false; onMigrateNow() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.migration_action_open)) },
                onClick = { expanded = false; onOpenOriginal() },
            )
        }
    }
}

@Composable
private fun BottomBar(readyCount: Int, onRequestMigrate: (MigrationMode) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onRequestMigrate(MigrationMode.COPY) },
                enabled = readyCount > 0,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.migration_copy_n, readyCount)) }
            Button(
                onClick = { onRequestMigrate(MigrationMode.REPLACE) },
                enabled = readyCount > 0,
                modifier = Modifier.weight(1.4f),
            ) { Text(stringResource(R.string.migration_migrate_n, readyCount)) }
        }
    }
}

@Composable
private fun MigrationDialogs(
    dialog: MigrationDialog?,
    onConfirmMigrate: (MigrationMode) -> Unit,
    onCancelMigrate: () -> Unit,
    onDismissDialog: () -> Unit,
    onAbandon: () -> Unit,
) {
    when (dialog) {
        null -> Unit
        is MigrationDialog.Confirm -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text(stringResource(R.string.migration_confirm_title, dialog.readyCount)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (dialog.skippedCount > 0) Text(stringResource(R.string.migration_confirm_skipped, dialog.skippedCount))
                    Text(
                        stringResource(
                            if (dialog.mode == MigrationMode.REPLACE) R.string.migration_confirm_replace else R.string.migration_confirm_copy,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirmMigrate(dialog.mode) }) {
                    Text(
                        stringResource(
                            if (dialog.mode == MigrationMode.REPLACE) R.string.migration_migrate_n else R.string.migration_copy_n,
                            dialog.readyCount,
                        ),
                    )
                }
            },
            dismissButton = { TextButton(onClick = onDismissDialog) { Text(stringResource(android.R.string.cancel)) } },
        )
        is MigrationDialog.Progress -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.migration_migrating)) },
            text = {
                LinearProgressIndicator(
                    progress = { if (dialog.total == 0) 0f else dialog.done.toFloat() / dialog.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancelMigrate) { Text(stringResource(android.R.string.cancel)) } },
        )
        is MigrationDialog.Result -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text(stringResource(R.string.migration_result, dialog.succeeded, dialog.failed)) },
            confirmButton = { TextButton(onClick = onDismissDialog) { Text(stringResource(android.R.string.ok)) } },
        )
        MigrationDialog.Exit -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text(stringResource(R.string.migration_exit_title)) },
            text = { Text(stringResource(R.string.migration_exit_message)) },
            confirmButton = { TextButton(onClick = onAbandon) { Text(stringResource(R.string.migration_abandon)) } },
            dismissButton = { TextButton(onClick = onDismissDialog) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
