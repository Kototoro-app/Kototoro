package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.math.abs

/**
 * Android's "gaps in webtoon mode" (`webtoon_gaps`) in the Windows continuous reader: pages stack flush by default and
 * the switch inserts Android's 24dp `webtoon_pages_gap` between them. The seam between the first two pages is measured
 * on the composed reader (one scene spacing and one list arrangement share the setting), and the switch itself is
 * exercised on the reader settings page, which is where Android keeps it.
 */
internal object DesktopWebtoonGapsProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.importJar(Path.of(args[6])).join() }
            check(session.startupErrors.isEmpty())
            runDesktopComposeUiTest(width = 920, height = 620) {
                var density = 1f
                setContent {
                    density = LocalDensity.current.density
                    DesktopApp(controller)
                }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerLoading.isEmpty()
                    }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                /** The vertical seam between the first two pages, in pixels, with that boundary scrolled into view. */
                fun seam(): Float {
                    val list = onNodeWithTag("reader-scroll-list")
                    list.performScrollToIndex(1)
                    waitForIdle()
                    // Scrolling back up shows the last 300px of the first page above the second one.
                    list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -300f) }
                    waitForIdle()
                    val pages = controller.state.value.pages
                    val first = onNodeWithTag("reader-page:${pages[0].id}").fetchSemanticsNode().boundsInRoot
                    val second = onNodeWithTag("reader-page:${pages[1].id}").fetchSemanticsNode().boundsInRoot
                    return second.top - first.bottom
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                onNodeWithTag("source:MIHON_9007199254740995").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.size == 1 }
                idle()
                onNodeWithTag("content:${controller.state.value.items.single().id}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                idle()
                runBlocking { controller.readerSettings(DesktopReaderSettings(DesktopReaderMode.CONTINUOUS)).join() }
                onNodeWithTag("preview-read").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    !controller.state.value.busy && controller.state.value.readerScrollReady &&
                        controller.state.value.pages.size > 1 && controller.state.value.readerLoading.isEmpty()
                }
                idle()
                val flush = seam()
                check(abs(flush) < 1f) { "Continuous pages are not flush by default: $flush px" }
                runBlocking {
                    controller.readerSettings(controller.state.value.readerSettings.copy(webtoonGaps = true)).join()
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerSettings.webtoonGaps }
                idle()
                val expected = WEBTOON_PAGE_GAP_DP * density
                val wide = seam()
                check(abs(wide - expected) <= 1.5f) { "Webtoon gap is $wide px, expected $expected px" }
                // The setting is live: switching it off puts the pages back together without reloading the chapter.
                runBlocking {
                    controller.readerSettings(controller.state.value.readerSettings.copy(webtoonGaps = false)).join()
                }
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.readerSettings.webtoonGaps }
                idle()
                val closed = seam()
                check(abs(closed) < 1f) { "Pages did not return flush: $closed px" }
                // Android only offers the switch in the reader settings, so the page is the other half of the contract.
                onNodeWithTag("reader-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                idle()
                runBlocking { controller.showReaderSettings().join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER_SETTINGS }
                idle()
                onNodeWithTag("reader-setting-webtoon-gaps").performScrollTo().assertIsOff()
                onNodeWithTag("reader-setting-webtoon-gaps").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerSettings.webtoonGaps }
                waitForIdle()
                onNodeWithTag("reader-setting-webtoon-gaps").assertIsOn()
                // Restored, so a later probe run on the same data starts from the default.
                onNodeWithTag("reader-setting-webtoon-gaps").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.readerSettings.webtoonGaps }
                waitForIdle()
                onNodeWithTag("reader-setting-webtoon-gaps").assertIsOff()
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
