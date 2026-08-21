package org.skepsun.kototoro.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.settings.compose.ProxySettingsScreen

@Composable
fun ProxySettingsRoute(
    settings: AppSettings,
    testSummaryFlow: MutableStateFlow<String?>,
    isTestRunningFlow: MutableStateFlow<Boolean>,
    onTestConnection: () -> Unit,
) {
    val testSummary by testSummaryFlow.collectAsStateWithLifecycle()
    val isTestRunning by isTestRunningFlow.collectAsStateWithLifecycle()
    ProxySettingsScreen(
        settings = settings,
        testSummary = testSummary,
        isTestRunning = isTestRunning,
        onTestConnection = onTestConnection,
    )
}
