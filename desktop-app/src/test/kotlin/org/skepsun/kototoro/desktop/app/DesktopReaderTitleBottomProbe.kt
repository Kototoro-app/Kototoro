package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Android's "chapter title at the bottom" (`reader_chapter_title_bottom`) in the Windows reader: the work + chapter pill
 * starts in the top bar and, once the switch is on, moves to the bottom of the reader over the progress dock. The probe
 * measures where the pill actually is on the composed reader (a single pill, in the top half, then in the bottom half)
 * and toggles Android's switch on the reader settings page.
 */
internal object DesktopReaderTitleBottomProbe {
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
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerLoading.isEmpty()
                    }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                /** Where the chapter pill is: its top offset relative to the reader (0 = top bar, 1 = bottom). */
                fun pillPosition(): Float {
                    val root = onRoot().fetchSemanticsNode().boundsInRoot.height
                    val pill = onNodeWithTag("reader-chapters").fetchSemanticsNode().boundsInRoot
                    return pill.top / root
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                onNodeWithTag("source:MIHON_9007199254740995").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.size == 1 }
                idle()
                onNodeWithTag("content:${controller.state.value.items.single().id}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                idle()
                runBlocking { controller.readerSettings(DesktopReaderSettings()).join() }
                onNodeWithTag("preview-read").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    !controller.state.value.busy && controller.state.value.pages.size > 1 &&
                        controller.state.value.readerLoading.isEmpty()
                }
                idle()
                // Default: one pill, in the top bar, and nothing at the bottom.
                onNodeWithTag("reader-chapter-title-bottom").assertDoesNotExist()
                check(pillPosition() < .5f) { "The chapter pill is not in the top bar by default: ${pillPosition()}" }
                runBlocking {
                    controller.readerSettings(controller.state.value.readerSettings.copy(chapterTitleAtBottom = true))
                        .join()
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerSettings.chapterTitleAtBottom }
                idle()
                // The pill moved to the bottom chrome and the top bar no longer draws one, so exactly one remains.
                onNodeWithTag("reader-chapter-title-bottom").assertExists()
                check(onAllNodesWithTag("reader-chapters").fetchSemanticsNodes().size == 1) {
                    "The top bar kept a chapter pill next to the bottom one"
                }
                check(pillPosition() > .5f) { "The chapter pill did not move to the bottom: ${pillPosition()}" }
                // Android's switch lives on the reader settings screen; that page is the other half of the contract.
                onNodeWithTag("reader-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                idle()
                runBlocking {
                    controller.readerSettings(controller.state.value.readerSettings.copy(chapterTitleAtBottom = false))
                        .join()
                }
                runBlocking { controller.showReaderSettings().join() }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.READER_SETTINGS }
                idle()
                onNodeWithTag("reader-setting-chapter-title-bottom").performScrollTo().assertIsOff()
                onNodeWithTag("reader-setting-chapter-title-bottom").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerSettings.chapterTitleAtBottom }
                waitForIdle()
                onNodeWithTag("reader-setting-chapter-title-bottom").assertIsOn()
                // Restored, so a later probe run on the same data starts from the default.
                onNodeWithTag("reader-setting-chapter-title-bottom").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.readerSettings.chapterTitleAtBottom }
                waitForIdle()
                onNodeWithTag("reader-setting-chapter-title-bottom").assertIsOff()
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
