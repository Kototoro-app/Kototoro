package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.core.ui.text.uiString

/** Different crop editors keep their original storage and commit semantics in the host. */
sealed interface ReaderCropSetting {
    data class CurrentMode(val checked: Boolean, val onChange: (Boolean) -> Unit) : ReaderCropSetting
    data class ByReaderType(
        val selected: Set<String>,
        val options: List<PreferenceChoice<String>>,
        val onChange: (Set<String>) -> Unit,
    ) : ReaderCropSetting
}

data class ReaderZoomSettingsUiState(
    val fitMode: String,
    val animation: String,
    val animationEnabled: Boolean = true,
)

data class ReaderZoomSettingsIcons(
    val fit: Painter? = null,
    val crop: Painter? = null,
    val animation: Painter? = null,
)

private enum class ReaderZoomDialog { FIT, CROP, ANIMATION }

/** Shared zoom/crop/animation group. Device-specific zoom and gesture controls arrive as optional rows. */
@Composable
fun ReaderZoomSettingsContent(
    state: ReaderZoomSettingsUiState,
    fitOptions: List<PreferenceChoice<String>>,
    animationOptions: List<PreferenceChoice<String>>,
    crop: ReaderCropSetting,
    onFitChange: (String) -> Unit,
    onAnimationChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icons: ReaderZoomSettingsIcons = ReaderZoomSettingsIcons(),
    platformZoomControls: (@Composable ColumnScope.() -> Unit)? = null,
    platformScrollControls: (@Composable ColumnScope.() -> Unit)? = null,
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = { PreferenceDialog(it) },
) {
    var dialog by remember { mutableStateOf<ReaderZoomDialog?>(null) }
    Column(modifier.fillMaxWidth().testTag("reader-zoom-settings-content")) {
        SettingsSectionHeader(uiString("reader_settings_group_zoom_scroll"))
        SettingsGroup {
            SettingsValueRow(uiString("scale_mode"), null,
                fitOptions.firstOrNull { it.value == state.fitMode }?.label,
                enabled, { dialog = ReaderZoomDialog.FIT }, testTag = "reader-setting-fit", leadingIcon = icons.fit,
                modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
            if (platformZoomControls != null) {
                SettingsRowDivider()
                platformZoomControls()
            }
            SettingsRowDivider()
            when (crop) {
                is ReaderCropSetting.CurrentMode -> SettingsSwitchRow(
                    uiString("crop_pages"), null, crop.checked, enabled, crop.onChange,
                    testTag = "reader-setting-crop", leadingIcon = icons.crop,
                    modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
                is ReaderCropSetting.ByReaderType -> SettingsValueRow(
                    uiString("crop_pages"), null,
                    crop.options.filter { it.value in crop.selected }.joinToString { it.label }
                        .ifBlank { uiString("none") },
                    enabled, { dialog = ReaderZoomDialog.CROP },
                    testTag = "reader-setting-crop", leadingIcon = icons.crop,
                    modifier = Modifier.tvFocusable(enabled = enabled, addFocusTarget = false))
            }
            SettingsRowDivider()
            SettingsValueRow(uiString("pages_animation"), null,
                animationOptions.firstOrNull { it.value == state.animation }?.label,
                enabled && state.animationEnabled, { dialog = ReaderZoomDialog.ANIMATION },
                testTag = "reader-setting-animation", leadingIcon = icons.animation,
                modifier = Modifier.tvFocusable(enabled = enabled && state.animationEnabled, addFocusTarget = false))
            if (platformScrollControls != null) {
                SettingsRowDivider()
                platformScrollControls()
            }
        }
    }
    when (dialog) {
        ReaderZoomDialog.FIT -> PreferenceChoiceDialog(
            title = uiString("scale_mode"), selected = state.fitMode, options = fitOptions,
            onSelect = onFitChange, onDismiss = { dialog = null }, enabled = enabled,
            tagPrefix = "reader-fit", renderDialog = renderDialog)
        ReaderZoomDialog.ANIMATION -> PreferenceChoiceDialog(
            title = uiString("pages_animation"), selected = state.animation, options = animationOptions,
            onSelect = onAnimationChange, onDismiss = { dialog = null }, enabled = enabled && state.animationEnabled,
            tagPrefix = "reader-animation", renderDialog = renderDialog)
        ReaderZoomDialog.CROP -> if (crop is ReaderCropSetting.ByReaderType) {
            PreferenceMultiChoiceDialog(
                title = uiString("crop_pages"), selected = crop.selected, options = crop.options,
                onValueChange = crop.onChange, onDismiss = { dialog = null }, enabled = enabled,
                tagPrefix = "reader-crop", renderDialog = renderDialog,
            )
        }
        null -> Unit
    }
}
