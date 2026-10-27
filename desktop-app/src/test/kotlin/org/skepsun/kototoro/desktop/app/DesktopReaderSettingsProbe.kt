package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.reader.core.ZoomMode
import java.nio.file.Path

/** Exercises the real page separately from the reader so failures are attributable to settings or persistence. */
internal object DesktopReaderSettingsProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            if (args[0] == "reader-settings-read") {
                checkSettings(controller)
                val stored = session.storage.preferences.open("desktop_reader").snapshot()
                check((stored["mode"] as? SourcePreferenceValue.Text)?.value == "DOUBLE")
                check((stored["background"] as? SourcePreferenceValue.Text)?.value == "BLACK")
                check((stored["reader_mode_detect"] as? SourcePreferenceValue.Toggle)?.value == false)
                check((stored["crop_paged"] as? SourcePreferenceValue.Toggle)?.value == true)
                check((stored["crop_continuous"] as? SourcePreferenceValue.Toggle)?.value == false)
                check((stored["animation"] as? SourcePreferenceValue.Text)?.value == "NONE")
                check((stored["reader_chapter_title_bottom"] as? SourcePreferenceValue.Toggle)?.value == true)
                check((stored["page_numbers"] as? SourcePreferenceValue.Toggle)?.value == true)
            } else {
                runDesktopComposeUiTest(width = 1260, height = 850) {
                    setContent { DesktopApp(controller) }
                    fun idle() {
                        waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                        waitForIdle()
                    }
                    idle()
                    onNodeWithTag("nav:更多").performClick(); idle()
                    onNodeWithTag("reader-settings-open").performScrollTo().performClick(); idle()
                    check(controller.state.value.screen == DesktopScreen.READER_SETTINGS)
                    val initialMode = controller.state.value.readerSettings.mode
                    onNodeWithTag("reader-setting-mode").performScrollTo().performClick()
                    onNodeWithTag("reader-mode-close").performClick()
                    check(controller.state.value.readerSettings.mode == initialMode)
                    onNodeWithTag("reader-setting-mode").performClick()
                    onNodeWithTag("reader-mode-option:DOUBLE").performClick(); idle()
                    onNodeWithTag("reader-setting-background").performScrollTo().performClick()
                    onNodeWithTag("reader-background-option:BLACK").performClick(); idle()
                    onNodeWithTag("reader-setting-detect-mode").performScrollTo().performClick(); idle()
                    // These host-specific editors must keep their behavior when the shared group moves above them.
                    onNodeWithTag("reader-setting-direction").performScrollTo().performClick()
                    onNodeWithTag("reader-direction:RTL").performClick(); idle()
                    onNodeWithTag("reader-setting-fit").performScrollTo().performClick()
                    onNodeWithTag("reader-fit-option:FIT_WIDTH").performClick(); idle()
                    onNodeWithTag("reader-setting-animation").performScrollTo().performClick()
                    onNodeWithTag("reader-animation-option:NONE").performClick(); idle()
                    onNodeWithTag("reader-setting-crop").performScrollTo().assertIsOff().performClick(); idle()
                    check(controller.state.value.readerSettings.cropPaged)
                    fun changeMode(mode: String) {
                        onNodeWithTag("reader-setting-mode").performScrollTo().performClick()
                        onNodeWithTag("reader-mode-option:$mode").performClick(); idle()
                    }
                    onNodeWithTag("reader-webtoon-settings-content").assertDoesNotExist()
                    changeMode("CONTINUOUS")
                    onNodeWithTag("reader-setting-webtoon-gaps").performScrollTo().assertIsOff().performClick(); idle()
                    onNodeWithTag("reader-setting-animation").performScrollTo().assertIsNotEnabled()
                    onNodeWithTag("reader-setting-crop").performScrollTo().assertIsOff().performClick(); idle()
                    check(controller.state.value.readerSettings.cropPaged)
                    check(controller.state.value.readerSettings.cropContinuous)
                    changeMode("CONTINUOUS_HORIZONTAL")
                    onNodeWithTag("reader-webtoon-settings-content").assertDoesNotExist()
                    onNodeWithTag("reader-setting-animation").performScrollTo().assertIsNotEnabled()
                    onNodeWithTag("reader-setting-crop").performScrollTo().assertIsOn().performClick(); idle()
                    check(!controller.state.value.readerSettings.cropContinuous)
                    changeMode("DOUBLE")
                    onNodeWithTag("reader-setting-crop").performScrollTo().assertIsOn()
                    onNodeWithTag("reader-setting-chapter-title-bottom").performScrollTo().assertIsOff().performClick(); idle()
                    onNodeWithTag("reader-setting-page-numbers").performScrollTo().assertIsOff().performClick(); idle()
                    onNodeWithTag("reader-setting-reduce-preloading").performScrollTo().assertIsOff().performClick(); idle()
                    onNodeWithTag("reader-setting-speed-slider").performScrollTo()
                        .performSemanticsAction(SemanticsActions.SetProgress) { it(.65f) }; idle()
                    checkSettings(controller)
                    onNodeWithTag("reader-setting-replace-rules").performScrollTo().performClick(); idle()
                    check(controller.state.value.screen == DesktopScreen.REPLACE_RULES)
                    controller.showReaderSettings(); idle()
                    onNodeWithTag("reader-setting-dictionary-rules").performScrollTo().performClick(); idle()
                    check(controller.state.value.screen == DesktopScreen.DICTIONARY_RULES)
                    controller.showReaderSettings(); idle()
                    onNodeWithTag("reader-settings-back").performScrollTo().performClick(); idle()
                    check(controller.state.value.screen == DesktopScreen.MORE)
                    checkSettings(controller)
                }
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    private fun checkSettings(controller: DesktopController) {
        val settings = controller.state.value.readerSettings
        check(settings.mode == DesktopReaderMode.DOUBLE)
        check(settings.background == DesktopReaderBackground.BLACK)
        check(!settings.readerModeDetection)
        check(settings.rightToLeft && !settings.vertical)
        check(settings.fitMode == ZoomMode.FIT_WIDTH)
        check(settings.animation == ReaderAnimation.NONE)
        check(settings.chapterTitleAtBottom && settings.pageNumbers)
        check(settings.webtoonGaps && settings.reducePagePreloading)
        check(kotlin.math.abs(settings.autoScrollSpeed - .65f) < .001f)
        check(settings.cropPaged && !settings.cropContinuous)
    }
}
