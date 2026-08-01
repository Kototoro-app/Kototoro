package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.ext.copyToClipboard
import org.skepsun.kototoro.reader.domain.TranslationLayerState
import org.skepsun.kototoro.reader.ui.ReaderViewModel
import org.skepsun.kototoro.reader.ui.TranslationTaskBenchmarkFormatter

@Composable
internal fun ComposeTranslationTaskPanelContent(
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier,
) {
    val version by viewModel.translationTaskPanelVersion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snapshots = remember(version) { viewModel.getCurrentChapterTranslationTaskSnapshots() }
    val benchmark = remember(snapshots, context) { TranslationTaskBenchmarkFormatter(context).format(snapshots) }
    var filter by remember { mutableStateOf(TranslationTaskFilter.ALL) }
    var detail by remember { mutableStateOf<ReaderViewModel.TranslationPageTaskSnapshot?>(null) }
    var benchmarkVisible by remember { mutableStateOf(false) }
    val filtered = remember(snapshots, filter) { snapshots.filter(filter::matches) }
    val ready = filtered.count { it.state == TranslationLayerState.READY }
    val generating = filtered.count { it.state == TranslationLayerState.GENERATING }
    val failed = filtered.count { it.state == TranslationLayerState.FAILED }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.reader_translation_task_panel_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = if (snapshots.isEmpty()) {
                stringResource(R.string.reader_translation_task_panel_empty)
            } else {
                stringResource(
                    R.string.reader_translation_task_panel_summary,
                    filtered.size,
                    ready,
                    generating,
                    failed,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 72.dp),
        ) {
            items(TranslationTaskFilter.entries) { item ->
                FilterChip(
                    selected = filter == item,
                    onClick = { filter = item },
                    label = { Text(stringResource(item.label)) },
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = viewModel::retranslateFailedInCurrentChapter) {
                Text(stringResource(R.string.reader_translation_retry_failed_pages))
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (benchmark.isNotBlank()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { benchmarkVisible = true }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            stringResource(R.string.reader_translation_task_benchmark_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            benchmark.lineSequence().drop(1).firstOrNull().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (filtered.isEmpty() && snapshots.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(
                            R.string.reader_translation_task_panel_empty_for_filter,
                            stringResource(filter.label),
                        ),
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
            items(filtered, key = { it.pageId }) { snapshot ->
                TranslationTaskRow(snapshot = snapshot, onClick = { detail = snapshot })
            }
        }
    }

    detail?.let { snapshot ->
        TranslationTaskDetailDialog(
            snapshot = snapshot,
            onDismiss = { detail = null },
            onRetry = {
                viewModel.retryTranslationForPage(snapshot.pageId)
                detail = null
            },
        )
    }
    if (benchmarkVisible) {
        TranslationBenchmarkDialog(
            message = benchmark,
            onDismiss = { benchmarkVisible = false },
        )
    }
}

@Composable
private fun TranslationBenchmarkDialog(message: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val title = stringResource(R.string.reader_translation_task_benchmark_title)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                item { Text(message) }
            }
        },
        confirmButton = {
            TextButton(onClick = { context.copyToClipboard(title, message) }) {
                Text(stringResource(androidx.preference.R.string.copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun TranslationTaskRow(
    snapshot: ReaderViewModel.TranslationPageTaskSnapshot,
    onClick: () -> Unit,
) {
    val state = translationStateLabel(snapshot.state)
    val preview = snapshot.log.lineSequence().lastOrNull().orEmpty().ifBlank {
        stringResource(R.string.reader_translation_page_log_empty)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text("P${snapshot.pageIndex + 1} [$state]", style = MaterialTheme.typography.titleSmall)
        Text(preview, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TranslationTaskDetailDialog(
    snapshot: ReaderViewModel.TranslationPageTaskSnapshot,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val title = stringResource(
        R.string.reader_translation_task_detail_title,
        snapshot.pageIndex + 1,
        translationStateLabel(snapshot.state),
    )
    val rawLog = snapshot.log.ifBlank { stringResource(R.string.reader_translation_page_log_empty) }
    val report = remember(snapshot.log, context) {
        TranslationTaskBenchmarkFormatter(context).formatPageDetail(snapshot.log)
    }
    val message = if (report.isBlank()) rawLog else "$report\n\n----------------\n$rawLog"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                item { Text(message) }
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.reader_translation_retry_this_page))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { context.copyToClipboard(title, message) }) {
                    Text(stringResource(androidx.preference.R.string.copy))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}

@Composable
private fun translationStateLabel(state: TranslationLayerState): String = when (state) {
    TranslationLayerState.IDLE -> stringResource(R.string.reader_translation_task_state_idle)
    TranslationLayerState.GENERATING -> stringResource(R.string.reader_translation_task_state_generating)
    TranslationLayerState.READY -> stringResource(R.string.reader_translation_task_state_ready)
    TranslationLayerState.FAILED -> stringResource(R.string.reader_translation_task_state_failed)
}

private enum class TranslationTaskFilter(val label: Int) {
    ALL(R.string.reader_translation_task_filter_all),
    FAILED(R.string.reader_translation_task_filter_failed),
    OCR_EMPTY(R.string.reader_translation_task_filter_ocr_empty),
    TRANSLATE_EMPTY(R.string.reader_translation_task_filter_translate_empty),
    RENDER_FILTERED(R.string.reader_translation_task_filter_render_filtered),
    PROCESS_EXCEPTION(R.string.reader_translation_task_filter_exception),
    ;

    fun matches(item: ReaderViewModel.TranslationPageTaskSnapshot): Boolean = when (this) {
        ALL -> true
        FAILED -> item.state == TranslationLayerState.FAILED
        OCR_EMPTY -> item.failCode == "OCR_EMPTY"
        TRANSLATE_EMPTY -> item.failCode == "TRANSLATE_EMPTY"
        RENDER_FILTERED -> item.failCode == "RENDER_FILTERED"
        PROCESS_EXCEPTION -> item.failCode == "PROCESS_EXCEPTION"
    }
}
