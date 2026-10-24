package org.skepsun.kototoro.alternatives.ui.compose

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.alternatives.ui.AlternativesViewModel
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.compose.TvModalBottomSheet
import org.skepsun.kototoro.parsers.model.Content

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlternativesSheetRoute(
    manga: Content,
    onOpenDetails: (Content) -> Unit,
    onOpenSourceSearch: (source: org.skepsun.kototoro.parsers.model.ContentSource, query: String) -> Unit,
    onDismissRequest: () -> Unit,
    viewModel: AlternativesViewModel = hiltViewModel(key = "alternatives-${manga.id}"),
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val items by viewModel.list.collectAsStateWithLifecycle()
    val pinnedOnly by viewModel.pinnedOnly.collectAsStateWithLifecycle()
    var migrationTarget by remember { mutableStateOf<Content?>(null) }

    LaunchedEffect(manga) {
        viewModel.initialize(manga)
    }

    LaunchedEffect(viewModel.onMigrated) {
        viewModel.onMigrated.collect { event ->
            event?.consume { migrated ->
                Toast.makeText(context, R.string.migration_completed, Toast.LENGTH_SHORT).show()
                onDismissRequest()
                onOpenDetails(migrated)
            }
        }
    }

    TvModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(0.9f),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TopAppBar(
                title = { Text(text = stringResource(R.string.alternatives)) },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Checkbox(
                            checked = pinnedOnly,
                            onCheckedChange = viewModel::setPinnedOnly,
                        )
                        Text(text = stringResource(R.string.pinned_sources_only))
                    }
                },
            )
            AlternativesSheetContent(
                items = items,
                onItemClick = { onOpenDetails(it.manga) },
                onSourceClick = { onOpenSourceSearch(it.manga.source, viewModel.manga.title) },
                onMigrateClick = { migrationTarget = it.manga },
                onRetry = { viewModel.retry() },
                onContinueSearch = { viewModel.continueSearch() },
            )
        }
    }

    migrationTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { migrationTarget = null },
            title = { Text(text = stringResource(R.string.manga_migration)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.migrate_confirmation,
                        viewModel.manga.title,
                        viewModel.manga.source.getTitle(context),
                        target.title,
                        target.source.getTitle(context),
                    ),
                )
            },
            confirmButton = {
                TvTextButton(
                    onClick = {
                        migrationTarget = null
                        viewModel.migrate(target)
                    },
                ) {
                    Text(text = stringResource(R.string.migrate))
                }
            },
            dismissButton = {
                TvTextButton(onClick = { migrationTarget = null }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
