package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import java.net.Proxy

@Composable
fun ProxySettingsScreen(
    settings: AppSettings,
    testSummary: String?,
    isTestRunning: Boolean,
    onTestConnection: () -> Unit,
) {
    val proxyType by settings.observeAsState(AppSettings.KEY_PROXY_TYPE) { proxyType }
    val proxyAddress by settings.observeAsState(AppSettings.KEY_PROXY_ADDRESS) { proxyAddress.orEmpty() }
    val proxyPort by settings.observeAsState(AppSettings.KEY_PROXY_PORT) {
        prefs.getString(AppSettings.KEY_PROXY_PORT, "").orEmpty()
    }
    val proxyLogin by settings.observeAsState(AppSettings.KEY_PROXY_LOGIN) { proxyLogin.orEmpty() }
    val proxyPassword by settings.observeAsState(AppSettings.KEY_PROXY_PASSWORD) { proxyPassword.orEmpty() }
    val isProxyEnabled = proxyType != Proxy.Type.DIRECT
    val isProxyConfigured = !isProxyEnabled || (
        proxyAddress.isNotBlank() &&
            proxyPort.toIntOrNull() in 1..0xFFFF
        )
    val typeOptions = listOf(
        SettingsChoiceOption(Proxy.Type.DIRECT.name, stringResource(R.string.disabled)),
        SettingsChoiceOption(Proxy.Type.HTTP.name, "HTTP"),
        SettingsChoiceOption(Proxy.Type.SOCKS.name, "SOCKS"),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SettingsContentHorizontalPadding, vertical = 8.dp),
    ) {
        SettingsChoicePreference(
            title = stringResource(R.string.type),
            value = proxyType.name,
            options = typeOptions,
            onValueChange = { value ->
                settings.prefs.edit().putString(AppSettings.KEY_PROXY_TYPE, value).apply()
            },
        )
        SettingsTextInputPreference(
            title = stringResource(R.string.address),
            value = proxyAddress,
            enabled = isProxyEnabled,
            onValueChange = { value ->
                settings.prefs.edit().putString(AppSettings.KEY_PROXY_ADDRESS, value).apply()
            },
        )
        SettingsTextInputPreference(
            title = stringResource(R.string.port),
            value = proxyPort,
            enabled = isProxyEnabled,
            onValueChange = { value ->
                settings.prefs.edit().putString(AppSettings.KEY_PROXY_PORT, value).apply()
            },
        )
        SettingsPreferenceSection(title = stringResource(R.string.authorization_optional)) {
            SettingsTextInputPreference(
                title = stringResource(R.string.username),
                value = proxyLogin,
                enabled = isProxyEnabled,
                onValueChange = { value ->
                    settings.prefs.edit().putString(AppSettings.KEY_PROXY_LOGIN, value).apply()
                },
            )
            SettingsTextInputPreference(
                title = stringResource(R.string.password),
                value = proxyPassword,
                enabled = isProxyEnabled,
                isPassword = true,
                onValueChange = { value ->
                    settings.prefs.edit().putString(AppSettings.KEY_PROXY_PASSWORD, value).apply()
                },
            )
        }
        SettingsActionPreference(
            title = stringResource(R.string.test_connection),
            summary = testSummary ?: if (!isProxyConfigured) {
                stringResource(R.string.invalid_proxy_configuration)
            } else {
                null
            },
            enabled = isProxyEnabled && isProxyConfigured && !isTestRunning,
            onClick = onTestConnection,
        )
    }
}
