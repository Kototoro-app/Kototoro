package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.desktop.runtime.DesktopRuntime
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Loopback interaction fixture; no production website or clearance token is involved. */
internal object DesktopBrowserProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        check(Files.isRegularFile(Path.of(args[4]))) { "Build the Windows WebView2 bridge before this probe" }
        System.setProperty("kototoro.compat.bridge.exe", args[4])
        val root = Path.of(args[1])
        val reports = Files.createDirectories(Path.of(args[3]))
        val requests = LinkedBlockingQueue<String>()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/") { exchange ->
                try {
                    requests.add(exchange.requestURI.path)
                    if (exchange.requestURI.path == "/slow") Thread.sleep(2000)
                    val bytes = """
                        <!doctype html><meta charset="utf-8"><title>本地交互页面</title>
                        <style>body{background:#184860;color:white;font:24px sans-serif}</style>
                        <button id="confirm" onclick="document.cookie='desktop_interaction=confirmed; Max-Age=3600; Path=/';
                        document.title='交互已确认'">确认</button>
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (_: IOException) { /* cancelled navigation */ }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                finally { exchange.close() }
            }
            start()
        }
        var controller: DesktopController? = null
        try {
            val session = runBlocking { DesktopSession.open(root) }
            val owner = DesktopController(session).also { controller = it }
            val browser = requireNotNull(session.browser)
            val address = "http://127.0.0.1:${server.address.port}/interaction"
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(owner) }
                waitUntil(timeoutMillis = 15_000) { !owner.state.value.busy }
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { !owner.state.value.busy }
                onNodeWithText("浏览器调试", useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 15_000) { owner.state.value.screen == DesktopScreen.BROWSER && !owner.state.value.busy }
                fun click(tag: String, expected: String) {
                    onNodeWithTag(tag).performScrollTo().performClick()
                    waitUntil(timeoutMillis = 15_000) {
                        onAllNodesWithTag("browser-busy").fetchSemanticsNodes().isEmpty() &&
                            onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    onNodeWithTag("browser-result").assertTextContains(expected, substring = true)
                }
                onNodeWithTag("browser-url").performTextReplacement(address)
                click("browser-open", "本地交互页面")
                check(runBlocking { browser.windowState() }.visible)
                check(requests.contains("/interaction"))
                onNodeWithTag("browser-script").performTextReplacement("document.getElementById('confirm').click();document.title")
                click("browser-evaluate", "交互已确认")
                click("browser-cookies", "Cookie 已同步")
                val stored = session.storage.preferences.open("cookie_store").snapshot()["127.0.0.1"]
                check((stored as? SourcePreferenceValue.TextSet)?.values?.any {
                    it.startsWith("desktop_interaction=confirmed;")
                } == true) { "Browser interaction Cookie did not reach the SDK preference store" }
                click("browser-hide", "窗口已隐藏")
                check(!runBlocking { browser.windowState() }.visible)
                click("browser-show", "窗口已显示")
                check(runBlocking { browser.windowState() }.visible)
                onNodeWithTag("browser-url").performTextReplacement("file:///invalid")
                click("browser-open", "请输入有效的 HTTP 或 HTTPS 网页地址")
                onNodeWithTag("browser-script").performTextReplacement("document.title")
                click("browser-evaluate", "交互已确认")
                onNodeWithTag("browser-html").performTextReplacement("<meta charset='utf-8'><title>中文离线页面</title>")
                click("browser-load", "中文离线页面")
                onNodeWithTag("browser-url").performScrollTo()
                val bitmap = onRoot().captureToImage()
                Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image ->
                    image.encodeToData()?.use { Files.write(reports.resolve("browser-panel.png"), it.bytes) }
                }
                // Leaving the panel cancels its UI coroutine, but the browser remains owned and reusable.
                onNodeWithTag("browser-url").performTextReplacement(address.replace("/interaction", "/slow"))
                requests.clear()
                onNodeWithTag("browser-open").performClick()
                waitUntil(timeoutMillis = 5000) { requests.contains("/slow") }
                onNodeWithText("收藏", useUnmergedTree = true).performClick()
                waitUntil(timeoutMillis = 5000) { owner.state.value.screen == DesktopScreen.LIBRARY }
                waitUntil(timeoutMillis = 5000) { !browser.isBusy }
                check(runBlocking { browser.windowState() }.visible)
                check(runBlocking { browser.evaluate("<title>导航取消后恢复</title>", "document.title") } == "\"导航取消后恢复\"")
            }
            // A session close must release a visible bridge with a navigation outstanding.
            runBlocking {
                requests.clear()
                val navigation = async(Dispatchers.IO) { runCatching { browser.openUrl(address.replace("/interaction", "/slow")) } }
                check(requests.poll(3, TimeUnit.SECONDS) == "/slow")
                owner.shutdown()
                check(withTimeout(3000) { navigation.await() }.isFailure)
            }
            controller = null
            runBlocking { DesktopRuntime.open(root).use { check(it.storageInfo().schemaVersion == 84) } }
            val moved = root.resolveSibling("${root.fileName}-released")
            check(moved.parent == root.parent && moved != root)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                try { Files.move(root, moved); break } catch (error: IOException) {
                    if (System.nanoTime() >= deadline) throw error
                    Thread.sleep(100)
                }
            }
            Files.move(moved, root)
            println("DESKTOP_UI_OK=browser")
            println("NETWORK_SCOPE=127.0.0.1")
        } finally {
            controller?.let { runBlocking { it.shutdown() } }
            server.stop(0)
            executor.shutdownNow()
            executor.awaitTermination(3, TimeUnit.SECONDS)
        }
    }
}
