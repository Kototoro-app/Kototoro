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
import javax.swing.JFileChooser

@Composable
internal fun DesktopDownloadsPanel(controller: DesktopController, closing: Boolean) {
    val downloads by controller.downloads.state.collectAsState()
    val selection by controller.downloadSelection.collectAsState()
    val state by controller.state.collectAsState()
    val preview by controller.cleanupPreview.collectAsState()
    val usage = remember(downloads) { desktopDownloadUsage(downloads.map { it.record }) }
    val autoCleanup by controller.autoCleanupDownloads.collectAsState()
    val selected = remember(downloads, selection) {
        downloads.filter { it.key in selection }
    }
    val selectedActive = selected.count { it.status.isActive }
    val selectedPaused = selected.count { it.status == DesktopDownloadStatus.PAUSED }
    val selectedFailed = selected.count { it.status == DesktopDownloadStatus.FAILED }
    val selectedRemovable = selected.count { !it.status.isActive }
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
                OutlinedButton(controller::pauseSelectedDownloads, enabled = !closing && selectedActive > 0,
                    modifier = Modifier.testTag("download-batch-pause")) {
                    Text(desktopText("downloads.batchPause", selectedActive))
                }
                OutlinedButton(controller::resumeSelectedDownloads, enabled = !closing && !state.busy &&
                    selectedPaused > 0, modifier = Modifier.testTag("download-batch-resume")) {
                    Text(desktopText("downloads.batchResume", selectedPaused))
                }
                OutlinedButton(controller::retrySelectedDownloads, enabled = !closing && !state.busy &&
                    selectedFailed > 0, modifier = Modifier.testTag("download-batch-retry")) {
                    Text(desktopText("downloads.batchRetry", selectedFailed))
                }
                OutlinedButton(controller::removeSelectedDownloads, enabled = !closing && selectedRemovable > 0,
                    modifier = Modifier.testTag("download-batch-remove")) {
                    Text(desktopText("downloads.batchRemove", selectedRemovable))
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
            items(downloads, key = { it.key }) { item ->
                val record = item.record
                val active = item.status.isActive
                val checked = item.key in selection
                Card(Modifier.fillMaxWidth().testTag("download:${item.key}"),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Checkbox(checked = checked, onCheckedChange = { controller.toggleDownloadSelection(item.key) },
                            enabled = !closing, modifier = Modifier.testTag("download-select:${item.key}"))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(record.contentTitle)
                            Text(record.chapter.title ?: desktopText("common.chapterTitle", record.chapter.number))
                            record.chapter.branch?.let { Text(it) }
                            Text(when (item.status) {
                                DesktopDownloadStatus.QUEUED -> desktopText("downloads.status.QUEUED")
                                DesktopDownloadStatus.DOWNLOADING -> desktopText("downloads.status.DOWNLOADING")
                                DesktopDownloadStatus.PAUSED -> desktopText("downloads.status.PAUSED")
                                DesktopDownloadStatus.COMPLETE -> desktopText("downloads.status.COMPLETE")
                                DesktopDownloadStatus.FAILED -> desktopText("downloads.status.FAILED")
                            }, modifier = Modifier.testTag("download-status:${item.key}"))
                            Text(desktopText("downloads.pages", record.completedPages, record.pages.size))
                            item.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (active) OutlinedButton({ controller.downloads.pause(item.key) }, enabled = !closing,
                                    modifier = Modifier.testTag("download-pause:${item.key}")) { Text(desktopText("downloads.pause")) }
                                else OutlinedButton({ controller.resumeDownload(record) }, enabled = !closing && !state.busy,
                                    modifier = Modifier.testTag("download-resume:${item.key}")) {
                                    Text(if (item.status == DesktopDownloadStatus.COMPLETE) desktopText("downloads.verify") else desktopText("downloads.resume"))
                                }
                                Button({ controller.readDownload(record) }, enabled = !closing && !state.busy &&
                                    item.status == DesktopDownloadStatus.COMPLETE,
                                    modifier = Modifier.testTag("download-read:${item.key}")) { Text(desktopText("downloads.readOffline")) }
                                if (item.status == DesktopDownloadStatus.COMPLETE) {
                                    OutlinedButton({
                                        val chooser = JFileChooser().apply {
                                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                            dialogTitle = desktopText("downloads.export")
                                        }
                                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                                            controller.exportDownload(record, chooser.selectedFile.toPath())
                                        }
                                    }, enabled = !closing && !state.busy,
                                        modifier = Modifier.testTag("download-export:${item.key}")) {
                                        Text(desktopText("downloads.export"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
