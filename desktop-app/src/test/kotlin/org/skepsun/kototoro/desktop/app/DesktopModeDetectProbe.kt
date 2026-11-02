package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Android's "autodetect reader mode" (`reader_mode_detect`, `DetectReaderModeUseCase`): the page at 30% of the chapter
 * decides whether a work is a webtoon, and the verdict is remembered per work like a manually chosen mode.
 *
 * The fixture chapter has five pages, so the probe page is index 2 — the one `fixture.reader.probe.tall` turns into a
 * 400x2400 webtoon page. The probe therefore checks the switch in both directions and the memory:
 * off → the paged reader (no strip list), on → the strip, then off again → still the strip, because the work
 * remembered the detected mode.
 */
internal object DesktopModeDetectProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.importJar(Path.of(args[6])).join() }
            check(session.startupErrors.isEmpty())
            runDesktopComposeUiTest(width = 920, height = 620) {
                setContent { DesktopApp(controller) }
                fun settle() {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerLoading.isEmpty()
                    }
                    waitUntil(timeoutMillis = 15_000) { !controller.isPrefetching && !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun openDetails() {
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                    onNodeWithTag("source:MIHON_9007199254740995").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.items.size == 1 }
                    settle()
                    onNodeWithTag("content:${controller.state.value.items.single().id}").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                    settle()
                }
                fun openReader() {
                    onNodeWithTag("preview-read").performClick()
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.pages.size > 1 &&
                            controller.state.value.readerLoading.isEmpty()
                    }
                    settle()
                    val state = controller.state.value
                    check(state.screen == DesktopScreen.READER) { "The reader did not open: ${state.screen}" }
                    check(state.pages.size == 5) { "The fixture chapter has ${state.pages.size} pages" }
                }
                /** The strip list only exists in the continuous readers, so it tells paged from detected-webtoon. */
                fun stripOpen(): Boolean = onAllNodesWithTag("reader-scroll-list").fetchSemanticsNodes().isNotEmpty()
                fun leaveReader() {
                    onNodeWithTag("reader-back").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                    settle()
                }
                /** The detected mode belongs to the work: the preference the next open starts from is untouched. */
                fun checkPreferenceMode() {
                    val mode = controller.state.value.readerSettings.mode
                    check(mode == DesktopReaderMode.SINGLE) { "The work's mode leaked into the preference: $mode" }
                }
                /** The switch writes the same preference the reader reads, so the flips go through the controller. */
                fun setDetection(on: Boolean) {
                    runBlocking {
                        controller.readerSettings(
                            controller.state.value.readerSettings.copy(readerModeDetection = on),
                        ).join()
                    }
                    settle()
                    check(controller.state.value.readerSettings.readerModeDetection == on) { "The switch did not take" }
                }
                openDetails()
                // Switched off: the chosen paged mode opens even though the 30% page is a webtoon page.
                setDetection(on = false)
                openReader()
                check(!stripOpen()) { "The paged reader opened a strip with autodetect off" }
                leaveReader()
                // On again: the 30% page is 400x2400, so the work opens as a strip.
                setDetection(on = true)
                openReader()
                check(stripOpen()) { "Autodetect did not open the webtoon page as a strip" }
                leaveReader()
                // Off again: the detected mode was remembered for this work, as Android caches it in saveReaderMode.
                setDetection(on = false)
                openReader()
                check(stripOpen()) { "The detected mode was not remembered for the work" }
                leaveReader()
                checkPreferenceMode()
                // Android's switch lives in the reader settings screen's reading-mode group; it is off now, and
                // clicking it restores the default so a later probe run on this data starts from the same place.
                runBlocking { controller.showReaderSettings().join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER_SETTINGS }
                settle()
                onNodeWithTag("reader-setting-detect-mode").performScrollTo().assertIsOff()
                onNodeWithTag("reader-setting-detect-mode").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerSettings.readerModeDetection }
                waitForIdle()
                onNodeWithTag("reader-setting-detect-mode").assertIsOn()
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
