package org.skepsun.kototoro.local.ui.compose

import androidx.compose.foundation.layout.Box
import org.skepsun.kototoro.core.ui.compose.AppLayoutTokens
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.FlowCollector
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.util.FileSize
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.calculateTimeAgo
import org.skepsun.kototoro.download.ui.list.DownloadItemModel
import org.skepsun.kototoro.download.ui.list.DownloadsViewModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.local.ui.LocalDownloadStorageStats
import org.skepsun.kototoro.local.ui.LocalDownloadStorageViewModel

private const val MAX_EXTRA_ACTIVE_TASKS = 2
private const val MAX_RECENT_ITEMS = 3

@Composable
internal fun LocalDownloadsCardRoute(
    onOpenDownloads: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
    storageViewModel: LocalDownloadStorageViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val storage by storageViewModel.stats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel, context) {
        viewModel.onError.collect { event ->
            event?.consume(FlowCollector { error ->
                Toast.makeText(context, error.getDisplayMessage(context.resources), Toast.LENGTH_LONG).show()
            })
        }
    }
    val summary = remember(items) { summarizeLocalDownloads(items.filterIsInstance<DownloadItemModel>()) }
    val isLoading = items.any { it is LoadingState }
    val isIdle = !isLoading && summary.pendingCount == 0
    // Directory scans are cheap enough once the queue settles; rescan whenever it returns to idle.
    LaunchedEffect(isIdle) {
        if (isIdle) storageViewModel.refresh()
    }
    LocalDownloadsCard(
        summary = summary,
        isLoading = isLoading,
        storage = storage.takeIf { isIdle },
        onOpenDownloads = onOpenDownloads,
        onPause = { item -> viewModel.pause(setOf(item.id.mostSignificantBits)) },
        onResume = { item -> viewModel.resume(setOf(item.id.mostSignificantBits)) },
        onPauseAll = viewModel::pauseAll,
        onResumeAll = viewModel::resumeAll,
    )
}

@Composable
internal fun LocalDownloadsCard(
    summary: LocalDownloadSummary,
    isLoading: Boolean,
    storage: LocalDownloadStorageStats?,
    onOpenDownloads: () -> Unit,
    onPause: (DownloadItemModel) -> Unit,
    onResume: (DownloadItemModel) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
) {
    val featured = summary.featured
    val colors = MaterialTheme.colorScheme
    val cover = (featured?.displayManga ?: featured?.executionManga)?.coverUrl?.takeIfUsableImageUri()
    val statusColor = if (featured?.workState == WorkInfo.State.FAILED) colors.error else colors.primary
    Surface(
        onClick = onOpenDownloads,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppLayoutTokens.screenHorizontalPadding, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainer,
    ) {
        Box {
            if (cover != null) {
                // Decorative artwork stays behind a tonal scrim, including in light themes.
                Box(Modifier.matchParentSize()) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.CenterEnd,
                        modifier = Modifier.fillMaxHeight().width(200.dp).align(Alignment.CenterEnd),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.horizontalGradient(
                                0f to colors.surfaceContainer,
                                0.45f to colors.surfaceContainer.copy(alpha = 0.97f),
                                1f to colors.surfaceContainer.copy(alpha = 0.82f),
                            ),
                        ),
                    )
                }
            }
            Column(Modifier.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 12.dp)) {
                CardHeader(summary, isLoading, featured, onPauseAll, onResumeAll)
                if (featured != null) {
                    FeaturedTask(
                        item = featured,
                        statusColor = statusColor,
                        onPause = onPause,
                        onResume = onResume,
                    )
                    val extra = summary.active.drop(1)
                    if (extra.isNotEmpty()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 10.dp),
                            color = colors.outlineVariant.copy(alpha = 0.5f),
                        )
                        extra.take(MAX_EXTRA_ACTIVE_TASKS).forEach { item ->
                            ActiveTaskRow(
                                item = item,
                                onPause = onPause,
                                onResume = onResume,
                            )
                        }
                        if (extra.size > MAX_EXTRA_ACTIVE_TASKS) {
                            Text(
                                text = stringResource(
                                    R.string.local_download_more,
                                    extra.size - MAX_EXTRA_ACTIVE_TASKS,
                                ),
                                modifier = Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary,
                            )
                        }
                    }
                } else if (summary.recent.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.local_download_recent),
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    summary.recent.take(MAX_RECENT_ITEMS).forEach { item ->
                        RecentTaskRow(item = item)
                    }
                }
                CardFooter(
                    summary = summary,
                    storage = storage,
                    showCounts = featured != null,
                )
            }
        }
    }
}

@Composable
private fun CardHeader(
    summary: LocalDownloadSummary,
    isLoading: Boolean,
    featured: DownloadItemModel?,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Same accent tile as PageSummaryHeader on the other top-level pages.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.local_download_manager),
                style = MaterialTheme.typography.titleSmall,
            )
            // When idle with history rows below, the history itself carries the context.
            if (featured == null && (isLoading || summary.recent.isEmpty())) {
                Text(
                    text = stringResource(
                        if (isLoading) R.string.local_download_loading else R.string.local_download_idle,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (summary.pendingCount > 0) {
            Surface(shape = MaterialTheme.shapes.small, color = colors.secondaryContainer) {
                Text(
                    text = summary.pendingCount.toString(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSecondaryContainer,
                )
            }
            if (summary.canPause || summary.canResume) {
                DownloadBatchMenu(summary, onPauseAll, onResumeAll)
            }
        }
        // Idle state needs no extra chevron: the whole card is clickable and the
        // footer's "Manage" row already carries the navigation affordance.
    }
}

@Composable
private fun FeaturedTask(
    item: DownloadItemModel,
    statusColor: Color,
    onPause: (DownloadItemModel) -> Unit,
    onResume: (DownloadItemModel) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val chapters by item.chapters.collectAsStateWithLifecycle()
    val totalChapters = chapters?.size ?: 0
    Text(
        text = item.displayManga?.title ?: item.executionManga?.title ?: stringResource(R.string.unknown),
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            val status = statusText(item)
            val progress = item.percent.coerceIn(0f, 1f)
            Text(
                text = if (!item.isIndeterminate && item.max > 0) {
                    stringResource(R.string.local_download_progress, status, (progress * 100).toInt())
                } else {
                    status
                },
                color = statusColor,
                style = MaterialTheme.typography.bodySmall,
            )
            val details = buildList {
                when {
                    item.isStuck -> add(stringResource(R.string.stuck))
                    item.hasEta -> item.getEtaString()?.toString()?.let { add(it) }
                }
                chaptersText(item, totalChapters)?.let { add(it) }
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (!item.error.isNullOrBlank()) {
                Text(
                    text = item.error.orEmpty(),
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.canPause || item.canResume) {
            TextButton(onClick = {
                if (item.canPause) onPause(item) else onResume(item)
            }) {
                if (item.canPause) {
                    Icon(painterResource(R.drawable.ic_pause), contentDescription = null)
                } else {
                    Icon(
                        if (item.workState == WorkInfo.State.FAILED) Icons.Default.Refresh
                        else Icons.Default.PlayArrow,
                        contentDescription = null,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(
                        when {
                            item.canPause -> R.string.pause
                            item.workState == WorkInfo.State.FAILED -> R.string.try_again
                            else -> R.string.resume
                        },
                    ),
                )
            }
        }
    }
    if (item.workState == WorkInfo.State.RUNNING) {
        if (item.isIndeterminate && !item.isPaused) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
        } else {
            LinearProgressIndicator(
                progress = { item.percent.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = if (item.isPaused) colors.outline else colors.primary,
                trackColor = colors.surfaceContainerHighest,
            )
        }
    }
}

@Composable
private fun ActiveTaskRow(
    item: DownloadItemModel,
    onPause: (DownloadItemModel) -> Unit,
    onResume: (DownloadItemModel) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dotColor = when {
        item.workState == WorkInfo.State.FAILED -> colors.error
        item.isPaused -> colors.outline
        item.workState == WorkInfo.State.RUNNING -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(dotColor, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.displayManga?.title ?: item.executionManga?.title ?: stringResource(R.string.unknown),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val status = statusText(item)
            val detail = if (!item.isIndeterminate && item.max > 0 &&
                item.workState.let { it == WorkInfo.State.RUNNING || it == WorkInfo.State.ENQUEUED }
            ) {
                stringResource(R.string.local_download_progress, status, (item.percent.coerceIn(0f, 1f) * 100).toInt())
            } else {
                status
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (item.workState == WorkInfo.State.FAILED) colors.error else colors.onSurfaceVariant,
            )
        }
        if (item.canPause || item.canResume) {
            IconButton(
                onClick = { if (item.canPause) onPause(item) else onResume(item) },
                modifier = Modifier.size(36.dp),
            ) {
                if (item.canPause) {
                    Icon(
                        painterResource(R.drawable.ic_pause),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                } else if (item.workState == WorkInfo.State.FAILED) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentTaskRow(item: DownloadItemModel) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = item.displayManga?.title ?: item.executionManga?.title ?: stringResource(R.string.unknown),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        val timeAgo = calculateTimeAgo(item.timestamp)?.format(context)
        Text(
            text = listOfNotNull(chaptersText(item, totalChapters = 0), timeAgo).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun CardFooter(
    summary: LocalDownloadSummary,
    storage: LocalDownloadStorageStats?,
    showCounts: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val leftText = if (showCounts) {
            buildList {
                if (summary.running > 0) add(stringResource(R.string.local_download_running, summary.running))
                if (summary.queued > 0) add(stringResource(R.string.local_download_queued, summary.queued))
                if (summary.paused > 0) add(stringResource(R.string.local_download_paused, summary.paused))
                if (summary.failed > 0) add(stringResource(R.string.local_download_failed, summary.failed))
            }.joinToString(" · ")
        } else if (storage != null) {
            buildList {
                add(
                    stringResource(
                        R.string.local_download_storage_used,
                        FileSize.BYTES.format(context, storage.usedBytes),
                    ),
                )
                storage.availableBytes?.let { available ->
                    add(
                        stringResource(
                            R.string.local_download_storage_free,
                            FileSize.BYTES.format(context, available),
                        ),
                    )
                }
            }.joinToString(" · ")
        } else {
            ""
        }
        Text(
            text = leftText,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = if (showCounts && summary.failed > 0) colors.error else colors.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.manage), style = MaterialTheme.typography.labelMedium)
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier.padding(start = 4.dp).size(16.dp),
        )
    }
}

// Only pending/failed tasks reach this helper; completions render as history rows.
@Composable
private fun statusText(item: DownloadItemModel): String = stringResource(
    when {
        item.workState == WorkInfo.State.FAILED -> R.string.error_occurred
        item.isPaused -> R.string.paused
        item.workState == WorkInfo.State.RUNNING -> item.taskKind.activeStatusResId
        else -> R.string.queued
    },
)

@Composable
private fun chaptersText(item: DownloadItemModel, totalChapters: Int): String? {
    val downloaded = item.chaptersDownloaded
    if (downloaded <= 0) return null
    return if (totalChapters > 0 && downloaded <= totalChapters) {
        stringResource(R.string.local_download_chapters_progress, downloaded, totalChapters)
    } else {
        stringResource(R.string.local_download_chapters_count, downloaded)
    }
}

@Composable
private fun DownloadBatchMenu(
    summary: LocalDownloadSummary,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.local_download_batch))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (summary.canPause) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.local_download_pause_all)) },
                    onClick = {
                        expanded = false
                        onPauseAll()
                    },
                )
            }
            if (summary.canResume) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.local_download_resume_all)) },
                    onClick = {
                        expanded = false
                        onResumeAll()
                    },
                )
            }
        }
    }
}
