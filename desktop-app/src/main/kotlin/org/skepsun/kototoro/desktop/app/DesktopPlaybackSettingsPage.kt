package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.settings.SettingsGroup
import org.skepsun.kototoro.core.ui.settings.SettingsRowDivider
import org.skepsun.kototoro.core.ui.settings.SettingsSectionHeader
import org.skepsun.kototoro.core.ui.settings.SettingsSliderRow
import org.skepsun.kototoro.core.ui.settings.SettingsValueRow

/**
 * Android's playback settings screen (PlaybackSettingsScreen): the player's backdrop, the decoder preference, and the
 * opacity of the controls and of the fade above them.
 *
 * The rows write the same four preferences Android writes, through [DesktopController.videoSettings], and the player
 * reads them: the backdrop becomes the letterbox colour around the video, the decoder mode becomes libmpv's `hwdec`
 * pair, and the two opacities are applied to the control bar. Android's fifth row here is its AI settings entry; OCR and
 * translation are not ported yet, so the page does not offer a door to a screen that does not exist.
 */
@Composable
internal fun DesktopPlaybackSettingsPage(controller: DesktopController, state: DesktopAppState) {
    val settings = state.videoSettings
    val active = !state.busy
    var dialog by remember { mutableStateOf<PlaybackSettingsDialog?>(null) }
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedButton({ controller.more() }, enabled = active, modifier = Modifier.testTag("playback-settings-back")) {
            Text(desktopText("common.back"))
        }
        SettingsSectionHeader(AndroidStrings["playback_settings"])
        SettingsGroup {
            SettingsValueRow(AndroidStrings["video_background"], null, settings.background.title, active,
                { dialog = PlaybackSettingsDialog.BACKGROUND }, testTag = "playback-setting-background")
            SettingsRowDivider()
            SettingsValueRow(AndroidStrings["video_decoder_mode"], settings.decoderMode.summary,
                settings.decoderMode.title, active,
                { dialog = PlaybackSettingsDialog.DECODER }, testTag = "playback-setting-decoder")
            SettingsRowDivider()
            SettingsSliderRow(AndroidStrings["video_controls_alpha"], null, settings.controlsAlpha,
                DesktopVideoSettings.CONTROLS_ALPHA_RANGE, { "$it%" }, active,
                { controller.videoSettings(settings.copy(controlsAlpha = it)) },
                testTag = "playback-controls-alpha")
            SettingsRowDivider()
            SettingsSliderRow(AndroidStrings["video_gradient_alpha"], null, settings.gradientAlpha,
                DesktopVideoSettings.GRADIENT_ALPHA_RANGE, { "$it%" }, active,
                { controller.videoSettings(settings.copy(gradientAlpha = it)) },
                testTag = "playback-gradient-alpha")
        }
    }
    val open = dialog ?: return
    when (open) {
        PlaybackSettingsDialog.BACKGROUND -> DesktopChoiceSettingsDialog(
            title = AndroidStrings["video_background"], summary = null,
            bodyTag = "playback-background-dialog", optionTagPrefix = "playback-background",
            // Android's array lists exactly the five video choices, in this order.
            options = videoBackgrounds.map { it.title to it.name },
            selected = settings.background.name, enabled = active,
            onPick = { value ->
                controller.videoSettings(settings.copy(background = DesktopReaderBackground.valueOf(value)))
            },
            onDismiss = { dialog = null },
        )
        PlaybackSettingsDialog.DECODER -> DesktopChoiceSettingsDialog(
            title = AndroidStrings["video_decoder_mode"], summary = null,
            bodyTag = "playback-decoder-dialog", optionTagPrefix = "playback-decoder",
            options = DesktopVideoDecoderMode.entries.map { it.title to it.name },
            selected = settings.decoderMode.name, enabled = active,
            onPick = { value ->
                controller.videoSettings(settings.copy(decoderMode = DesktopVideoDecoderMode.valueOf(value)))
            },
            onDismiss = { dialog = null },
        )
    }
}

/** Which row's dialog is open; each row owns exactly one choice list. */
private enum class PlaybackSettingsDialog { BACKGROUND, DECODER }

/** Android's `video_backgrounds` array: the reader backgrounds minus `AUTO`, which it does not offer for video. */
private val videoBackgrounds = listOf(
    DesktopReaderBackground.DEFAULT,
    DesktopReaderBackground.LIGHT,
    DesktopReaderBackground.DARK,
    DesktopReaderBackground.WHITE,
    DesktopReaderBackground.BLACK,
)
