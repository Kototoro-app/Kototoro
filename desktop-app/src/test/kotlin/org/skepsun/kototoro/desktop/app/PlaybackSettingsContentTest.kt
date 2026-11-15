package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import org.skepsun.kototoro.core.ui.settings.LocalSettingsSearchTarget
import org.skepsun.kototoro.core.ui.settings.SettingsSearchTarget
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.ui.settings.PlaybackSettingsAction
import org.skepsun.kototoro.core.ui.settings.PlaybackSettingsContent
import org.skepsun.kototoro.core.ui.settings.PlaybackSettingsUiState
import org.skepsun.kototoro.core.ui.settings.PreferenceChoice

/** The playback page Android and desktop now share: choice writes, the optional AI door and the two sliders. */
@OptIn(ExperimentalTestApi::class)
class PlaybackSettingsContentTest {
    private val backgrounds = listOf(
        PreferenceChoice("DEFAULT", "Default"),
        PreferenceChoice("WHITE", "White"),
    )
    private val decoders = listOf(
        PreferenceChoice("HARDWARE_PREFERRED", "Hardware preferred"),
        PreferenceChoice("SOFTWARE_PREFERRED", "Software preferred"),
    )

    @Test
    fun `search reveals advanced rows and a repeated search reopens the group without changing values`() =
        runDesktopComposeUiTest(width = 390, height = 640) {
            var target by mutableStateOf<SettingsSearchTarget?>(null)
            var writes = 0
            setContent {
                DesktopTheme {
                    CompositionLocalProvider(LocalSettingsSearchTarget provides target) {
                        PlaybackSettingsContent(
                            state = PlaybackSettingsUiState("DEFAULT", "HARDWARE_PREFERRED", 90, 70),
                            backgroundOptions = backgrounds,
                            decoderOptions = decoders,
                            onBackgroundChange = { writes++ }, onDecoderChange = { writes++ },
                            onControlsAlphaChange = { writes++ }, onGradientAlphaChange = { writes++ },
                            contentPadding = PaddingValues(top = 500.dp),
                        )
                    }
                }
            }
            onNodeWithTag("playback-setting-decoder").assertDoesNotExist()
            runOnIdle { target = SettingsSearchTarget("video_decoder_mode", 1) }
            onNodeWithTag("playback-setting-decoder").assertIsDisplayed()
            onNodeWithText(AndroidStrings["playback_more_settings"]).performScrollTo().performClick()
            onNodeWithTag("playback-setting-decoder").assertDoesNotExist()
            runOnIdle { target = SettingsSearchTarget("video_decoder_mode", 2) }
            onNodeWithTag("playback-setting-decoder").assertIsDisplayed()
            runOnIdle { target = SettingsSearchTarget("video_gradient_alpha", 3) }
            onNodeWithTag("playback-gradient-alpha").assertIsDisplayed()
            runOnIdle { assertEquals(0, writes) }
        }

    @Test
    fun `choice rows write host keys and the optional AI row stays host supplied`() =
        runDesktopComposeUiTest(width = 760, height = 800) {
            var state by mutableStateOf(PlaybackSettingsUiState("DEFAULT", "HARDWARE_PREFERRED", 90, 70))
            var aiClicks = 0
            setContent {
                DesktopTheme {
                    PlaybackSettingsContent(
                        state = state,
                        backgroundOptions = backgrounds,
                        decoderOptions = decoders,
                        onBackgroundChange = { state = state.copy(background = it) },
                        onDecoderChange = { state = state.copy(decoderMode = it) },
                        onControlsAlphaChange = { state = state.copy(controlsAlpha = it) },
                        onGradientAlphaChange = { state = state.copy(gradientAlpha = it) },
                        aiSettings = PlaybackSettingsAction("AI settings", "open AI") { aiClicks++ },
                    )
                }
            }
            onNodeWithTag("playback-settings-content").assertExists()
            onNodeWithTag("playback-setting-background").performClick()
            onNodeWithTag("playback-background-option:WHITE").performClick()
            onNodeWithTag("playback-background-dialog").assertDoesNotExist()
            runOnIdle { assertEquals("WHITE", state.background) }

            onNodeWithText(AndroidStrings["playback_more_settings"]).performScrollTo().performClick()
            onNodeWithTag("playback-setting-decoder").performClick()
            onNodeWithTag("playback-decoder-option:SOFTWARE_PREFERRED").performClick()
            runOnIdle { assertEquals("SOFTWARE_PREFERRED", state.decoderMode) }

            onNodeWithTag("playback-ai-settings").performScrollTo().performClick()
            runOnIdle { assertEquals(1, aiClicks) }

            onNodeWithTag("playback-controls-alpha").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
            runOnIdle { assertEquals(50, state.controlsAlpha) }

            onNodeWithTag("playback-gradient-alpha").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
            runOnIdle { assertEquals(20, state.gradientAlpha) }
        }

    @Test
    fun `sliders clamp to the shared ranges and a disabled page disables its rows`() =
        runDesktopComposeUiTest(width = 760, height = 800) {
            var state by mutableStateOf(PlaybackSettingsUiState("DEFAULT", "HARDWARE_PREFERRED", 90, 70))
            var enabled by mutableStateOf(true)
            setContent {
                DesktopTheme {
                    PlaybackSettingsContent(
                        state = state,
                        backgroundOptions = backgrounds,
                        decoderOptions = decoders,
                        onBackgroundChange = {},
                        onDecoderChange = {},
                        onControlsAlphaChange = { state = state.copy(controlsAlpha = it) },
                        onGradientAlphaChange = { state = state.copy(gradientAlpha = it) },
                        enabled = enabled,
                    )
                }
            }
            onNodeWithTag("playback-ai-settings").assertDoesNotExist()
            onNodeWithText(AndroidStrings["playback_more_settings"]).performScrollTo().performClick()
            onNodeWithTag("playback-controls-alpha").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(10f) }
            runOnIdle { assertEquals(30, state.controlsAlpha) }

            runOnIdle { enabled = false }
            onNodeWithTag("playback-setting-background").assertIsNotEnabled()
            onNodeWithTag("playback-setting-decoder").assertIsNotEnabled()
        }
}
