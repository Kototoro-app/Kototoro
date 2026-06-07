package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings

data class SyncSettingsUiState(
    val isWebDavEnabled: Boolean,
    val webDavServerUrl: String,
    val webDavUsername: String,
    val webDavPassword: String,
    val webDavRemotePath: String,
    val isWebDavCheckLoading: Boolean,
    val isWebDavAutoSyncEnabled: Boolean,
    val isWebDavAutoRestoreEnabled: Boolean,
    val isWebDavKeepLocalCopyEnabled: Boolean,
    val webDavLastActionSummary: String?,
    val isPolicyNoteVisible: Boolean,
    val legacyRestoreBlockSummary: String?,
    val webDavBusySummary: String?,
)

@Composable
fun SyncSettingsScreen(
    settings: AppSettings,
    state: SyncSettingsUiState,
    snackbarHostState: SnackbarHostState,
    onWebDavEnabledChange: (Boolean) -> Unit,
    onWebDavServerUrlChange: (String) -> Unit,
    onWebDavUsernameChange: (String) -> Unit,
    onWebDavPasswordChange: (String) -> Unit,
    onWebDavRemotePathChange: (String) -> Unit,
    onWebDavTestClick: () -> Unit,
    onWebDavUploadNowClick: () -> Unit,
    onWebDavRestoreNowClick: () -> Unit,
    onWebDavAutoSyncChange: (Boolean) -> Unit,
    onWebDavAutoRestoreChange: (Boolean) -> Unit,
    onWebDavKeepLocalCopyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "webdav") {
                SettingsPreferenceSection(title = stringResource(R.string.webdav_integration)) {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.sync_webdav_enable),
                        checked = state.isWebDavEnabled,
                        summary = stringResource(R.string.sync_webdav_enable_summary),
                        onCheckedChange = onWebDavEnabledChange,
                    )
                    SettingsSectionDivider()
                    SettingsTextInputPreference(
                        title = stringResource(R.string.webdav_server_url),
                        value = state.webDavServerUrl,
                        enabled = state.isWebDavEnabled,
                        placeholder = "https://example.com/dav",
                        onValueChange = onWebDavServerUrlChange,
                    )
                    SettingsSectionDivider()
                    SettingsTextInputPreference(
                        title = stringResource(R.string.webdav_username),
                        value = state.webDavUsername,
                        enabled = state.isWebDavEnabled,
                        placeholder = stringResource(R.string.username),
                        onValueChange = onWebDavUsernameChange,
                    )
                    SettingsSectionDivider()
                    SettingsTextInputPreference(
                        title = stringResource(R.string.webdav_password),
                        value = state.webDavPassword,
                        enabled = state.isWebDavEnabled,
                        isPassword = true,
                        onValueChange = onWebDavPasswordChange,
                    )
                    SettingsSectionDivider()
                    SettingsTextInputPreference(
                        title = stringResource(R.string.webdav_remote_path),
                        value = state.webDavRemotePath,
                        enabled = state.isWebDavEnabled,
                        placeholder = "/backup",
                        onValueChange = onWebDavRemotePathChange,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.test_connection),
                        summary = stringResource(R.string.webdav_integration),
                        enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading,
                        onClick = onWebDavTestClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.webdav_upload_now),
                        summary = stringResource(R.string.create_backup),
                        enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading,
                        onClick = onWebDavUploadNowClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.webdav_restore_now),
                        summary = state.webDavBusySummary ?: stringResource(R.string.restore_backup),
                        enabled = state.isWebDavEnabled && !state.isWebDavCheckLoading,
                        onClick = onWebDavRestoreNowClick,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.webdav_auto_sync),
                        checked = state.isWebDavAutoSyncEnabled,
                        summary = state.legacyRestoreBlockSummary ?: stringResource(R.string.webdav_auto_sync_summary),
                        enabled = state.isWebDavEnabled,
                        onCheckedChange = onWebDavAutoSyncChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.webdav_auto_restore),
                        checked = state.isWebDavAutoRestoreEnabled,
                        summary = stringResource(R.string.webdav_auto_restore_summary),
                        enabled = state.isWebDavEnabled,
                        onCheckedChange = onWebDavAutoRestoreChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.webdav_keep_local_copy),
                        checked = state.isWebDavKeepLocalCopyEnabled,
                        summary = stringResource(R.string.webdav_keep_local_copy_summary),
                        enabled = state.isWebDavEnabled,
                        onCheckedChange = onWebDavKeepLocalCopyChange,
                    )
                    state.webDavLastActionSummary?.let {
                        SettingsSectionDivider()
                        SettingsInfoPreference(
                            title = stringResource(R.string.recent_webdav_action),
                            summary = it,
                        )
                    }
                    state.webDavBusySummary?.let {
                        SettingsSectionDivider()
                        SettingsInfoPreference(
                            title = stringResource(R.string.processing_),
                            summary = it,
                            iconRes = R.drawable.ic_info_outline,
                        )
                    }
                    if (state.isPolicyNoteVisible) {
                        SettingsSectionDivider()
                        SettingsInfoPreference(
                            title = stringResource(R.string.read_more),
                            summary = stringResource(R.string.backup_periodic_explain_keep_local_copy_off),
                            iconRes = R.drawable.ic_info_outline,
                        )
                    }
                    state.legacyRestoreBlockSummary?.let {
                        SettingsSectionDivider()
                        SettingsInfoPreference(
                            title = stringResource(R.string.webdav_sync_attention_title),
                            summary = it,
                            iconRes = R.drawable.ic_info_outline,
                        )
                    }
                }
            }
        }
    }
}
