package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvTextInputFocusEscape
import org.skepsun.kototoro.core.ui.text.uiString

enum class ProxyMode { DIRECT, HTTP, SOCKS }
enum class ProxyTextField(val tag: String, val titleKey: String) {
    ADDRESS("address", "address"), PORT("port", "port"), USERNAME("username", "username"), PASSWORD("password", "password"),
}
enum class PreferenceEditMode { INLINE, DIALOG }

/** Configuration validity is supplied by the host that actually runs the connection. */
data class ProxySettingsUiState(
    val mode: ProxyMode,
    val address: String,
    val port: String,
    val username: String,
    val password: String,
    val configured: Boolean,
    val testRunning: Boolean = false,
    val testSummary: String? = null,
) {
    val proxyEnabled: Boolean get() = mode != ProxyMode.DIRECT
    val canTest: Boolean get() = proxyEnabled && configured && !testRunning

    fun value(field: ProxyTextField): String = when (field) {
        ProxyTextField.ADDRESS -> address
        ProxyTextField.PORT -> port
        ProxyTextField.USERNAME -> username
        ProxyTextField.PASSWORD -> password
    }
}

/** One proxy settings page, driven by a feature state and three operations rather than a platform controller. */
@Composable
fun ProxySettingsContent(
    state: ProxySettingsUiState,
    onModeChange: (ProxyMode) -> Unit,
    onTextChange: (ProxyTextField, String) -> Boolean,
    onTestConnection: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    editMode: PreferenceEditMode = PreferenceEditMode.DIALOG,
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = { PreferenceDialog(it) },
) {
    var choosingType by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProxyTextField?>(null) }
    val editable = enabled && state.proxyEnabled
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("proxy-settings-content"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSectionHeader(uiString("network"))
        SettingsGroup {
            SettingsValueRow(uiString("type"), null, modeLabel(state.mode), enabled,
                { choosingType = true }, testTag = "proxy-type")
            SettingsRowDivider()
            ProxyField(state, ProxyTextField.ADDRESS, editMode, editable, onTextChange) { editing = it }
            SettingsRowDivider()
            ProxyField(state, ProxyTextField.PORT, editMode, editable, onTextChange) { editing = it }
            SettingsRowDivider()
            val summary = when {
                state.testRunning -> uiString("loading_")
                state.testSummary != null -> state.testSummary
                !state.configured -> uiString("invalid_proxy_configuration")
                else -> null
            }
            SettingsValueRow(uiString("test_connection"), summary, null, enabled && state.canTest,
                onTestConnection, testTag = "proxy-test")
        }
        SettingsSectionHeader(uiString("authorization_optional"))
        SettingsGroup {
            ProxyField(state, ProxyTextField.USERNAME, editMode, editable, onTextChange) { editing = it }
            SettingsRowDivider()
            ProxyField(state, ProxyTextField.PASSWORD, editMode, editable, onTextChange) { editing = it }
        }
    }
    if (choosingType) {
        PreferenceChoiceDialog(uiString("type"), state.mode,
            ProxyMode.entries.map { PreferenceChoice(it, modeLabel(it)) }, onModeChange,
            { choosingType = false }, enabled, "proxy-type", renderDialog)
    }
    editing?.let { field ->
        PreferenceTextDialog(
            title = uiString(field.titleKey), initial = state.value(field),
            onSave = { value -> onTextChange(field, value) }, onDismiss = { editing = null },
            enabled = editable, secret = field == ProxyTextField.PASSWORD,
            rejectionMessage = if (field == ProxyTextField.PORT) uiString("invalid_proxy_configuration") else null,
            tagPrefix = "proxy-${field.tag}", renderDialog = renderDialog,
        )
    }
}

@Composable
private fun ProxyField(
    state: ProxySettingsUiState,
    field: ProxyTextField,
    mode: PreferenceEditMode,
    enabled: Boolean,
    onChange: (ProxyTextField, String) -> Boolean,
    onEdit: (ProxyTextField) -> Unit,
) {
    val value = state.value(field)
    val secret = field == ProxyTextField.PASSWORD
    if (mode == PreferenceEditMode.INLINE) {
        var draft by remember(value) { mutableStateOf(value) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiString(field.titleKey), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(draft, { if (onChange(field, it)) draft = it }, enabled = enabled,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().tvTextInputFocusEscape().testTag("proxy-${field.tag}-field"))
        }
    } else {
        SettingsValueRow(uiString(field.titleKey), null,
            if (secret) value.takeIf(String::isNotEmpty)?.let { "•".repeat(it.length.coerceAtMost(8)) }
            else value.ifBlank { null }, enabled, { onEdit(field) }, testTag = "proxy-${field.tag}")
    }
}

@Composable
private fun modeLabel(mode: ProxyMode): String = when (mode) {
    ProxyMode.DIRECT -> uiString("disabled")
    ProxyMode.HTTP -> "HTTP"
    ProxyMode.SOCKS -> "SOCKS"
}

/** Shared connection-result presentation; each host still owns the test job and result lifetime. */
@Composable
fun ProxyTestResultDialog(
    message: String,
    onDismiss: () -> Unit,
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = { PreferenceDialog(it) },
) {
    renderDialog(PreferenceDialogContent(
        title = uiString("test_connection"), onDismiss = onDismiss,
        text = { Text(message, modifier = Modifier.testTag("proxy-test-result")) },
        confirmButton = {
            TextButton(onDismiss, modifier = Modifier.testTag("proxy-test-close")) { Text(uiString("close")) }
        },
    ))
}
