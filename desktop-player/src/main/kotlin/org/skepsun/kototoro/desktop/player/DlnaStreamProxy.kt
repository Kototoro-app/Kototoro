package org.skepsun.kototoro.desktop.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A small HTTP relay a renderer on the LAN fetches the stream through, as Android's LAN proxy does: renderers cannot
 * send the Referer / User-Agent a site requires, so the relay adds them. HLS playlists are rewritten so their segments,
 * keys and variant playlists also come through the relay. Range requests pass through for seeking.
 */
class DlnaStreamProxy(bind: InetAddress = InetAddress.getByName("0.0.0.0")) : Closeable {
    private class Entry(val url: String, val headers: Map<String, String>)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val executor = Executors.newCachedThreadPool { Thread(it, "dlna-relay").apply { isDaemon = true } }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build()
    private val server = HttpServer.create(InetSocketAddress(bind, 0), 32).apply {
        createContext("/cast/") { exchange -> serve(exchange) }
        executor = this@DlnaStreamProxy.executor
        start()
    }
    val port: Int get() = server.address.port

    /** The relay URL a renderer reaching this PC at [host] should play for [url]. */
    fun register(url: String, headers: Map<String, String>, host: InetAddress): String {
        val id = UUID.randomUUID().toString().replace("-", "")
        entries[id] = Entry(url, headers)
        return "http://${host.hostAddress}:$port/cast/$id/stream${extension(url)}"
    }

    fun unregisterAll() = entries.clear()

    private fun serve(exchange: HttpExchange) {
        exchange.use {
            try {
                val parts = exchange.requestURI.rawPath.removePrefix("/cast/").split('/', limit = 3)
                val entry = entries[parts.getOrNull(0)] ?: return exchange.sendResponseHeaders(404, -1)
                val target = if (parts.getOrNull(1) == "u") {
                    String(Base64.getUrlDecoder().decode(parts.getOrElse(2) { "" }.substringBefore('.')), Charsets.UTF_8)
                } else entry.url
                require(target.startsWith("http://") || target.startsWith("https://")) { "unsupported target" }
                relay(exchange, parts[0], entry, target)
            } catch (error: Exception) {
                runCatching { exchange.sendResponseHeaders(502, -1) }
            }
        }
    }

    private fun relay(exchange: HttpExchange, id: String, entry: Entry, target: String) {
        val head = exchange.requestMethod.equals("HEAD", ignoreCase = true)
        val request = HttpRequest.newBuilder(URI(target)).timeout(Duration.ofMinutes(10)).apply {
            entry.headers.forEach { (name, value) -> runCatching { header(name, value) } }
            exchange.requestHeaders.getFirst("Range")?.let { header("Range", it) }
            if (head) method("HEAD", HttpRequest.BodyPublishers.noBody()) else GET()
        }.build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            val type = response.headers().firstValue("Content-Type").orElse("")
            if (!head && isPlaylist(target, type) && response.statusCode() in 200..299) {
                val text = String(body.readNBytes(8 * 1024 * 1024), Charsets.UTF_8)
                if (text.trimStart().startsWith("#EXTM3U")) {
                    val bytes = rewrite(text, response.uri().toString(), id, exchange).toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                    return
                }
                val bytes = text.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(response.statusCode(), bytes.size.toLong())
                exchange.responseBody.write(bytes)
                return
            }
            for (name in listOf("Content-Type", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")) {
                response.headers().firstValue(name).ifPresent { exchange.responseHeaders.add(name, it) }
            }
            exchange.responseHeaders.add("transferMode.dlna.org", "Streaming")
            exchange.responseHeaders.add("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000")
            val length = response.headers().firstValueAsLong("Content-Length").orElse(0)
            exchange.sendResponseHeaders(response.statusCode(), if (head) -1 else if (length > 0) length else 0)
            if (!head) body.transferTo(exchange.responseBody)
        }
    }

    /** Every URI in the playlist (segment lines and URI="…" attributes) goes through the relay, resolved first. */
    private fun rewrite(playlist: String, base: String, id: String, exchange: HttpExchange): String {
        val host = exchange.localAddress.address.hostAddress
        fun relayed(uri: String): String {
            val absolute = URI(base).resolve(uri.trim()).toString()
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(absolute.toByteArray(Charsets.UTF_8))
            return "http://$host:$port/cast/$id/u/$encoded${extension(absolute)}"
        }
        return playlist.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${relayed(it.groupValues[1])}\"" }
                else -> relayed(line)
            }
        }
    }

    private fun isPlaylist(url: String, type: String) =
        url.substringBefore('?').lowercase().endsWith(".m3u8") || "mpegurl" in type.lowercase()

    private fun extension(url: String): String {
        val name = url.substringBefore('?').substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext in setOf("m3u8", "ts", "mp4", "mkv", "webm", "m4s", "aac", "vtt", "key")) ".$ext" else ""
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        entries.clear()
    }

    private inline fun <T> HttpExchange.use(block: (HttpExchange) -> T): T = try { block(this) } finally {
        runCatching { close() }
    }
}
