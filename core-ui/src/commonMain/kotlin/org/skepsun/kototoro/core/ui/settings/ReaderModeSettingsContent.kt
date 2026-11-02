package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString

/** Persisted keys remain opaque: Android and desktop support different sets of reader modes and backgrounds. */
data class ReaderModeSettingsUiState(
    val mode: String,
    val detectMode: Boolean,
    val background: String,
)

data class ReaderModeSettingsIcons(
    val mode: Painter? = null,
    val detectMode: Painter? = null,
    val background: Painter? = null,
)

private enum class ReaderModeDialog { MODE, BACKGROUND }

/** Shared reading-mode group; the host owns scrolling, persistence and any device-specific display controls. */
@Composable
fun ReaderModeSettingsContent(
    state: ReaderModeSettingsUiState,
    modeOptions: List<PreferenceChoice<String>>,
    backgroundOptions: List<PreferenceChoice<String>>,
    onModeChange: (String) -> Unit,
    onDetectModeChange: (Boolean) -> Unit,
    onBackgroundChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icons: ReaderModeSettingsIcons = ReaderModeSettingsIcons(),
    title: String = uiString("reader_settings_group_reading_mode"),
    platformSettings: (@Composable ColumnScope.() -> Unit)? = null,
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = { PreferenceDialog(it) },
) {
    var dialog by remember { mutableStateOf<ReaderModeDialog?>(null) }
    Column(modifier.fillMaxWidth().testTag("reader-mode-settings-content")) {
        SettingsSectionHeader(title)
        SettingsGroup {
            SettingsValueRow(uiString("default_mode"), null,
                modeOptions.firstOrNull { it.value == state.mode }?.label,
                enabled, { dialog = ReaderModeDialog.MODE }, testTag = "reader-setting-mode",
                leadingIcon = icons.mode,
                modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
            SettingsRowDivider()
            SettingsSwitchRow(uiString("detect_reader_mode"), uiString("detect_reader_mode_summary"),
                state.detectMode, enabled, onDetectModeChange, testTag = "reader-setting-detect-mode",
                leadingIcon = icons.detectMode,
                modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
            SettingsRowDivider()
            SettingsValueRow(uiString("reader_background"), null,
                backgroundOptions.firstOrNull { it.value == state.background }?.label,
                enabled, { dialog = ReaderModeDialog.BACKGROUND }, testTag = "reader-setting-background",
                leadingIcon = icons.background,
                modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
            if (platformSettings != null) {
                SettingsRowDivider()
                platformSettings()
            }
        }
    }
    when (dialog) {
        ReaderModeDialog.MODE -> PreferenceChoiceDialog(
            title = uiString("default_mode"), selected = state.mode, options = modeOptions,
            onSelect = onModeChange, onDismiss = { dialog = null }, enabled = enabled,
            tagPrefix = "reader-mode", renderDialog = renderDialog,
        )
        ReaderModeDialog.BACKGROUND -> PreferenceChoiceDialog(
            title = uiString("reader_background"), selected = state.background, options = backgroundOptions,
            onSelect = onBackgroundChange, onDismiss = { dialog = null }, enabled = enabled,
            tagPrefix = "reader-background", renderDialog = renderDialog,
        )
        null -> Unit
    }
}

/** The novel rules entrances shared by the Android novel tab and desktop reader settings. */
@Composable
fun NovelRuleSettingsRows(
    onReplaceRulesClick: () -> Unit,
    onDictionaryRulesClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    replaceRulesIcon: Painter? = null,
    dictionaryRulesIcon: Painter? = null,
) {
    Column(modifier.fillMaxWidth()) {
        SettingsValueRow(uiString("replace_rules"), uiString("replace_rules_summary"), null,
            enabled, onReplaceRulesClick, testTag = "reader-setting-replace-rules", leadingIcon = replaceRulesIcon,
            modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
        SettingsRowDivider()
        SettingsValueRow(uiString("dictionary_rules"), uiString("dictionary_rules_summary"), null,
            enabled, onDictionaryRulesClick, testTag = "reader-setting-dictionary-rules", leadingIcon = dictionaryRulesIcon,
            modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
    }
}
