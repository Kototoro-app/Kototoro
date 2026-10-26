package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.settings.ProxyMode
import org.skepsun.kototoro.core.ui.settings.ProxySettingsContent
import org.skepsun.kototoro.core.ui.settings.ProxySettingsUiState
import org.skepsun.kototoro.core.ui.settings.ProxyTestResultDialog
import org.skepsun.kototoro.core.ui.settings.ProxyTextField
import org.skepsun.kototoro.desktop.runtime.DesktopProxyType

data class DesktopProxyTestState(
    val running: Boolean = false,
    val message: String? = null,
    val success: Boolean = true,
)

/** Desktop only projects runtime state and connects persistence, tests and navigation. */
@Composable
internal fun DesktopProxySettingsPage(controller: DesktopController, state: DesktopAppState) {
    val settings = state.proxySettings
    val test = state.proxyTest
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        OutlinedButton({ controller.backToStorage() }, enabled = !state.busy,
            modifier = Modifier.testTag("proxy-settings-back")) { Text(desktopText("common.back")) }
        ProxySettingsContent(
            state = ProxySettingsUiState(ProxyMode.valueOf(settings.type.name), settings.address, settings.port,
                settings.login, settings.password, settings.isConfigured, test.running, test.message),
            enabled = !state.busy,
            onModeChange = { controller.updateProxySettings(settings.copy(type = DesktopProxyType.valueOf(it.name))) },
            onTextChange = { field, text ->
                val updated = when (field) {
                    ProxyTextField.ADDRESS -> settings.copy(address = text.trim())
                    ProxyTextField.PORT -> settings.copy(port = text.trim())
                    ProxyTextField.USERNAME -> settings.copy(login = text)
                    ProxyTextField.PASSWORD -> settings.copy(password = text)
                }
                val accepted = field != ProxyTextField.PORT ||
                    updated.port.isEmpty() || updated.port.toIntOrNull() in 1..65535
                if (accepted) controller.updateProxySettings(updated)
                accepted
            },
            onTestConnection = controller::testProxyConnection,
        )
    }
    test.message?.takeIf { !test.running }?.let { ProxyTestResultDialog(it, controller::closeProxyTest) }
}

internal val DesktopProxyType.label: String
    get() = when (this) {
        DesktopProxyType.DIRECT -> AndroidStrings["disabled"]
        DesktopProxyType.HTTP -> "HTTP"
        DesktopProxyType.SOCKS -> "SOCKS"
    }
