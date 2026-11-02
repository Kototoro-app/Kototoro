package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString

/** Platform enums and persistence remain in the hosts; null switches represent unavailable capabilities. */
data class SourcesSettingsContentState(
    val sortOrder: String,
    val customSort: Boolean,
    val gridMode: Boolean?,
    val groupByLanguage: Boolean,
    val showBrokenSources: Boolean,
    val installBehaviorSummary: String,
    val incognitoMode: String,
    val blacklistedTagCount: Int,
    val tagsWarnings: Boolean,
    val mirrorSwitching: Boolean? = null,
    val handleLinks: Boolean? = null,
)

data class SourcesSettingsTitles(val overview: String, val remote: String, val adult: String, val more: String)

data class SourcesSettingsIcons(
    val sort: Painter? = null,
    val reorder: Painter? = null,
    val grid: Painter? = null,
    val language: Painter? = null,
    val wizard: Painter? = null,
    val broken: Painter? = null,
    val install: Painter? = null,
    val incognito: Painter? = null,
    val blacklist: Painter? = null,
    val warnings: Painter? = null,
    val mirror: Painter? = null,
    val links: Painter? = null,
)

private enum class SourcesChoiceDialog { SORT, INCOGNITO }

/** Shared page and common editors; hosts retain different priority/filter commit semantics. */
@Composable
fun SourcesSettingsContent(
    state: SourcesSettingsContentState,
    titles: SourcesSettingsTitles,
    sortOptions: List<PreferenceChoice<String>>,
    incognitoOptions: List<PreferenceChoice<String>>,
    onSortChange: (String) -> Unit,
    onGridChange: (Boolean) -> Unit,
    onGroupByLanguageChange: (Boolean) -> Unit,
    onReorderClick: () -> Unit,
    onWizardClick: () -> Unit,
    onShowBrokenChange: (Boolean) -> Unit,
    onInstallBehaviorClick: () -> Unit,
    onIncognitoChange: (String) -> Unit,
    onBlacklistClick: () -> Unit,
    onTagsWarningsChange: (Boolean) -> Unit,
    jarPriorityEditor: @Composable () -> Unit,
    adultFilterEditor: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icons: SourcesSettingsIcons = SourcesSettingsIcons(),
    reorderSummary: String = uiString("reorder_hint"),
    wizardSummary: String = uiString("setup_wizard_summary"),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onMirrorChange: (Boolean) -> Unit = {},
    onHandleLinksChange: (Boolean) -> Unit = {},
    adultNote: String? = null,
    moreNote: String? = null,
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = { PreferenceDialog(it) },
) {
    var dialog by remember { mutableStateOf<SourcesChoiceDialog?>(null) }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(contentPadding)
            .testTag("sources-settings-content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            SettingsSectionHeader(titles.overview)
            SettingsGroup {
                SettingsValueRow(uiString("sort_order"), null,
                    sortOptions.firstOrNull { it.value == state.sortOrder }?.label, enabled,
                    { dialog = SourcesChoiceDialog.SORT }, testTag = "source-setting-sort", leadingIcon = icons.sort,
                    modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
                if (state.customSort) {
                    SettingsRowDivider()
                    SettingsValueRow(uiString("reorder"), reorderSummary, null, enabled, onReorderClick,
                        testTag = "source-setting-reorder", leadingIcon = icons.reorder,
                        modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
                }
                SettingsRowDivider()
                if (state.gridMode == null) {
                    SettingsInfoRow(uiString("show_in_grid_view"), uiString("browse_display_options_summary"),
                        Modifier.testTag("source-setting-grid"))
                } else {
                    SettingsSwitchRow(uiString("show_in_grid_view"), null, state.gridMode, enabled, onGridChange,
                        testTag = "source-setting-grid", leadingIcon = icons.grid,
                        modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
                }
                SettingsRowDivider()
                SettingsSwitchRow(uiString("group_sources_by_language"), uiString("group_sources_by_language_summary"),
                    state.groupByLanguage, enabled, onGroupByLanguageChange,
                    testTag = "source-setting-group-language", leadingIcon = icons.language,
                    modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
                SettingsRowDivider()
                SettingsValueRow(uiString("setup_wizard"), wizardSummary, null, enabled,
                    onWizardClick, testTag = "source-setting-wizard", leadingIcon = icons.wizard,
                    modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
            }
        }
        Column {
            SettingsSectionHeader(titles.remote)
            SettingsGroup {
                jarPriorityEditor()
                SettingsRowDivider()
                SettingsSwitchRow(uiString("show_broken_sources"), uiString("show_broken_sources_summary"),
                    state.showBrokenSources, enabled, onShowBrokenChange,
                    testTag = "source-setting-show-broken", leadingIcon = icons.broken,
                    modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
                SettingsRowDivider()
                SettingsValueRow(uiString("extension_install_behavior"), state.installBehaviorSummary, null, enabled,
                    onInstallBehaviorClick, testTag = "source-setting-install-behavior", leadingIcon = icons.install,
                    modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
            }
        }
        Column {
            SettingsSectionHeader(titles.adult)
            SettingsGroup {
                adultFilterEditor()
                SettingsRowDivider()
                SettingsValueRow(uiString("incognito_for_nsfw"), null,
                    incognitoOptions.firstOrNull { it.value == state.incognitoMode }?.label, enabled,
                    { dialog = SourcesChoiceDialog.INCOGNITO }, testTag = "source-setting-incognito-nsfw",
                    leadingIcon = icons.incognito,
                    modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
                SettingsRowDivider()
                SettingsValueRow(uiString("blacklisted_tags"),
                    if (state.blacklistedTagCount == 0) uiString("blacklisted_tags_summary")
                    else uiString("selected_count", state.blacklistedTagCount),
                    null, enabled, onBlacklistClick, testTag = "source-setting-tag-blacklist",
                    leadingIcon = icons.blacklist,
                    modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
                if (adultNote != null) {
                    SettingsRowDivider()
                    SettingsInfoRow(adultNote, null)
                }
            }
        }
        Column {
            SettingsSectionHeader(titles.more)
            SettingsGroup {
                SettingsSwitchRow(uiString("tags_warnings"), uiString("tags_warnings_summary"), state.tagsWarnings,
                    enabled, onTagsWarningsChange, testTag = "source-setting-tags-warnings", leadingIcon = icons.warnings,
                    modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
                state.mirrorSwitching?.let {
                    SettingsRowDivider()
                    SettingsSwitchRow(uiString("mirror_switching"), uiString("mirror_switching_summary"), it,
                        enabled, onMirrorChange, testTag = "source-setting-mirror", leadingIcon = icons.mirror,
                        modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
                }
                state.handleLinks?.let {
                    SettingsRowDivider()
                    SettingsSwitchRow(uiString("handle_links"), uiString("handle_links_summary"), it,
                        enabled, onHandleLinksChange, testTag = "source-setting-links", leadingIcon = icons.links,
                        modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
                }
                if (moreNote != null) {
                    SettingsRowDivider()
                    SettingsInfoRow(moreNote, null)
                }
            }
        }
    }
    when (dialog) {
        SourcesChoiceDialog.SORT -> PreferenceChoiceDialog(uiString("sort_order"), state.sortOrder, sortOptions,
            onSortChange, { dialog = null }, enabled, tagPrefix = "source-sort", renderDialog = renderDialog)
        SourcesChoiceDialog.INCOGNITO -> PreferenceChoiceDialog(uiString("incognito_for_nsfw"), state.incognitoMode,
            incognitoOptions, onIncognitoChange, { dialog = null }, enabled,
            tagPrefix = "source-incognito", renderDialog = renderDialog)
        null -> Unit
    }
}
