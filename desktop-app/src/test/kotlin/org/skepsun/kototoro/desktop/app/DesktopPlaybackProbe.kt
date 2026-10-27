package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Android's playback settings over the real UI: the four rows must exist, the two choice dialogs must write the
 * preference the player reads, and the two opacity sliders must commit on release rather than on every drag pixel.
 */
internal object DesktopPlaybackProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                }
                idle()
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("playback-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.PLAYBACK_SETTINGS }
                idle()

                onNodeWithTag("playback-setting-background").assertExists()
                onNodeWithTag("playback-setting-decoder").assertExists()
                onNodeWithTag("playback-controls-alpha").performScrollTo().assertExists()
                onNodeWithTag("playback-gradient-alpha").performScrollTo().assertExists()
                check(controller.state.value.videoSettings == DesktopVideoSettings()) { "defaults: ${controller.state.value.videoSettings}" }

                // The decoder choice is what libmpv gets as `hwdec`; picking software must say so in the state.
                onNodeWithTag("playback-setting-decoder").performScrollTo().performClick()
                onNodeWithTag("playback-decoder-dialog").assertExists()
                onNodeWithTag("playback-decoder-option:SOFTWARE_PREFERRED").performClick()
                idle()
                onNodeWithTag("playback-decoder-dialog").assertDoesNotExist()
                check(controller.state.value.videoSettings.decoderMode == DesktopVideoDecoderMode.SOFTWARE_PREFERRED) {
                    "decoder: ${controller.state.value.videoSettings}"
                }

                // The backdrop list is Android's five video backgrounds: no AUTO, and DEFAULT first.
                onNodeWithTag("playback-setting-background").performScrollTo().performClick()
                onNodeWithTag("playback-background-dialog").assertExists()
                onNodeWithTag("playback-background-option:AUTO").assertDoesNotExist()
                onNodeWithTag("playback-background-option:WHITE").performClick()
                idle()
                check(controller.state.value.videoSettings.background == DesktopReaderBackground.WHITE) {
                    "background: ${controller.state.value.videoSettings}"
                }

                // The sliders commit through the row's release callback: the accessibility path sets the value and
                // finishes, which is also what a real drag's pointer-up does.
                onNodeWithTag("playback-controls-alpha").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
                idle()
                check(controller.state.value.videoSettings.controlsAlpha == 50) {
                    "controls alpha: ${controller.state.value.videoSettings}"
                }
                onNodeWithTag("playback-gradient-alpha").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
                idle()
                check(controller.state.value.videoSettings.gradientAlpha == 20) {
                    "gradient alpha: ${controller.state.value.videoSettings}"
                }

                onNodeWithTag("playback-settings-back").performClick()
                idle()
                check(controller.state.value.screen == DesktopScreen.MORE) { "the page's back returns to more" }
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
