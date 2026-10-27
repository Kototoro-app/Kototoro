package org.skepsun.kototoro.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.text.uiString

/**
 * The playback settings page shared by Android's `PlaybackSettingsScreen` and desktop's
 * `DesktopPlaybackSettingsPage`.
 *
 * Values are the keys the hosts already persist (Android's `ReaderBackground` / `VideoDecoderMode` names); the shared
 * page does not read preferences or own player state. Choice labels come from the host because Android resolves the
 * option arrays and the desktop catalog resolves its own translations.
 */
data class PlaybackSettingsUiState(
    val background: String,
    val decoderMode: String,
    val controlsAlpha: Int,
    val gradientAlpha: Int,
)

/** Android's icons for the same rows; desktop passes none because its settings pages have no leading artwork. */
data class PlaybackSettingsRowIcons(
    val background: Painter? = null,
    val decoderMode: Painter? = null,
    val controlsAlpha: Painter? = null,
    val gradientAlpha: Painter? = null,
    val aiSettings: Painter? = null,
)

/** Android's extra door to the AI settings page; hosts without that screen leave it out. */
data class PlaybackSettingsAction(
    val title: String,
    val summary: String?,
    val onClick: () -> Unit,
)

/**
 * The preference ranges Android defines for the two opacity sliders. The desktop player also clamps its live values to
 * them, so both hosts keep one definition instead of copying the numbers.
 */
object PlaybackSettingsRanges {
    val controlsAlpha: IntRange = 30..100
    val gradientAlpha: IntRange = 0..100
}

private enum class PlaybackDialog { BACKGROUND, DECODER }

@Composable
fun PlaybackSettingsContent(
    state: PlaybackSettingsUiState,
    backgroundOptions: List<PreferenceChoice<String>>,
    decoderOptions: List<PreferenceChoice<String>>,
    onBackgroundChange: (String) -> Unit,
    onDecoderChange: (String) -> Unit,
    onControlsAlphaChange: (Int) -> Unit,
    onGradientAlphaChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icons: PlaybackSettingsRowIcons = PlaybackSettingsRowIcons(),
    aiSettings: PlaybackSettingsAction? = null,
    renderDialog: @Composable (PreferenceDialogContent) -> Unit = { PreferenceDialog(it) },
) {
    var dialog by remember { mutableStateOf<PlaybackDialog?>(null) }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("playback-settings-content"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSectionHeader(uiString("playback_settings"))
        SettingsGroup {
            SettingsValueRow(uiString("video_background"), null,
                backgroundOptions.firstOrNull { it.value == state.background }?.label, enabled,
                { dialog = PlaybackDialog.BACKGROUND }, testTag = "playback-setting-background",
                leadingIcon = icons.background)
            SettingsRowDivider()
            SettingsValueRow(uiString("video_decoder_mode"), null,
                decoderOptions.firstOrNull { it.value == state.decoderMode }?.label, enabled,
                { dialog = PlaybackDialog.DECODER }, testTag = "playback-setting-decoder",
                leadingIcon = icons.decoderMode)
            if (aiSettings != null) {
                SettingsRowDivider()
                SettingsValueRow(aiSettings.title, aiSettings.summary, null, enabled, aiSettings.onClick,
                    testTag = "playback-ai-settings", leadingIcon = icons.aiSettings)
            }
            SettingsRowDivider()
            SettingsSliderRow(uiString("video_controls_alpha"), null, state.controlsAlpha,
                PlaybackSettingsRanges.controlsAlpha, { "$it%" }, enabled,
                { onControlsAlphaChange(it) }, testTag = "playback-controls-alpha",
                leadingIcon = icons.controlsAlpha)
            SettingsRowDivider()
            SettingsSliderRow(uiString("video_gradient_alpha"), null, state.gradientAlpha,
                PlaybackSettingsRanges.gradientAlpha, { "$it%" }, enabled,
                { onGradientAlphaChange(it) }, testTag = "playback-gradient-alpha",
                leadingIcon = icons.gradientAlpha)
        }
    }
    when (dialog) {
        PlaybackDialog.BACKGROUND -> PreferenceChoiceDialog(
            title = uiString("video_background"), selected = state.background,
            options = backgroundOptions, onSelect = onBackgroundChange,
            onDismiss = { dialog = null }, enabled = enabled,
            tagPrefix = "playback-background", renderDialog = renderDialog,
        )
        PlaybackDialog.DECODER -> PreferenceChoiceDialog(
            title = uiString("video_decoder_mode"), selected = state.decoderMode,
            options = decoderOptions, onSelect = onDecoderChange,
            onDismiss = { dialog = null }, enabled = enabled,
            tagPrefix = "playback-decoder", renderDialog = renderDialog,
        )
        null -> Unit
    }
}
