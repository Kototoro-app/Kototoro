package org.skepsun.kototoro.settings.compose

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.network.LocalNetworkPermissionHelper
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.buttons.TextButton
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
    val context = LocalContext.current
    val type by settings.observeAsState(AppSettings.KEY_PROXY_TYPE) { proxyType }
    val address by settings.observeAsState(AppSettings.KEY_PROXY_ADDRESS) { proxyAddress.orEmpty() }
    val port by settings.observeAsState(AppSettings.KEY_PROXY_PORT) {
        prefs.getString(AppSettings.KEY_PROXY_PORT, "").orEmpty()
    }
    val login by settings.observeAsState(AppSettings.KEY_PROXY_LOGIN) { proxyLogin.orEmpty() }
    val password by settings.observeAsState(AppSettings.KEY_PROXY_PASSWORD) { proxyPassword.orEmpty() }
    var permissionRefreshTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        permissionRefreshTick++
        onPauseOrDispose {}
    }
    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRefreshTick++
        if (!granted) {
            val activity = context as? Activity
            if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    LocalNetworkPermissionHelper.PERMISSION,
                )
            ) {
                runCatching {
                    context.startActivity(LocalNetworkPermissionHelper.createAppSettingsIntent(context))
                }
            }
        }
    }
    val needsLocalNetworkPermission = remember(type, address, permissionRefreshTick) {
        type != Proxy.Type.DIRECT && LocalNetworkPermissionHelper.needsPermissionForHost(context, address)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = settingsContentTopInset())
            .padding(horizontal = SettingsContentHorizontalPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (needsLocalNetworkPermission) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.local_network_permission_proxy_banner),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            localNetworkPermissionLauncher.launch(LocalNetworkPermissionHelper.PERMISSION)
                        },
                    ) {
                        Text(stringResource(R.string.local_network_permission_grant))
                    }
                }
            }
        }
        ProxySettingsContent(
            state = ProxySettingsUiState(
                ProxyMode.valueOf(type.name),
                address,
                port,
                login,
                password,
                configured = type == Proxy.Type.DIRECT || address.isNotBlank() && port.toIntOrNull() in 1..65535,
                testRunning = isTestRunning,
                testSummary = testSummary,
            ),
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
            modifier = Modifier.weight(1f).fillMaxWidth(),
            renderDialog = { AndroidPreferenceDialog(it) },
        )
    }
}
