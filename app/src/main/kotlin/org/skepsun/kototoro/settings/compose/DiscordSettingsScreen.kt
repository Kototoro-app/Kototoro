package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.edit
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState

@Composable
fun DiscordSettingsScreen(
    settings: AppSettings,
    tokenSummary: String?,
    isLogoutVisible: Boolean,
    onTokenClick: () -> Unit,
    onLogoutClick: () -> Unit,
) {
    val isEnabled by settings.observeAsState(AppSettings.KEY_DISCORD_RPC) {
        isDiscordRpcEnabled
    }
    val skipNsfw by settings.observeAsState(AppSettings.KEY_DISCORD_RPC_SKIP_NSFW) {
        isDiscordRpcSkipNsfw
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsContentHorizontalPadding, vertical = 20.dp),
        ) {
            SettingsPreferenceSection(
                title = stringResource(R.string.discord),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsSwitchPreference(
                    title = stringResource(R.string.discord_rpc),
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        settings.prefs.edit {
                            putBoolean(AppSettings.KEY_DISCORD_RPC, checked)
                        }
                    },
                )
                SettingsSectionDivider()
                SettingsActionPreference(
                    title = stringResource(R.string.sign_in),
                    summary = tokenSummary ?: stringResource(R.string.discord_token_summary),
                    enabled = isEnabled,
                    onClick = onTokenClick,
                )
                if (isLogoutVisible) {
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.logout),
                        summary = stringResource(R.string.discord_logout_summary),
                        enabled = isEnabled,
                        onClick = onLogoutClick,
                    )
                }
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.disable_nsfw),
                    summary = stringResource(R.string.rpc_skip_nsfw_summary),
                    checked = skipNsfw,
                    enabled = isEnabled,
                    onCheckedChange = { checked ->
                        settings.prefs.edit {
                            putBoolean(AppSettings.KEY_DISCORD_RPC_SKIP_NSFW, checked)
                        }
                    },
                )
            }
        }
    }
}
