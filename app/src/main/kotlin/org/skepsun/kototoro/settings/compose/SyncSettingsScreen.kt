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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.compose.KototoroSnackbarHost

@Composable
fun SyncSettingsScreen(
    settings: AppSettings,
    state: SyncSettingsUiState,
    snackbarHostState: SnackbarHostState,
    onGoogleDriveSignInClick: () -> Unit,
    onGoogleDriveSignOutClick: () -> Unit,
    onGoogleDriveSyncNowClick: () -> Unit,
    onGoogleDriveDeleteRemoteClick: () -> Unit,
    onGoogleDriveImportLegacyClick: () -> Unit,
    onGoogleDriveEnabledChange: (Boolean) -> Unit,
    onGoogleDriveIntervalChange: (Int) -> Unit,
    onGoogleDriveWifiOnlyChange: (Boolean) -> Unit,
    onGoogleDriveSyncOnStartChange: (Boolean) -> Unit,
    onGoogleDriveSettingsSyncEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = {
            KototoroSnackbarHost(hostState = snackbarHostState)
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = settingsContentTopInset(),
                bottom = innerPadding.calculateBottomPadding() +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "google_drive") {
                GoogleDriveSyncSettingsContent(
                    state = state,
                    onGoogleDriveSignInClick = onGoogleDriveSignInClick,
                    onGoogleDriveSignOutClick = onGoogleDriveSignOutClick,
                    onGoogleDriveSyncNowClick = onGoogleDriveSyncNowClick,
                    onGoogleDriveDeleteRemoteClick = onGoogleDriveDeleteRemoteClick,
                    onGoogleDriveImportLegacyClick = onGoogleDriveImportLegacyClick,
                    onGoogleDriveEnabledChange = onGoogleDriveEnabledChange,
                    onGoogleDriveIntervalChange = onGoogleDriveIntervalChange,
                    onGoogleDriveWifiOnlyChange = onGoogleDriveWifiOnlyChange,
                    onGoogleDriveSyncOnStartChange = onGoogleDriveSyncOnStartChange,
                    onGoogleDriveSettingsSyncEnabledChange = onGoogleDriveSettingsSyncEnabledChange,
                )
            }
        }
    }
}
