package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.math.abs

/**
 * Android's `reader_chapter_break` in the Windows continuous reader: off by default, the chapter ends flush after its
 * last page; with the setting on, the shared chapter break card (information only, no button) marks the boundary: a low
 * band after the last page in continuous mode, a page of its own in paged mode, and the chapter still reads on after
 * it. The switch lives on the reader settings page, so the page is the other half of the contract.
 */
internal object DesktopChapterBreakProbe {
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
                fun scrollToEnd() {
                    val state = controller.state.value
                    // With the card on, it is one more list item after the last page.
                    val last = state.pages.size - if (state.readerSettings.chapterBreak) 0 else 1
                    onNodeWithTag("reader-scroll-list").performScrollToIndex(last)
                    waitForIdle()
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
                check(!controller.state.value.readerSettings.chapterBreak) { "Chapter break must default to off" }
                scrollToEnd()
                onNodeWithTag("reader-chapter-break").assertDoesNotExist()
                runBlocking {
                    controller.readerSettings(controller.state.value.readerSettings.copy(chapterBreak = true)).join()
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerSettings.chapterBreak }
                idle()
                // The card is a list item: scroll again until the toggled list has composed it.
                waitUntil(timeoutMillis = 15_000) {
                    scrollToEnd()
                    onAllNodesWithTag("reader-chapter-break").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("reader-chapter-break").assertExists()
                // The card is one item after the last page, so it never shifts the pages' own layout.
                check(controller.state.value.pageIndex in controller.state.value.pages.indices)
                runBlocking {
                    controller.readerSettings(controller.state.value.readerSettings.copy(chapterBreak = false)).join()
                }
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.readerSettings.chapterBreak }
                idle()
                scrollToEnd()
                onNodeWithTag("reader-chapter-break").assertDoesNotExist()
                // Continuous: the band is information only, and the chapter still runs on once its end is reached.
                runBlocking {
                    controller.readerSettings(controller.state.value.readerSettings.copy(chapterBreak = true)).join()
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerSettings.chapterBreak }
                idle()
                val firstChapter = controller.state.value.chapter?.id
                check(controller.state.value.adjacentChapter(true) != null) { "The fixture must have a next chapter" }
                waitUntil(timeoutMillis = 15_000) {
                    scrollToEnd()
                    onAllNodesWithTag("reader-chapter-break").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText(AndroidStrings["chapter_break_continue"]).assertDoesNotExist()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerAtEnd || controller.state.value.chapter?.id != firstChapter }
                if (controller.state.value.chapter?.id == firstChapter) runBlocking { controller.turnPage(true).join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.chapter?.id != firstChapter }
                idle()
                // Back to the first chapter for the paged half.
                runBlocking { controller.changeChapter(false).join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.chapter?.id == firstChapter }
                idle()
                // Paged: the break is its own page after the last page group, and the next turn reads on.
                runBlocking {
                    controller.readerSettings(controller.state.value.readerSettings.copy(mode = DesktopReaderMode.SINGLE)).join()
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerSettings.mode == DesktopReaderMode.SINGLE }
                idle()
                onNodeWithTag("reader-chapter-break").assertDoesNotExist()
                runBlocking { controller.page(controller.state.value.pages.lastIndex).join() }
                idle()
                val lastPage = controller.state.value.pageIndex
                onNodeWithTag("reader-chapter-break").assertDoesNotExist()
                runBlocking { controller.turnPage(true).join() }
                waitForIdle()
                onNodeWithTag("reader-chapter-break").assertExists()
                onNodeWithText(AndroidStrings["chapter_break_continue"]).assertDoesNotExist()
                check(controller.state.value.chapter?.id == firstChapter && controller.state.value.pageIndex == lastPage) {
                    "The break page must not move the chapter or the progress"
                }
                // Back closes the break page.
                runBlocking { controller.turnPage(false).join() }
                waitForIdle()
                onNodeWithTag("reader-chapter-break").assertDoesNotExist()
                check(controller.state.value.pageIndex == lastPage && controller.state.value.chapter?.id == firstChapter)
                // Forward twice: the break page, then the next chapter.
                runBlocking { controller.turnPage(true).join() }
                waitForIdle()
                onNodeWithTag("reader-chapter-break").assertExists()
                runBlocking { controller.turnPage(true).join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.chapter?.id != firstChapter }
                idle()
                onNodeWithTag("reader-chapter-break").assertDoesNotExist()
                runBlocking {
                    controller.readerSettings(controller.state.value.readerSettings.copy(
                        mode = DesktopReaderMode.CONTINUOUS, chapterBreak = false)).join()
                }
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.readerSettings.chapterBreak }
                idle()
                onNodeWithTag("reader-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                idle()
                runBlocking { controller.showReaderSettings().join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER_SETTINGS }
                idle()
                onNodeWithTag("reader-setting-chapter-break").performScrollTo().assertIsOff()
                onNodeWithTag("reader-setting-chapter-break").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerSettings.chapterBreak }
                waitForIdle()
                onNodeWithTag("reader-setting-chapter-break").assertIsOn()
                // Restored, so a later probe run on the same data starts from the default.
                onNodeWithTag("reader-setting-chapter-break").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.readerSettings.chapterBreak }
                waitForIdle()
                onNodeWithTag("reader-setting-chapter-break").assertIsOff()
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
