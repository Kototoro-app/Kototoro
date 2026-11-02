package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourceEcosystem
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android's "test all sources" on Windows (plan §184): a local Apple-CMS site is imported as a source, the extensions
 * page row opens the test page, and the run has to reach every step this host can honour — list, covers, details,
 * chapters, pages (here: a reachable stream, because a CMS source is a video source) and search — against that site.
 *
 * The fixture is deliberately complete: a list entry with a cover URL and one play URL, so a PASS really means each
 * request answered instead of each request being skipped.
 */
internal object DesktopSourceTestProbe {
    private const val NAME = "本地测试源"
    private const val BAD_NAME = "坏掉的测试源"

    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val root = Path.of(args[1])
        val listRequests = AtomicInteger()
        val streamRequests = AtomicInteger()
        val cover = run {
            val image = java.awt.image.BufferedImage(60, 90, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = java.awt.Color(0x1F, 0x5A, 0x4A)
            graphics.fillRect(0, 0, 60, 90)
            graphics.dispose()
            java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/cover.png") { exchange ->
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, cover.size.toLong())
                exchange.responseBody.use { it.write(cover) }
            }
            createContext("/stream.m3u8") { exchange ->
                streamRequests.incrementAndGet()
                val body = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            // One handler for every CMS query: list, detail and search all get the same work, which is enough to prove
            // each step answered. The title carries an "a" so the search step's client-side filter can match it, and the
            // play URL is a parameter so the second site can point at an episode that is down.
            fun answer(exchange: com.sun.net.httpserver.HttpExchange, stream: String = "/stream.m3u8") {
                listRequests.incrementAndGet()
                val port = address.port
                val body = """
                    {"class":[{"type_id":"1","type_name":"电影"}],
                     "list":[{"vod_id":"11","vod_name":"Fixture Arena Video","vod_remarks":"HD",
                              "type_id":"1","type_name":"电影",
                              "vod_pic":"http://127.0.0.1:$port/cover.png",
                              "vod_play_from":"line1",
                              "vod_play_url":"第1集${'$'}http://127.0.0.1:$port$stream"}]}
                """.trimIndent().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/api.php/provide/vod") { exchange -> answer(exchange) }
            // The second site lists and opens like the first, but its episode answers 500: the failing half of a run, and
            // the source "disable the failed sources" switches off. A site whose catalogue itself is broken would read as
            // UNSUPPORTED here (the CMS runtime reports a site it cannot recognise that way), which is a SKIP, not a
            // failure — so the failure lives in the one step that really did fail.
            createContext("/broken.php") { exchange -> answer(exchange, "/down.m3u8") }
            createContext("/down.m3u8") { exchange ->
                val body = "the episode is down".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                exchange.sendResponseHeaders(500, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val session = runBlocking { DesktopSession.open(root) }
        val controller = DesktopController(session)
        try {
            runDesktopComposeUiTest(width = 1120, height = 900) {
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
                fun openExtensions() {
                    onNodeWithTag("nav:更多").performClick(); idle()
                    onNodeWithTag("nav:扩展与仓库").performClick(); idle()
                }
                idle()
                openExtensions()
                onNodeWithTag("extension-add").performClick(); idle()
                onNodeWithTag("extension-add-tvbox").performClick(); idle()
                onNodeWithTag("repository-url").performTextInput("http://127.0.0.1:${server.address.port}/api.php/provide/vod")
                onNodeWithTag("json-source-name").performTextInput(NAME)
                onNodeWithTag("repository-add").performClick(); idle()
                check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                onNodeWithTag("extension-add").performClick(); idle()
                onNodeWithTag("extension-add-tvbox").performClick(); idle()
                onNodeWithTag("repository-url").performTextInput("http://127.0.0.1:${server.address.port}/broken.php")
                onNodeWithTag("json-source-name").performTextInput(BAD_NAME)
                onNodeWithTag("repository-add").performClick(); idle()
                check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                val stored = controller.state.value.jsonSources.firstOrNull { it.name == NAME }
                    ?: error("expected the working JSON source, got ${controller.state.value.jsonSources}")
                val broken = controller.state.value.jsonSources.firstOrNull { it.name == BAD_NAME }
                    ?: error("expected the broken JSON source, got ${controller.state.value.jsonSources}")
                check(controller.state.value.jsonSources.size == 2)
                val listing = controller.state.value.sources.firstOrNull { it.source.name == stored.id }
                    ?: error("the JSON source is missing from the listings")
                check(listing.ecosystem == SourceEcosystem.TVBOX) { listing.ecosystem.name }
                check(controller.state.value.enabledSources.map { it.source.name }.containsAll(listOf(stored.id, broken.id)))

                // The extensions page row: Android's "test all", as a page of its own here.
                // The test row sits on the packages tab, under the installed list's auto-update switch.
                onNodeWithTag("extensions-tab:2").performClick(); idle()
                onNodeWithTag("extension-test-sources").performClick(); idle()
                check(controller.state.value.screen == DesktopScreen.SOURCE_TEST) {
                    "expected the source test page, got ${controller.state.value.screen}"
                }
                snapshot("source-test-idle")
                onNodeWithTag("source-test-run").performClick()
                waitUntil(timeoutMillis = 60_000) { controller.sourceTester.progress.value.finished }
                waitForIdle()
                check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                val progress = controller.sourceTester.progress.value
                check(!progress.cancelled) { "the run was cancelled" }
                check(progress.done == 2 && progress.total == 2) { "done=${progress.done} total=${progress.total}" }
                check(progress.results.map { it.sourceName }.toSet() == setOf(stored.id, broken.id)) {
                    "the run covered ${progress.results.map { it.sourceName }}"
                }
                val result = progress.results.first { it.sourceName == stored.id }
                check(result.title == NAME) { result.title }
                val expected = mapOf(
                    SourceTestStep.LIST to SourceTestStatus.PASS,
                    SourceTestStep.COVERS to SourceTestStatus.PASS,
                    SourceTestStep.DETAILS to SourceTestStatus.PASS,
                    SourceTestStep.CHAPTERS to SourceTestStatus.PASS,
                    SourceTestStep.PAGES to SourceTestStatus.PASS,
                    SourceTestStep.SEARCH to SourceTestStatus.PASS,
                )
                expected.forEach { (step, status) ->
                    val actual = result.steps[step]?.status
                        ?: error("$step was not run: ${result.steps}")
                    check(actual == status) { "$step read $actual: ${result.steps[step]?.message}" }
                }
                check(result.overall == SourceTestStatus.PASS) { result.overall.name }
                check(listRequests.get() > 1) { "the CMS site was only asked ${listRequests.get()} times" }
                check(streamRequests.get() > 0) { "the stream URL was never probed" }
                // The site with the dead episode fails exactly that step and keeps the rest, instead of stopping the run.
                val failed = progress.results.first { it.sourceName == broken.id }
                check(failed.overall == SourceTestStatus.FAIL) { failed.overall.name }
                check(failed.steps[SourceTestStep.LIST]?.status == SourceTestStatus.PASS) { failed.steps }
                check(failed.steps[SourceTestStep.PAGES]?.status == SourceTestStatus.FAIL) {
                    "the dead episode read ${failed.steps[SourceTestStep.PAGES]}"
                }
                check(failed.steps[SourceTestStep.PAGES]?.message.orEmpty().contains("HTTP 500")) {
                    failed.steps[SourceTestStep.PAGES]?.message.orEmpty()
                }
                check(failed.steps[SourceTestStep.SEARCH]?.status == SourceTestStatus.PASS) {
                    "a failing step stopped the steps after it: $failed"
                }
                check(progress.failedSources.map { it.sourceName } == listOf(broken.id)) { progress.failedSources }
                // The page agrees with the run: both rows, the passing one expanded down to a step, and the summary.
                onNodeWithTag("source-test-result:${stored.id}").performClick(); waitForIdle()
                onNodeWithTag("source-test-verdict:${stored.id}", useUnmergedTree = true).assertExists()
                onNodeWithTag("source-test-step:${stored.id}:PAGES", useUnmergedTree = true).assertExists()
                onNodeWithTag("source-test-verdict:${broken.id}", useUnmergedTree = true).assertExists()
                onNodeWithTag("source-test-summary").assertExists()
                snapshot("source-test-results")
                println("DESKTOP_SOURCE_TEST=overall:${result.overall}")
                result.steps.forEach { (step, value) -> println("DESKTOP_SOURCE_TEST=${step.name}:${value.status}") }
                // The exported JSON is the run's own record, compared across platforms by these four words.
                val exported = progress.toExportJson()
                check(exported.contains("\"status\":\"pass\"")) { exported }
                check(exported.contains("\"status\":\"fail\"")) { exported }
                check(exported.contains("\"LIST\":\"pass\"")) { exported }
                check(exported.contains("\"messages\":{\"PAGES\":\"Stream failed: HTTP 500\"}")) { exported }
                println("DESKTOP_SOURCE_TEST_EXPORT=$exported")
                // Android's "disable the failed sources": the broken row leaves the rail, the passing one stays.
                onNodeWithTag("source-test-disable-failed").performClick(); idle()
                check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                check(broken.id in controller.state.value.disabledSources) { controller.state.value.disabledSources }
                check(controller.state.value.enabledSources.none { it.source.name == broken.id })
                check(controller.state.value.enabledSources.any { it.source.name == stored.id })
                check(session.disabledSources().contains(broken.id)) { session.disabledSources() }
                snapshot("source-test-disabled")
                // Re-entering forgets the run, so a second visit does not show verdicts for a source now switched off.
                onNodeWithTag("source-test-back").performClick(); idle()
                check(controller.state.value.screen == DesktopScreen.EXTENSIONS)
                // The test row sits on the packages tab, under the installed list's auto-update switch.
                onNodeWithTag("extensions-tab:2").performClick(); idle()
                onNodeWithTag("extension-test-sources").performClick(); idle()
                check(!controller.sourceTester.progress.value.finished) { "the previous run was kept" }
                check(controller.sourceTester.progress.value.results.isEmpty())
            }
        } finally {
            runBlocking { controller.shutdown() }
            server.stop(0)
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }
}
