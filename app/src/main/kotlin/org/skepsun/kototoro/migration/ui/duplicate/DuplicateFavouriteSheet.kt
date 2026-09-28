package org.skepsun.kototoro.migration.ui.duplicate

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.chaptersCount
import org.skepsun.kototoro.migration.ui.migrationTitle
import org.skepsun.kototoro.parsers.model.Content

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateFavouriteSheet(
    content: Content,
    duplicates: List<DuplicateEntry>,
    viewModel: DuplicateFavouriteViewModel,
    onOpen: (Long) -> Unit,
    onAddAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(viewModel.onSwitched) {
        viewModel.onSwitched.collect { event ->
            event?.consume {
                Toast.makeText(context, R.string.migration_completed, Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.duplicate_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.duplicate_subtitle, content.title, content.source.migrationTitle(context), content.chaptersCount()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(duplicates, key = { it.row.id }) { entry ->
                    DuplicateCard(
                        entry = entry,
                        onOpen = { onOpen(entry.row.id) },
                        onSwitch = { viewModel.switchSource(entry.row, content) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(android.R.string.cancel))
                }
                Button(onClick = onAddAnyway, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.duplicate_add_anyway)) }
            }
            TextButton(
                onClick = {
                    viewModel.disableCheck()
                    onAddAnyway()
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(stringResource(R.string.duplicate_dont_ask)) }
        }
    }
}

@Composable
private fun DuplicateCard(entry: DuplicateEntry, onOpen: () -> Unit, onSwitch: () -> Unit) {
    val context = LocalContext.current
    val row = entry.row
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.width(132.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Box(Modifier.clickable(onClick = onOpen)) {
                AsyncImage(
                    model = row.coverUrl,
                    contentDescription = row.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(13f / 18f).clip(RoundedCornerShape(10.dp)),
                )
                if (entry.sourceBroken) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.padding(4.dp),
                    ) {
                        Text(
                            stringResource(R.string.duplicate_source_broken),
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Text(row.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${ContentSource(row.source).migrationTitle(context)} · ${row.chaptersCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                row.historyChapterNumber?.let {
                    stringResource(R.string.duplicate_read_to, if (it % 1f == 0f) it.toInt().toString() else it.toString())
                } ?: stringResource(R.string.duplicate_unread),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { (row.historyPercent ?: 0f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            if (entry.sourceBroken) {
                Button(onClick = onSwitch, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.duplicate_switch_source), style = MaterialTheme.typography.labelSmall)
                }
            } else {
                OutlinedButton(onClick = onSwitch, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.duplicate_switch_source), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
