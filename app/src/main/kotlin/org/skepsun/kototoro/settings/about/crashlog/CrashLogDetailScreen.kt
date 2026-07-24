package org.skepsun.kototoro.settings.about.crashlog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                title = { Text(text = "Crash Log Detail") }, // TODO: use stringResource
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onCopy) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy to clipboard",
                        )
                    }
                    IconButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "Share",
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
        /** Scrollable monospace text replaces the [ScrollView] + [TextView] from [activity_crash_log_detail.xml]. */
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            SelectionContainer {
                Text(
                    text = logContent,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                )
            }
        }
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
