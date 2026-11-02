package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.settings.compose.GoogleDriveSyncSettingsContent
import org.skepsun.kototoro.settings.compose.SyncSettingsUiState
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalTestApi::class)
class GoogleDriveSyncSettingsContentTest {
    private val initial = SyncSettingsUiState(
        isGoogleDriveSignedIn = false, isGoogleDriveEnabled = false, isWebDavEnabled = true,
        googleDriveAccountSummary = "fixture@example.test", googleDriveIntervalMinutes = 360,
        isGoogleDriveWifiOnly = false, isGoogleDriveSyncOnStart = true,
        googleDriveLastSyncSummary = "2026-10-28 12:00", googleDriveErrorSummary = null, isGoogleDriveSyncing = false,
    )

    @Test
    fun `failed first login keeps connection controls closed but an enabled backend remains recoverable`() =
        runDesktopComposeUiTest(width = 700, height = 900) {
            var state by mutableStateOf(initial.copy(isWebDavEnabled = false,
                googleDriveAuthorizationRequired = true, googleDriveErrorSummary = "Fixture client configuration error"))
            setContent {
                DesktopTheme {
                    GoogleDriveSyncSettingsContent(
                        state = state,
                        onGoogleDriveSignInClick = {}, onGoogleDriveSignOutClick = {}, onGoogleDriveSyncNowClick = {},
                        onGoogleDriveDeleteRemoteClick = {}, onGoogleDriveImportLegacyClick = {},
                        onGoogleDriveEnabledChange = { state = state.copy(isGoogleDriveEnabled = it) },
                        onGoogleDriveIntervalChange = {}, onGoogleDriveWifiOnlyChange = {},
                        onGoogleDriveSyncOnStartChange = {}, onGoogleDriveSettingsSyncEnabledChange = {},
                        supportsWifiOnly = false, supportsSettingsSync = false,
                        supportsRemoteDelete = false, supportsLegacyImport = false,
                    )
                }
            }
            onNodeWithTag("drive-sign-in").assertExists()
            onNodeWithTag("drive-sync-now").assertDoesNotExist()
            onNodeWithText(AndroidStrings["sync_google_drive_enable"]).assertDoesNotExist()
            onNodeWithText("Fixture client configuration error", substring = true).assertExists()
            runOnIdle { state = state.copy(isGoogleDriveEnabled = true) }
            onNodeWithText(AndroidStrings["sync_google_drive_enable"]).performClick()
            onNodeWithTag("drive-sync-now").assertDoesNotExist()
            onNodeWithTag("drive-sign-in").assertExists()
        }

    @Test
    fun `shared narrow and wide controls confirm backend switches and respect host capabilities`() {
        for (width in listOf(380, 1000)) runDesktopComposeUiTest(width = width, height = 900) {
            var state by mutableStateOf(initial)
            var syncs = 0
            setContent {
                DesktopTheme(DesktopAppearance.LIGHT) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                            GoogleDriveSyncSettingsContent(
                                state = state,
                                onGoogleDriveSignInClick = { state = state.copy(isGoogleDriveSignedIn = true) },
                                onGoogleDriveSignOutClick = { state = state.copy(isGoogleDriveSignedIn = false) },
                                onGoogleDriveSyncNowClick = { syncs++ },
                                onGoogleDriveDeleteRemoteClick = {}, onGoogleDriveImportLegacyClick = {},
                                onGoogleDriveEnabledChange = { state = state.copy(isGoogleDriveEnabled = it) },
                                onGoogleDriveIntervalChange = { state = state.copy(googleDriveIntervalMinutes = it) },
                                onGoogleDriveWifiOnlyChange = {}, onGoogleDriveSettingsSyncEnabledChange = {},
                                onGoogleDriveSyncOnStartChange = { state = state.copy(isGoogleDriveSyncOnStart = it) },
                                supportsWifiOnly = false, supportsSettingsSync = false,
                                supportsRemoteDelete = false, supportsLegacyImport = false,
                            )
                        }
                    }
                }
            }
            onNodeWithTag("drive-sign-in").performClick()
            onNodeWithTag("drive-sync-now").assertIsNotEnabled()
            onNodeWithText(AndroidStrings["sync_google_drive_enable"]).performClick()
            onNodeWithText(AndroidStrings["sync_backend_switch_google_drive_title"]).assertExists()
            onNodeWithText(AndroidStrings["enable"]).performClick()
            onNodeWithTag("drive-sync-now").performClick()
            runOnIdle { assertEquals(1, syncs) }
            onNodeWithText(AndroidStrings["sync_wifi_only"]).assertDoesNotExist()
            onNodeWithText(AndroidStrings["sync_settings_enabled_title"]).assertDoesNotExist()
            onNodeWithText(AndroidStrings["sync_delete_remote_data"]).assertDoesNotExist()
            onNodeWithText(AndroidStrings["sync_import_legacy_remote_data"]).assertDoesNotExist()
            val report = Path.of("build/reports/google-drive-$width.png")
            Files.createDirectories(report.parent)
            Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                image.encodeToData()?.use { Files.write(report, it.bytes) }
            }
            onNodeWithTag("drive-sign-out").performScrollTo().performClick()
            onNodeWithTag("drive-sign-in").assertExists()
        }
    }
}
