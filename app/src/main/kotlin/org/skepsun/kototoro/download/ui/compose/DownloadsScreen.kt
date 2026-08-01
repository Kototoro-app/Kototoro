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
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.core.ui.compose.CompactContentCoverShape
import org.skepsun.kototoro.core.ui.compose.performSelectionHapticFeedback
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.util.ext.getThemeColor
import org.skepsun.kototoro.core.util.ext.takeIfUsableImageUri
import org.skepsun.kototoro.download.ui.list.DownloadItemModel
import coil3.compose.AsyncImage
import org.skepsun.kototoro.download.ui.list.DownloadsViewModel
import org.skepsun.kototoro.download.ui.list.chapters.DownloadChapter
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.main.ui.MainActivity
import org.skepsun.kototoro.details.ui.model.DetailsOrigin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDownloadsRoute(
    appRouter: AppRouter,
    contentPadding: PaddingValues,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsStateWithLifecycle(emptyList())
    val hasActiveWorks by viewModel.hasActiveWorks.collectAsStateWithLifecycle(false)
    val hasPausedWorks by viewModel.hasPausedWorks.collectAsStateWithLifecycle(false)
    val hasCancellableWorks by viewModel.hasCancellableWorks.collectAsStateWithLifecycle(false)

    var selectionIds by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }
    val inSelectionMode = selectionIds.isNotEmpty()
    val hapticFeedback = LocalHapticFeedback.current
    val rootView = LocalView.current
    val mainActivity = LocalContext.current as? MainActivity

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (inSelectionMode) {
                val snapshot = viewModel.snapshot(androidx.collection.MutableLongSet().apply {
                    selectionIds.forEach { add(it) }
                })
                var canPause = true
                var canResume = true
                var canCancel = true
                var canRemove = true
                for (item in snapshot) {
                    canPause = canPause and item.canPause
                    canResume = canResume and item.canResume
                    canCancel = canCancel and !item.workState.isFinished
                    canRemove = canRemove and item.workState.isFinished
                }

                TopAppBar(
                    title = { Text(text = selectionIds.size.toString()) },
                    navigationIcon = {
                        IconButton(onClick = { selectionIds = emptySet() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            hapticFeedback.performSelectionHapticFeedback()
                            selectionIds = viewModel.allIds()
                        }) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Select All")
                        }
                        if (canPause) {
                            IconButton(onClick = {
                                viewModel.pause(selectionIds)
                                selectionIds = emptySet()
                            }) {
                                Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause")
                            }
                        }
                        if (canResume) {
                            IconButton(onClick = {
                                viewModel.resume(selectionIds)
                                selectionIds = emptySet()
                            }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                            }
                        }
                        if (canCancel) {
                            IconButton(onClick = {
                                viewModel.cancel(selectionIds)
                                selectionIds = emptySet()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel")
                            }
                        }
                        if (canRemove) {
                            IconButton(onClick = {
                                viewModel.remove(selectionIds)
                                selectionIds = emptySet()
                            }) {
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
                        IconButton(onClick = { (context as? android.app.Activity)?.finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        var showMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            if (hasCancellableWorks) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(android.R.string.cancel)) },
                                    onClick = {
                                        viewModel.cancelAll()
                                        showMenu = false
                                    }
                                )
                            }
                            if (hasActiveWorks) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pause)) },
                                    onClick = {
                                        viewModel.pauseAll()
                                        showMenu = false
                                    }
                                )
                            }
                            if (hasPausedWorks) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.resume)) },
                                    onClick = {
                                        viewModel.resumeAll()
                                        showMenu = false
                                    }
                                )
                            }
                            DropdownMenuItem(
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
                                        mainActivity?.resolveDetailsOriginForContent(item.displayManga) { origin ->
                                            when (origin) {
                                                is DetailsOrigin.EntityGraph -> {
                                                    appRouter.openEntityDetails(
                                                        entityId = origin.entityId,
                                                        initialProjectionLocalMangaId = origin.initialProjectionLocalMangaId,
                                                    )
                                                }
                                                else -> appRouter.openResolvedDetails(item.displayManga, rootView)
                                            }
                                        } ?: appRouter.openResolvedDetails(item.displayManga, rootView)
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
                            onSkipClick = { }, // need to handle from Worker? We leave it for now
                            onSkipAllClick = { } 
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadItemRow(
    item: DownloadItemModel,
    isSelected: Boolean,
    onItemClick: () -> Unit,
    onItemLongClick: () -> Unit,
    onExpandClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onCancelClick: () -> Unit,
    onSkipClick: () -> Unit,
    onSkipAllClick: () -> Unit
) {
    val context = LocalContext.current
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .combinedClickable(
                onClick = onItemClick,
                onLongClick = onItemLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Row(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)) {
                AsyncImage(
                    model = item.displayManga?.coverUrl?.takeIfUsableImageUri(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CompactContentCoverShape)
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.displayManga?.title ?: item.executionManga?.title ?: stringResource(R.string.unknown),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        
                        val chapters = item.chapters.collectAsStateWithLifecycle().value
                        if (!chapters.isNullOrEmpty()) {
                            IconButton(onClick = onExpandClick, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = if (item.isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(if (item.isExpanded) R.string.collapse else R.string.expand)
                                )
                            }
                        }
                    }
                    
                    val statusText = when (item.workState) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> stringResource(R.string.queued)
                        WorkInfo.State.RUNNING -> stringResource(if (item.isPaused) R.string.paused else item.taskKind.activeStatusResId)
                        WorkInfo.State.SUCCEEDED -> stringResource(item.taskKind.completedStatusResId)
                        WorkInfo.State.FAILED -> stringResource(R.string.error_occurred)
                        WorkInfo.State.CANCELLED -> stringResource(R.string.canceled)
                    }
                    
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (item.workState == WorkInfo.State.RUNNING) {
                        val detailsText = when {
                            item.isPaused -> item.getErrorMessage(context)?.toString() ?: ""
                            item.isStuck -> stringResource(R.string.stuck)
                            else -> item.getEtaString()?.toString() ?: ""
                        }
                        if (detailsText.isNotEmpty()) {
                            Text(
                                text = detailsText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.error != null) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else if (item.workState == WorkInfo.State.SUCCEEDED && item.chaptersDownloaded > 0) {
                        Text(
                            text = "${item.chaptersDownloaded} chapters", // Quick fallback
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (item.workState == WorkInfo.State.FAILED) {
                        Text(
                            text = item.getErrorMessage(context)?.toString() ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Red
                        )
                    }
                }
                
                if (item.workState == WorkInfo.State.RUNNING && !item.isIndeterminate) {
                    Text(
                        text = "${(item.percent * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Bottom)
                    )
                }
            }
            
            if (item.workState == WorkInfo.State.RUNNING) {
                if (item.isIndeterminate) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp))
                } else {
                    LinearProgressIndicator(
                        progress = { item.percent },
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp),
                    )
                }
            }
            
            val chapters = item.chapters.collectAsStateWithLifecycle().value
            if (item.isExpanded && !chapters.isNullOrEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .heightIn(max = 240.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                ) {
                    items(chapters) { chapter ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (chapter.number != null) {
                                Text(
                                    text = chapter.number,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(32.dp)
                                )
                            }
                            Text(
                                text = chapter.name,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            if (chapter.isDownloaded) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (item.workState == WorkInfo.State.ENQUEUED || item.workState == WorkInfo.State.BLOCKED) {
                    OutlinedButton(onClick = onCancelClick, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(android.R.string.cancel)) }
                } else if (item.workState == WorkInfo.State.RUNNING) {
                    if (item.canPause) OutlinedButton(onClick = onPauseClick, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.pause)) }
                    if (item.isPaused) OutlinedButton(onClick = onResumeClick, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(if (item.error == null) R.string.resume else R.string.retry)) }
                    OutlinedButton(onClick = onCancelClick, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(android.R.string.cancel)) }
                } else if (item.workState == WorkInfo.State.FAILED) {
                    OutlinedButton(onClick = onResumeClick, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}
