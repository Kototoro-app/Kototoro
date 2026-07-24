package org.skepsun.kototoro.settings.about.crashlog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Crash Logs") }, // TODO: use stringResource when ResourceManager is wired
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back",
                        )
                    }
                },
                actions = {
                    if (logFiles.isNotEmpty()) {
                        IconButton(onClick = onClearAll) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Clear all crash logs",
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
        if (logFiles.isEmpty()) {
            /** Empty state — matches the centered [TextView] from [activity_crash_log.xml]. */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No crash logs", // TODO: use stringResource(R.string.no_crash_logs)
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            /** LazyColumn replaces the [RecyclerView] with [DividerItemDecoration]. */
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(logFiles, key = { it.absolutePath }) { file ->
                    CrashLogItem(
                        file = file,
                        onClick = { onLogClick(file) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * List item composable replacing [item_crash_log.xml]:
 * - Vertical [LinearLayout] with 16dp padding + selectableItemBackground
 * - bodyLarge title (date timestamp)
 * - bodySmall subtitle (first exception line), maxLines=2, ellipsize, secondary color
 */
@Composable
private fun CrashLogItem(
    file: File,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        Text(
            text = dateFormat.format(Date(file.lastModified())),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        val content = file.readText()
        val firstException = content.lines()
            .firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?.trim()
            ?: file.name

        Text(
            text = firstException,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CrashLogItemPreview() {
    // Preview uses a temp file to demonstrate the item layout.
    val tempFile = java.io.File.createTempFile("crash_preview_", ".log").apply {
        writeText("java.lang.NullPointerException: Attempt to invoke virtual method...\n\tat org.example.Test.method(Test.kt:42)")
        deleteOnExit()
    }
    MaterialTheme {
        Column {
            CrashLogItem(
                file = tempFile,
                onClick = {},
            )
            HorizontalDivider()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CrashLogScreenEmptyPreview() {
    MaterialTheme {
        CrashLogScreen(
            logFiles = emptyList(),
            onLogClick = {},
            onClearAll = {},
            onNavigateUp = {},
        )
    }
}
