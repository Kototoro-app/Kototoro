package org.skepsun.kototoro.desktop.compat

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
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
