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
        Text(desktopText("backups.title"), style = MaterialTheme.typography.h6)
        Text(desktopText("backups.exportDetail"))
        Text(desktopText("backups.excluded"))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val chooser = JFileChooser().apply {
                    fileFilter = FileNameExtensionFilter(desktopText("backups.filterName"), "zip")
                    selectedFile = java.io.File("kototoro-library-${System.currentTimeMillis()}.zip")
                }
                if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    controller.exportLibraryBackup(chooser.selectedFile.toPath())
                }
            }, enabled = enabled, modifier = Modifier.testTag("backup-export")) { Text(desktopText("backups.export")) }
            OutlinedButton(onClick = {
                val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter(desktopText("backups.filterNameBoth"), "zip") }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    controller.previewLibraryBackup(chooser.selectedFile.toPath())
                }
            }, enabled = enabled, modifier = Modifier.testTag("backup-open")) { Text(desktopText("backups.choose")) }
        }
        preview?.let { archive ->
            Divider()
            Text(desktopText("backups.preview"), style = MaterialTheme.typography.h6, modifier = Modifier.testTag("backup-preview"))
            Text(desktopText("backups.file", archive.filename))
            Text(desktopText("backups.time", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
                DesktopStrings.locale).format(Date(archive.index.createdAt))))
            for ((section, count) in archive.counts) Text(desktopText("backups.sectionCount", label(section), count))
            if (archive.otherEntries.isNotEmpty()) {
                Text(desktopText("backups.otherEntries", archive.otherEntries.size))
                Text(archive.otherEntries.joinToString { label(BackupSection.of(it)) })
            }
            Text(desktopText("backups.mergeDetail"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ controller.restoreLibraryBackup(archive) }, enabled = enabled,
                    modifier = Modifier.testTag("backup-confirm")) { Text(desktopText("backups.merge")) }
                OutlinedButton({ controller.dismissLibraryBackup() }, enabled = enabled,
                    modifier = Modifier.testTag("backup-cancel")) { Text(desktopText("common.cancel")) }
            }
        }
    }
}

private fun label(section: BackupSection?): String = when (section) {
    BackupSection.CATEGORIES -> desktopText("backups.section.CATEGORIES")
    BackupSection.CONTENTS -> desktopText("backups.section.CONTENTS")
    BackupSection.FAVOURITES, BackupSection.WORK_FAVOURITES -> desktopText("backups.section.FAVOURITES")
    BackupSection.HISTORY, BackupSection.WORK_HISTORY -> desktopText("backups.section.HISTORY")
    BackupSection.SOURCES -> desktopText("backups.section.SOURCES")
    BackupSection.SETTINGS -> desktopText("backups.section.SETTINGS")
    BackupSection.SETTINGS_READER_GRID -> desktopText("backups.section.SETTINGS_READER_GRID")
    BackupSection.AUTH -> desktopText("backups.section.AUTH")
    BackupSection.BOOKMARKS -> desktopText("backups.section.BOOKMARKS")
    BackupSection.STATS, BackupSection.WORK_STATS -> desktopText("backups.section.STATS")
    BackupSection.SCROBBLING -> desktopText("backups.section.SCROBBLING")
    BackupSection.TRACKS, BackupSection.TRACK_LOGS -> desktopText("backups.section.TRACKS")
    BackupSection.EXTENSION_REPOS -> desktopText("backups.section.EXTENSION_REPOS")
    BackupSection.EXTENSION_GROUPS -> desktopText("backups.section.EXTENSION_GROUPS")
    BackupSection.SOURCE_ORIGINS -> desktopText("backups.section.SOURCE_ORIGINS")
    BackupSection.SAVED_FILTERS -> desktopText("backups.section.SAVED_FILTERS")
    BackupSection.ENTITY_GRAPH_ENTITIES, BackupSection.ENTITY_GRAPH_BINDINGS,
    BackupSection.ENTITY_GRAPH_RELATIONS, BackupSection.ENTITY_GRAPH_PREFS -> desktopText("backups.section.LEGACY")
    else -> desktopText("backups.section.OTHER")
}
