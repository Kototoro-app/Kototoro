package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import java.nio.file.Files
import java.nio.file.Path

/** Real UI virtualization, shared vertical scene, native extension and persisted v84 pixel progress. */
internal object DesktopScrollProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        val root = Path.of(args[1])
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            if (mode == "reader-scroll-write") runBlocking { controller.importJar(Path.of(args[6])).join() }
            check(session.startupErrors.isEmpty())
            runDesktopComposeUiTest(width = if (mode == "reader-scroll-read") 920 else 1260,
                height = if (mode == "reader-scroll-read") 620 else 850) {
                setContent { DesktopApp(controller) }
                fun settled(allowError: Boolean = false) {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.readerLoading.isEmpty() }
                    waitForIdle()
                    if (!allowError) check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun ready(index: Int) {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerScrollReady &&
                            controller.state.value.pageIndex == index && controller.state.value.readerLoading.isEmpty()
                    }
                    settled()
                }
                fun snapshot(name: String) {
                    val bitmap = onRoot().captureToImage()
                    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use {
                            Files.write(Files.createDirectories(Path.of(args[3])).resolve("$mode-$name.png"), it.bytes)
                        }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                onNodeWithTag("source:MIHON_9007199254740995").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.size == 1 }
                settled()
                val content = controller.state.value.items.single()
                onNodeWithTag("content:${content.id}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                settled()
                val expected = runBlocking { session.library.progress(content.id) }
                if (mode == "reader-scroll-read") System.setProperty("fixture.reader.offline", "true")
                // Pages 4 and 5 (indices 15, 16) fail from the start: the reader prefetches its lookahead in the background,
                // and a failed prefetch must leave showing those pages to report the error.
                else System.setProperty("fixture.reader.failure.index", "15,16")
                onNodeWithTag("preview-read").performClick()
                if (mode == "reader-scroll-read") {
                    ready(3)
                    check(controller.state.value.readerSettings.mode == DesktopReaderMode.CONTINUOUS)
                    check(expected != null && expected.page == 3 && expected.scroll > 0)
                    check(kotlin.math.abs(controller.state.value.readerScroll - expected.scroll) < 2)
                    check(System.getProperty("fixture.reader.requests", "0") == "0") { "Offline restart requested an image" }
                    onNodeWithTag("reader-scrollbar").assertExists()
                    onNodeWithTag("reader-zoom-in").assertDoesNotExist()
                    snapshot("restored")
                } else {
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.image != null && !controller.state.value.busy }
                    readerDoublePages()
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerSettings.mode == DesktopReaderMode.DOUBLE
                    }
                    readerMode("webtoon")
                    ready(0)
                    check(controller.state.value.readerSettings.mode == DesktopReaderMode.CONTINUOUS)
                    // The lookahead loads in the background; the failing pages stay for the visible load to report.
                    waitUntil(timeoutMillis = 15_000) { !controller.isPrefetching }
                    check(controller.state.value.pages.drop(3).none { it.id in controller.state.value.readerImages })
                    waitUntil(timeoutMillis = 15_000) {
                        runBlocking { session.library.progress(content.id) }?.let {
                            it.page == 0 && it.scroll == 0f && kotlin.math.abs(it.percent - .1f) < .001f
                        } == true
                    }
                    val before = runBlocking { session.library.progress(content.id) }
                    onNodeWithTag("reader-scroll-list").performScrollToIndex(2)
                    waitUntil(timeoutMillis = 15_000) {
                        controller.state.value.readerFailedPages.isNotEmpty() && controller.state.value.readerLoading.isEmpty()
                    }
                    settled(allowError = true)
                    check(runBlocking { session.library.progress(content.id) } == before) { "Failed visible page advanced history" }
                    onNodeWithTag("reader-scroll-list").performScrollToIndex(3)
                    settled(allowError = true)
                    val failedPage = controller.state.value.pages[3].id
                    onNodeWithTag("reader-page-retry:$failedPage").assertExists()
                    snapshot("failure")
                    // Page 4 recovers; page 5 keeps failing for the navigation check below.
                    System.setProperty("fixture.reader.failure.index", "16")
                    onNodeWithTag("reader-page-retry:$failedPage").performClick()
                    ready(3)
                    val loadedCount = System.getProperty("fixture.reader.requests")
                    onNodeWithTag("reader-scroll-list").performScrollToIndex(0)
                    ready(0)
                    check(System.getProperty("fixture.reader.requests") == loadedCount) { "Loaded page fetched again" }
                    onNodeWithTag("reader-viewport").performKeyInput { pressKey(Key.PageDown) }
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.readerScroll > 100f }
                    settled()
                    val afterDown = controller.state.value.readerScroll
                    onNodeWithTag("reader-viewport").performKeyInput {
                        keyDown(Key.ShiftLeft); pressKey(Key.Spacebar); keyUp(Key.ShiftLeft)
                    }
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.readerScroll < afterDown - 50f }
                    settled()
                    val verifiedPage = controller.state.value.pageIndex
                    val verifiedOffset = controller.state.value.readerScroll
                    System.setProperty("fixture.reader.failure.index", "16")
                    onNodeWithTag("reader-viewport").performKeyInput { pressKey(Key.MoveEnd) }
                    waitUntil(timeoutMillis = 15_000) {
                        controller.state.value.pages[4].id in controller.state.value.readerFailedPages &&
                            controller.state.value.readerLoading.isEmpty()
                    }
                    settled(allowError = true)
                    check(!controller.state.value.readerScrollReady)
                    check(controller.state.value.readerTargetPage == 4 && controller.state.value.pageIndex == verifiedPage)
                    check(controller.state.value.readerScroll == verifiedOffset)
                    val failedNavigation = runBlocking { session.library.progress(content.id) }!!
                    check(failedNavigation.page == verifiedPage && kotlin.math.abs(failedNavigation.scroll - verifiedOffset) < 2)
                    snapshot("failed-navigation")
                    System.clearProperty("fixture.reader.failure.index")
                    readerReload()
                    ready(4)
                    snapshot("end")
                    onNodeWithTag("reader-viewport").performKeyInput { pressKey(Key.MoveHome) }
                    ready(0)
                    onNodeWithTag("reader-next-chapter").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.chapter?.number == 2f }
                    ready(0)
                    onNodeWithTag("reader-next-chapter").assertIsNotEnabled()
                    onNodeWithTag("reader-previous-chapter").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.chapter?.number == 1f }
                    ready(0)
                    onNodeWithTag("reader-scroll-list").performScrollToIndex(3)
                    ready(3)
                    onNodeWithTag("reader-scroll-list").performMouseInput { moveTo(center); scroll(5f) }
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.readerScroll > 20f }
                    settled()
                    snapshot("pixel-progress")
                    // Leave immediately after the final viewport update: the owner flushes pending debounced history.
                    val offset = controller.state.value.readerScroll
                    onNodeWithTag("reader-back").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS && !controller.state.value.busy }
                    val stored = runBlocking { session.library.progress(content.id) }!!
                    check(stored.page == 3 && kotlin.math.abs(stored.scroll - offset) < 2)
                }
            }
        } catch (error: Throwable) {
            val state = controller.state.value
            System.err.println("SCROLL_FAILURE screen=${state.screen} page=${state.pageIndex} scroll=${state.readerScroll} " +
                "target=${state.readerTargetPage} ready=${state.readerScrollReady} nav=${state.readerNavigation} " +
                "loading=${state.readerLoading} failed=${state.readerFailedPages.keys} error=${state.error}")
            throw error
        } finally {
            System.clearProperty("fixture.reader.failure.index")
            System.clearProperty("fixture.reader.offline")
            runBlocking { controller.shutdown() }
        }
        runBlocking { DesktopRuntime.open(root).use { check(it.storageInfo().schemaVersion == 84) } }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
