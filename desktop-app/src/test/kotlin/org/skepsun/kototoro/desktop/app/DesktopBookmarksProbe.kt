package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.math.abs

/** Separate JVMs verify the real SDK/cache, shared bookmark table and continuous position restoration. */
internal object DesktopBookmarksProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val reading = args[0] == "bookmarks-read"
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            if (!reading) runBlocking { controller.importJar(Path.of(args[6])).join() }
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerLoading.isEmpty()
                    }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun ready(page: Int) {
                    waitUntil(timeoutMillis = 15_000) {
                        controller.state.value.readerScrollReady && controller.state.value.pageIndex == page &&
                            !controller.state.value.busy && controller.state.value.readerLoading.isEmpty()
                    }
                    idle()
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                onNodeWithTag("source:${controller.state.value.sources.single().source.name}").performClick(); idle()
                val content = controller.state.value.items.single()
                onNodeWithTag("content:${content.id}").performClick(); idle()
                if (reading) System.setProperty("fixture.reader.offline", "true")
                onNodeWithText("开始 / 继续阅读").performClick(); idle()
                if (!reading) {
                    runBlocking {
                        controller.readerSettings(controller.state.value.readerSettings.copy(
                            mode = DesktopReaderMode.CONTINUOUS)).join()
                    }
                    ready(0)
                    onNodeWithTag("reader-scroll-list").performScrollToIndex(3); ready(3)
                    onNodeWithTag("reader-scroll-list").performMouseInput { moveTo(center); scroll(5f) }
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.readerScroll > 20f }; idle()
                    val offset = controller.state.value.readerScroll
                    onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.B) }; idle()
                    val bookmark = controller.state.value.bookmarks.single()
                    check(bookmark.page == 3 && abs(bookmark.scroll - offset) < 2)
                    onNodeWithText("下一章").performClick(); ready(0)
                    check(controller.state.value.chapter?.id != bookmark.chapterId)
                } else {
                    ready(0)
                    val bookmark = controller.state.value.bookmarks.single()
                    check(bookmark.scroll > 20 && controller.state.value.chapter?.id != bookmark.chapterId)
                    onNodeWithTag("reader-bookmarks").performClick(); idle()
                    onNodeWithTag("reader-bookmark:${bookmark.pageId}").performClick(); ready(3)
                    check(controller.state.value.chapter?.id == bookmark.chapterId)
                    check(abs(controller.state.value.readerScroll - bookmark.scroll) < 2)
                    check(System.getProperty("fixture.reader.requests", "0") == "0")
                    onNodeWithTag("reader-bookmark-toggle").performClick(); idle()
                    check(controller.state.value.bookmarks.isEmpty())
                    check(runBlocking { session.library.bookmarks(content.id) }.isEmpty())
                }
            }
        } finally { runBlocking { controller.shutdown() } }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
