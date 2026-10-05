package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import org.skepsun.kototoro.reader.core.ZoomMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/** Actual native image decode and layer/input geometry, not a controller-only camera assertion. */
internal object DesktopReaderCameraProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        val root = Path.of(args[1])
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            if (mode == "reader-camera-write") runBlocking { controller.importJar(Path.of(args[6])).join() }
            check(session.startupErrors.isEmpty())
            runDesktopComposeUiTest(width = if (mode == "reader-camera-read") 920 else 1260,
                height = if (mode == "reader-camera-read") 620 else 850) {
                setContent { DesktopApp(controller) }
                fun settled() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                }
                fun ready(index: Int, count: Int) {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.pageIndex == index &&
                            controller.state.value.image != null
                    }
                    settled()
                    // Neighbouring slots are composed off screen (clipped to empty bounds); count what the viewport shows.
                    waitUntil(timeoutMillis = 15_000) {
                        val viewport = onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot
                        onAllNodesWithTag("reader-page").fetchSemanticsNodes().count { node ->
                            node.boundsInRoot.width > 0f && node.boundsInRoot.left < viewport.right &&
                                node.boundsInRoot.right > viewport.left
                        } == count && !controller.isPrefetching
                    }
                }
                fun fit(fit: ZoomMode) {
                    readerFit(fit)
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerSettings.fitMode == fit
                    }
                    settled()
                    onNodeWithTag("reader-zoom").assertTextEquals("100%")
                }
                fun reset() { onNodeWithTag("reader-zoom").performClick(); settled() }
                fun shortcut(key: Key) {
                    onNodeWithTag("reader-viewport").performKeyInput {
                        keyDown(Key.CtrlLeft); pressKey(key); keyUp(Key.CtrlLeft)
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
                fun whiteCenter(): Offset {
                    val bitmap = onNodeWithTag("reader-viewport").captureToImage().asSkiaBitmap()
                    var sumX = 0L
                    var sumY = 0L
                    var count = 0
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val color = bitmap.getColor(x, y)
                        if ((color shr 16 and 255) > 230 && (color shr 8 and 255) > 230 && (color and 255) > 230) {
                            sumX += x; sumY += y; count++
                        }
                    }
                    check(count > 20) { "Authored white page numeral was not rendered" }
                    return Offset(sumX.toFloat() / count, sumY.toFloat() / count)
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                onNodeWithTag("source:MIHON_9007199254740995").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.size == 1 }
                settled()
                val content = controller.state.value.items.single()
                onNodeWithTag("content:${content.id}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS }
                settled()
                onNodeWithTag("preview-read").performClick()
                if (mode == "reader-camera-read") {
                    ready(3, 2)
                    check(controller.state.value.readerSettings ==
                        DesktopReaderSettings(DesktopReaderMode.DOUBLE, true, ZoomMode.FIT_WIDTH))
                    onNodeWithTag("reader-zoom").assertTextEquals("100%")
                    assertReaderProgress("4–5 / 5")
                    val first = onNodeWithContentDescription("第 4 页").getUnclippedBoundsInRoot()
                    val second = onNodeWithContentDescription("第 5 页").getUnclippedBoundsInRoot()
                    check(first.left > second.left)
                    val progress = runBlocking { session.library.progress(content.id) }!!
                    check(progress.page == 3 && progress.percent == .5f)
                    snapshot("restored-fit")
                } else {
                    ready(0, 1)
                    val progress = runBlocking { session.library.progress(content.id) }
                    val requests = System.getProperty("fixture.reader.requests")
                    val baseline = onNodeWithContentDescription("第 1 页").fetchSemanticsNode().boundsInRoot
                    onNodeWithTag("reader-zoom-in").performClick()
                    settled()
                    onNodeWithTag("reader-zoom").assertTextEquals("125%")
                    onNodeWithTag("reader-zoom-out").performClick()
                    settled()
                    onNodeWithTag("reader-zoom").assertTextEquals("100%")
                    shortcut(Key.Equals)
                    onNodeWithTag("reader-zoom").assertTextEquals("125%")
                    shortcut(Key.Minus)
                    onNodeWithTag("reader-zoom").assertTextEquals("100%")
                    onNodeWithTag("reader-viewport").performMouseInput { moveTo(center); doubleClick() }
                    settled()
                    onNodeWithTag("reader-zoom").assertTextEquals("200%")
                    val zoomed = onNodeWithContentDescription("第 1 页").fetchSemanticsNode().boundsInRoot
                    check(abs(zoomed.width - baseline.width * 2) < 3) { "Page layer did not scale: $baseline -> $zoomed" }
                    val whiteBeforeDrag = whiteCenter()
                    onNodeWithTag("reader-viewport").performMouseInput {
                        dragAndDrop(center, center + Offset(0f, -120f))
                    }
                    settled()
                    check(whiteCenter().y < whiteBeforeDrag.y - 40f) { "Mouse drag did not pan rendered pixels" }
                    snapshot("zoom-drag")
                    shortcut(Key.Zero)
                    onNodeWithTag("reader-zoom").assertTextEquals("100%")
                    onNodeWithTag("reader-viewport").performMultiModalInput {
                        key { keyDown(Key.CtrlLeft) }
                        mouse { moveTo(center); scroll(-2f) }
                        key { keyUp(Key.CtrlLeft) }
                    }
                    settled()
                    check(onNodeWithTag("reader-zoom").fetchSemanticsNode().config[
                        androidx.compose.ui.semantics.SemanticsProperties.Text].single().text.removeSuffix("%").toInt() > 100)
                    reset()
                    onNodeWithTag("reader-viewport").performMouseInput { exit() }
                    onNodeWithTag("reader-viewport").performTouchInput {
                        down(0, center + Offset(-60f, 0f)); down(1, center + Offset(60f, 0f))
                        repeat(10) { index ->
                            val distance = 60f + (index + 1) * 7f
                            updatePointerTo(0, center + Offset(-distance, 0f))
                            updatePointerTo(1, center + Offset(distance, 0f)); move(16)
                        }
                        up(0); up(1)
                    }
                    settled()
                    check(onNodeWithTag("reader-zoom").fetchSemanticsNode().config[
                        androidx.compose.ui.semantics.SemanticsProperties.Text].single().text.removeSuffix("%").toInt() > 150)
                    reset()
                    repeat(10) { onNodeWithTag("reader-zoom-in").performClick(); settled() }
                    onNodeWithTag("reader-zoom").assertTextEquals("500%")
                    onNodeWithTag("reader-zoom-in").assertIsNotEnabled()
                    reset()
                    check(runBlocking { session.library.progress(content.id) } == progress)
                    check(System.getProperty("fixture.reader.requests") == requests)
                    fit(ZoomMode.FIT_WIDTH)
                    val viewport = onNodeWithTag("reader-viewport").getUnclippedBoundsInRoot()
                    val fitted = onNodeWithContentDescription("第 1 页").getUnclippedBoundsInRoot()
                    check(abs(fitted.width.value - viewport.width.value) < 2)
                    check(fitted.height > viewport.height && abs(fitted.top.value - viewport.top.value) < 2)
                    onNodeWithTag("reader-viewport").performMouseInput { moveTo(center); scroll(2f) }
                    settled()
                    check(onNodeWithContentDescription("第 1 页").getUnclippedBoundsInRoot().top < fitted.top)
                    snapshot("fit-width-pan")
                    fit(ZoomMode.KEEP_START)
                    val native = onNodeWithContentDescription("第 1 页").getUnclippedBoundsInRoot()
                    check(abs(native.width.value - 400) < 2 && abs(native.height.value - 600) < 2)
                    fit(ZoomMode.FIT_HEIGHT)
                    readerKey(Key.PageDown); ready(1, 1)
                    readerKey(Key.PageDown); ready(2, 1)
                    onNodeWithTag("reader-zoom").assertTextEquals("100%")
                    val wideViewport = onNodeWithTag("reader-viewport").getUnclippedBoundsInRoot()
                    val wide = onNodeWithContentDescription("第 3 页").getUnclippedBoundsInRoot()
                    check(abs(wide.height.value - wideViewport.height.value) < 2 && wide.width > wideViewport.width)
                    val ltrNumeral = whiteCenter()
                    readerMode("right_to_left")
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.readerSettings.rightToLeft }
                    settled()
                    check(whiteCenter().x < ltrNumeral.x - 100f) { "RTL fit-height reading edge did not move rendered pixels" }
                    snapshot("fit-height-rtl")
                    readerKey(Key.PageDown); ready(3, 1)
                    readerDoublePages()
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.readerSettings.mode == DesktopReaderMode.DOUBLE
                    }
                    ready(3, 2)
                    fit(ZoomMode.FIT_WIDTH)
                    val groupProgress = runBlocking { session.library.progress(content.id) }
                    val groupRequests = System.getProperty("fixture.reader.requests")
                    repeat(10) { onNodeWithTag("reader-zoom-in").performClick(); settled() }
                    onNodeWithTag("reader-viewport").performMouseInput {
                        dragAndDrop(center, center + Offset(140f, -110f))
                    }
                    settled()
                    check(controller.state.value.pageIndex == 3 && controller.state.value.readerImages.size == 5)
                    check(runBlocking { session.library.progress(content.id) } == groupProgress)
                    check(System.getProperty("fixture.reader.requests") == groupRequests)
                    snapshot("double-zoom")
                }
            }
        } finally { runBlocking { controller.shutdown() } }
        runBlocking { DesktopRuntime.open(root).use { check(it.storageInfo().schemaVersion == 84) } }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
