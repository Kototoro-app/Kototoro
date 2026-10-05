package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object DesktopChallengeProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        System.setProperty("kototoro.compat.bridge.exe", args[4])
        val requests = CopyOnWriteArrayList<Triple<String, String?, String?>>()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/") { exchange ->
                try {
                    val mode = exchange.requestURI.query?.substringAfter("mode=").orEmpty()
                    val cookie = exchange.requestHeaders.getFirst("Cookie")
                    requests.add(Triple(mode, cookie, exchange.requestHeaders.getFirst("User-Agent")))
                    val success = mode == "success" && cookie?.contains("interaction_cookie=fixture") == true
                    val body = if (success) "source recovered" else """
                        <!doctype html><meta charset="utf-8"><title>来源验证测试页</title>
                        <h1>本地交互 fixture</h1>
                        ${if (mode == "success") "<script>document.cookie='interaction_cookie=fixture; Path=/'</script>" else ""}
                    """.trimIndent()
                    if (!success) exchange.responseHeaders.set("cf-mitigated", "challenge")
                    exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
                    exchange.responseHeaders.set("Cache-Control", "no-store")
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(if (success) 200 else 403, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally { exchange.close() }
            }
            start()
        }
        System.setProperty("fixture.interaction.url", "http://127.0.0.1:${server.address.port}")
        val root = Path.of(args[1])
        var owner: DesktopController? = null
        try {
            val session = runBlocking { DesktopSession.open(root) }
            val controller = DesktopController(session).also { owner = it }
            runBlocking { controller.importJar(Path.of(args[5])).join() }
            val challenges = requireNotNull(session.browserChallenges)
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                onNodeWithTag("source:MIHON_9007199254740994").performClick()
                waitUntil(timeoutMillis = 15_000) { challenges.pending.value != null }
                onNodeWithTag("challenge-continue").assertIsEnabled()
                onNodeWithTag("challenge-show").performClick()
                waitUntil(timeoutMillis = 5000) {
                    onAllNodesWithText("验证窗口已显示").fetchSemanticsNodes().isNotEmpty()
                }
                val bitmap = onRoot().captureToImage()
                val report = Files.createDirectories(Path.of(args[3])).resolve("source-challenge.png")
                Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                    image.encodeToData()?.use { Files.write(report, it.bytes) }
                }
                onNodeWithTag("challenge-continue").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                check(controller.state.value.items.single().title == "source recovered")
                check(challenges.pending.value == null)
                // Source request, the hidden Mihon solve (no challenge on this page, so it hands over at once), the
                // manual window and the retry. The hidden solve is skipped while the host cools down after a failure.
                check(requests.count { it.first == "success" } == 4) { "${requests.map { it.first }}" }
                check(requests.filter { it.first == "success" }.map { it.third }.distinct().size == 1)

                onNodeWithTag("source-query").performTextReplacement("cancel")
                onNodeWithTag("source-filters").performClick()
                waitUntil(timeoutMillis = 5000) { controller.state.value.filterDialogOpen && !controller.state.value.busy }
                val criterion = controller.state.value.dynamicFilters!!.nodes.single()
                onNodeWithTag("filter:${criterion.id}").performTextReplacement("保留验证草稿")
                onNodeWithTag("filter-apply").performClick()
                waitUntil(timeoutMillis = 15_000) { challenges.pending.value != null }
                onNodeWithTag("filter-dialog").assertDoesNotExist()
                check(System.getProperty("fixture.interaction.filter") == "保留验证草稿")
                val previous = requireNotNull(challenges.pending.value)
                onNodeWithTag("challenge-cancel").performClick()
                waitUntil(timeoutMillis = 5000) { !controller.state.value.busy }
                check(controller.state.value.error != null)
                check(requests.count { it.first == "cancel" } <= 2)
                check(challenges.pending.value == null)
                onNodeWithTag("filter-dialog").assertIsDisplayed()
                onNodeWithTag("filter:${criterion.id}").assertTextContains("保留验证草稿")
                onNodeWithTag("filter-cancel").performClick()

                val cancelled = controller.browse(query = "callcancel")
                waitUntil(timeoutMillis = 15_000) { challenges.pending.value != null }
                challenges.complete(previous.id, true)
                check(challenges.pending.value != null)
                cancelled.cancel()
                waitUntil(timeoutMillis = 5000) { cancelled.isCompleted && challenges.pending.value == null }

                controller.browse(query = "unchanged")
                waitUntil(timeoutMillis = 15_000) { challenges.pending.value != null }
                onNodeWithTag("challenge-continue").performClick()
                waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                check(controller.state.value.error != null)
                check(challenges.pending.value == null)
                check(requests.count { it.first == "unchanged" } in 3..4) { "${requests.map { it.first }}" }

                controller.browse(query = "close")
                waitUntil(timeoutMillis = 15_000) { challenges.pending.value != null }
            }
            runBlocking { controller.shutdown() }
            owner = null
            check(challenges.pending.value == null)
            runBlocking { DesktopRuntime.open(root).use { check(it.storageInfo().schemaVersion == 84) } }
            println("DESKTOP_UI_OK=challenge")
            println("NETWORK_SCOPE=127.0.0.1")
        } catch (error: Throwable) {
            println("CHALLENGE_STATE=" + owner?.state?.value?.error)
            println("CHALLENGE_PENDING=" + owner?.session?.browserChallenges?.pending?.value?.id)
            println("CHALLENGE_BUSY=" + owner?.state?.value?.busy)
            println("FIXTURE_REQUEST_MODES=" + requests.map { it.first })
            throw error
        } finally {
            owner?.let { runBlocking { it.shutdown() } }
            server.stop(0)
            executor.shutdownNow()
            executor.awaitTermination(3, TimeUnit.SECONDS)
        }
    }
}
