package org.skepsun.kototoro.core.ui.crashlog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One report in the crash-log list: Android's `CrashLogItem` used a file, and a host reads its own directory, so the
 * shared list takes what both already have — a stable [id] for the row's tag, the timestamp [title] both hosts format
 * the same way, the [summary] line beside it ([crashLogSummaryLine] derives it from the report), and an optional
 * [sizeLabel] for hosts that also show how big the report is.
 */
data class CrashLogEntry(
    val id: String,
    val title: String,
    val summary: String,
    val sizeLabel: String? = null,
)

/**
 * Android's crash-log list body: one row per report, newest first, timestamp over the first exception line, a hairline
 * between rows. The host keeps the screen around it (Android's `Scaffold` top bar with back and clear, the desktop
 * page's header row) and the report itself opens wherever the platform can show it.
 *
 * [loading] distinguishes "the directory has not been read yet" from a real empty answer, which is why it is a flag
 * rather than an empty list.
 */
@Composable
fun CrashLogsContent(
    entries: List<CrashLogEntry>,
    loading: Boolean,
    emptyText: String,
    enabled: Boolean,
    onOpen: (CrashLogEntry) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "crash-log",
    loadingText: String = emptyText,
    emptyTag: String? = null,
    loadingTag: String? = null,
) {
    when {
        loading -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = loadingText,
                style = MaterialTheme.typography.bodySmall,
                modifier = if (loadingTag != null) Modifier.testTag(loadingTag) else Modifier,
            )
        }

        entries.isEmpty() -> Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.fillMaxWidth().let { if (emptyTag != null) it.testTag(emptyTag) else it },
        )

        else -> LazyColumn(modifier.fillMaxWidth()) {
            items(entries, key = { it.id }) { entry ->
                CrashLogRow(entry, enabled = enabled, onClick = { onOpen(entry) }, tag = "$tagPrefix:${entry.id}")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun CrashLogRow(entry: CrashLogEntry, enabled: Boolean, onClick: () -> Unit, tag: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (entry.sizeLabel != null) {
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.sizeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = entry.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The report itself, as Android's `CrashLogDetailScreen` draws it: the whole text, selectable, in the monospace style a
 * stack trace needs, scrolling inside whatever container the host gives it (a phone screen, a desktop dialog).
 */
@Composable
fun CrashLogReportContent(
    text: String,
    modifier: Modifier = Modifier,
    tag: String = "crash-log-text",
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
                modifier = Modifier.fillMaxWidth().testTag(tag),
            )
        }
    }
}
