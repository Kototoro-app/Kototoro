package org.skepsun.kototoro.download.ui.compose

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.adaptive.tvIconButtonRing
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.core.ui.compose.CompactContentCoverShape
import org.skepsun.kototoro.core.ui.compose.TvDropdownMenuItem
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueAction
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueBatchItem
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueCard
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueChapterUiModel
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueItemUiModel
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueStatus
import org.skepsun.kototoro.core.ui.downloads.downloadQueueBatchState
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.util.ext.getThemeColor
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.download.ui.list.DownloadItemModel
import org.skepsun.kototoro.download.ui.list.formatDownloadSize
import coil3.compose.AsyncImage
import org.skepsun.kototoro.download.ui.list.DownloadsViewModel
import org.skepsun.kototoro.download.ui.list.chapters.DownloadChapter
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDownloadsRoute(
    appRouter: AppRouter,
    contentPadding: PaddingValues,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle(emptyList())
    val hasActiveWorks by viewModel.hasActiveWorks.collectAsStateWithLifecycle(false)
    val hasPausedWorks by viewModel.hasPausedWorks.collectAsStateWithLifecycle(false)
    val hasCancellableWorks by viewModel.hasCancellableWorks.collectAsStateWithLifecycle(false)

    var selectionIds by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }
    val inSelectionMode = selectionIds.isNotEmpty()
    val hapticFeedback = LocalHapticFeedback.current
    val rootView = LocalView.current

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    androidx.activity.compose.BackHandler(enabled = inSelectionMode) {
        selectionIds = emptySet()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (inSelectionMode) {
                val batch = downloadQueueBatchState(
                    viewModel.snapshot(
                        androidx.collection.MutableLongSet().apply { selectionIds.forEach { add(it) } },
                    ).map { it.toBatchItem() },
                )

                TopAppBar(
                    title = { Text(text = selectionIds.size.toString()) },
                    navigationIcon = {
                        IconButton(onClick = { selectionIds = emptySet() }, modifier = Modifier.tvIconButtonRing()) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                hapticFeedback.performSelectionHapticFeedback()
                                selectionIds = viewModel.allIds()
                            },
                            modifier = Modifier.tvIconButtonRing(),
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Select All")
                        }
                        if (batch.canPause) {
                            IconButton(
                                onClick = {
                                    viewModel.pause(selectionIds)
                                    selectionIds = emptySet()
                                },
                                modifier = Modifier.tvIconButtonRing(),
                            ) {
                                Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause")
                            }
                        }
                        if (batch.canResume) {
                            IconButton(
                                onClick = {
                                    viewModel.resume(selectionIds)
                                    selectionIds = emptySet()
                                },
                                modifier = Modifier.tvIconButtonRing(),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                            }
                        }
                        if (batch.canCancel) {
                            IconButton(
                                onClick = {
                                    viewModel.cancel(selectionIds)
                                    selectionIds = emptySet()
                                },
                                modifier = Modifier.tvIconButtonRing(),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel")
                            }
                        }
                        if (batch.canRemove) {
                            IconButton(
                                onClick = {
                                    viewModel.remove(selectionIds)
                                    selectionIds = emptySet()
                                },
                                modifier = Modifier.tvIconButtonRing(),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.downloads)) },
                    navigationIcon = {
                        val context = LocalContext.current
                        IconButton(
                            onClick = { (context as? android.app.Activity)?.finish() },
                            modifier = Modifier.tvIconButtonRing(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        var showMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.tvIconButtonRing()) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            if (hasCancellableWorks) {
                                TvDropdownMenuItem(
                                    text = { Text(stringResource(android.R.string.cancel)) },
                                    onClick = {
                                        viewModel.cancelAll()
                                        showMenu = false
                                    }
                                )
                            }
                            if (hasActiveWorks) {
                                TvDropdownMenuItem(
                                    text = { Text(stringResource(R.string.pause)) },
                                    onClick = {
                                        viewModel.pauseAll()
                                        showMenu = false
                                    }
                                )
                            }
                            if (hasPausedWorks) {
                                TvDropdownMenuItem(
                                    text = { Text(stringResource(R.string.resume)) },
                                    onClick = {
                                        viewModel.resumeAll()
                                        showMenu = false
                                    }
                                )
                            }
                            TvDropdownMenuItem(
                                text = { Text(stringResource(R.string.remove_completed)) },
                                onClick = {
                                    viewModel.removeCompleted()
                                    showMenu = false
                                }
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + contentPadding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                top = innerPadding.calculateTopPadding(),
                end = innerPadding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + contentPadding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                bottom = innerPadding.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(items, key = { index, item ->
                when (item) {
                    is DownloadItemModel -> "model_${item.id}"
                    is ListHeader -> "header_$index"
                    is EmptyState -> "empty"
                    LoadingState -> "loading"
                    else -> item.hashCode().toString()
                }
            }) { _, item ->
                when (item) {
                    is LoadingState -> {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            KototoroLoadingIndicator()
                        }
                    }
                    is EmptyState -> {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (item.icon != 0) {
                                    Icon(painterResource(item.icon), contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                                Text(
                                    text = if (item.textPrimary != 0) stringResource(item.textPrimary) else "",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                    is ListHeader -> {
                        val headerStr = item.getText(LocalContext.current)?.toString() ?: ""
                        Text(
                            text = headerStr,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    is DownloadItemModel -> {
                        val isSelected = selectionIds.contains(item.id.mostSignificantBits)
                        DownloadItemRow(
                            item = item,
                            isSelected = isSelected,
                            onItemClick = {
                                if (inSelectionMode) {
                                    hapticFeedback.performSelectionHapticFeedback()
                                    selectionIds = if (isSelected) selectionIds - item.id.mostSignificantBits else selectionIds + item.id.mostSignificantBits
                                } else {
                                    if (item.displayManga != null) {
                                        appRouter.openResolvedDetails(item.displayManga, rootView)
                                    }
                                }
                            },
                            onItemLongClick = {
                                selectionIds = if (isSelected) selectionIds - item.id.mostSignificantBits else selectionIds + item.id.mostSignificantBits
                            },
                            onExpandClick = { viewModel.expandCollapse(item) },
                            onPauseClick = { viewModel.pause(setOf(item.id.mostSignificantBits)) },
                            onResumeClick = { viewModel.resume(setOf(item.id.mostSignificantBits)) },
                            onCancelClick = { viewModel.cancel(setOf(item.id.mostSignificantBits)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadItemRow(
    item: DownloadItemModel,
    isSelected: Boolean,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    onExpandClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onCancelClick: () -> Unit,
) {
    val context = LocalContext.current
    val chapters = item.chapters.collectAsStateWithLifecycle().value.orEmpty()
    val detailText = when {
        item.workState == WorkInfo.State.RUNNING -> when {
            item.isPaused -> item.getErrorMessage(context)?.toString()
            item.isStuck -> stringResource(R.string.stuck)
            else -> item.getEtaString()?.toString()
        }
        item.workState == WorkInfo.State.SUCCEEDED && item.chaptersDownloaded > 0 ->
            uiString("chapters_count_info", item.chaptersDownloaded)
        item.workState == WorkInfo.State.FAILED -> item.getErrorMessage(context)?.toString()
        else -> null
    }
    val actions = mutableListOf<DownloadQueueAction>()
    when (item.workState) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> actions += DownloadQueueAction(
            label = stringResource(android.R.string.cancel),
            onClick = onCancelClick,
            tag = "download-cancel:${item.id}",
        )
        WorkInfo.State.RUNNING -> {
            if (item.canPause) actions += DownloadQueueAction(
                label = stringResource(R.string.pause),
                onClick = onPauseClick,
                tag = "download-pause:${item.id}",
            )
            if (item.isPaused) actions += DownloadQueueAction(
                label = stringResource(if (item.error == null) R.string.resume else R.string.retry),
                onClick = onResumeClick,
                tag = "download-resume:${item.id}",
            )
            actions += DownloadQueueAction(
                label = stringResource(android.R.string.cancel),
                onClick = onCancelClick,
                tag = "download-cancel:${item.id}",
            )
        }
        WorkInfo.State.FAILED -> actions += DownloadQueueAction(
            label = stringResource(R.string.retry),
            onClick = onResumeClick,
            tag = "download-resume:${item.id}",
        )
        else -> Unit
    }
    DownloadQueueCard(
        item = DownloadQueueItemUiModel(
            key = item.id.toString(),
            title = item.displayManga?.title ?: item.executionManga?.title ?: stringResource(R.string.unknown),
            subtitle = item.currentChapterTitle,
            status = item.workState.toQueueStatus(),
            statusText = stringResource(
                when (item.workState) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> R.string.queued
                    WorkInfo.State.RUNNING -> if (item.isPaused) R.string.paused else item.taskKind.activeStatusResId
                    WorkInfo.State.SUCCEEDED -> if (item.isPartial) R.string.download_partial else item.taskKind.completedStatusResId
                    WorkInfo.State.FAILED -> R.string.error_occurred
                    WorkInfo.State.CANCELLED -> R.string.canceled
                },
            ),
            infoText = formatDownloadSize(item.byteProgress),
            detailText = detailText,
            detailIsError = item.error != null,
            percent = if (item.workState == WorkInfo.State.RUNNING && !item.isIndeterminate) item.percent else null,
            isSelected = isSelected,
            isExpanded = item.isExpanded,
            chapters = chapters.map { DownloadQueueChapterUiModel(it.number, it.name, it.isDownloaded) },
            canPause = item.canPause,
            canResume = item.canResume,
            canCancel = !item.workState.isFinished,
            canRemove = item.workState.isFinished,
        ),
        modifier = Modifier.padding(horizontal = 16.dp),
        onClick = onItemClick,
        onLongClick = onItemLongClick,
        onToggleExpanded = onExpandClick,
        actions = actions,
        cover = {
            AsyncImage(
                model = item.displayManga?.coverUrl?.takeIfUsableImageUri(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

private fun WorkInfo.State.toQueueStatus(): DownloadQueueStatus = when (this) {
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadQueueStatus.QUEUED
    WorkInfo.State.RUNNING -> DownloadQueueStatus.DOWNLOADING
    WorkInfo.State.SUCCEEDED -> DownloadQueueStatus.COMPLETE
    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> DownloadQueueStatus.FAILED
}

/** The selection bar only shows an action when every selected row supports it, so it asks for the shared rule. */
private fun DownloadItemModel.toBatchItem(): DownloadQueueBatchItem = DownloadQueueBatchItem(
    status = workState.toQueueStatus(),
    canPause = canPause,
    canResume = canResume,
    canCancel = !workState.isFinished,
    canRemove = workState.isFinished,
)
