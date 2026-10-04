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

/** Actual independently loaded HTTP source, shared protocol, native image decode, UI and v84 history. */
internal object DesktopReaderProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        val root = Path.of(args[1])
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            if (mode == "reader-write") runBlocking { controller.importJar(Path.of(args[6])).join() }
            check(session.startupErrors.isEmpty()) { session.startupErrors.joinToString() }
            runDesktopComposeUiTest(width = if (mode == "reader-read") 920 else 1260,
                height = if (mode == "reader-read") 620 else 850) {
                setContent { DesktopApp(controller) }
                fun settled(allowError: Boolean = false) {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    if (!allowError) check(controller.state.value.error == null) {
                        controller.state.value.error.orEmpty()
                    }
                }
                fun ready(index: Int, count: Int) {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && (controller.state.value.error != null ||
                            (controller.state.value.pageIndex == index && controller.state.value.image != null))
                    }
                    settled()
                    waitUntil(timeoutMillis = 15_000) {
                        onAllNodesWithTag("reader-page").fetchSemanticsNodes().size == count
                    }
                }
                fun press(key: Key) {
                    onNodeWithTag("reader-viewport").performTouchInput { click() }
                    onNodeWithTag("reader-surface").performKeyInput { pressKey(key) }
                    settled()
                }
                fun snapshot(name: String) {
                    val bitmap = onRoot().captureToImage()
                    val file = Files.createDirectories(Path.of(args[3])).resolve("$mode-$name.png")
                    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use { Files.write(file, it.bytes) }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                settled()
                if (mode == "reader-read") {
                    check(controller.state.value.readerSettings ==
                        DesktopReaderSettings(DesktopReaderMode.DOUBLE, true))
                }
                onNodeWithTag("source:MIHON_9007199254740995").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.size == 1 }
                settled()
                val content = controller.state.value.items.single()
                onNodeWithTag("content:${content.id}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                settled()
                val chapters = requireNotNull(controller.state.value.content?.chapters)
                check(chapters.size == 2)
                if (mode == "reader-read") System.setProperty("fixture.reader.offline", "true")
                onNodeWithText("开始 / 继续阅读").performClick()
                if (mode == "reader-read") {
                    ready(3, 2)
                    check(System.getProperty("fixture.reader.requests", "0") == "0") { "Offline spread requested an image" }
                    check(controller.state.value.chapter?.id == chapters[0].id)
                    onNodeWithTag("reader-progress").assertTextEquals("4–5 / 5")
                    val progress = runBlocking { session.library.progress(content.id) }!!
                    check(progress.page == 3 && progress.percent == .5f)
                    val viewport = onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot
                    val left = onNodeWithContentDescription("第 5 页").fetchSemanticsNode().boundsInRoot
                    val right = onNodeWithContentDescription("第 4 页").fetchSemanticsNode().boundsInRoot
                    check(left.left < right.left)
                    for (bounds in listOf(left, right)) {
                        check(bounds.left >= viewport.left - 1 && bounds.right <= viewport.right + 1)
                        check(bounds.top >= viewport.top - 1 && bounds.bottom <= viewport.bottom + 1)
                    }
                    snapshot("restored")
                } else {
                    ready(0, 1)
                    press(Key.DirectionRight)
                    ready(1, 1)
                    val beforeFailure = controller.state.value.image
                    val progress = runBlocking { session.library.progress(content.id) }!!
                    System.setProperty("fixture.reader.failure.index", "14")
                    onNodeWithTag("reader-surface").performKeyInput { pressKey(Key.DirectionRight) }
                    waitUntil(timeoutMillis = 15_000) {
                        controller.state.value.error != null && !controller.state.value.busy
                    }
                    settled(allowError = true)
                    check(controller.state.value.pageIndex == 1 && controller.state.value.image == beforeFailure)
                    check(runBlocking { session.library.progress(content.id) } == progress)
                    System.clearProperty("fixture.reader.failure.index")
                    press(Key.DirectionRight)
                    ready(2, 1)
                    val count = System.getProperty("fixture.reader.requests")
                    press(Key.MoveHome)
                    ready(0, 1)
                    check(System.getProperty("fixture.reader.requests") == count) { "Cached page was fetched again" }
                    onNodeWithTag("reader-mode").performClick()
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy &&
                            controller.state.value.readerSettings.mode == DesktopReaderMode.DOUBLE
                    }
                    ready(0, 2)
                    onNodeWithTag("reader-progress").assertTextEquals("1–2 / 5")
                    val first = onNodeWithContentDescription("第 1 页").fetchSemanticsNode().boundsInRoot
                    val second = onNodeWithContentDescription("第 2 页").fetchSemanticsNode().boundsInRoot
                    check(first.left < second.left)
                    snapshot("ltr")
                    onNodeWithTag("reader-direction").performClick()
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerSettings.rightToLeft
                    }
                    ready(0, 2)
                    check(onNodeWithContentDescription("第 1 页").fetchSemanticsNode().boundsInRoot.left >
                        onNodeWithContentDescription("第 2 页").fetchSemanticsNode().boundsInRoot.left)
                    snapshot("rtl")
                    press(Key.DirectionLeft)
                    ready(2, 1)
                    check(controller.state.value.readerImages[controller.state.value.pages[2].id]?.width == 900)
                    snapshot("wide")
                    press(Key.PageDown)
                    ready(3, 2)
                    onNodeWithTag("reader-progress").assertTextEquals("4–5 / 5")
                    check(runBlocking { session.library.progress(content.id) }?.percent == .5f)
                    onNodeWithTag("reader-surface").performKeyInput {
                        keyDown(Key.ShiftLeft); pressKey(Key.Spacebar); keyUp(Key.ShiftLeft)
                    }
                    ready(2, 1)
                    press(Key.Spacebar)
                    ready(3, 2)
                    val beforeModified = runBlocking { session.library.progress(content.id) }
                    onNodeWithTag("reader-surface").performKeyInput {
                        keyDown(Key.CtrlLeft); pressKey(Key.DirectionRight); keyUp(Key.CtrlLeft)
                    }
                    settled()
                    check(runBlocking { session.library.progress(content.id) } == beforeModified)
                    press(Key.PageDown)
                    ready(3, 2)
                    press(Key.PageUp)
                    ready(2, 1)
                    press(Key.MoveHome)
                    ready(0, 2)
                    press(Key.PageUp)
                    ready(0, 2)
                    press(Key.Escape)
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                    check(controller.state.value.screen == DesktopScreen.DETAILS)
                    onNodeWithText("开始 / 继续阅读").performClick()
                    ready(0, 2)
                    onNodeWithText("下一章").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.chapter?.id == chapters[1].id }
                    ready(0, 2)
                    onNodeWithText("下一章").assertIsNotEnabled()
                    check(runBlocking { session.library.progress(content.id) }?.percent == 1f)
                    onNodeWithText("上一章").performClick()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.chapter?.id == chapters[0].id }
                    ready(0, 2)
                    press(Key.DirectionLeft)
                    ready(2, 1)
                    press(Key.MoveEnd)
                    ready(3, 2)
                    val beforeReload = System.getProperty("fixture.reader.requests").toInt()
                    onNodeWithText("重新加载").performClick()
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy &&
                            System.getProperty("fixture.reader.requests").toInt() == beforeReload + 2
                    }
                    ready(3, 2)
                    check(System.getProperty("fixture.reader.requests").toInt() == beforeReload + 2)
                    snapshot("last-spread")
                }
            }
        } catch (error: Throwable) {
            val state = controller.state.value
            System.err.println("READER_FAILURE screen=${state.screen} page=${state.pageIndex} busy=${state.busy} " +
                "error=${state.error} chapter=${state.chapter?.id} images=${state.readerImages.size} " +
                "requests=${System.getProperty("fixture.reader.requests")}")
            throw error
        } finally { runBlocking { controller.shutdown() } }
        runBlocking { DesktopRuntime.open(root).use { check(it.storageInfo().schemaVersion == 84) } }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
