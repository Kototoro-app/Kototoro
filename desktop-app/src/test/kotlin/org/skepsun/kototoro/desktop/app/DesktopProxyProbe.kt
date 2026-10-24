package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.desktop.runtime.DesktopProxySettings
import org.skepsun.kototoro.desktop.runtime.DesktopProxyType
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android's proxy page over the real UI and the real HTTP client. A loopback server plays the proxy, so both the page's
 * "test connection" row and a JSON import that only resolves when it is proxied are observable without the internet.
 * Windows had no proxy at all before this page: every source, cover and import used the direct connection.
 */
internal object DesktopProxyProbe {
    @OptIn(ExperimentalTestApi::class)
    fun run(args: Array<String>) {
        val hits = AtomicInteger()
        val targets = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val target = exchange.requestURI.toString()
            hits.incrementAndGet()
            synchronized(targets) { targets.add(target) }
            val body = if (target.endsWith("/config.json")) PROXIED_CONFIG else "ok"
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
        val port = server.address.port
        val session = runBlocking { DesktopSession.open(Path.of(args[1])) }
        val controller = DesktopController(session)
        try {
            runDesktopComposeUiTest(width = 1260, height = 850) {
                setContent { DesktopApp(controller) }
                fun idle() {
                    waitUntil(timeoutMillis = 15_000) { !controller.state.value.busy }
                    waitForIdle()
                }
                idle()
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("proxy-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.PROXY_SETTINGS }
                idle()
                // A fresh install is Android's direct connection: the address rows are inert and nothing is routed.
                check(controller.state.value.proxySettings == DesktopProxySettings()) {
                    "a fresh store is direct: ${controller.state.value.proxySettings}"
                }

                // Android's type row, then the address and port the extension's requests will use.
                onNodeWithTag("proxy-type").performScrollTo().performClick()
                onNodeWithTag("proxy-type-option:HTTP").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.proxySettings.type == DesktopProxyType.HTTP }
                idle()
                // Without an address the test row explains the incomplete configuration instead of offering the test.
                onNodeWithTag("proxy-test").performScrollTo().assertExists()
                onNodeWithTag("proxy-address").performScrollTo().performClick()
                onNodeWithTag("proxy-address-field").performTextReplacement("127.0.0.1")
                onNodeWithTag("proxy-address-save").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.proxySettings.port.isEmpty() }
                idle()
                onNodeWithTag("proxy-port").performScrollTo().performClick()
                onNodeWithTag("proxy-port-field").performTextReplacement(port.toString())
                onNodeWithTag("proxy-port-save").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.proxySettings.isConfigured }
                // A port that is not a port keeps the dialog open instead of saving nonsense.
                onNodeWithTag("proxy-port").performScrollTo().performClick()
                onNodeWithTag("proxy-port-field").performTextReplacement("70000")
                onNodeWithTag("proxy-port-save").performClick()
                idle()
                onNodeWithTag("proxy-port-dialog").assertExists()
                onNodeWithTag("proxy-port-field").performTextReplacement(port.toString())
                onNodeWithTag("proxy-port-save").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.proxySettings.port == port.toString() }
                idle()
                val saved = controller.state.value.proxySettings
                check(saved == DesktopProxySettings(DesktopProxyType.HTTP, "127.0.0.1", port.toString())) { "$saved" }
                // The page writes the store the shared client reads, not a copy the page owns.
                check(session.proxy.settings.value == saved) { "the client saw ${session.proxy.settings.value}" }

                // Android's "test connection" row: the shared client asks a plain HTTP target through the saved route.
                val before = hits.get()
                onNodeWithTag("proxy-test").performScrollTo().performClick()
                waitUntil(timeoutMillis = 30_000) { controller.state.value.proxyTest.message != null }
                idle()
                onNodeWithTag("proxy-test-result").assertExists()
                val test = controller.state.value.proxyTest
                check(test.success && test.message == AndroidStrings["connection_ok"]) { "result: $test" }
                check(hits.get() > before) { "the test request did not reach the proxy: hits=${hits.get()}" }
                onNodeWithTag("proxy-test-close").performClick()
                idle()
                check(controller.state.value.proxyTest.message == null) { "closing leaves no result behind" }

                // The real client behind every source and import now uses that route: this host cannot resolve, so the
                // import only succeeds when the request goes through the proxy.
                runBlocking { controller.importJsonSource(PROXIED_CONFIG_URL).join() }
                waitUntil(timeoutMillis = 30_000) { controller.state.value.jsonSources.isNotEmpty() }
                idle()
                check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
                check(controller.state.value.jsonSources.single().name == "Proxied") {
                    "the config came through the proxy: ${controller.state.value.jsonSources}"
                }
                check(synchronized(targets) { targets.any { it.endsWith("/config.json") } }) { "$targets" }

                // The import leaves for the extensions page, as Android's add-source flow does; the proxy page is one
                // "more" visit away.
                onNodeWithTag("nav:更多").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.MORE }
                idle()
                onNodeWithTag("proxy-settings-open").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.screen == DesktopScreen.PROXY_SETTINGS }
                idle()

                // Android stores the type by name; turning it back to direct must change the next request's route.
                onNodeWithTag("proxy-type").performScrollTo().performClick()
                onNodeWithTag("proxy-type-option:DIRECT").performClick()
                waitUntil(timeoutMillis = 15_000) {
                    controller.state.value.proxySettings.type == DesktopProxyType.DIRECT
                }
                idle()
                check(session.proxy.settings.value.type == DesktopProxyType.DIRECT)
                onNodeWithTag("proxy-settings-back").performScrollTo().performClick()
                idle()
                check(controller.state.value.screen == DesktopScreen.MORE) { "the page's back button returns to more" }
                val routed = hits.get()
                runBlocking { controller.importJsonSource(DIRECT_CONFIG_URL).join() }
                waitUntil(timeoutMillis = 30_000) { controller.state.value.error != null }
                idle()
                check(hits.get() == routed) { "a direct import must not reach the proxy: hits=${hits.get()}" }
                check(controller.state.value.jsonSources.size == 1) { "the failed import added a source" }
            }
        } finally {
            runBlocking { controller.shutdown() }
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
        println("DESKTOP_UI_OK=${args[0]}")
    }

    /** A TVBox document the importer accepts; only the config request is proxied, the API is never called here. */
    private const val PROXIED_CONFIG =
        """{"sites":[{"key":"proxied","name":"Proxied","type":1,"api":"https://a.example/api.php/provide/vod"}]}"""
    private const val PROXIED_CONFIG_URL = "http://tvbox-import.invalid/config.json"
    private const val DIRECT_CONFIG_URL = "http://tvbox-direct.invalid/config.json"
}
