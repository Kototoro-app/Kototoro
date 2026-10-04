package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.reader.core.ZoomMode
import java.nio.file.Files
import java.nio.file.Path

/** Actual native extension, 24k-pixel page, both renderers and region pixel evidence. */
internal object DesktopTilesProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        System.setProperty("fixture.reader.tall", "true")
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runBlocking { controller.importJar(Path.of(args[6])).join() }
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun tiles() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy && controller.state.value.readerLoading.isEmpty() &&
                        onAllNodes(hasTestTag("reader-tile:0:0")).fetchSemanticsNodes().isNotEmpty() }
                }
                fun snapshot(name: String) {
                    val bitmap = onRoot().captureToImage()
                    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image -> image.encodeToData()?.use {
                        Files.write(Path.of(args[3]).resolve("reader-tiles-$name.png"), it.bytes)
                    } }
                }
                fun bluePixels() {
                    waitUntil(timeoutMillis = 15_000) {
                        val viewport = onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot
                        onRoot().captureToImage().asSkiaBitmap().use { pixels ->
                            ((viewport.top + 20).toInt() until (viewport.bottom - 20).toInt()).all { y ->
                                val color = java.awt.Color(pixels.getColor(viewport.center.x.toInt(), y), true)
                                color.blue > color.red + 40 && color.blue > color.green + 20
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                onNodeWithTag("source:MIHON_9007199254740995").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.size == 1 && !controller.state.value.busy }
                val content = controller.state.value.items.single()
                onNodeWithTag("content:${content.id}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS && !controller.state.value.busy }
                onNodeWithText("开始 / 继续阅读").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.image != null && !controller.state.value.busy }
                runBlocking { controller.readerSettings(DesktopReaderSettings(fitMode = ZoomMode.FIT_WIDTH)).join() }
                tiles()
                check(controller.state.value.readerImages.values.single().height == 24000)
                snapshot("paged-top")
                val requests = System.getProperty("fixture.reader.requests")
                val history = runBlocking { session.library.progress(content.id) }
                // The immersive reader is wider. Reach the authored blue middle in source coordinates,
                // rather than depending on the former sidebar's fixed viewport width.
                val viewportWidth = onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot.width
                val sourceWidth = controller.state.value.readerImages.values.single().width
                onNodeWithTag("reader-viewport").performMouseInput {
                    moveTo(center); scroll(viewportWidth * 12000f / sourceWidth / 48f)
                }
                waitUntil(timeoutMillis = 15_000) { onAllNodes(hasTestTag("reader-tile:0:0")).fetchSemanticsNodes().isEmpty() }
                waitForIdle()
                check(System.getProperty("fixture.reader.requests") == requests)
                check(runBlocking { session.library.progress(content.id) } == history)
                bluePixels()
                snapshot("paged-middle")
                runBlocking { controller.readerSettings(DesktopReaderSettings(DesktopReaderMode.CONTINUOUS)).join() }
                tiles()
                val image = controller.state.value.readerImages.values.single()
                val width = onNodeWithTag("reader-page:${controller.state.value.pages[0].id}")
                    .fetchSemanticsNode().boundsInRoot.width
                val target = width * 12180f / image.width
                onNodeWithTag("reader-scroll-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, target) }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.readerScroll > target - 20 && controller.state.value.readerScrollReady }
                waitUntil(timeoutMillis = 15_000) { onAllNodes(hasTestTag("reader-tile:23:0")).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                check(controller.state.value.pageIndex == 0)
                check(System.getProperty("fixture.reader.requests") == requests)
                bluePixels()
                snapshot("continuous-middle")
                val viewport = onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot
                val pixels = onRoot().captureToImage().asSkiaBitmap()
                val color = java.awt.Color(pixels.getColor(viewport.center.x.toInt(), viewport.center.y.toInt()), true)
                check(color.blue > color.red + 40 && color.blue > color.green + 20) { "Middle tile pixels are incorrect: $color" }
            }
        } finally {
            runBlocking { controller.shutdown() }
            System.clearProperty("fixture.reader.tall")
        }
        println("DESKTOP_UI_OK=reader-tiles")
        println("NETWORK_REQUESTS=0")
    }
}
