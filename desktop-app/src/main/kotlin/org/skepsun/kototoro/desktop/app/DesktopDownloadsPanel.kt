package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopDownloadsPanel(controller: DesktopController, closing: Boolean) {
    val downloads by controller.downloads.state.collectAsState()
    val selection by controller.downloadSelection.collectAsState()
    val state by controller.state.collectAsState()
    val preview by controller.cleanupPreview.collectAsState()
    val usage = remember(downloads) { desktopDownloadUsage(downloads.map { it.record }) }
    val selected = remember(downloads, selection) {
        downloads.filter { it.key in selection }
    }
    val selectedActive = selected.count { it.status.isActive }
    val selectedPaused = selected.count { it.status == DesktopDownloadStatus.PAUSED }
    val selectedFailed = selected.count { it.status == DesktopDownloadStatus.FAILED }
    val selectedRemovable = selected.count { !it.status.isActive }
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
        }
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
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(downloads, key = { it.key }) { item ->
                val record = item.record
                val active = item.status.isActive
                val checked = item.key in selection
                Card(Modifier.fillMaxWidth().testTag("download:${item.key}"), elevation = 0.dp) {
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
                            item.error?.let { Text(it, color = MaterialTheme.colors.error) }
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
                            }
                        }
                    }
                }
            }
        }
    }
}
