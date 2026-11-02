package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.settings.PlaybackSettingsContent
import org.skepsun.kototoro.core.ui.settings.PlaybackSettingsUiState
import org.skepsun.kototoro.core.ui.settings.PreferenceChoice

/**
 * Desktop only projects the player state and writes Android's four playback preferences; the page, its choice lists and
 * the two opacity sliders come from the shared [PlaybackSettingsContent].
 *
 * The rows write through [DesktopController.videoSettings], and the player reads them: the backdrop becomes the
 * letterbox colour around the video, the decoder mode becomes libmpv's `hwdec` pair, and the two opacities are applied
 * to the control bar. Android's AI settings row is left out because OCR and translation are not ported yet.
 */
@Composable
internal fun DesktopPlaybackSettingsPage(controller: DesktopController, state: DesktopAppState) {
    val settings = state.videoSettings
    val active = !state.busy
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
        PlaybackSettingsContent(
            state = PlaybackSettingsUiState(
                background = settings.background.name,
                decoderMode = settings.decoderMode.name,
                controlsAlpha = settings.controlsAlpha,
                gradientAlpha = settings.gradientAlpha,
            ),
            backgroundOptions = videoBackgrounds.map { PreferenceChoice(it.name, it.title) },
            decoderOptions = DesktopVideoDecoderMode.entries.map { PreferenceChoice(it.name, it.title) },
            onBackgroundChange = {
                controller.videoSettings(settings.copy(background = DesktopReaderBackground.valueOf(it)))
            },
            onDecoderChange = {
                controller.videoSettings(settings.copy(decoderMode = DesktopVideoDecoderMode.valueOf(it)))
            },
            onControlsAlphaChange = { controller.videoSettings(settings.copy(controlsAlpha = it)) },
            onGradientAlphaChange = { controller.videoSettings(settings.copy(gradientAlpha = it)) },
            enabled = active,
        )
    }
}

/** Android's `video_backgrounds` array: the reader backgrounds minus `AUTO`, which it does not offer for video. */
private val videoBackgrounds = listOf(
    DesktopReaderBackground.DEFAULT,
    DesktopReaderBackground.LIGHT,
    DesktopReaderBackground.DARK,
    DesktopReaderBackground.WHITE,
    DesktopReaderBackground.BLACK,
)
