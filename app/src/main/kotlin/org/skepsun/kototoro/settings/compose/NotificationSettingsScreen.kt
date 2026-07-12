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

data class NotificationSettingsUiState(
    val isTrackerNotificationsEnabled: Boolean,
    val ringtoneSummary: String,
    val isNotificationLightEnabled: Boolean,
    val isNotificationsInfoVisible: Boolean,
)

@Composable
fun NotificationSettingsScreen(
    notificationsTitle: String,
    state: NotificationSettingsUiState,
    snackbarHostState: SnackbarHostState,
    onTrackerNotificationsEnabledChange: (Boolean) -> Unit,
    onNotificationSoundClick: () -> Unit,
    onNotificationVibrateClick: () -> Unit,
    onNotificationLightChange: (Boolean) -> Unit,
) {
    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        LazyColumn(state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "notifications") {
                SettingsPreferenceSection(title = notificationsTitle) {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.notifications_enable),
                        checked = state.isTrackerNotificationsEnabled,
                        onCheckedChange = onTrackerNotificationsEnabledChange,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.notification_sound),
                        summary = state.ringtoneSummary,
                        enabled = state.isTrackerNotificationsEnabled,
                        onClick = onNotificationSoundClick,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.vibration),
                        enabled = state.isTrackerNotificationsEnabled,
                        onClick = onNotificationVibrateClick,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.light_indicator),
                        checked = state.isNotificationLightEnabled,
                        enabled = state.isTrackerNotificationsEnabled,
                        onCheckedChange = onNotificationLightChange,
                    )
                    if (state.isNotificationsInfoVisible) {
                        SettingsSectionDivider()
                        SettingsInfoPreference(
                            title = stringResource(R.string.notifications),
                            summary = stringResource(R.string.show_notification_new_chapters_off),
                            iconRes = R.drawable.ic_info_outline,
                        )
                    }
                }
            }
        }
    }
}
