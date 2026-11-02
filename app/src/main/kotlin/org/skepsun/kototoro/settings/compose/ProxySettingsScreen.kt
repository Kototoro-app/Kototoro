package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.settings.PreferenceEditMode
import org.skepsun.kototoro.core.ui.settings.ProxyMode
import org.skepsun.kototoro.core.ui.settings.ProxySettingsContent
import org.skepsun.kototoro.core.ui.settings.ProxySettingsUiState
import org.skepsun.kototoro.core.ui.settings.ProxyTextField
import java.net.Proxy

/** Android observes/persists preferences; the page and its editors are shared with desktop. */
@Composable
fun ProxySettingsScreen(
    settings: AppSettings,
    testSummary: String?,
    isTestRunning: Boolean,
    onTestConnection: () -> Unit,
) {
    val type by settings.observeAsState(AppSettings.KEY_PROXY_TYPE) { proxyType }
    val address by settings.observeAsState(AppSettings.KEY_PROXY_ADDRESS) { proxyAddress.orEmpty() }
    val port by settings.observeAsState(AppSettings.KEY_PROXY_PORT) {
        prefs.getString(AppSettings.KEY_PROXY_PORT, "").orEmpty()
    }
    val login by settings.observeAsState(AppSettings.KEY_PROXY_LOGIN) { proxyLogin.orEmpty() }
    val password by settings.observeAsState(AppSettings.KEY_PROXY_PASSWORD) { proxyPassword.orEmpty() }
    ProxySettingsContent(
        state = ProxySettingsUiState(ProxyMode.valueOf(type.name), address, port, login, password,
            configured = type == Proxy.Type.DIRECT || address.isNotBlank() && port.toIntOrNull() in 1..65535,
            testRunning = isTestRunning, testSummary = testSummary),
        onModeChange = { settings.prefs.edit().putString(AppSettings.KEY_PROXY_TYPE, it.name).apply() },
        onTextChange = { field, value ->
            val key = when (field) {
                ProxyTextField.ADDRESS -> AppSettings.KEY_PROXY_ADDRESS
                ProxyTextField.PORT -> AppSettings.KEY_PROXY_PORT
                ProxyTextField.USERNAME -> AppSettings.KEY_PROXY_LOGIN
                ProxyTextField.PASSWORD -> AppSettings.KEY_PROXY_PASSWORD
            }
            settings.prefs.edit().putString(key, value).apply()
            true
        },
        onTestConnection = onTestConnection,
        editMode = if (LocalUiPresentationConfig.current.isTv) PreferenceEditMode.DIALOG else PreferenceEditMode.INLINE,
        modifier = Modifier.fillMaxSize().padding(top = settingsContentTopInset())
            .padding(horizontal = SettingsContentHorizontalPadding, vertical = 8.dp),
        renderDialog = { AndroidPreferenceDialog(it) },
    )
}
