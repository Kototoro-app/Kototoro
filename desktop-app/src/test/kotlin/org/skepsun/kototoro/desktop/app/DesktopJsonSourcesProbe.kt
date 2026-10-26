package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android's "JSON 源" tab: a MacCMS link is imported from the extensions page, reaches the browse rail as a source of
 * the shared CMS runtime, lists works, and disappears again when removed. The site is a local HTTP server, so nothing
 * leaves this machine.
 */
internal object DesktopJsonSourcesProbe {
    private const val NAME = "本地 JSON 源"

    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val root = Path.of(args[1])
        val requests = AtomicInteger()
        val cover = run {
            val image = java.awt.image.BufferedImage(60, 90, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = java.awt.Color(0x2E, 0x4A, 0x7A)
            graphics.fillRect(0, 0, 60, 90)
            graphics.color = java.awt.Color(0xE8, 0xC0, 0x6A)
            graphics.fillRect(0, 60, 60, 30)
            graphics.dispose()
            java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        }
        // Browse states: the list endpoint can fail with 500, or hold one request until the probe has seen the skeleton.
        val failList = java.util.concurrent.atomic.AtomicBoolean(false)
        val holdList = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch?>(null)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/cover.png") { exchange ->
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, cover.size.toLong())
                exchange.responseBody.use { it.write(cover) }
            }
            createContext("/api.php/provide/vod") { exchange ->
                requests.incrementAndGet()
                holdList.getAndSet(null)?.await(20, java.util.concurrent.TimeUnit.SECONDS)
                if (failList.get()) {
                    exchange.sendResponseHeaders(500, -1)
                    exchange.close()
                    return@createContext
                }
                val port = address.port
                val body = """
                    {"class":[{"type_id":"1","type_name":"电影"}],
                     "list":[{"vod_id":"7","vod_name":"Fixture Video","vod_remarks":"HD",
                              "type_id":"1","type_name":"电影",
                              "vod_pic":"http://127.0.0.1:$port/cover.png",
                              "vod_play_from":"line1",
                              "vod_play_url":"第1集${'$'}http://127.0.0.1:$port/ep1.m3u8"}]}
                """.trimIndent().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            runDesktopComposeUiTest(width = if (args[0] == "json-sources-narrow") 920 else 1120, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 20_000) { !controller.state.value.busy }
                    waitForIdle()
                }
                fun snapshot(name: String) {
                    Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use {
                            Files.write(Path.of(args[3]).resolve("${args[0]}-$name.png"), it.bytes)
                        }
                    }
                }
                fun snapshotTag(tag: String, label: String) {
                    Image.makeFromBitmap(onNodeWithTag(tag).captureToImage().asSkiaBitmap()).use { image ->
                        image.encodeToData()?.use {
                            Files.write(Path.of(args[3]).resolve("${args[0]}-$label.png"), it.bytes)
                        }
                    }
                }
                fun openExtensions() {
                    onNodeWithTag("nav:更多").performClick(); idle()
                    onNodeWithTag("nav:扩展与仓库").performClick(); idle()
                }
                check(controller.state.value.jsonSources.isEmpty())
                idle()
                openExtensions()
                onNodeWithTag("extension-add").performClick(); idle()
                onNodeWithTag("repository-add").assertIsNotEnabled()
                onNodeWithTag("extension-add-tvbox").performClick(); idle()
                val unavailable = "http://127.0.0.1:${server.address.port}/missing.json"
                onNodeWithTag("repository-url").performTextInput(unavailable)
                onNodeWithTag("repository-add").performClick(); idle()
                onNodeWithTag("extension-add-error").assertExists()
                onNodeWithTag("repository-url").assertTextContains(unavailable)
                onNodeWithTag("repository-url").performTextClearance()
                onNodeWithTag("repository-url")
                    .performTextInput("http://127.0.0.1:${server.address.port}/api.php/provide/vod")
                onNodeWithTag("json-source-name").performTextInput(NAME)
                snapshotTag("extension-add-dialog", "extension-add-dialog")
                onNodeWithTag("repository-add").performClick(); idle()
                check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                val stored = controller.state.value.jsonSources.singleOrNull()
                check(stored != null) { "expected one stored JSON source, got ${controller.state.value.jsonSources}" }
                check(stored.name == NAME) { stored.name }
                val saved = (session.storage.preferences.open("desktop_json_sources").snapshot()["json_sources"]
                    as? SourcePreferenceValue.Text)?.value.orEmpty()
                check(saved.contains(stored.id)) { "the JSON source was not persisted: $saved" }
                // The site reaches the browse rail as a TVBox listing, next to the installed extensions.
                val listing = controller.state.value.sources.firstOrNull { it.source.name == stored.id }
                check(listing != null) { "the JSON source is missing from the listings" }
                check(listing.displayName == NAME && listing.ecosystem == SourceEcosystem.TVBOX)
                snapshot("json-sources")
                // Browsing it goes through the shared CMS runtime and the local site.
                onNodeWithTag("nav:浏览").performClick(); idle()
                onNodeWithTag("nav:浏览").performClick(); idle()
                onNodeWithTag("source-list").performScrollToNode(hasTestTag("source:${stored.id}"))
                // Android's list states: a failed first page is the error card with retry, a pending one the skeleton.
                failList.set(true)
                onNodeWithTag("source:${stored.id}").performClick()
                idle()
                onNodeWithTag("browse-error").assertExists()
                snapshot("json-source-error")
                failList.set(false)
                val held = java.util.concurrent.CountDownLatch(1)
                holdList.set(held)
                onNodeWithTag("browse-retry").performClick()
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag("browse-loading").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithTag("browse-error").assertDoesNotExist()
                snapshot("json-source-loading")
                held.countDown()
                waitUntil(timeoutMillis = 20_000) { controller.state.value.items.isNotEmpty() }
                onNodeWithTag("browse-loading").assertDoesNotExist()
                waitForIdle()
                check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                check(requests.get() > 0) { "the CMS site was never asked for a list" }
                val item = controller.state.value.items.first()
                onNodeWithTag("content:${item.id}").assertExists()
                // TVBox sources name the cover by URL only: the host downloads it directly when the protocol says no.
                // The cover of a merged card lives in the unmerged tree; waiting on it proves the URL-only fetch works.
                waitUntil(timeoutMillis = 20_000) {
                    onAllNodesWithTag("cover:${item.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                waitForIdle()
                snapshot("json-source-browse")
                // Removing it drops both the stored document and the rail entry.
                openExtensions()
                onNodeWithTag("json-source-remove:${stored.id}").performClick(); idle()
                check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                check(controller.state.value.jsonSources.isEmpty())
                check(controller.state.value.sources.none { it.source.name == stored.id })
                snapshot("json-sources-empty")
                // Local TVBox files use the same add flow and group their sites by the stored origin.
                val file = root.resolve("local-tvbox.json")
                Files.writeString(file, """{"sites":[
                    {"key":"local-a","name":"Local A","type":1,"api":"http://127.0.0.1:${server.address.port}/api.php/provide/vod"},
                    {"key":"local-b","name":"Local B","type":1,"api":"http://127.0.0.1:${server.address.port}/api.php/provide/vod"}]}""")
                controller.importExtensionFile(file); idle()
                check(controller.state.value.jsonSources.size == 2)
                onNodeWithTag("extensions-tab:1").performClick(); idle()
                onNodeWithText("local-tvbox.json").assertExists()
                snapshot("repositories")
                onNodeWithTag("repository-remove:${file.toUri()}").performClick(); idle()
                check(controller.state.value.jsonSources.size == 2)
                onNodeWithTag("repository-remove-confirm").performClick(); idle()
                check(controller.state.value.jsonSources.isEmpty())
                check(controller.state.value.sources.none { it.ecosystem == SourceEcosystem.TVBOX })

            }
        } finally {
            runBlocking { controller.shutdown() }
            server.stop(0)
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
