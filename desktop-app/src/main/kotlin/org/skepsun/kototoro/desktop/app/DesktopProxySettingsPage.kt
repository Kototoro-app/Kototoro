package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow
import org.skepsun.kototoro.desktop.runtime.DesktopProxyType

/** Android's proxy "test connection" state: the row summary and the result dialog share it. */
data class DesktopProxyTestState(
    /** True while the request runs; the row shows Android's loading summary and cannot be pressed again. */
    val running: Boolean = false,
    /** Android shows `connection_ok` or the failure message in a dialog; null when there is nothing to show. */
    val message: String? = null,
    val success: Boolean = true,
)

/**
 * Android's `ProxySettingsScreen`. Windows had no proxy at all: every source, image and JSON import used the direct
 * connection the compatibility client was built with. The page writes the same four preference keys Android writes, and
 * the value reaches the SDK client's selector, so a change applies to the next request without restarting the app.
 */
@Composable
internal fun DesktopProxySettingsPage(controller: DesktopController, state: DesktopAppState) {
    val settings = state.proxySettings
    val test = state.proxyTest
    val active = !state.busy
    val editable = active && settings.isEnabled
    var dialog by remember { mutableStateOf<ProxySettingsDialog?>(null) }
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton({ controller.more() }, enabled = active,
            modifier = Modifier.testTag("proxy-settings-back")) { Text(desktopText("common.back")) }
        SettingsSectionHeader(AndroidStrings["network"])
        SettingsGroup {
            SettingsValueRow(AndroidStrings["type"], null, settings.type.label, active,
                { dialog = ProxySettingsDialog.TYPE }, testTag = "proxy-type")
            SettingsRowDivider()
            SettingsValueRow(AndroidStrings["address"], null, settings.address.ifBlank { null }, editable,
                { dialog = ProxySettingsDialog.ADDRESS }, testTag = "proxy-address")
            SettingsRowDivider()
            SettingsValueRow(AndroidStrings["port"], null, settings.port.ifBlank { null }, editable,
                { dialog = ProxySettingsDialog.PORT }, testTag = "proxy-port")
            SettingsRowDivider()
            // Android's summary: the running label, the last result, or the invalid-configuration warning.
            val testSummary = when {
                test.running -> AndroidStrings["loading_"]
                test.message != null -> test.message
                !settings.isConfigured -> AndroidStrings["invalid_proxy_configuration"]
                else -> null
            }
            SettingsValueRow(AndroidStrings["test_connection"], testSummary, null,
                settings.isEnabled && settings.isConfigured && !test.running && active,
                { controller.testProxyConnection() }, testTag = "proxy-test")
        }
        SettingsSectionHeader(AndroidStrings["authorization_optional"])
        SettingsGroup {
            SettingsValueRow(AndroidStrings["username"], null, settings.login.ifBlank { null }, editable,
                { dialog = ProxySettingsDialog.LOGIN }, testTag = "proxy-username")
            SettingsRowDivider()
            SettingsValueRow(AndroidStrings["password"], null,
                settings.password.takeIf { it.isNotEmpty() }?.let { "·".repeat(it.length.coerceAtMost(8)) },
                editable, { dialog = ProxySettingsDialog.PASSWORD }, testTag = "proxy-password")
        }
    }
    when (dialog) {
        ProxySettingsDialog.TYPE -> DesktopChoiceSettingsDialog(
            title = AndroidStrings["type"], summary = null, bodyTag = "proxy-type-dialog",
            optionTagPrefix = "proxy-type-option",
            options = DesktopProxyType.entries.map { type -> type.label to type.name }, selected = settings.type.name,
            enabled = active,
            onPick = { name ->
                DesktopProxyType.entries.firstOrNull { it.name == name }
                    ?.let { controller.updateProxySettings(settings.copy(type = it)) }
            },
            onDismiss = { dialog = null },
        )
        ProxySettingsDialog.ADDRESS -> DesktopTextSettingsDialog(
            title = AndroidStrings["address"], summary = null, bodyTag = "proxy-address-dialog",
            fieldTag = "proxy-address-field", saveTag = "proxy-address-save", initial = settings.address,
            enabled = editable, onSave = { controller.updateProxySettings(settings.copy(address = it.trim())); true },
            onDismiss = { dialog = null },
        )
        ProxySettingsDialog.PORT -> DesktopTextSettingsDialog(
            title = AndroidStrings["port"], summary = AndroidStrings["invalid_proxy_configuration"],
            bodyTag = "proxy-port-dialog", fieldTag = "proxy-port-field", saveTag = "proxy-port-save",
            initial = settings.port, enabled = editable,
            // Android keeps whatever was typed; the page only refuses text that is not a port at all.
            onSave = { text ->
                val value = text.trim()
                if (value.isEmpty() || value.toIntOrNull() in 1..65535) {
                    controller.updateProxySettings(settings.copy(port = value))
                    true
                } else {
                    false
                }
            },
            onDismiss = { dialog = null },
        )
        ProxySettingsDialog.LOGIN -> DesktopTextSettingsDialog(
            title = AndroidStrings["username"], summary = null, bodyTag = "proxy-username-dialog",
            fieldTag = "proxy-username-field", saveTag = "proxy-username-save", initial = settings.login,
            enabled = editable, onSave = { controller.updateProxySettings(settings.copy(login = it)); true },
            onDismiss = { dialog = null },
        )
        ProxySettingsDialog.PASSWORD -> DesktopTextSettingsDialog(
            title = AndroidStrings["password"], summary = null, bodyTag = "proxy-password-dialog",
            fieldTag = "proxy-password-field", saveTag = "proxy-password-save", initial = settings.password,
            enabled = editable, secret = true,
            onSave = { controller.updateProxySettings(settings.copy(password = it)); true },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
    // Android's `SettingsDialogState.ProxyTestResult`: one message, no choice to make.
    test.message?.takeIf { !test.running }?.let { message ->
        AlertDialog(
            onDismissRequest = controller::closeProxyTest,
            title = { Text(AndroidStrings["test_connection"]) },
            text = { Text(message, modifier = Modifier.testTag("proxy-test-result")) },
            confirmButton = {
                TextButton(controller::closeProxyTest, modifier = Modifier.testTag("proxy-test-close")) {
                    Text(desktopText("common.close"))
                }
            },
        )
    }
}

private enum class ProxySettingsDialog { TYPE, ADDRESS, PORT, LOGIN, PASSWORD }

/** Android's option labels: `disabled` for DIRECT, and the protocol names for the other two. */
private val DesktopProxyType.label: String
    get() = when (this) {
        DesktopProxyType.DIRECT -> AndroidStrings["disabled"]
        DesktopProxyType.HTTP -> "HTTP"
        DesktopProxyType.SOCKS -> "SOCKS"
    }
