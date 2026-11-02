package org.skepsun.kototoro.desktop.compat

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class DesktopWebViewBridgeTest {
    private fun bridgeExecutable(): File? {
        val path = System.getProperty("kototoro.compat.bridge.exe")
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.isFile) return file
        }
        return null
    }

    private fun testProfileDir(prefix: String): Path {
        val root = Path.of("build/test-wv2-profiles").resolve("${prefix}_${UUID.randomUUID()}")
        Files.createDirectories(root)
        return root
    }

    @Test
    fun `bridge launches and reports Evergreen Edge WebView2 version`() = runBlocking {
        val exe = bridgeExecutable()
        assumeTrue(exe != null && exe.isFile, "WebView2 bridge executable must be available on Windows")

        val profileDir = testProfileDir("version")
        DesktopWebViewBridge(exe!!, userDataDir = profileDir).use { bridge ->
            val version = bridge.start()
            assertFalse(version.isBlank(), "WebView2 version should not be blank")
            assertTrue(version.contains("."), "Expected semantic version format, got: $version")
        }
    }

    @Test
    fun `bridge loads offline HTML and evaluates JavaScript expressions`() = runBlocking {
        val exe = bridgeExecutable()
        assumeTrue(exe != null && exe.isFile, "WebView2 bridge executable must be available on Windows")

        val profileDir = testProfileDir("html")
        DesktopWebViewBridge(exe!!, userDataDir = profileDir).use { bridge ->
            bridge.start()

            val html = """
                <!DOCTYPE html>
                <html>
                <head><title>Kototoro KMP</title></head>
                <body>
                    <div id="content" data-count="21">Hello Kototoro</div>
                </body>
                </html>
            """.trimIndent()

            val loaded = bridge.loadHtml(html)
            assertTrue(loaded, "Failed to load offline HTML")

            val titleResult = bridge.evaluateJs("document.title")
            assertEquals("\"Kototoro KMP\"", titleResult)

            val divContent = bridge.evaluateJs("document.getElementById('content').innerText")
            assertEquals("\"Hello Kototoro\"", divContent)

            val calcResult = bridge.evaluateJs("parseInt(document.getElementById('content').dataset.count) * 2")
            assertEquals("42", calcResult)
        }
    }

    @Test
    fun `bridge sets and retrieves cookies for an origin`() = runBlocking {
        val exe = bridgeExecutable()
        assumeTrue(exe != null && exe.isFile, "WebView2 bridge executable must be available on Windows")

        val profileDir = testProfileDir("cookie")
        DesktopWebViewBridge(exe!!, userDataDir = profileDir).use { bridge ->
            bridge.start()

            val origin = "https://kototoro.org/"
            val setOk = bridge.setCookie(
                url = origin,
                name = "cf_clearance",
                value = "mock_cf_token_12345",
                domain = ".kototoro.org",
                path = "/",
            )
            assertTrue(setOk, "Cookie set failed")

            val cookies = bridge.getCookies(origin)
            assertTrue(cookies.any { it.name == "cf_clearance" && it.value == "mock_cf_token_12345" },
                "Expected cf_clearance cookie not found: $cookies")
        }
    }

    @Test
    fun `DesktopWebViewProvider integrates with android webkit WebView to evaluate JavaScript`() = runBlocking {
        val exe = bridgeExecutable()
        assumeTrue(exe != null && exe.isFile, "WebView2 bridge executable must be available on Windows")

        val profileDir = testProfileDir("provider")
        val bridge = DesktopWebViewBridge(exe!!, userDataDir = profileDir)
        bridge.start()
        var activeProvider: DesktopWebViewProvider? = null

        try {
            if (Looper.myLooper() == null) {
                Looper.prepare()
            }

            WebView.setProviderFactory { wv ->
                DesktopWebViewProvider(wv, bridge).also { activeProvider = it }
            }

            val mockContext = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
            }

            // In AndroidCompat, WebView(Context) invokes the provider factory
            val webView = WebView(mockContext)
            assertNotNull(activeProvider)
            assertNotNull(webView.settings)

            val provider = requireNotNull(activeProvider)
            val html = "<html><head><title>Provider Title</title></head><body>OK</body></html>"
            provider.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)

            // Evaluate JS with ValueCallback
            val callbackResult = CompletableDeferred<String>()
            provider.evaluateJavaScript("document.title") { result ->
                callbackResult.complete(result)
            }

            val title = withTimeout(10000) { callbackResult.await() }
            assertEquals("\"Provider Title\"", title)
        } finally {
            activeProvider?.destroy()
            bridge.close()
            WebView.setProviderFactory(null)
        }
    }

    /** A page that requests a stream from its script and from a frame, as video embeds do. */
    private fun requestPage(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = when (exchange.requestURI.path) {
                "/page" -> "<iframe src='/frame'></iframe><script>" +
                    "fetch('/api/data', {method: 'POST', headers: {'X-Token': 'abc'}, body: '{}'});</script>"
                "/frame" -> "<script>fetch('/media/master.m3u8?token=1');</script>"
                else -> "{}"
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", if (exchange.requestURI.path.startsWith("/api")) "application/json" else "text/html")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    private suspend fun awaitRequest(requests: Collection<String>, suffix: String) = withTimeout(10000) {
        while (requests.none { it.endsWith(suffix) }) kotlinx.coroutines.delay(50)
    }

    @Test
    fun `bridge reports the page's requests while observing them`() = runBlocking<Unit> {
        val exe = bridgeExecutable()
        assumeTrue(exe != null && exe.isFile, "A desktop browser bridge executable must be available")
        val server = requestPage()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            DesktopWebViewBridge(exe!!, testProfileDir("requests")).use { bridge ->
                bridge.start()
                val requests = ConcurrentLinkedQueue<DesktopWebRequest>()
                bridge.requestListener = { requests += it }
                assertTrue(bridge.observeRequests(true))

                bridge.navigate("$base/page")
                withTimeout(10000) {
                    while (requests.none { it.url == "$base/media/master.m3u8?token=1" }) kotlinx.coroutines.delay(50)
                }

                assertTrue(requests.any { it.url == "$base/page" && it.isForMainFrame }, "main frame: $requests")
                val post = requests.single { it.url == "$base/api/data" }
                assertEquals("POST", post.method)
                assertEquals("abc", post.headers.entries.single { it.key.equals("X-Token", true) }.value)
                assertTrue(bridge.observeRequests(false))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `provider hands the page's requests to shouldInterceptRequest`() = runBlocking<Unit> {
        val exe = bridgeExecutable()
        assumeTrue(exe != null && exe.isFile, "A desktop browser bridge executable must be available")
        val server = requestPage()
        val base = "http://127.0.0.1:${server.address.port}"
        val bridge = DesktopWebViewBridge(exe!!, userDataDir = testProfileDir("provider-requests"))
        bridge.start()
        var activeProvider: DesktopWebViewProvider? = null
        try {
            if (Looper.myLooper() == null) Looper.prepare()
            WebView.setProviderFactory { wv -> DesktopWebViewProvider(wv, bridge).also { activeProvider = it } }
            val webView = WebView(object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
            })
            val seen = ConcurrentLinkedQueue<String>()
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    seen += request.url.toString()
                    return null
                }
            }

            webView.loadUrl("$base/page")

            awaitRequest(seen, "/media/master.m3u8?token=1")
            assertTrue(seen.contains("$base/api/data"), "requests: $seen")
        } finally {
            activeProvider?.destroy()
            bridge.close()
            WebView.setProviderFactory(null)
            server.stop(0)
        }
    }

    @Test
    fun `cookie deletion acknowledgement removes only the matching domain and path`() = runBlocking<Unit> {
        val exe = bridgeExecutable()
        assumeTrue(exe != null && exe.isFile, "WebView2 bridge executable must be available on Windows")
        DesktopWebViewBridge(exe!!, testProfileDir("delete-cookie")).use { bridge ->
            bridge.start()
            val origin = "https://cookie-delete.invalid/"
            bridge.setCookie(origin, "scoped", "fixture", domain = "cookie-delete.invalid", path = "/")
            bridge.setCookie(origin, "scoped", "fixture", domain = "cookie-delete.invalid", path = "/other")
            bridge.setCookie(origin, "scoped", "fixture", domain = ".cookie-delete.invalid", path = "/")
            assertEquals(3, bridge.getCookies("").count { it.name == "scoped" })
            bridge.deleteCookie("scoped", "cookie-delete.invalid", "/")
            val after = bridge.getCookies("").filter { it.name == "scoped" }
            assertEquals(setOf("cookie-delete.invalid" to "/other", ".cookie-delete.invalid" to "/"),
                after.map { it.domain to it.path }.toSet())
            bridge.deleteCookie("scoped", "cookie-delete.invalid", "/other")
            bridge.deleteCookie("scoped", ".cookie-delete.invalid", "/")
            assertTrue(bridge.getCookies("").isEmpty())
        }
    }

    @Test
    fun `bridge closes cleanly and terminates process`() = runBlocking {
        val exe = bridgeExecutable()
        assumeTrue(exe != null && exe.isFile, "WebView2 bridge executable must be available on Windows")

        val profileDir = testProfileDir("clean")
        val bridge = DesktopWebViewBridge(exe!!, userDataDir = profileDir)
        bridge.start()
        bridge.loadHtml("<html><body>cleanup test</body></html>")
        bridge.close()
        assertEquals(0, bridge.processExitCode)

        // Calling methods after close fails
        val failure = runCatching { bridge.loadHtml("<html></html>") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is IllegalStateException)
    }
}
