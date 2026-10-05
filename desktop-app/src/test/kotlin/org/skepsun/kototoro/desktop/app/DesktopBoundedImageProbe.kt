package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Actual SDK source and Skia WebP rendering; the second JVM has no image source available. */
internal object DesktopBoundedImageProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val mode = args[0]
        val root = Path.of(args[1])
        if (mode == "reader-bounded-write") {
            Files.createDirectories(root)
            val supplied = root.resolve("authored-page.webp")
            val original = BufferedImage(3000, 2000, BufferedImage.TYPE_INT_RGB)
            val graphics = original.createGraphics()
            try {
                graphics.color = Color.RED; graphics.fillRect(0, 0, 1500, 2000)
                graphics.color = Color.BLUE; graphics.fillRect(1500, 0, 1500, 2000)
                val encoded = ByteArrayOutputStream().also { check(ImageIO.write(original, "png", it)) }
                Image.makeFromEncoded(encoded.toByteArray()).use { image ->
                    requireNotNull(image.encodeToData(EncodedImageFormat.WEBP)).use { Files.write(supplied, it.bytes) }
                }
            } finally { graphics.dispose(); original.flush() }
            System.setProperty("fixture.reader.image.path", supplied.toString())
        } else System.setProperty("fixture.reader.offline", "true")
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            if (mode == "reader-bounded-write") runBlocking { controller.importJar(Path.of(args[6])).join() }
            check(session.startupErrors.isEmpty())
            runDesktopComposeUiTest(width = if (mode == "reader-bounded-read") 920 else 1260,
                height = if (mode == "reader-bounded-read") 620 else 850) {
                setContent { DesktopApp(controller) }
                fun ready() {
                    waitUntil(timeoutMillis = 15_000) {
                        !controller.state.value.busy && controller.state.value.image != null &&
                            controller.state.value.readerLoading.isEmpty() && !controller.isPrefetching &&
                            // Neighbours are prefetched and composed too; the opened page itself must be decoded.
                            onAllNodes(hasContentDescription("第 1 页")).fetchSemanticsNodes().isNotEmpty()
                    }
                    check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                    val image = controller.state.value.readerImages[controller.state.value.pages[0].id]!!
                    check(image.width == 3000 && image.height == 2000 && !image.tiled)
                }
                fun pixels(name: String) {
                    waitUntil(timeoutMillis = 15_000) {
                        val page = onNodeWithContentDescription("第 1 页").fetchSemanticsNode().boundsInRoot
                        val viewport = onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot
                        val top = maxOf(page.top, viewport.top)
                        val bottom = minOf(page.bottom, viewport.bottom)
                        if (bottom <= top) false else onRoot().captureToImage().asSkiaBitmap().use { bitmap ->
                            val y = ((top + bottom) / 2).toInt()
                            val left = Color(bitmap.getColor((page.left + page.width / 4).toInt(), y), true)
                            val right = Color(bitmap.getColor((page.left + page.width * 3 / 4).toInt(), y), true)
                            left.red > 240 && left.blue < 15 && right.blue > 240 && right.red < 15
                        }
                    }
                    val screenshot = onRoot().captureToImage()
                    Image.makeFromBitmap(screenshot.asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use { Files.write(Path.of(args[3]).resolve("$mode-$name.png"), it.bytes) }
                    }
                }
                waitUntil(timeoutMillis = 15_000) { controller.state.value.sources.size == 1 }
                onNodeWithTag("source:MIHON_9007199254740995").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.items.size == 1 && !controller.state.value.busy }
                val content = controller.state.value.items.single()
                onNodeWithTag("content:${content.id}").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.DETAILS && !controller.state.value.busy }
                onNodeWithTag("preview-read").performClick()
                ready()
                pixels("opened")
                if (mode == "reader-bounded-write") {
                    val requests = System.getProperty("fixture.reader.requests").toInt()
                    val loadedBefore = controller.state.value.readerImages.keys
                    val history = runBlocking { session.library.progress(content.id) }
                    onNodeWithTag("reader-zoom-in").performClick()
                    pixels("zoomed")
                    check(runBlocking { session.library.progress(content.id) } == history)
                    runBlocking { controller.readerSettings(DesktopReaderSettings(DesktopReaderMode.CONTINUOUS)).join() }
                    ready()
                    waitUntil(timeoutMillis = 15_000) { controller.state.value.readerScrollReady }
                    pixels("continuous")
                    // Continuous mode prefetches its own lookahead; pages already loaded are never fetched again.
                    waitUntil(timeoutMillis = 15_000) { !controller.isPrefetching }
                    val fetched = System.getProperty("fixture.reader.requests").toInt() - requests
                    check(fetched == (controller.state.value.readerImages.keys - loadedBefore).size) { "Cached page was fetched again" }
                    runBlocking { controller.readerSettings(DesktopReaderSettings(DesktopReaderMode.DOUBLE)).join() }
                    ready()
                    val viewport = onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot
                    check(onAllNodes(hasTestTag("reader-page")).fetchSemanticsNodes().count { node ->
                        node.boundsInRoot.width > 0f && node.boundsInRoot.left >= viewport.left - 1 &&
                            node.boundsInRoot.right <= viewport.right + 1
                    } == 1)
                    pixels("wide-solo")
                } else {
                    check(controller.state.value.readerSettings.mode == DesktopReaderMode.DOUBLE)
                    check(System.getProperty("fixture.reader.requests", "0") == "0")
                    val viewport = onNodeWithTag("reader-viewport").fetchSemanticsNode().boundsInRoot
                    check(onAllNodes(hasTestTag("reader-page")).fetchSemanticsNodes().count { node ->
                        node.boundsInRoot.width > 0f && node.boundsInRoot.left >= viewport.left - 1 &&
                            node.boundsInRoot.right <= viewport.right + 1
                    } == 1)
                }
            }
        } finally {
            runBlocking { controller.shutdown() }
            System.clearProperty("fixture.reader.image.path")
            System.clearProperty("fixture.reader.offline")
        }
        println("DESKTOP_UI_OK=$mode")
        println("NETWORK_REQUESTS=0")
    }
}
