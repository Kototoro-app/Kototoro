package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/**
 * Android's continuous horizontal reading mode (`continuous_horizontal`) on the Windows reader: the pages form one row
 * fitted to the window height and read left to right, the wheel moves that row, the reading position goes to the same
 * history row the webtoon strip writes, and Android's `reader_continuous_horizontal_reversed` flips the row so the first
 * page sits at the right edge. The mode and the direction both come back from the preference store on the next open.
 */
internal object DesktopHorizontalStripProbe {
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
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                /** The chapter is open on the horizontal strip and its viewport has been reported. */
                fun strip() {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerScrollReady &&
                            controller.state.value.readerLoading.isEmpty()
                    }
                    settle()
                    check(controller.state.value.screen == DesktopScreen.READER) { "Reader is closed" }
                    check(controller.state.value.readerSettings.mode == DesktopReaderMode.CONTINUOUS_HORIZONTAL) {
                        "Reading mode is ${controller.state.value.readerSettings.mode}"
                    }
                }
                fun bounds(index: Int): Rect {
                    val page = controller.state.value.pages[index]
                    return onNodeWithTag("reader-page:${page.id}").fetchSemanticsNode().boundsInRoot
                }
                /** The strip as drawn, so the row of fitted pages can be looked at, not only measured. */
                fun snapshot(name: String) {
                    val bitmap = onRoot().captureToImage()
                    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use {
                            Files.write(Files.createDirectories(Path.of(args[3])).resolve("$mode-$name.png"), it.bytes)
                        }
                    }
                }
                fun openDetails() {
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                    onNodeWithTag("source:MIHON_9007199254740995").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.items.size == 1 }
                    settle()
                    onNodeWithTag("content:${controller.state.value.items.single().id}").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                    settle()
                    runBlocking { controller.readerSettings(DesktopReaderSettings()).join() }
                    settle()
                }
                fun openReader() {
                    onNodeWithTag("preview-read").performClick()
                    waitUntil(timeoutMillis = 15_000) {
                        controller.state.value.screen == DesktopScreen.READER && controller.state.value.pages.size == 5 &&
                            !controller.state.value.busy && controller.state.value.readerLoading.isEmpty()
                    }
                    settle()
                }
                /** Opens the reader options sheet and scrolls Android's row for [tag] into view. */
                fun optionsRow(tag: String) {
                    onNodeWithTag("reader-options").performClick()
                    waitForIdle()
                    for (step in 0 until 40) {
                        if (onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) break
                        onNodeWithTag("reader-options-list").performMouseInput { moveTo(center); scroll(10f) }
                        waitForIdle()
                    }
                    onNodeWithTag(tag).performScrollTo()
                    waitForIdle()
                }
                openDetails()
                openReader()
                // Android's panel offers the mode beside the other reading modes; picking it turns the strip around.
                readerMode("continuous_horizontal")
                strip()
                val row = onNodeWithTag("reader-scroll-list").fetchSemanticsNode().boundsInRoot
                val first = bounds(0)
                val second = bounds(1)
                check(abs(first.top - second.top) <= 1f) { "Pages are not in one row: $first / $second" }
                check(second.left >= first.right - 1f) { "Pages are not left to right: $first / $second" }
                // Fitted to the window height: the list already reserves the scrollbar gutter under the pages.
                check(abs(first.height - row.height) <= 1f) {
                    "Page height ${first.height} does not fill the strip ${row.height}"
                }
                check(abs(first.width / first.height - 400f / 600f) <= .03f) {
                    "Page aspect ${first.width} x ${first.height} does not keep the fixture's 400x600"
                }
                snapshot("left-to-right")
                // A mouse wheel only turns vertically, so the strip maps it onto its own axis.
                onNodeWithTag("reader-viewport").performMouseInput { moveTo(center); scroll(2f) }
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.pageIndex > 0 || controller.state.value.readerScroll > 0f
                }
                settle()
                // Android's reversed direction, from the same panel switch the phone draws.
                optionsRow("reader-options-horizontal-reversed")
                onNodeWithTag("reader-options-horizontal-reversed").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerSettings.continuousHorizontalReversed }
                onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.Escape) }
                waitForIdle()
                strip()
                // The current page now anchors the right edge, so the next page is the one on its left.
                waitUntil(timeoutMillis = 15_000) {
                    val current = controller.state.value.pageIndex
                    current + 1 in controller.state.value.pages.indices &&
                        onAllNodesWithTag("reader-page:${controller.state.value.pages[current + 1].id}")
                            .fetchSemanticsNodes().isNotEmpty()
                }
                val reversedFirst = bounds(controller.state.value.pageIndex)
                val reversedSecond = bounds(controller.state.value.pageIndex + 1)
                check(reversedFirst.left > reversedSecond.left) {
                    "Reversed strip still reads left to right: $reversedFirst / $reversedSecond"
                }
                snapshot("reversed")
                val content = controller.state.value.content ?: error("No content")
                val savedPage = controller.state.value.pageIndex
                val savedScroll = controller.state.value.readerScroll
                onNodeWithTag("reader-back").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                settle()
                waitUntil(timeoutMillis = 15_000) { runBlocking { session.library.progress(content.id) } != null }
                val progress = runBlocking { session.library.progress(content.id) } ?: error("No progress")
                check(progress.page == savedPage) { "History page ${progress.page} instead of $savedPage" }
                // The mode and the reversed direction come back from the preference store, on the same position.
                openReader()
                strip()
                check(controller.state.value.readerSettings.continuousHorizontalReversed) {
                    "The reversed direction was not restored"
                }
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.pageIndex == savedPage && controller.state.value.readerScrollReady
                }
                settle()
                check(abs(controller.state.value.readerScroll - savedScroll) <= 2f) {
                    "Restored offset ${controller.state.value.readerScroll} instead of $savedScroll"
                }
                // Back to the default direction, so the strip keeps reading left to right for the next run.
                optionsRow("reader-options-horizontal-reversed")
                onNodeWithTag("reader-options-horizontal-reversed").assertIsOn()
                onNodeWithTag("reader-options-horizontal-reversed").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.readerSettings.continuousHorizontalReversed }
                onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.Escape) }
                waitForIdle()
                strip()
                waitUntil(timeoutMillis = 15_000) {
                    val current = controller.state.value.pageIndex
                    current + 1 in controller.state.value.pages.indices &&
                        onAllNodesWithTag("reader-page:${controller.state.value.pages[current + 1].id}")
                            .fetchSemanticsNodes().isNotEmpty()
                }
                val restoredFirst = bounds(controller.state.value.pageIndex)
                val restoredSecond = bounds(controller.state.value.pageIndex + 1)
                check(restoredFirst.left < restoredSecond.left) {
                    "The strip did not return to left to right: $restoredFirst / $restoredSecond"
                }
            }
        } finally {
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
