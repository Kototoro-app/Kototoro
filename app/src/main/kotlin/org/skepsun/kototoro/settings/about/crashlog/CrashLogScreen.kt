package org.skepsun.kototoro.settings.about.crashlog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import org.skepsun.kototoro.core.diagnostics.crashLogSummaryLine
import org.skepsun.kototoro.core.ui.crashlog.CrashLogEntry
import org.skepsun.kototoro.core.ui.crashlog.CrashLogsContent
import androidx.compose.ui.Modifier
import org.skepsun.kototoro.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.skepsun.kototoro.core.ui.adaptive.tvIconButtonRing

/**
 * Full Compose crash log list screen replacing:
 * - activity_crash_log.xml (LinearLayout + MaterialToolbar + RecyclerView + empty TextView)
 * - item_crash_log.xml (LinearLayout with title/subtitle TextViews + selectableItemBackground)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogScreen(
    logFiles: List<File>,
    onLogClick: (File) -> Unit,
    onClearAll: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    val entries = remember(logFiles) { crashLogEntries(logFiles) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.crash_logs)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp, modifier = Modifier.tvIconButtonRing()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (logFiles.isNotEmpty()) {
                        IconButton(onClick = onClearAll, modifier = Modifier.tvIconButtonRing()) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.clear_crash_logs),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            CrashLogsContent(
                entries = entries,
                // Android reads the directory before it draws, so "loading" is the desktop host's answer to give.
                loading = false,
                emptyText = stringResource(R.string.no_crash_logs),
                enabled = true,
                onOpen = { entry -> logFiles.firstOrNull { it.name == entry.id }?.let(onLogClick) },
            )
        }
    }
}

/** The rows' view of a report: Android's own timestamp format and the shared first-exception-line rule. */
private fun crashLogEntries(files: List<File>): List<CrashLogEntry> {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return files.map { file ->
        CrashLogEntry(
            id = file.name,
            title = dateFormat.format(Date(file.lastModified())),
            summary = crashLogSummary(file),
        )
    }
}

private const val SUMMARY_BYTES = 8 * 1024

/**
 * The subtitle of a report: the first 8 KiB are enough for the line the list shows, and reading only them keeps the
 * twenty-report cap from parsing every trace on the main thread.
 */
private fun crashLogSummary(file: File): String = runCatching {
    file.inputStream().use { stream ->
        val buffer = ByteArray(SUMMARY_BYTES)
        val read = stream.read(buffer).coerceAtLeast(0)
        crashLogSummaryLine(String(buffer, 0, read, Charsets.UTF_8), file.name)
    }
}.getOrDefault(file.name)
