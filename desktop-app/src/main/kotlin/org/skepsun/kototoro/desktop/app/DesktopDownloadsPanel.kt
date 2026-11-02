package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueAction
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueCard
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueItemUiModel
import org.skepsun.kototoro.core.ui.downloads.DownloadQueueStatus
import org.skepsun.kototoro.core.ui.downloads.downloadQueueBatchStateOf
import javax.swing.JFileChooser

@Composable
internal fun DesktopDownloadsPanel(controller: DesktopController, closing: Boolean) {
    val downloads by controller.downloads.state.collectAsState()
    val selection by controller.downloadSelection.collectAsState()
    val state by controller.state.collectAsState()
    val preview by controller.cleanupPreview.collectAsState()
    val usage = remember(downloads) { desktopDownloadUsage(downloads.map { it.record }) }
    val autoCleanup by controller.autoCleanupDownloads.collectAsState()
    // One queue row per chapter, shaped the way Android draws it; the batch bar reads the same rows.
    val rows = remember(downloads, selection) { downloads.map { it to it.toQueueItem(selection) } }
    val batch = remember(rows) { downloadQueueBatchStateOf(rows.map { it.second }.filter { it.isSelected }) }
    val completedCount = downloads.count { it.status == DesktopDownloadStatus.COMPLETE }
    // A finished chapter publishes blobs after the page was opened; re-read the cache figures once the queue settles.
    val activeCount = downloads.count { it.status.isActive }
    LaunchedEffect(activeCount) { if (activeCount == 0) controller.refreshImageCache() }
    preview?.let { plan ->
        AlertDialog(onDismissRequest = controller::dismissDownloadCleanup,
            modifier = Modifier.testTag("download-cleanup-dialog"),
            title = { Text(desktopText("downloads.cleanupTitle")) },
            text = {
                Text(desktopText("downloads.cleanupText", plan.metadataBytes / 1024, plan.candidates.size,
                    plan.reclaimableBytes / 1024))
            },
            confirmButton = {
                TextButton({ controller.cleanupDownloads(plan) }, enabled = !closing && !state.busy &&
                    plan.candidates.isNotEmpty(), modifier = Modifier.testTag("download-cleanup-confirm")) { Text(desktopText("downloads.cleanupConfirm")) }
            },
            dismissButton = {
                TextButton(controller::dismissDownloadCleanup, modifier = Modifier.testTag("download-cleanup-cancel")) {
                    Text(desktopText("common.cancel"))
                }
            })
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ controller.previewDownloadCleanup() }, enabled = !closing && !state.busy,
                modifier = Modifier.testTag("download-storage")) { Text(desktopText("downloads.storage")) }
            Text(desktopText("downloads.usage", usage.chapters, usage.completeChapters, usage.pages,
                usage.bytes / 1024), modifier = Modifier.testTag("download-usage"))
            Checkbox(checked = autoCleanup, onCheckedChange = controller::setAutoDownloadCleanup, enabled = !closing,
                modifier = Modifier.testTag("download-auto-cleanup"))
            Text(desktopText("downloads.autoCleanup"))
        }
        DesktopImageCacheRow(controller, state.imageCache, !closing && !state.busy)
        if (downloads.isEmpty()) Text(desktopText("downloads.empty"))
        if (downloads.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ controller.selectAllDownloads() }, enabled = !closing,
                    modifier = Modifier.testTag("download-select-all")) { Text(desktopText("downloads.selectAll")) }
                TextButton(controller::clearDownloadSelection, enabled = !closing && selection.isNotEmpty(),
                    modifier = Modifier.testTag("download-select-none")) { Text(desktopText("downloads.selectNone")) }
                Text(desktopText("downloads.selected", selection.size),
                    modifier = Modifier.testTag("download-selected-count"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(controller::pauseSelectedDownloads, enabled = !closing && batch.pausable > 0,
                    modifier = Modifier.testTag("download-batch-pause")) {
                    Text(desktopText("downloads.batchPause", batch.pausable))
                }
                OutlinedButton(controller::resumeSelectedDownloads, enabled = !closing && !state.busy &&
                    batch.resumable > 0, modifier = Modifier.testTag("download-batch-resume")) {
                    Text(desktopText("downloads.batchResume", batch.resumable))
                }
                OutlinedButton(controller::retrySelectedDownloads, enabled = !closing && !state.busy &&
                    batch.retryable > 0, modifier = Modifier.testTag("download-batch-retry")) {
                    Text(desktopText("downloads.batchRetry", batch.retryable))
                }
                OutlinedButton(controller::removeSelectedDownloads, enabled = !closing && batch.removable > 0,
                    modifier = Modifier.testTag("download-batch-remove")) {
                    Text(desktopText("downloads.batchRemove", batch.removable))
                }
                OutlinedButton({
                    val chooser = JFileChooser().apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = desktopText("downloads.exportAll")
                    }
                    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                        controller.exportAllDownloads(chooser.selectedFile.toPath())
                    }
                }, enabled = !closing && !state.busy && completedCount > 0,
                    modifier = Modifier.testTag("download-export-all")) {
                    Text(desktopText("downloads.exportAll"))
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rows, key = { it.first.key }) { (item, row) ->
                val record = item.record
                val active = item.status.isActive
                DownloadQueueCard(
                    item = row,
                    actions = downloadRowActions(controller, item, closing, state.busy),
                    leading = {
                        Checkbox(checked = row.isSelected, onCheckedChange = { controller.toggleDownloadSelection(item.key) },
                            enabled = !closing, modifier = Modifier.testTag("download-select:${item.key}"))
                    },
                )
            }
        }
    }
}

/** The row's buttons: what the desktop queue can do with this chapter right now, labelled by the desktop catalog. */
private fun downloadRowActions(
    controller: DesktopController,
    item: DesktopDownloadState,
    closing: Boolean,
    busy: Boolean,
): List<DownloadQueueAction> {
    val key = item.key
    val actions = mutableListOf<DownloadQueueAction>()
    if (item.status.isActive) {
        actions += DownloadQueueAction(desktopText("downloads.pause"), { controller.downloads.pause(key) },
            enabled = !closing, tag = "download-pause:$key")
    } else {
        actions += DownloadQueueAction(
            if (item.status == DesktopDownloadStatus.COMPLETE) desktopText("downloads.verify") else desktopText("downloads.resume"),
            { controller.resumeDownload(item.record) },
            enabled = !closing && !busy, tag = "download-resume:$key",
        )
    }
    actions += DownloadQueueAction(desktopText("downloads.readOffline"), { controller.readDownload(item.record) },
        enabled = !closing && !busy && item.status == DesktopDownloadStatus.COMPLETE,
        filled = true, tag = "download-read:$key")
    if (item.status == DesktopDownloadStatus.COMPLETE) {
        actions += DownloadQueueAction(desktopText("downloads.export"), {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = desktopText("downloads.export")
            }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                controller.exportDownload(item.record, chooser.selectedFile.toPath())
            }
        }, enabled = !closing && !busy, tag = "download-export:$key")
    }
    return actions
}

/**
 * The queue row as the shared card draws it. Identity, page progress and the flags a batch action reads live in the
 * queue, so the row only carries text: the desktop catalog has the labels, the queue has the facts.
 */
private fun DesktopDownloadState.toQueueItem(selection: Set<String>): DownloadQueueItemUiModel {
    val record = this.record
    val chapterTitle = record.chapter.title ?: desktopText("common.chapterTitle", record.chapter.number)
    val subtitle = listOfNotNull(chapterTitle, record.chapter.branch).joinToString(" · ")
    val pages = record.pages.size
    return DownloadQueueItemUiModel(
        key = key,
        title = record.contentTitle,
        subtitle = subtitle,
        status = when (status) {
            DesktopDownloadStatus.QUEUED -> DownloadQueueStatus.QUEUED
            DesktopDownloadStatus.DOWNLOADING -> DownloadQueueStatus.DOWNLOADING
            DesktopDownloadStatus.PAUSED -> DownloadQueueStatus.PAUSED
            DesktopDownloadStatus.COMPLETE -> DownloadQueueStatus.COMPLETE
            DesktopDownloadStatus.FAILED -> DownloadQueueStatus.FAILED
        },
        statusText = when (status) {
            DesktopDownloadStatus.QUEUED -> desktopText("downloads.status.QUEUED")
            DesktopDownloadStatus.DOWNLOADING -> desktopText("downloads.status.DOWNLOADING")
            DesktopDownloadStatus.PAUSED -> desktopText("downloads.status.PAUSED")
            DesktopDownloadStatus.COMPLETE -> desktopText("downloads.status.COMPLETE")
            DesktopDownloadStatus.FAILED -> desktopText("downloads.status.FAILED")
        },
        infoText = desktopText("downloads.pages", record.completedPages, pages),
        detailText = error,
        detailIsError = error != null,
        percent = if (status == DesktopDownloadStatus.DOWNLOADING && pages > 0) {
            record.completedPages.toFloat() / pages
        } else {
            null
        },
        isSelected = key in selection,
        canPause = status.isActive,
        canResume = status == DesktopDownloadStatus.PAUSED,
        // The desktop queue removes a chapter instead of cancelling it, so only a settled row is removable.
        canCancel = false,
        canRemove = !status.isActive,
    )
}

/**
 * Android's storage page condenses its image cache settings into one row: what the directory holds, how much of it
 * saved chapters keep, the current limit and a manual sweep. The limit is enforced by the periodic loop, but choosing
 * a value applies it at once, because the user is watching this number.
 */
@Composable
private fun DesktopImageCacheRow(controller: DesktopController, cache: DesktopImageCacheReport, enabled: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    val budget = controller.imageBudgetMb()
    val label = desktopImageBudgetLabel(budget)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(desktopText("downloads.imageCache"), modifier = Modifier.testTag("image-cache-title"))
        Text(desktopText("downloads.imageCacheUsage", desktopCacheSize(cache.usedBytes),
            desktopCacheSize(cache.protectedBytes), label), modifier = Modifier.testTag("image-cache-usage"))
        Box {
            OutlinedButton({ menuOpen = true }, enabled = enabled,
                modifier = Modifier.testTag("image-cache-budget")) {
                Text(desktopText("downloads.imageCacheBudget", label))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (choice in DesktopImageBudget.choicesMb) {
                    DropdownMenuItem(onClick = {
                        menuOpen = false
                        controller.setImageBudget(choice)
                    }, modifier = Modifier.testTag("image-cache-budget:$choice"),
                        text = { Text(desktopImageBudgetLabel(choice)) })
                }
            }
        }
        TextButton({ controller.clearImageCache() }, enabled = enabled,
            modifier = Modifier.testTag("image-cache-clear")) { Text(desktopText("downloads.imageCacheClear")) }
    }
}

private fun desktopImageBudgetLabel(megabytes: Int): String =
    if (megabytes == DesktopImageBudget.UNLIMITED_MB) desktopText("downloads.imageCacheUnlimited")
    else desktopCacheSize(megabytes.toLong() * 1024 * 1024)
