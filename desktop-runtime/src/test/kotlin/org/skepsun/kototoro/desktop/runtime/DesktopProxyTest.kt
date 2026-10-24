package org.skepsun.kototoro.desktop.runtime

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourcePreferences
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Android's `ProxySettingsScreen` reaches Windows through these two pieces: the value the page writes and the selector
 * the shared SDK client asks on every request. A loopback HTTP server plays the proxy, so routing and credentials are
 * checked without any outside network.
 */
class DesktopProxyTest {
    @TempDir
    lateinit var directory: Path

    // A closed loopback port: proxied it answers from the proxy, direct it is a refused connection, never DNS.
    // "localhost" rather than the proxy's own address keeps OkHttp from reusing the pooled proxy connection when the
    // setting flips back to direct, which is exactly the transition the page performs.
    private val directRequest = URI("http://localhost:1/probe")

    @Test
    fun `a fresh store is Android's direct connection`() = withStore { preferences ->
        val proxy = DesktopProxy(preferences)
        val settings = proxy.settings.value
        assertEquals(DesktopProxyType.DIRECT, settings.type)
        assertFalse(settings.isEnabled)
        assertTrue(settings.isConfigured)
        assertNull(proxy.proxyFor())
        assertEquals(listOf<Proxy>(Proxy.NO_PROXY), proxy.selector.select(directRequest))
    }

    @Test
    fun `the page's value round-trips through Android's proxy keys`() = withStore { preferences ->
        val saved = DesktopProxySettings(DesktopProxyType.SOCKS, "127.0.0.1", "1080", "user", "secret")
        val proxy = DesktopProxy(preferences)
        assertTrue(proxy.save(saved))

        assertEquals(
            setOf("proxy_type_2", "proxy_address", "proxy_port", "proxy_login", "proxy_password"),
            saved.toPreferences().keys,
        )
        assertEquals(saved, proxy.settings.value)
        // A restart reads the same keys back, exactly like Android's preference file.
        assertEquals(saved, DesktopProxy(preferences).settings.value)
        proxy.reload()
        assertEquals(saved, proxy.settings.value)
    }

    @Test
    fun `an incomplete configuration stays direct instead of inventing a proxy`() = withStore { preferences ->
        val proxy = DesktopProxy(preferences)
        for (broken in listOf(
            DesktopProxySettings(DesktopProxyType.HTTP, "", "7890"),
            DesktopProxySettings(DesktopProxyType.HTTP, "127.0.0.1", ""),
            DesktopProxySettings(DesktopProxyType.HTTP, "127.0.0.1", "0"),
            DesktopProxySettings(DesktopProxyType.SOCKS, "127.0.0.1", "70000"),
            DesktopProxySettings(DesktopProxyType.SOCKS, "127.0.0.1", "port"),
        )) {
            proxy.save(broken)
            assertFalse(broken.isConfigured, "$broken")
            assertNull(proxy.proxyFor(), "$broken")
            assertEquals(listOf<Proxy>(Proxy.NO_PROXY), proxy.selector.select(directRequest))
        }
        val fixed = proxy.save(DesktopProxySettings(DesktopProxyType.HTTP, " 127.0.0.1 ", "7890"))
        assertTrue(fixed)
        val route = proxy.proxyFor()
        assertEquals(Proxy.Type.HTTP, route?.type())
        assertEquals(InetSocketAddress.createUnresolved("127.0.0.1", 7890), route?.address())
    }

    @Test
    fun `the selector sends the shared client's request to the configured proxy`() = withStore { preferences ->
        val proxy = DesktopProxy(preferences)
        ProxySite().use { site ->
            proxy.save(DesktopProxySettings(DesktopProxyType.HTTP, "127.0.0.1", site.port.toString()))
            val client = OkHttpClient.Builder().proxySelector(proxy.selector).build()
            client.newCall(Request.Builder().url(directRequest.toString()).build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("proxied", response.body.string())
            }
            assertEquals(1, site.hits.get())
            assertEquals(directRequest.toString(), site.target.get())

            // Turning the page back to direct makes the selector answer for the next connection; no client rebuild.
            proxy.save(proxy.settings.value.copy(type = DesktopProxyType.DIRECT))
            assertEquals(listOf<Proxy>(Proxy.NO_PROXY), proxy.selector.select(directRequest))
            // The session drops pooled connections when the route changes, so the proxy connection is not reused.
            client.connectionPool.evictAll()
            val direct = runCatching {
                client.newCall(Request.Builder().url(directRequest.toString()).build()).execute().use {
                    it.code to it.body.string()
                }
            }
            assertTrue(direct.isFailure, "$direct hits=${site.hits.get()}")
            assertEquals(1, site.hits.get())
        }
    }

    @Test
    fun `credentials answer an HTTP proxy challenge and are absent when unset`() = withStore { preferences ->
        val proxy = DesktopProxy(preferences)
        ProxySite(credentials = "user:secret").use { site ->
            proxy.save(DesktopProxySettings(DesktopProxyType.HTTP, "127.0.0.1", site.port.toString(), "user", "secret"))
            val client = OkHttpClient.Builder()
                .proxySelector(proxy.selector)
                .proxyAuthenticator(proxy.authenticator)
                .build()
            client.newCall(Request.Builder().url(directRequest.toString()).build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("proxied", response.body.string())
            }
            assertEquals(2, site.hits.get())

            proxy.save(DesktopProxySettings(DesktopProxyType.HTTP, "127.0.0.1", site.port.toString()))
            client.newCall(Request.Builder().url(directRequest.toString()).build()).execute().use { response ->
                assertEquals(407, response.code)
            }
            assertEquals(3, site.hits.get())
        }
    }

    private fun withStore(block: (SourcePreferences) -> Unit) {
        FileSourcePreferenceStore(directory.resolve("preferences")).use { store ->
            block(store.open("desktop_network"))
        }
    }

    /** A loopback HTTP proxy: it answers every absolute-URI request itself and records what OkHttp asked for. */
    private class ProxySite(private val credentials: String? = null) : AutoCloseable {
        val hits = AtomicInteger()
        val target = AtomicReference<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        val port: Int get() = server.address.port

        init {
            server.createContext("/") { exchange -> exchange.respond() }
            server.executor = Executors.newSingleThreadExecutor()
            server.start()
        }

        private fun HttpExchange.respond() {
            hits.incrementAndGet()
            target.set(requestURI.toString())
            val expected = credentials?.let { "Basic " + Base64.getEncoder().encodeToString(it.toByteArray()) }
            if (expected != null && requestHeaders.getFirst("Proxy-Authorization") != expected) {
                responseHeaders.add("Proxy-Authenticate", "Basic realm=\"desktop-proxy\"")
                sendResponseHeaders(407, -1)
                close()
                return
            }
            val body = "proxied".toByteArray()
            sendResponseHeaders(200, body.size.toLong())
            responseBody.use { it.write(body) }
            close()
        }

        override fun close() {
            server.stop(0)
            (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }
    }
}
