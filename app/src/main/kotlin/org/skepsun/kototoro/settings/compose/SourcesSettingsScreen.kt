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
    val adultContentFilterTargets: Set<AdultContentFilterTarget>,
    val incognitoModeForNsfw: TriStateOption,
    val blacklistedTagCount: Int,
    val isTagsWarningsEnabled: Boolean,
    val isMirrorSwitchingEnabled: Boolean,
    val isHandleLinksEnabled: Boolean,
)

enum class AdultContentFilterTarget {
    SOURCES_AND_BROWSE,
    HISTORY,
    FAVOURITES,
    FEED,
    UPDATES,
    SUGGESTIONS,
}

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
    onAdultContentFilterTargetsChange: (Set<AdultContentFilterTarget>) -> Unit,
    onIncognitoModeForNsfwChange: (TriStateOption) -> Unit,
    onGlobalTagBlacklistClick: () -> Unit,
    onTagsWarningsEnabledChange: (Boolean) -> Unit,
    onMirrorSwitchingChange: (Boolean) -> Unit,
    onHandleLinksEnabledChange: (Boolean) -> Unit,
) {
    val adultContentFilterOptions = listOf(
        SettingsChoiceOption(
            AdultContentFilterTarget.SOURCES_AND_BROWSE,
            stringResource(R.string.disable_sources_and_browse_nsfw),
        ),
        SettingsChoiceOption(AdultContentFilterTarget.HISTORY, stringResource(R.string.disable_history_nsfw)),
        SettingsChoiceOption(AdultContentFilterTarget.FAVOURITES, stringResource(R.string.disable_favourites_nsfw)),
        SettingsChoiceOption(AdultContentFilterTarget.FEED, stringResource(R.string.disable_feed_nsfw)),
        SettingsChoiceOption(AdultContentFilterTarget.UPDATES, stringResource(R.string.disable_updates_nsfw)),
        SettingsChoiceOption(AdultContentFilterTarget.SUGGESTIONS, stringResource(R.string.disable_suggestions_nsfw)),
    )
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
                    SettingsMultiChoicePreference(
                        title = stringResource(R.string.disable_nsfw),
                        values = state.adultContentFilterTargets,
                        options = adultContentFilterOptions,
                        emptySelectionText = stringResource(R.string.none),
                        onValueChange = onAdultContentFilterTargetsChange,
                    )
                    SettingsSectionDivider()
                    SettingsChoicePreference(
                        title = stringResource(R.string.incognito_for_nsfw),
                        value = state.incognitoModeForNsfw,
                        options = incognitoOptions,
                        onValueChange = onIncognitoModeForNsfwChange,
                    )
                    SettingsSectionDivider()
                    SettingsActionPreference(
                        title = stringResource(R.string.blacklisted_tags),
                        summary = if (state.blacklistedTagCount == 0) {
                            stringResource(R.string.blacklisted_tags_summary)
                        } else {
                            stringResource(R.string.selected_count, state.blacklistedTagCount)
                        },
                        onClick = onGlobalTagBlacklistClick,
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
