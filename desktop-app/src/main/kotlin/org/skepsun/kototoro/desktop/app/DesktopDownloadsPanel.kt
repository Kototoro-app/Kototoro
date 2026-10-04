package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopDownloadsPanel(controller: DesktopController, closing: Boolean) {
    val downloads by controller.downloads.state.collectAsState()
    val state by controller.state.collectAsState()
    val preview by controller.cleanupPreview.collectAsState()
    preview?.let { plan ->
        AlertDialog(onDismissRequest = controller::dismissDownloadCleanup,
            modifier = Modifier.testTag("download-cleanup-dialog"),
            title = { Text("回收旧下载记录") },
            text = { Text("下载记录占用 ${plan.metadataBytes / 1024} KB。可回收 ${plan.candidates.size} 条旧记录，" +
                "约 ${plan.reclaimableBytes / 1024} KB。仅回收未引用的元数据块，章节和图片保留。") },
            confirmButton = {
                TextButton({ controller.cleanupDownloads(plan) }, enabled = !closing && !state.busy &&
                    plan.candidates.isNotEmpty(), modifier = Modifier.testTag("download-cleanup-confirm")) { Text("确认回收") }
            },
            dismissButton = {
                TextButton(controller::dismissDownloadCleanup, modifier = Modifier.testTag("download-cleanup-cancel")) {
                    Text("取消")
                }
            })
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton({ controller.previewDownloadCleanup() }, enabled = !closing && !state.busy,
            modifier = Modifier.testTag("download-storage")) { Text("下载占用 / 回收旧记录") }
        if (downloads.isEmpty()) Text("在作品详情中选择章节下载，可保存整章供离线阅读。")
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(downloads, key = { it.key }) { item ->
                val record = item.record
                val active = item.status in setOf(DesktopDownloadStatus.QUEUED, DesktopDownloadStatus.DOWNLOADING)
                Card(Modifier.fillMaxWidth().testTag("download:${item.key}"), elevation = 0.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(record.contentTitle)
                        Text(record.chapter.title ?: "第 ${record.chapter.number} 章")
                        record.chapter.branch?.let { Text(it) }
                        Text(when (item.status) {
                            DesktopDownloadStatus.QUEUED -> "等待下载"
                            DesktopDownloadStatus.DOWNLOADING -> "正在下载"
                            DesktopDownloadStatus.PAUSED -> "已暂停"
                            DesktopDownloadStatus.COMPLETE -> "已下载"
                            DesktopDownloadStatus.FAILED -> "下载失败"
                        }, modifier = Modifier.testTag("download-status:${item.key}"))
                        Text("${record.completedPages} / ${record.pages.size} 页")
                        item.error?.let { Text(it, color = MaterialTheme.colors.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (active) OutlinedButton({ controller.downloads.pause(item.key) }, enabled = !closing,
                                modifier = Modifier.testTag("download-pause:${item.key}")) { Text("暂停") }
                            else OutlinedButton({ controller.resumeDownload(record) }, enabled = !closing && !state.busy,
                                modifier = Modifier.testTag("download-resume:${item.key}")) {
                                Text(if (item.status == DesktopDownloadStatus.COMPLETE) "校验 / 继续" else "继续下载")
                            }
                            Button({ controller.readDownload(record) }, enabled = !closing && !state.busy &&
                                item.status == DesktopDownloadStatus.COMPLETE,
                                modifier = Modifier.testTag("download-read:${item.key}")) { Text("离线阅读") }
                        }
                    }
                }
            }
        }
    }
}
