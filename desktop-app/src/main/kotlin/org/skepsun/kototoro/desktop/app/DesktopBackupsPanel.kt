package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.backups.domain.BackupSection
import java.util.Date
import java.text.DateFormat
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
internal fun DesktopBackupsPanel(controller: DesktopController, closing: Boolean) {
    val state by controller.state.collectAsState()
    val preview by controller.backupPreview.collectAsState()
    val enabled = !state.busy && !closing
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("图书馆备份", style = MaterialTheme.typography.h6)
        Text("导出收藏分类、收藏、阅读历史、书签、阅读统计、作品信息与来源排序，使用 Android 可读取的 Kototoro ZIP 格式。")
        Text("扩展程序、下载图片、来源设置和阅读设置不包含在此备份中。")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val chooser = JFileChooser().apply {
                    fileFilter = FileNameExtensionFilter("Kototoro 备份 ZIP", "zip")
                    selectedFile = java.io.File("kototoro-library-${System.currentTimeMillis()}.zip")
                }
                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    controller.exportLibraryBackup(chooser.selectedFile.toPath())
                }
            }, enabled = enabled, modifier = Modifier.testTag("backup-export")) { Text("导出备份") }
            OutlinedButton(onClick = {
                val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("Kototoro / Kotatsu 备份 ZIP", "zip") }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    controller.previewLibraryBackup(chooser.selectedFile.toPath())
                }
            }, enabled = enabled, modifier = Modifier.testTag("backup-open")) { Text("选择恢复文件") }
        }
        preview?.let { archive ->
            Divider()
            Text("恢复预览", style = MaterialTheme.typography.h6, modifier = Modifier.testTag("backup-preview"))
            Text("恢复文件：${archive.filename}")
            Text("备份时间：${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.CHINA)
                .format(Date(archive.index.createdAt))}")
            for ((section, count) in archive.counts) Text("${label(section)}：$count 条")
            if (archive.otherEntries.isNotEmpty()) {
                Text("文件另含 ${archive.otherEntries.size} 项其他数据，本次不会恢复：" +
                    archive.otherEntries.joinToString { label(BackupSection.of(it)) })
            }
            Text("确认后合并到当前数据；保留现有分类和更新的收藏、历史、书签。相同阅读会话的统计取较大值，重复恢复不会累加。编号冲突或数据错误时，整次恢复回滚。")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ controller.restoreLibraryBackup(archive) }, enabled = enabled,
                    modifier = Modifier.testTag("backup-confirm")) { Text("确认合并恢复") }
                OutlinedButton({ controller.dismissLibraryBackup() }, enabled = enabled,
                    modifier = Modifier.testTag("backup-cancel")) { Text("取消") }
            }
        }
    }
}

private fun label(section: BackupSection?): String = when (section) {
    BackupSection.CATEGORIES -> "收藏分类"
    BackupSection.CONTENTS -> "作品信息"
    BackupSection.FAVOURITES, BackupSection.WORK_FAVOURITES -> "收藏"
    BackupSection.HISTORY, BackupSection.WORK_HISTORY -> "阅读历史"
    BackupSection.SOURCES -> "来源排序"
    BackupSection.SETTINGS -> "应用设置"
    BackupSection.SETTINGS_READER_GRID -> "阅读点击区域"
    BackupSection.AUTH -> "账号与授权"
    BackupSection.BOOKMARKS -> "书签"
    BackupSection.STATS, BackupSection.WORK_STATS -> "阅读统计"
    BackupSection.SCROBBLING -> "追踪服务"
    BackupSection.TRACKS, BackupSection.TRACK_LOGS -> "更新追踪"
    BackupSection.EXTENSION_REPOS -> "扩展仓库"
    BackupSection.SOURCE_ORIGINS -> "来源记录"
    BackupSection.SAVED_FILTERS -> "筛选预设"
    BackupSection.ENTITY_GRAPH_ENTITIES, BackupSection.ENTITY_GRAPH_BINDINGS,
    BackupSection.ENTITY_GRAPH_RELATIONS, BackupSection.ENTITY_GRAPH_PREFS -> "旧版实体数据"
    else -> "其他数据"
}
