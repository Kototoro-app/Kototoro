package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.backups.webdav.WebDavBackupFile
import org.skepsun.kototoro.core.ui.settings.SettingsActionRow
import org.skepsun.kototoro.core.ui.settings.SettingsInfoRow
import org.skepsun.kototoro.core.ui.settings.SettingsSliderRow
import org.skepsun.kototoro.core.ui.settings.SettingsSwitchRow
import java.text.DateFormat
import java.util.Date
import javax.swing.JFileChooser

/**
 * Android's WebDAV backup destination on the backups page: where the files live, a connection test, the library upload,
 * and the remote listing with restore and delete.
 *
 * Restoring a remote file goes through the same preview/merge flow as a local one — the panel only downloads; the
 * confirmation, the section counts and the rollback all stay in the shared part of the page.
 */
@Composable
internal fun DesktopWebDavSection(controller: DesktopController, ui: DesktopWebDavUi, enabled: Boolean) {
    val settings = ui.settings
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(desktopText("webdav.title"), style = MaterialTheme.typography.titleLarge)
            Text(desktopText("webdav.detail"), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = settings.serverUrl,
                onValueChange = { controller.webDavSettings(settings.copy(serverUrl = it.trim())) },
                label = { Text(desktopText("webdav.server")) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("webdav-server"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = settings.remotePath,
                    onValueChange = { controller.webDavSettings(settings.copy(remotePath = it.trim())) },
                    label = { Text(desktopText("webdav.path")) },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("webdav-path"),
                )
                OutlinedTextField(
                    value = settings.username,
                    onValueChange = { controller.webDavSettings(settings.copy(username = it)) },
                    label = { Text(desktopText("webdav.user")) },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("webdav-user"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = settings.password,
                    onValueChange = { controller.webDavSettings(settings.copy(password = it)) },
                    label = { Text(desktopText("webdav.password")) },
                    singleLine = true,
                    enabled = enabled,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f).testTag("webdav-password"),
                )
                // The retention count is what the shared catalog reads: files past it are retired after an upload.
                var keep by remember(settings.maxCount) { mutableStateOf(settings.maxCount.toString()) }
                OutlinedTextField(
                    value = keep,
                    onValueChange = {
                        val digits = it.filter(Char::isDigit).take(2)
                        keep = digits
                        controller.webDavSettings(settings.copy(maxCount = digits.toIntOrNull() ?: 0))
                    },
                    label = { Text(desktopText("webdav.keep")) },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.width(180.dp).testTag("webdav-keep"),
                )
                // `0` is the off switch: the page then works exactly like the manual-only version.
                var interval by remember(settings.intervalHours) { mutableStateOf(settings.intervalHours.toString()) }
                OutlinedTextField(
                    value = interval,
                    onValueChange = {
                        val digits = it.filter(Char::isDigit).take(3)
                        interval = digits
                        controller.webDavSettings(settings.copy(intervalHours = digits.toIntOrNull() ?: 0))
                    },
                    label = { Text(desktopText("webdav.interval")) },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.width(200.dp).testTag("webdav-interval"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ controller.webDavProbe() }, enabled = enabled && settings.configured,
                    modifier = Modifier.testTag("webdav-test")) { Text(desktopText("webdav.test")) }
                OutlinedButton({ controller.webDavRefresh() }, enabled = enabled && settings.configured,
                    modifier = Modifier.testTag("webdav-refresh")) { Text(desktopText("webdav.refresh")) }
                Button({ controller.webDavUpload() }, enabled = enabled && settings.configured,
                    modifier = Modifier.testTag("webdav-upload")) { Text(desktopText("webdav.upload")) }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            // Android's boot service has no Windows twin, so "on startup" is this check, run when the app opens and
            // again whenever the page saves the destination; it stays off until a complete destination is saved.
            SettingsSwitchRow(
                title = AndroidStrings["webdav_auto_restore"],
                summary = AndroidStrings["webdav_auto_restore_summary"],
                checked = settings.autoRestore,
                enabled = enabled && settings.configured,
                onCheckedChange = { controller.webDavSettings(settings.copy(autoRestore = it)) },
                testTag = "webdav-auto-restore",
            )
            // Android's periodical page keeps the local copy beside the upload, with its own retention count.
            SettingsSwitchRow(
                title = AndroidStrings["webdav_keep_local_copy"],
                summary = AndroidStrings["webdav_keep_local_copy_summary"],
                checked = settings.keepLocalCopy,
                enabled = enabled,
                onCheckedChange = { controller.webDavSettings(settings.copy(keepLocalCopy = it)) },
                testTag = "webdav-keep-local",
            )
            if (settings.keepLocalCopy) {
                SettingsSwitchRow(
                    title = AndroidStrings["delete_old_backups"],
                    summary = AndroidStrings["delete_old_backups_summary"],
                    checked = settings.trimLocal,
                    enabled = enabled,
                    onCheckedChange = { controller.webDavSettings(settings.copy(trimLocal = it)) },
                    testTag = "webdav-trim-local",
                )
                if (settings.trimLocal) {
                    SettingsSliderRow(
                        title = AndroidStrings["max_backups_count"],
                        summary = null,
                        value = settings.localCount,
                        valueRange = 1..32,
                        valueText = Int::toString,
                        enabled = enabled,
                        onValueChange = { controller.webDavSettings(settings.copy(localCount = it)) },
                        testTag = "webdav-local-count",
                    )
                }
                // Android's "backups output directory": the row shows where the copies land and can change it, which a
                // phone does through the SAF picker. An empty value is the data root's own `backups` folder.
                SettingsActionRow(
                    title = AndroidStrings["backups_output_directory"],
                    summary = ui.localDirectory?.toString().orEmpty(),
                    modifier = Modifier.testTag("webdav-local-open"),
                ) {
                    TextButton(onClick = {
                        val chooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = AndroidStrings["backups_output_directory"]
                            ui.localDirectory?.let { selectedFile = it.toFile() }
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            controller.webDavOutputDirectory(chooser.selectedFile.toPath())
                        }
                    }, enabled = enabled, modifier = Modifier.testTag("webdav-local-choose")) {
                        Text(desktopText("webdav.choose"))
                    }
                    if (settings.outputDirectory.isNotBlank()) {
                        TextButton(onClick = { controller.webDavOutputDirectory(null) }, enabled = enabled,
                            modifier = Modifier.testTag("webdav-local-reset")) {
                            Text(desktopText("webdav.outputReset"))
                        }
                    }
                    TextButton(onClick = { controller.webDavOpenLocalDirectory() }, enabled = enabled) {
                        Text(desktopText("webdav.open"))
                    }
                }
                SettingsInfoRow(
                    title = if (ui.lastLocalMillis > 0L) {
                        AndroidStrings.format(
                            "last_successful_backup",
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, DesktopStrings.locale)
                                .format(Date(ui.lastLocalMillis)),
                        )
                    } else {
                        desktopText("webdav.localNever")
                    },
                    summary = ui.localFiles.firstOrNull()?.fileName?.toString()?.let { newest ->
                        desktopText("webdav.localSummary", ui.localFiles.size, newest)
                    },
                    modifier = Modifier.testTag("webdav-local-status"),
                )
            }
            if (ui.reachable == true) Text(desktopText("webdav.reachable"), style = MaterialTheme.typography.bodyMedium)
            // Only shown for a timer that uploads: a local-only timer reports itself in the local row below.
            if (settings.autoBackupEnabled && settings.configured) {
                Text(
                    if (ui.lastUploadMillis > 0L) {
                        desktopText(
                            "webdav.autoLast",
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, DesktopStrings.locale)
                                .format(Date(ui.lastUploadMillis)),
                        )
                    } else {
                        desktopText("webdav.autoNever")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("webdav-auto-status"),
                )
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            when {
                !ui.listed -> Text(desktopText("webdav.unlisted"), style = MaterialTheme.typography.bodyMedium)
                ui.files.isEmpty() -> Text(desktopText("webdav.empty"), style = MaterialTheme.typography.bodyMedium)
                else -> {
                    ui.latest?.let {
                        Text(desktopText("webdav.latest", it.name), style = MaterialTheme.typography.bodyMedium)
                    }
                    ui.files.forEach { file -> DesktopWebDavRow(controller, file, enabled) }
                }
            }
        }
    }
}

@Composable
private fun DesktopWebDavRow(controller: DesktopController, file: WebDavBackupFile, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            desktopText(
                "webdav.file",
                file.name,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, DesktopStrings.locale)
                    .format(Date(file.lastModifiedMillis)),
                file.size / 1024,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).testTag("webdav-file:${file.name}"),
        )
        OutlinedButton({ controller.webDavRestore(file) }, enabled = enabled,
            modifier = Modifier.testTag("webdav-restore:${file.name}")) { Text(desktopText("webdav.restore")) }
        TextButton({ controller.webDavDelete(file) }, enabled = enabled,
            modifier = Modifier.testTag("webdav-delete:${file.name}")) { Text(desktopText("webdav.delete")) }
    }
}
