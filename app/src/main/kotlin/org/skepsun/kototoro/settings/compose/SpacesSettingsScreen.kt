package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsState

data class SpacesSettingsUiState(
    val spacesEnabled: Boolean,
    val switcherEnabled: Boolean,
    val persistentNavigationEnabled: Boolean,
    val immersiveSwitchEnabled: Boolean,
    val routePreferencesEnabled: Boolean,
)

@Composable
fun SpacesSettingsRoute(
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val state = SpacesSettingsUiState(
        spacesEnabled = settings.observeAsState(AppSettings.KEY_ENTITY_SPACE_ENABLED) {
            isEntitySpaceEnabled
        }.value,
        switcherEnabled = settings.observeAsState(AppSettings.KEY_SPACE_SWITCHER_ENABLED) {
            isSpaceSwitcherEnabled
        }.value,
        persistentNavigationEnabled = settings.observeAsState(AppSettings.KEY_SPACE_PERSISTENT_NAVIGATION_ENABLED) {
            isSpacePersistentNavigationEnabled
        }.value,
        immersiveSwitchEnabled = settings.observeAsState(AppSettings.KEY_SPACE_IMMERSIVE_SWITCH_ENABLED) {
            isSpaceImmersiveSwitchEnabled
        }.value,
        routePreferencesEnabled = settings.observeAsState(AppSettings.KEY_SPACE_ROUTE_PREFERENCES_ENABLED) {
            isSpaceRoutePreferencesEnabled
        }.value,
    )
    SpacesSettingsScreen(
        state = state,
        onSpacesEnabledChange = { settings.isEntitySpaceEnabled = it },
        onSwitcherEnabledChange = { settings.isSpaceSwitcherEnabled = it },
        onPersistentNavigationEnabledChange = { settings.isSpacePersistentNavigationEnabled = it },
        onImmersiveSwitchEnabledChange = { settings.isSpaceImmersiveSwitchEnabled = it },
        onRoutePreferencesEnabledChange = { settings.isSpaceRoutePreferencesEnabled = it },
        modifier = modifier,
    )
}

@Composable
fun SpacesSettingsScreen(
    state: SpacesSettingsUiState,
    onSpacesEnabledChange: (Boolean) -> Unit,
    onSwitcherEnabledChange: (Boolean) -> Unit,
    onPersistentNavigationEnabledChange: (Boolean) -> Unit,
    onImmersiveSwitchEnabledChange: (Boolean) -> Unit,
    onRoutePreferencesEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsContentHorizontalPadding, vertical = 20.dp),
        ) {
            SettingsPreferenceSection(
                title = stringResource(R.string.spaces),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SettingsSwitchPreference(
                    title = stringResource(R.string.spaces_enabled),
                    summary = stringResource(R.string.spaces_enabled_summary),
                    checked = state.spacesEnabled,
                    onCheckedChange = onSpacesEnabledChange,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.space_switcher_enabled),
                    summary = stringResource(R.string.space_switcher_enabled_summary),
                    checked = state.switcherEnabled,
                    enabled = state.spacesEnabled,
                    onCheckedChange = onSwitcherEnabledChange,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.space_persistent_navigation_enabled),
                    summary = stringResource(R.string.space_persistent_navigation_enabled_summary),
                    checked = state.persistentNavigationEnabled,
                    enabled = state.spacesEnabled && state.switcherEnabled,
                    onCheckedChange = onPersistentNavigationEnabledChange,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.space_immersive_switch_enabled),
                    summary = stringResource(R.string.space_immersive_switch_enabled_summary),
                    checked = state.immersiveSwitchEnabled,
                    enabled = state.spacesEnabled && state.switcherEnabled,
                    onCheckedChange = onImmersiveSwitchEnabledChange,
                )
                SettingsSectionDivider()
                SettingsSwitchPreference(
                    title = stringResource(R.string.space_route_preferences_enabled),
                    summary = stringResource(R.string.space_route_preferences_enabled_summary),
                    checked = state.routePreferencesEnabled,
                    enabled = state.spacesEnabled,
                    onCheckedChange = onRoutePreferencesEnabledChange,
                )
            }
        }
    }
}
