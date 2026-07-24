package org.skepsun.kototoro.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.viewLifecycleScope
import org.skepsun.kototoro.parsers.util.await
import org.skepsun.kototoro.settings.compose.ProxySettingsScreen
import java.net.Proxy
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun ProxySettingsRoute(
    settings: AppSettings,
    testSummaryFlow: MutableStateFlow<String?>,
    isTestRunningFlow: MutableStateFlow<Boolean>,
    onTestConnection: () -> Unit,
) {
    val testSummary by testSummaryFlow.collectAsState()
    val isTestRunning by isTestRunningFlow.collectAsState()
    ProxySettingsScreen(
        settings = settings,
        testSummary = testSummary,
        isTestRunning = isTestRunning,
        onTestConnection = onTestConnection,
    )
}
