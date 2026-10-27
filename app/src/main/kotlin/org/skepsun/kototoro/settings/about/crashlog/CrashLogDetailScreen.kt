package org.skepsun.kototoro.settings.about.crashlog

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.crashlog.CrashLogReportContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvIconButtonRing

/**
 * Full Compose crash log detail screen replacing:
 * - activity_crash_log_detail.xml (LinearLayout + MaterialToolbar + ScrollView + monospace TextView)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogDetailScreen(
    logContent: String,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onNavigateUp: () -> Unit,
) {
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
                    IconButton(onClick = onCopy, modifier = Modifier.tvIconButtonRing()) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(R.string.copy),
                        )
                    }
                    IconButton(onClick = onShare, modifier = Modifier.tvIconButtonRing()) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.share),
                        )
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
        // The report body is shared with the desktop host, which shows it inside a dialog instead of a second screen.
        CrashLogReportContent(
            text = logContent,
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CrashLogDetailScreenPreview() {
    MaterialTheme {
        CrashLogDetailScreen(
            logContent = "=== Crash Report ===\nTime: 2026-07-16 10:30:45\nApp Version: 1.0.0\n\n=== Stack Trace ===\njava.lang.NullPointerException\n\tat org.example.Test.method(Test.kt:42)\n\tat org.example.Main.run(Main.kt:15)",
            onShare = {},
            onCopy = {},
            onNavigateUp = {},
        )
    }
}
