package org.skepsun.kototoro.desktop.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceListing
import org.skepsun.kototoro.core.source.SourceRef
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/** The desktop counterpart of Android's favicon fetcher: the browse tiles get the source's own icon, not a letter. */
class DesktopFaviconsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `a source that names its icon is fetched once and then served from disk`() = runBlocking<Unit> {
        val site = Site()
        site.answer("/icon.png", PNG, "image/png")
        try {
            val url = site.url("/icon.png")
            DesktopFavicons(directory, ::fetch, { listOf(url) }).use { favicons ->
                val icon = favicons.load(listing("named"))
                assertNotNull(icon)
                assertEquals(16, icon?.width)
                assertEquals(1, site.count("/icon.png"))
            }
            // A fresh instance on the same directory has no memory cache left; the disk copy answers instead.
            DesktopFavicons(directory, ::fetch, { error("the disk cache must answer before any candidate") })
                .use { favicons ->
                    assertNotNull(favicons.load(listing("named")))
                }
            assertEquals(1, site.count("/icon.png"))
            assertTrue(Files.list(directory).use { files -> files.anyMatch { it.toString().endsWith(".icon") } })
        } finally {
            site.close()
        }
    }

    @Test
    fun `a site address answers with its root favicon when the start page has no icon link`() = runBlocking<Unit> {
        val site = Site()
        site.answer("/", "<html><head><title>t</title></head></html>".toByteArray(), "text/html; charset=utf-8")
        site.answer("/favicon.ico", PNG, "image/png")
        try {
            val source = listing("site-only", siteUrl = site.url("/"))
            DesktopFavicons(directory, ::fetch, { emptyList() }).use { favicons ->
                assertNotNull(favicons.load(source))
            }
            assertEquals(1, site.count("/"))
            assertEquals(1, site.count("/favicon.ico"))
        } finally {
            site.close()
        }
    }

    @Test
    fun `the icon link of a start page is followed when the root favicon is missing`() = runBlocking<Unit> {
        val site = Site()
        site.answer(
            "/",
            """<html><head><link rel="shortcut icon" href="assets/logo.png"/></head></html>""".toByteArray(),
            "text/html",
        )
        site.answer("/assets/logo.png", PNG, "image/png")
        try {
            DesktopFavicons(directory, ::fetch, { listOf(site.url("/")) }).use { favicons ->
                assertNotNull(favicons.load(listing("linked")))
            }
            assertEquals(1, site.count("/assets/logo.png"))
        } finally {
            site.close()
        }
    }

    @Test
    fun `an icon that ships with the source is used before any address is asked`() = runBlocking<Unit> {
        val site = Site()
        site.answer("/icon.png", PNG, "image/png")
        try {
            // An extension APK's launcher bitmap: local, authoritative, and never a reason to reach the network.
            val packaged = Files.createDirectories(directory.resolve("apk")).resolve("launcher.png")
            Files.write(packaged, PNG)
            val asked = mutableListOf<String>()
            DesktopFavicons(
                directory.resolve("cache"),
                { url -> asked += url; fetch(url) },
                { listOf(site.url("/icon.png")) },
                packaged = { listOf(packaged) },
            ).use { favicons ->
                assertNotNull(favicons.load(listing("packaged")))
            }
            assertEquals(emptyList<String>(), asked)
            assertEquals(0, site.total())
        } finally {
            site.close()
        }
    }

    @Test
    fun `a source with no icon keeps the letter and is not asked again before the miss expires`() = runBlocking<Unit> {
        val site = Site()
        site.answer("/favicon.ico", PNG, "image/png", status = 404)
        try {
            val source = listing("missing", siteUrl = site.url("/"))
            DesktopFavicons(directory, ::fetch, { emptyList() }).use { favicons ->
                assertNull(favicons.load(source))
                val asked = site.total()
                // The remembered miss answers without a request even though the site now has an icon.
                site.answer("/favicon.ico", PNG, "image/png")
                assertNull(favicons.load(source))
                assertEquals(asked, site.total())
            }
            assertTrue(Files.list(directory).use { files -> files.anyMatch { it.toString().endsWith(".miss") } })
            // A week later the miss is stale and the source gets another chance.
            val later = DesktopFavicons(directory, ::fetch, { emptyList() }, clock = { System.currentTimeMillis() + WEEK })
            later.use { favicons -> assertNotNull(favicons.load(source)) }
        } finally {
            site.close()
        }
    }

    @Test
    fun `candidate addresses and icon links are derived like Android's fetcher`() {
        assertEquals("https://example.org/favicon.ico", DesktopFavicons.faviconOf("https://example.org/path"))
        assertEquals("https://example.org/favicon.ico", DesktopFavicons.faviconOf("example.org"))
        assertEquals("http://127.0.0.1:8080/favicon.ico", DesktopFavicons.faviconOf("http://127.0.0.1:8080/api"))
        assertNull(DesktopFavicons.faviconOf(""))
        val links = DesktopFavicons.iconLinks(
            """
            <link rel="stylesheet" href="/style.css">
            <link rel="icon" href="/a.png">
            <link rel='apple-touch-icon' href='/b.png'>
            """.trimIndent(),
            "https://example.org/start/",
        )
        assertEquals(listOf("https://example.org/a.png", "https://example.org/b.png"), links)
        assertFalse(DesktopFavicons.iconLinks("<link rel=\"stylesheet\" href=\"/s.css\">", "https://example.org")
            .contains("https://example.org/s.css"))
    }

    private fun listing(name: String, siteUrl: String? = null): SourceListing = SourceListing(
        source = SourceRef("FIXTURE_$name", "en", "manga"),
        displayName = name,
        ecosystem = SourceEcosystem.KOTOTORO,
        supportsLatest = false,
        siteUrl = siteUrl,
    )

    /**
     * The test's own HTTP stack: the production fetcher is the session's OkHttp client, which the test JVM has no
     * copy of, and every address here is plain loopback HTTP anyway.
     */
    private suspend fun fetch(url: String): DesktopFavicons.Response? = runInterruptible {
        runCatching {
            val connection = URI(url).toURL().openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Accept", "image/*,*/*;q=0.8")
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val bytes = connection.inputStream.use { it.readNBytes(MAX_BYTES) }
                bytes.takeIf { it.isNotEmpty() }?.let { DesktopFavicons.Response(it, connection.contentType) }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** A loopback site that counts the requests per path, so a cached icon is provably not fetched twice. */
    private class Site {
        val hits = ConcurrentHashMap<String, AtomicInteger>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.executor = java.util.concurrent.Executors.newCachedThreadPool()
            server.start()
        }

        fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

        fun count(path: String): Int = hits[path]?.get() ?: 0

        fun total(): Int = hits.values.sumOf { it.get() }

        fun answer(path: String, body: ByteArray, contentType: String, status: Int = 200) {
            val handler = { exchange: HttpExchange ->
                hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) } else exchange.close()
            }
            runCatching { server.removeContext(path) }
            server.createContext(path) { exchange -> handler(exchange) }
        }

        fun close() = server.stop(0)
    }

    private companion object {
        const val WEEK = 8L * 24 * 60 * 60 * 1000
        const val MAX_BYTES = 512 * 1024

        val PNG: ByteArray = ByteArrayOutputStream().also { bytes ->
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try {
                graphics.color = java.awt.Color(0x33, 0x99, 0x66)
                graphics.fillRect(0, 0, 16, 16)
            } finally {
                graphics.dispose()
            }
            ImageIO.write(image, "png", bytes)
        }.toByteArray()
    }
}
