package org.skepsun.kototoro.desktop.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image
import org.skepsun.kototoro.core.source.SourceListing
import java.io.Closeable
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Android's source favicons for the desktop host (`FaviconFetcher` + `favicon_cache`): a browse tile shows the site's
 * own icon instead of a letter.
 *
 * An extension source is shown with the launcher icon packaged in its APK, exactly like `fetchPluginIcon` — a local
 * file, no request. Everything else follows the phone: the icon addresses the source itself knows (a parser plugin's
 * `getFavicons()`, a TVBox `iconUrl`) first, then `<host>/favicon.ico` of the site, then the `<link rel="icon">`
 * entries of the site's start page. One successful icon is kept on disk keyed by the source name, so a relaunch costs
 * no request; a source that offered nothing is remembered for [MISS_TTL_MS] and simply keeps the letter tile.
 * Decoded icons stay in a small in-memory LRU, and concurrent tiles of the same source share one request.
 */
internal class DesktopFavicons(
    private val directory: Path,
    /** One candidate address, already judged: the server's bytes and content type, or null when it refused. */
    private val fetch: suspend (String) -> Response?,
    /** The icon addresses the session can name for a source without drawing the tile first. */
    private val candidates: suspend (SourceListing) -> List<String>,
    /** Site addresses whose start page and `/favicon.ico` answer when the source names no icon of its own. */
    private val sites: suspend (SourceListing) -> List<String> = { emptyList() },
    /** Icons that already ship with the source (an extension APK's launcher bitmap), tried before any address. */
    private val packaged: suspend (SourceListing) -> List<Path> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    internal class Response(val bytes: ByteArray, val contentType: String?)

    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.IO)
    private val permits = Semaphore(3)
    private val lock = Any()
    private var closed = false
    private val active = mutableMapOf<String, Deferred<ImageBitmap?>>()
    private val misses = mutableSetOf<String>()
    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 64
    }

    /** The source's icon, or null when it has none — the caller then draws the letter tile. */
    suspend fun load(listing: SourceListing): ImageBitmap? {
        currentCoroutineContext().ensureActive()
        val key = listing.source.name
        synchronized(lock) {
            if (closed) return null
            memory[key]?.let { return it }
            if (key in misses) return null
        }
        val request = synchronized(lock) {
            active.getOrPut(key) { scope.async { permits.withPermit { fetch(key, listing) } } }
        }
        return try {
            request.await()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A site answering garbage or a disk that refuses the write costs the tile its icon, nothing else.
            null
        } finally {
            synchronized(lock) { if (request.isCompleted) active.remove(key, request) }
        }
    }

    private suspend fun fetch(key: String, listing: SourceListing): ImageBitmap? {
        // An extension's own icon is local and authoritative, so it wins before any cached or fetched address.
        for (packagedIcon in packaged(listing)) {
            val bytes = runCatching { Files.readAllBytes(packagedIcon) }.getOrNull() ?: continue
            decode(bytes)?.let { return remember(key, it) }
        }
        val hex = imageKey(key.toByteArray(Charsets.UTF_8))
        val stored = directory.resolve("$hex.icon")
        if (Files.isRegularFile(stored)) {
            val cached = runCatching { Files.readAllBytes(stored) }.getOrNull()
            if (cached != null) decode(cached)?.let { return remember(key, it) }
            runCatching { Files.deleteIfExists(stored) }
        }
        val miss = directory.resolve("$hex.miss")
        if (Files.isRegularFile(miss)) {
            val stampedAt = runCatching { Files.getLastModifiedTime(miss).toMillis() }.getOrDefault(0L)
            if (clock() - stampedAt < MISS_TTL_MS) return rememberMiss(key)
        }
        val icon = withTimeoutOrNull(FETCH_TIMEOUT_MS) { download(listing) } ?: return rememberMiss(key)
        runCatching {
            Files.createDirectories(directory)
            val part = directory.resolve("$hex.icon.part")
            Files.write(part, icon)
            Files.move(part, stored, StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(miss)
        }
        return decode(icon)?.let { remember(key, it) } ?: rememberMiss(key)
    }

    /** Tries every candidate the source knows, then the site's own icon link; the first decodable answer wins. */
    private suspend fun download(listing: SourceListing): ByteArray? {
        val urls = LinkedHashSet<String>()
        candidates(listing).mapNotNullTo(urls, ::absoluteSite)
        val siteAddresses = sites(listing) + listOfNotNull(listing.siteUrl)
        for (site in siteAddresses.filter(String::isNotBlank)) {
            // The start page first: its `<link rel="icon">` is the address the site actually publishes.
            absoluteSite(site)?.let(urls::add)
            faviconOf(site)?.let(urls::add)
        }
        val visited = mutableSetOf<String>()
        var pending = urls.toList()
        while (pending.isNotEmpty()) {
            val url = pending.first()
            pending = pending.drop(1)
            if (!visited.add(url)) continue
            currentCoroutineContext().ensureActive()
            val response = fetch(url) ?: continue
            // Sites and proxies label icons inconsistently, so an address only counts as an icon once it decodes.
            if (response.contentType?.startsWith("image/") == true || isIcon(response.bytes)) return response.bytes
            // Not an image: the candidate was the site itself, so its `<link rel="icon">` entries answer instead.
            val html = response.bytes.toString(Charsets.UTF_8)
            val icons = iconLinks(html, url).filterNot(visited::contains)
            if (icons.isNotEmpty()) pending = icons + pending
        }
        return null
    }

    private fun isIcon(bytes: ByteArray): Boolean = decode(bytes) != null

    private fun decode(bytes: ByteArray): ImageBitmap? = try {
        Image.makeFromEncoded(bytes).use { image ->
            if (image.width in 1..MAX_ICON_PIXELS && image.height in 1..MAX_ICON_PIXELS) {
                image.toComposeImageBitmap()
            } else null
        }
    } catch (_: Exception) { null }

    private fun remember(key: String, icon: ImageBitmap): ImageBitmap = synchronized(lock) {
        memory[key] = icon
        icon
    }

    private fun rememberMiss(key: String): ImageBitmap? {
        synchronized(lock) { if (!closed) misses += key }
        runCatching {
            Files.createDirectories(directory)
            val miss = directory.resolve("${imageKey(key.toByteArray(Charsets.UTF_8))}.miss")
            Files.write(miss, clock().toString().toByteArray(Charsets.UTF_8))
        }
        return null
    }

    override fun close() {
        val running = synchronized(lock) {
            if (closed) return
            closed = true
            memory.clear()
            active.values.toList()
        }
        running.forEach { it.cancel() }
        owner.cancel()
    }

    companion object {
        /** Android's `favicon_cache` forgets a source that has no icon after a while; a week is enough. */
        private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_ICON_BYTES = 512 * 1024
        private const val MAX_ICON_PIXELS = 1024
        private const val FETCH_TIMEOUT_MS = 10_000L
        private val LINK_TAG = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val LINK_REL = Regex("""\brel\s*=\s*["']?([^"'>]+)""", RegexOption.IGNORE_CASE)
        private val LINK_HREF = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        /** The session's own HTTP stack (proxy, cookies, mirror), sized and interrupted like every other tile request. */
        internal fun httpFetcher(client: OkHttpClient): suspend (String) -> Response? = { url ->
            runCatching {
                runInterruptible {
                    val request = Request.Builder().url(url)
                        .header("Accept", "image/*,*/*;q=0.8")
                        .build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body ?: return@use null
                        if (!response.isSuccessful) return@use null
                        if (body.contentLength() > MAX_ICON_BYTES) return@use null
                        val bytes = body.byteStream().use { it.readNBytes(MAX_ICON_BYTES.toInt()) }
                        bytes.takeIf(ByteArray::isNotEmpty)?.let { Response(it, body.contentType()?.toString()) }
                    }
                }
            }.getOrNull()
        }

        /** `<scheme>://<host>[:port]/favicon.ico`, the address most sites answer even without an icon link. */
        internal fun faviconOf(site: String): String? = try {
            val uri = URI(site.trim().let { if ("://" in it) it else "https://$it" })
            val host = uri.host ?: return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "${uri.scheme ?: "https"}://$host$port/favicon.ico"
        } catch (_: Exception) { null }

        /** The site address with a scheme, so a bare `example.org` pasted from a source document is fetchable. */
        internal fun absoluteSite(site: String): String? = try {
            val text = site.trim()
            if (text.isEmpty()) null else {
                val uri = URI(if ("://" in text) text else "https://$text")
                uri.takeIf { it.host != null }?.toString()
            }
        } catch (_: Exception) { null }

        /** Every `<link rel="…icon…" href="…">` of a start page, resolved against the page it came from. */
        internal fun iconLinks(html: String, pageUrl: String): List<String> {
            if (html.isEmpty()) return emptyList()
            val base = runCatching { URI(pageUrl) }.getOrNull() ?: return emptyList()
            return LINK_TAG.findAll(html).mapNotNull { tag ->
                val text = tag.value
                val rel = LINK_REL.find(text)?.groupValues?.get(1)?.lowercase() ?: return@mapNotNull null
                if (!rel.contains("icon")) return@mapNotNull null
                val href = LINK_HREF.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                runCatching { base.resolve(href.trim()).toString() }.getOrNull()
            }.distinct().take(MAX_ICON_LINKS).toList()
        }

        private const val MAX_ICON_LINKS = 6
    }
}
