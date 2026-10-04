package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path

/** Real source branch boundaries, failed chapter preparation and restart of automatic reading. */
internal object DesktopChapterProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        System.setProperty("fixture.reader.branches", "true")
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            if (mode == "reader-chapters-write") runBlocking { controller.importJar(Path.of(args[6])).join() }
            runDesktopComposeUiTest(width = if (mode.endsWith("read")) 920 else 1260,
                height = if (mode.endsWith("read")) 620 else 850) {
                setContent { DesktopApp(controller) }
                fun ready(chapter: Int, page: Int) {
                    waitUntil(timeoutMillis = 15_000) {
                        val state = controller.state.value
                        !state.busy && state.error == null && state.chapter?.number == chapter.toFloat() &&
                            state.pageIndex == page && state.image != null && state.readerLoading.isEmpty() &&
                            (state.readerSettings.mode != DesktopReaderMode.CONTINUOUS || state.readerScrollReady)
                    }
                    waitForIdle()
                }
                fun press(key: Key) {
                    onNodeWithTag("reader-viewport").performTouchInput { click() }
                    onNodeWithTag("reader-surface").performKeyInput { pressKey(key) }
                }
                fun error() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.error != null }
                    waitForIdle()
                }
                fun snapshot(name: String) {
                    val bitmap = onRoot().captureToImage()
                    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image -> image.encodeToData()?.use {
                        Files.write(Path.of(args[3]).resolve("$mode-$name.png"), it.bytes)
                    } }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                onNodeWithTag("source:MIHON_9007199254740995").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.items.size == 1 }
                val content = controller.state.value.items.single()
                onNodeWithTag("content:${content.id}").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.screen == DesktopScreen.DETAILS }
                check(controller.state.value.content?.chapters?.size == 3)
                if (mode.endsWith("read")) System.setProperty("fixture.reader.offline", "true")
                onNodeWithText("开始 / 继续阅读").performClick()
                if (mode.endsWith("read")) {
                    ready(2, 0)
                    check(controller.state.value.readerSettings.automaticChapter)
                    check(controller.state.value.readerSettings.mode == DesktopReaderMode.CONTINUOUS)
                    check(System.getProperty("fixture.reader.requests", "0") == "0")
                    onNodeWithTag("reader-auto-chapter").assertTextEquals("自动跨章：开")
                    onNodeWithText("下一章").assertIsNotEnabled()
                    snapshot("restored")
                } else {
                    ready(1, 0)
                    val initialHistory = runBlocking { session.library.progress(content.id) }
                    onNodeWithTag("reader-auto-chapter").performClick()
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.readerSettings.automaticChapter }
                    check(runBlocking { session.library.progress(content.id) } == initialHistory)
                    press(Key.DirectionRight)
                    ready(1, 1)
                    press(Key.DirectionRight)
                    ready(1, 2)
                    press(Key.MoveEnd)
                    ready(1, 4)
                    onNodeWithTag("reader-auto-chapter").performClick()
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && !controller.state.value.readerSettings.automaticChapter }
                    onNodeWithTag("reader-forward").assertIsNotEnabled()
                    press(Key.DirectionRight)
                    ready(1, 4)
                    onNodeWithTag("reader-auto-chapter").performClick()
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.readerSettings.automaticChapter }
                    val previous = controller.state.value
                    val previousHistory = runBlocking { session.library.progress(content.id) }
                    System.setProperty("fixture.reader.failure.chapter", "2")
                    onNodeWithTag("reader-forward").performClick()
                    error()
                    check(controller.state.value.chapter == previous.chapter && controller.state.value.image == previous.image)
                    check(controller.state.value.pageIndex == 4 && runBlocking { session.library.progress(content.id) } == previousHistory)
                    System.clearProperty("fixture.reader.failure.chapter")
                    System.setProperty("fixture.reader.failure.index", "12")
                    onNodeWithTag("reader-forward").performClick()
                    error()
                    check(controller.state.value.chapter == previous.chapter && controller.state.value.image == previous.image)
                    check(runBlocking { session.library.progress(content.id) } == previousHistory)
                    snapshot("failed-image")
                    System.clearProperty("fixture.reader.failure.index")
                    onNodeWithTag("reader-forward").performClick()
                    ready(2, 0)
                    press(Key.PageUp)
                    ready(1, 4)
                    press(Key.DirectionRight)
                    ready(2, 0)
                    runBlocking { controller.readerSettings(controller.state.value.readerSettings.copy(
                        mode = DesktopReaderMode.DOUBLE, rightToLeft = true)).join() }
                    ready(2, 0)
                    onNodeWithTag("reader-forward").assertIsNotEnabled()
                    onNodeWithText("下一章").assertIsNotEnabled()
                    press(Key.DirectionRight)
                    ready(1, 3)
                    press(Key.DirectionLeft)
                    ready(2, 0)
                    onNodeWithTag("reader-mode").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.readerSettings.mode == DesktopReaderMode.CONTINUOUS }
                    ready(2, 0)
                    check(controller.state.value.readerSettings.mode == DesktopReaderMode.CONTINUOUS)
                    press(Key.PageUp)
                    ready(1, 4)
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.readerAtEnd }
                    press(Key.PageDown)
                    ready(2, 0)
                    onNodeWithTag("reader-scroll-list").performMouseInput { moveTo(center); scroll(-5f) }
                    ready(1, 4)
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.readerAtEnd }
                    val lastOffset = controller.state.value.readerScroll
                    System.setProperty("fixture.reader.failure.chapter", "2")
                    onNodeWithTag("reader-scroll-list").performMouseInput { moveTo(center); scroll(5f) }
                    error()
                    check(controller.state.value.chapter?.number == 1f && controller.state.value.pageIndex == 4)
                    val history = runBlocking { session.library.progress(content.id) }!!
                    check(history.chapterId == controller.state.value.chapter?.id && history.page == 4 &&
                        kotlin.math.abs(history.scroll - lastOffset) < 2)
                    val calls = System.getProperty("fixture.reader.page_lists")
                    onNodeWithTag("reader-scroll-list").performMouseInput { moveTo(center); scroll(5f) }
                    waitForIdle()
                    check(System.getProperty("fixture.reader.page_lists") == calls)
                    System.clearProperty("fixture.reader.failure.chapter")
                    onNodeWithText("下一章").performClick()
                    ready(2, 0)
                    snapshot("continuous-final")
                    onNodeWithText("返回详情").performClick()
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.screen == DesktopScreen.DETAILS }
                }
            }
        } catch (error: Throwable) {
            val state = controller.state.value
            System.err.println("CHAPTER_FAILURE chapter=${state.chapter?.number} page=${state.pageIndex} " +
                "scroll=${state.readerScroll} end=${state.readerAtEnd} ready=${state.readerScrollReady} " +
                "navigation=${state.readerNavigation} settings=${state.readerSettings} busy=${state.busy} error=${state.error}")
            throw error
        } finally {
            System.clearProperty("fixture.reader.failure.chapter")
            System.clearProperty("fixture.reader.failure.index")
            System.clearProperty("fixture.reader.offline")
            System.clearProperty("fixture.reader.branches")
            runBlocking { controller.shutdown() }
        }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
