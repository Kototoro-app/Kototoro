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
import org.skepsun.kototoro.core.prefs.TriStateOption
import org.skepsun.kototoro.explore.data.SourcesSortOrder

data class SourcesSettingsUiState(
    val sourcesSortOrder: SourcesSortOrder,
    val isSourcesGridMode: Boolean,
    val isSourcesGroupedByLanguage: Boolean,
    val jarPriorityOrder: List<String>,
    val isShowBrokenSources: Boolean,
    val isNsfwContentDisabled: Boolean,
    val isHistoryExcludeNsfw: Boolean,
    val isFavouritesExcludeNsfw: Boolean,
    val isFeedExcludeNsfw: Boolean,
    val isTrackerNsfwDisabled: Boolean,
    val isSuggestionsExcludeNsfw: Boolean,
    val incognitoModeForNsfw: TriStateOption,
    val isTagsWarningsEnabled: Boolean,
    val isMirrorSwitchingEnabled: Boolean,
    val isHandleLinksEnabled: Boolean,
)

@Composable
fun SourcesSettingsScreen(
    overviewTitle: String,
    remoteSourcesTitle: String,
    adultFilteringTitle: String,
    moreTitle: String,
    state: SourcesSettingsUiState,
    snackbarHostState: SnackbarHostState,
    sortOrderOptions: List<SettingsChoiceOption<SourcesSortOrder>>,
    incognitoOptions: List<SettingsChoiceOption<TriStateOption>>,
    onSourcesSortOrderChange: (SourcesSortOrder) -> Unit,
    onSourcesGridModeChange: (Boolean) -> Unit,
    onSourcesGroupedByLanguageChange: (Boolean) -> Unit,
    onSetupWizardClick: () -> Unit,
    onJarPriorityOrderChange: (List<String>) -> Unit,
    onShowBrokenSourcesChange: (Boolean) -> Unit,
    onNsfwContentDisabledChange: (Boolean) -> Unit,
    onHistoryExcludeNsfwChange: (Boolean) -> Unit,
    onFavouritesExcludeNsfwChange: (Boolean) -> Unit,
    onFeedExcludeNsfwChange: (Boolean) -> Unit,
    onTrackerNsfwDisabledChange: (Boolean) -> Unit,
    onSuggestionsExcludeNsfwChange: (Boolean) -> Unit,
    onIncognitoModeForNsfwChange: (TriStateOption) -> Unit,
    onTagsWarningsEnabledChange: (Boolean) -> Unit,
    onMirrorSwitchingChange: (Boolean) -> Unit,
    onHandleLinksEnabledChange: (Boolean) -> Unit,
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
            item(key = "overview") {
                SettingsPreferenceSection(title = overviewTitle) {
                    SettingsChoicePreference(
                        title = stringResource(R.string.sort_order),
                        value = state.sourcesSortOrder,
                        options = sortOrderOptions,
                        onValueChange = onSourcesSortOrderChange,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.show_in_grid_view),
                        summary = stringResource(R.string.browse_display_options_summary),
                        showChevron = false,
                        onClick = {},
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.group_sources_by_language),
                        checked = state.isSourcesGroupedByLanguage,
                        summary = stringResource(R.string.group_sources_by_language_summary),
                        onCheckedChange = onSourcesGroupedByLanguageChange,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.setup_wizard),
                        summary = stringResource(R.string.setup_wizard_summary),
                        onClick = onSetupWizardClick,
                    )
                }
            }
            item(key = "remote_sources") {
                SettingsPreferenceSection(title = remoteSourcesTitle) {
                    SettingsReorderPreference(
                        title = stringResource(R.string.jar_priority_order_title),
                        value = state.jarPriorityOrder,
                        summary = stringResource(R.string.jar_priority_order_summary),
                        emptyValueText = stringResource(R.string.not_specified),
                        onValueChange = onJarPriorityOrderChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.show_broken_sources),
                        checked = state.isShowBrokenSources,
                        summary = stringResource(R.string.show_broken_sources_summary),
                        onCheckedChange = onShowBrokenSourcesChange,
                    )
                }
            }
            item(key = "adult_filtering") {
                SettingsPreferenceSection(title = adultFilteringTitle) {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.disable_nsfw),
                        checked = state.isNsfwContentDisabled,
                        summary = stringResource(R.string.disable_nsfw_summary),
                        onCheckedChange = onNsfwContentDisabledChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.disable_history_nsfw),
                        checked = state.isHistoryExcludeNsfw,
                        summary = stringResource(R.string.disable_history_nsfw_summary),
                        onCheckedChange = onHistoryExcludeNsfwChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.disable_favourites_nsfw),
                        checked = state.isFavouritesExcludeNsfw,
                        summary = stringResource(R.string.disable_favourites_nsfw_summary),
                        onCheckedChange = onFavouritesExcludeNsfwChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.disable_feed_nsfw),
                        checked = state.isFeedExcludeNsfw,
                        summary = stringResource(R.string.disable_feed_nsfw_summary),
                        onCheckedChange = onFeedExcludeNsfwChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.disable_updates_nsfw),
                        checked = state.isTrackerNsfwDisabled,
                        summary = stringResource(R.string.disable_updates_nsfw_summary),
                        onCheckedChange = onTrackerNsfwDisabledChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.disable_suggestions_nsfw),
                        checked = state.isSuggestionsExcludeNsfw,
                        summary = stringResource(R.string.disable_suggestions_nsfw_summary),
                        onCheckedChange = onSuggestionsExcludeNsfwChange,
                    )
                    SettingsSectionDivider()
                    SettingsChoicePreference(
                        title = stringResource(R.string.incognito_for_nsfw),
                        value = state.incognitoModeForNsfw,
                        options = incognitoOptions,
                        onValueChange = onIncognitoModeForNsfwChange,
                    )
                }
            }
            item(key = "more") {
                SettingsPreferenceSection(title = moreTitle) {
                    SettingsSwitchPreference(
                        title = stringResource(R.string.tags_warnings),
                        checked = state.isTagsWarningsEnabled,
                        summary = stringResource(R.string.tags_warnings_summary),
                        onCheckedChange = onTagsWarningsEnabledChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.mirror_switching),
                        checked = state.isMirrorSwitchingEnabled,
                        summary = stringResource(R.string.mirror_switching_summary),
                        onCheckedChange = onMirrorSwitchingChange,
                    )
                    SettingsSectionDivider()
                    SettingsSwitchPreference(
                        title = stringResource(R.string.handle_links),
                        checked = state.isHandleLinksEnabled,
                        summary = stringResource(R.string.handle_links_summary),
                        onCheckedChange = onHandleLinksEnabledChange,
                    )
                }
            }
        }
    }
}
