package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.skepsun.kototoro.desktop.runtime.DesktopCrashLogFile
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
            Spacer(Modifier.width(12.dp))
            Text(AndroidStrings["crash_logs"], style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            OutlinedButton({ controller.clearCrashLogs() }, enabled = active && !ui.files.isNullOrEmpty(),
                modifier = Modifier.testTag("crash-logs-clear")) { Text(AndroidStrings["clear"]) }
        }
        Text(AndroidStrings["crash_logs_summary"], style = MaterialTheme.typography.bodySmall, color = Muted,
            modifier = Modifier.testTag("crash-logs-summary"))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val files = ui.files
        when {
            // Null means the directory has not been read yet: an empty list is a real answer, "not asked" is not.
            files == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(desktopText("crashLogs.loading"), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("crash-logs-loading"))
            }

            files.isEmpty() -> Text(desktopText("crashLogs.empty"), style = MaterialTheme.typography.bodySmall,
                color = Muted, modifier = Modifier.testTag("crash-logs-empty"))

            else -> LazyColumn(Modifier.fillMaxWidth().testTag("crash-logs-list")) {
                items(files, key = { it.name }) { log ->
                    CrashLogRow(log, enabled = active, onClick = { controller.openCrashLog(log) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    ui.detail?.let { detail -> DesktopCrashLogDetailDialog(detail, controller) }
}

@Composable
private fun CrashLogRow(log: DesktopCrashLogFile, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("crash-log:${log.name}")
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(crashLogTime(log.modifiedAt), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                Text(desktopCacheSize(log.sizeBytes), fontSize = 11.sp, color = Muted)
            }
            Text(log.summary, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The whole report, selectable and copyable; Android's detail screen with its copy and share actions. */
@Composable
private fun DesktopCrashLogDetailDialog(detail: DesktopCrashLogDetail, controller: DesktopController) {
    AlertDialog(
        onDismissRequest = { controller.closeCrashLogDetail() },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(crashLogTime(detail.log.modifiedAt), style = MaterialTheme.typography.titleMedium)
                Text(detail.log.name, fontSize = 11.sp, color = Muted)
            }
        },
        text = {
            Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp,
                modifier = Modifier.width(680.dp)) {
                SelectionContainer {
                    Text(
                        detail.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                            .padding(12.dp).testTag("crash-log-text"),
                    )
                }
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
