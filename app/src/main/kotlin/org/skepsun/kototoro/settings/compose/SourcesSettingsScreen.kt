package org.skepsun.kototoro.settings.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.settings.SourcesSettingsContent
import org.skepsun.kototoro.core.ui.settings.SourcesSettingsContentState
import org.skepsun.kototoro.core.ui.settings.SourcesSettingsTitles
import org.skepsun.kototoro.core.ui.settings.SourcesSettingsIcons
import org.skepsun.kototoro.core.ui.settings.PreferenceChoice
import org.skepsun.kototoro.core.prefs.TriStateOption
import org.skepsun.kototoro.core.ui.compose.KototoroSnackbarHost
import org.skepsun.kototoro.explore.data.SourcesSortOrder
import org.skepsun.kototoro.extensions.install.ExtensionInstallPolicy

data class ExtensionInstallBehaviorItem(
    val type: String,
    val title: String,
    val iconRes: Int,
    val policy: ExtensionInstallPolicy,
)

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
    val extensionInstallBehaviors: List<ExtensionInstallBehaviorItem>,
)

enum class AdultContentFilterTarget {
    SOURCES_AND_BROWSE,
    HISTORY,
    FAVOURITES,
    FEED,
    UPDATES,
    SUGGESTIONS,
    BOOKMARKS,
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
    extensionInstallPolicyOptions: List<SettingsChoiceOption<ExtensionInstallPolicy>>,
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
    onExtensionInstallPolicyChange: (String, ExtensionInstallPolicy) -> Unit,
    onBatchSetExtensionInstallPolicy: (ExtensionInstallPolicy) -> Unit,
    onReorderSourcesClick: () -> Unit = {},
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
        SettingsChoiceOption(AdultContentFilterTarget.BOOKMARKS, stringResource(R.string.disable_bookmarks_nsfw)),
    )
    Scaffold(
        snackbarHost = {
            KototoroSnackbarHost(hostState = snackbarHostState)
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        var isExtensionInstallSheetVisible by rememberSaveable { mutableStateOf(false) }
        val extensionInstallBehaviorSummary = remember(state.extensionInstallBehaviors, extensionInstallPolicyOptions) {
            val distinctPolicies = state.extensionInstallBehaviors.map { it.policy }.distinct()
            if (distinctPolicies.size == 1) {
                extensionInstallPolicyOptions.firstOrNull { it.value == distinctPolicies.first() }?.label
            } else {
                null
            }
        } ?: stringResource(R.string.custom)

        SourcesSettingsContent(
            state = SourcesSettingsContentState(
                sortOrder = state.sourcesSortOrder.name,
                customSort = state.sourcesSortOrder == SourcesSortOrder.MANUAL,
                gridMode = null,
                groupByLanguage = state.isSourcesGroupedByLanguage,
                showBrokenSources = state.isShowBrokenSources,
                installBehaviorSummary = extensionInstallBehaviorSummary,
                incognitoMode = state.incognitoModeForNsfw.name,
                blacklistedTagCount = state.blacklistedTagCount,
                tagsWarnings = state.isTagsWarningsEnabled,
                mirrorSwitching = state.isMirrorSwitchingEnabled,
                handleLinks = state.isHandleLinksEnabled,
            ),
            titles = SourcesSettingsTitles(overviewTitle, remoteSourcesTitle, adultFilteringTitle, moreTitle),
            sortOptions = sortOrderOptions.map { PreferenceChoice(it.value.name, it.label) },
            incognitoOptions = incognitoOptions.map { PreferenceChoice(it.value.name, it.label) },
            onSortChange = { onSourcesSortOrderChange(SourcesSortOrder.valueOf(it)) },
            onGridChange = onSourcesGridModeChange,
            onGroupByLanguageChange = onSourcesGroupedByLanguageChange,
            onReorderClick = onReorderSourcesClick,
            onWizardClick = onSetupWizardClick,
            onShowBrokenChange = onShowBrokenSourcesChange,
            onInstallBehaviorClick = { isExtensionInstallSheetVisible = true },
            onIncognitoChange = { onIncognitoModeForNsfwChange(TriStateOption.valueOf(it)) },
            onBlacklistClick = onGlobalTagBlacklistClick,
            onTagsWarningsChange = onTagsWarningsEnabledChange,
            onMirrorChange = onMirrorSwitchingChange,
            onHandleLinksChange = onHandleLinksEnabledChange,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = SettingsContentHorizontalPadding,
                end = SettingsContentHorizontalPadding,
                top = settingsContentTopInset(),
                bottom = innerPadding.calculateBottomPadding() +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            ),
            icons = SourcesSettingsIcons(
                sort = painterResource(R.drawable.ic_sort),
                reorder = painterResource(R.drawable.ic_reorder_handle),
                grid = painterResource(R.drawable.ic_grid),
                language = painterResource(R.drawable.ic_language),
                wizard = painterResource(R.drawable.ic_welcome),
                broken = painterResource(R.drawable.ic_error_small),
                install = painterResource(R.drawable.ic_extension),
                incognito = painterResource(R.drawable.ic_incognito),
                blacklist = painterResource(R.drawable.ic_tag),
                warnings = painterResource(R.drawable.ic_alert_outline),
                mirror = painterResource(R.drawable.ic_swap_vert),
                links = painterResource(R.drawable.ic_open_external),
            ),
            jarPriorityEditor = {
                SettingsReorderPreference(
                    title = stringResource(R.string.jar_priority_order_title),
                    iconRes = R.drawable.ic_tap_reorder,
                    value = state.jarPriorityOrder,
                    summary = stringResource(R.string.jar_priority_order_summary),
                    emptyValueText = stringResource(R.string.not_specified),
                    onValueChange = onJarPriorityOrderChange,
                )
            },
            adultFilterEditor = {
                SettingsMultiChoicePreference(
                    title = stringResource(R.string.disable_nsfw),
                    iconRes = R.drawable.ic_nsfw,
                    values = state.adultContentFilterTargets,
                    options = adultContentFilterOptions,
                    emptySelectionText = stringResource(R.string.none),
                    onValueChange = onAdultContentFilterTargetsChange,
                )
            },
            renderDialog = { AndroidPreferenceDialog(it) },
        )

        if (isExtensionInstallSheetVisible) {
            ExtensionInstallBehaviorSheet(
                behaviors = state.extensionInstallBehaviors,
                policyOptions = extensionInstallPolicyOptions,
                onPolicyChange = onExtensionInstallPolicyChange,
                onBatchSetPolicy = onBatchSetExtensionInstallPolicy,
                onDismissRequest = { isExtensionInstallSheetVisible = false },
            )
        }
    }
}
