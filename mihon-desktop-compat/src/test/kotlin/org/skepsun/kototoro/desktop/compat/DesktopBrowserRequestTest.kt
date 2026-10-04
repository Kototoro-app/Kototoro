package org.skepsun.kototoro.desktop.compat

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceProtocolJson
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

class DesktopBrowserRequestTest {
    @Test
    fun `real browser preserves custom headers user agent and binary POST body on loopback`() = runBlocking<Unit> {
        LocalBrowserSite().use { site ->
            bridge().use { browser ->
                browser.start()
                browser.applySettings(true, "Kototoro-Windows-OffLine/1")
                browser.navigate(site.url("/headers"), headers = mapOf("X-Source-Test" to "kept"))
                val get = site.requests.poll(3, TimeUnit.SECONDS) ?: error("GET not received")
                assertEquals("GET", get.method)
                assertEquals("kept", get.headers["X-source-test"]?.single())
                assertEquals("Kototoro-Windows-OffLine/1", get.headers["User-agent"]?.single())
                val body = byteArrayOf(0, 1, -1, 13, 10) + "中文=原样".toByteArray()
                browser.navigate(site.url("/post"), headers = mapOf("Content-Type" to "application/octet-stream"), postData = body)
                val post = site.requests.poll(3, TimeUnit.SECONDS) ?: error("POST not received")
                assertEquals("POST", post.method)
                assertArrayEquals(body, post.body)
                assertEquals("application/octet-stream", post.headers["Content-type"]?.single())
                assertEquals("loopback", browser.document().title)
            }
        }
    }

    @Test
    fun `injected HTML retains real origin path fragment and relative resource resolution without fetching main document`() = runBlocking<Unit> {
        LocalBrowserSite().use { site ->
            bridge().use { browser ->
                browser.start()
                val base = site.url("/base/document?x=1#focus")
                browser.loadHtml("<html><title>原生 origin</title><img src='asset'></html>", baseUrl = base)
                assertEquals(site.url(""), string(browser.evaluateJs("location.origin")))
                assertEquals(base, string(browser.evaluateJs("location.href")))
                assertEquals(site.url("/base/asset"), string(browser.evaluateJs("new URL('asset', document.baseURI).href")))
                assertEquals("原生 origin", browser.document().title)
                assertEquals("/base/asset", site.requests.poll(3, TimeUnit.SECONDS)?.path)
                assertFalse(site.requests.toList().any { it.path.startsWith("/base/document") })
            }
        }
    }

    @Test
    fun `cancelling timed out navigation clears pending requests and permits the next browser command`() = runBlocking<Unit> {
        LocalBrowserSite().use { site ->
            bridge().use { browser ->
                browser.start()
                val failed = runCatching { browser.navigate(site.url("/slow"), timeoutMs = 150) }.exceptionOrNull()
                assertTrue(failed is TimeoutCancellationException, "$failed")
                assertEquals(0, browser.pendingRequestCount)
                browser.loadHtml("<html><title>after cancellation</title></html>")
                assertEquals("after cancellation", string(browser.evaluateJs("document.title")))
                val malformed = runCatching {
                    browser.navigate(site.url("/invalid"), headers = mapOf("X-Injection" to "bad\r\nOther: header"))
                }.exceptionOrNull()
                assertTrue(malformed is IllegalStateException)
                assertEquals(0, browser.pendingRequestCount)
                assertEquals(42, browser.evaluateJs("6 * 7").toInt())
            }
        }
    }

    @Test
    fun `closing during navigation terminates the owned process normally and releases pending callers`() = runBlocking<Unit> {
        LocalBrowserSite().use { site ->
            bridge().use { browser ->
                browser.start()
                val navigation = async(Dispatchers.IO) { runCatching { browser.navigate(site.url("/slow")) } }
                val request = withContext(Dispatchers.IO) { site.requests.poll(3, TimeUnit.SECONDS) }
                assertNotNull(request)
                browser.close()
                assertTrue(withTimeout(3000) { navigation.await() }.isFailure)
                assertEquals(0, browser.pendingRequestCount)
                assertEquals(0, browser.processExitCode)
            }
        }
    }

    @Test
    fun `script enabled setting controls document scripts without blocking host JS evaluation`() = runBlocking<Unit> {
        bridge().use { browser ->
            browser.start()
            browser.applySettings(false, null)
            browser.loadHtml("<html><title>before</title><script>document.title='script ran';</script></html>")
            assertEquals("before", string(browser.evaluateJs("document.title")))
            browser.applySettings(true, null)
            browser.loadHtml("<html><title>before</title><script>document.title='script ran';</script></html>")
            assertEquals("script ran", string(browser.evaluateJs("document.title")))
        }
    }

    private fun string(value: String) = SourceProtocolJson.parseToJsonElement(value).jsonPrimitive.content
    private fun bridge(): DesktopWebViewBridge {
        val executable = File(System.getProperty("kototoro.compat.bridge.exe"))
        assumeTrue(executable.isFile, "Windows WebView2 bridge must be built")
        val directory = Path.of("build/test-wv2-profiles/requests-${UUID.randomUUID()}")
        Files.createDirectories(directory)
        return DesktopWebViewBridge(executable, directory)
    }
}

/** JDK loopback-only server: production websites/DNS are never used by these browser probes. */
internal class LocalBrowserSite(private val status: Int = 200) : AutoCloseable {
    data class Request(val method: String, val path: String, val headers: Map<String, List<String>>, val body: ByteArray)
    val requests = LinkedBlockingQueue<Request>()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@LocalBrowserSite.executor
        createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                val body = exchange.requestBody.use { it.readAllBytes() }
                if (path != "/favicon.ico") requests.add(Request(exchange.requestMethod, path, exchange.requestHeaders.toMap(), body))
                if (path == "/slow") Thread.sleep(2000)
                val html = "<html><head><title>loopback</title></head><body>offline</body></html>".toByteArray()
                exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(status, html.size.toLong())
                exchange.responseBody.use { it.write(html) }
            } catch (_: java.io.IOException) { /* cancelled request */ }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finally { exchange.close() }
        }
        start()
    }
    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
    override fun close() { server.stop(0); executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS) }
}
