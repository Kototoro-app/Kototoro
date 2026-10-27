package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.core.ui.crashlog.CrashLogEntry
import org.skepsun.kototoro.core.ui.crashlog.CrashLogReportContent
import org.skepsun.kototoro.core.ui.crashlog.CrashLogsContent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Android's crash-log screens for the Windows host: the reports written under `<data root>/logs/crash_logs`, newest
 * first, with the first exception line as their subtitle.
 *
 * Android needs a second screen for the text because a phone cannot show the list and the report together; here the
 * list is a page and the report opens in a dialog, which is the same two-step shape with one fewer route.
 */
@Composable
internal fun DesktopCrashLogsPage(controller: DesktopController, state: DesktopAppState) {
    val ui by controller.crashLogs.collectAsState()
    val active = !state.busy
    Column(Modifier.widthIn(max = 820.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ controller.backFromCrashLogs() }, enabled = active,
                modifier = Modifier.testTag("crash-logs-back")) { Text(desktopText("common.back")) }
            // No title here: the shell already names the page, as on the replace-rules and data-cleanup pages.
            Spacer(Modifier.weight(1f))
            OutlinedButton({ controller.clearCrashLogs() }, enabled = active && !ui.files.isNullOrEmpty(),
                modifier = Modifier.testTag("crash-logs-clear")) { Text(AndroidStrings["clear"]) }
        }
        Text(AndroidStrings["crash_logs_summary"], style = MaterialTheme.typography.bodySmall, color = Muted,
            modifier = Modifier.testTag("crash-logs-summary"))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val files = ui.files
        // Null means the directory has not been read yet: an empty list is a real answer, "not asked" is not.
        val entries = remember(files) {
            files.orEmpty().map { log ->
                CrashLogEntry(
                    id = log.name,
                    title = crashLogTime(log.modifiedAt),
                    summary = log.summary,
                    // Android's rows carry no size; the page has the room for it and the reports are few.
                    sizeLabel = desktopCacheSize(log.sizeBytes),
                )
            }
        }
        CrashLogsContent(
            entries = entries,
            loading = files == null,
            emptyText = desktopText("crashLogs.empty"),
            loadingText = desktopText("crashLogs.loading"),
            enabled = active,
            onOpen = { entry -> files?.firstOrNull { it.name == entry.id }?.let(controller::openCrashLog) },
            loadingTag = "crash-logs-loading",
            emptyTag = "crash-logs-empty",
        )
    }
    ui.detail?.let { detail -> DesktopCrashLogDetailDialog(detail, controller) }
}

/** The whole report, selectable and copyable; Android's detail screen with its copy and share actions. */
@Composable
private fun DesktopCrashLogDetailDialog(detail: DesktopCrashLogDetail, controller: DesktopController) {
    AlertDialog(
        onDismissRequest = { controller.closeCrashLogDetail() },
        // Material3's dialog caps itself at the platform width; a stack trace reads better with the extra room.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(760.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(crashLogTime(detail.log.modifiedAt), style = MaterialTheme.typography.titleMedium)
                Text(detail.log.name, fontSize = 11.sp, color = Muted)
            }
        },
        text = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("crash-log-report"),
            ) {
                CrashLogReportContent(detail.text, Modifier.padding(12.dp))
            }
        },
        confirmButton = {
            TextButton({ controller.copyCrashLog(detail.text) },
                modifier = Modifier.testTag("crash-log-copy")) { Text(AndroidStrings["copy"]) }
        },
        dismissButton = {
            Row {
                TextButton({ controller.deleteCrashLog(detail.log) },
                    modifier = Modifier.testTag("crash-log-delete")) { Text(AndroidStrings["delete"]) }
                TextButton({ controller.closeCrashLogDetail() },
                    modifier = Modifier.testTag("crash-log-close")) { Text(desktopText("common.close")) }
            }
        },
    )
}

private val crashLogTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

internal fun crashLogTime(modifiedAt: Long): String =
    crashLogTimeFormat.format(Instant.ofEpochMilli(modifiedAt).atZone(ZoneId.systemDefault()))
