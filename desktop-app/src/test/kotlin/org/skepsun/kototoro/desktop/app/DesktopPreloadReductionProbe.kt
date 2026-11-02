package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Android's "reduce page preloading" switch (`reader_reduce_offscreen_quality`). The Windows reader plans its background
 * page loads with reader-core's `PagedSceneResourceWindowStrategy`: with the switch off it keeps two slots ahead and one
 * behind, with it on a single slot. The fixture chapter has five pages, so the loaded set is exact in single-page mode:
 * `{0, 1, 2}` by default and `{0, 1}` with the switch on. The switch is flipped from the shared reader options panel and
 * checked again on the reader settings page, which writes the same preference Android uses.
 */
internal object DesktopPreloadReductionProbe {
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
                /** Waits for the page the reader shows and for the background preload of the window to be done. */
                fun settle() {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerLoading.isEmpty()
                    }
                    waitUntil(timeoutMillis = 15_000) { !controller.isPrefetching && !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                /** The indices of the pages the reader has loaded, in page order. */
                fun loadedPages(): Set<Int> {
                    val state = controller.state.value
                    return state.readerImages.keys.mapNotNull { id ->
                        state.pages.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                    }.toSet()
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
                openDetails()
                runBlocking { controller.readerSettings(DesktopReaderSettings()).join() }
                settle()
                openReader()
                // Default: the current page and the two slots ahead, as Android loads the pages around the viewport.
                check(loadedPages() == setOf(0, 1, 2)) { "Default window loaded ${loadedPages()}" }
                // Android's switch is a row of the shared reader options panel on both platforms. The layout page is a
                // lazy list with the performance group at its end, so wheel down until the row is composed.
                onNodeWithTag("reader-options").performClick()
                waitForIdle()
                for (step in 0 until 40) {
                    if (onAllNodesWithTag("reader-options-preload-reduction").fetchSemanticsNodes().isNotEmpty()) break
                    onNodeWithTag("reader-options-list").performMouseInput { moveTo(center); scroll(10f) }
                    waitForIdle()
                }
                // Composed but possibly still below the fold: bring the row fully into view before clicking it.
                onNodeWithTag("reader-options-preload-reduction").performScrollTo()
                waitForIdle()
                onNodeWithTag("reader-options-preload-reduction").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerSettings.reducePagePreloading }
                waitForIdle()
                onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.Escape) }
                waitForIdle()
                check(onAllNodesWithTag("reader-panel-sheet").fetchSemanticsNodes().isEmpty()) { "Sheet still open" }
                // Reopening the chapter plans the window again; only the next slot is kept now.
                onNodeWithTag("reader-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                settle()
                openReader()
                check(loadedPages() == setOf(0, 1)) { "Reduced window loaded ${loadedPages()}" }
                // Android also keeps the switch on its reader settings screen; the page writes the same preference.
                onNodeWithTag("reader-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                settle()
                runBlocking { controller.showReaderSettings().join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER_SETTINGS }
                settle()
                onNodeWithTag("reader-setting-reduce-preloading").performScrollTo().assertIsOn()
                onNodeWithTag("reader-setting-reduce-preloading").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.readerSettings.reducePagePreloading }
                waitForIdle()
                // Restored, so a later probe run on the same data starts from the default.
                onNodeWithTag("reader-setting-reduce-preloading").assertIsOff()
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
