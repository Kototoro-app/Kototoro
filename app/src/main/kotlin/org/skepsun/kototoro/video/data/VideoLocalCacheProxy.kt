package org.skepsun.kototoro.video.data

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.GZipOptions
import org.skepsun.kototoro.video.dlna.NetworkUtils
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class VideoLocalCacheProxy @Inject constructor(
    @ApplicationContext context: Context,
    @ContentHttpClient private val okHttpClient: OkHttpClient,
    private val settings: AppSettings,
) {
    fun interface DynamicSourceHandler {
        fun handle(request: DynamicRequest): DynamicResponse
    }

    data class DynamicRequest(
        val url: String,
        val pathSegments: List<String>,
        val queryParameters: Map<String, String>,
        val headers: Map<String, String>,
        val method: String,
    )

    data class DynamicResponse(
        val statusCode: Int = 200,
        val contentType: String = "application/octet-stream",
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        val bodyStream: InputStream? = null,
        val redirectUrl: String? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DynamicResponse
            if (statusCode != other.statusCode) return false
            if (contentType != other.contentType) return false
            if (headers != other.headers) return false
            if (!body.contentEquals(other.body)) return false
            if (bodyStream != other.bodyStream) return false
            if (redirectUrl != other.redirectUrl) return false
            return true
        }

        override fun hashCode(): Int {
            var result = statusCode
            result = 31 * result + contentType.hashCode()
            result = 31 * result + headers.hashCode()
            result = 31 * result + body.contentHashCode()
            result = 31 * result + (bodyStream?.hashCode() ?: 0)
            result = 31 * result + (redirectUrl?.hashCode() ?: 0)
            return result
        }
    }

    data class SessionStats(
        val hit: Long,
        val miss: Long,
        val writeCount: Long,
        val writeBytes: Long,
    )

    companion object {
        private const val TAG = "VideoLocalCacheProxy"
    }
    private val appContext: Context = context
    private val cacheRoot: File = context.getExternalFilesDir("video_proxy_cache")
        ?: File(context.filesDir, "video_proxy_cache")
    private val sourceMap = ConcurrentHashMap<String, SourceEntry>()
    private val dynamicSourceMap = ConcurrentHashMap<String, DynamicSourceEntry>()
    private val fileLocks = ConcurrentHashMap<String, Any>()
    private val sessionCacheHitCount = AtomicLong(0)
    private val sessionCacheMissCount = AtomicLong(0)
    private val sessionCacheWriteCount = AtomicLong(0)
    private val sessionCacheWriteBytes = AtomicLong(0)

    @Volatile
    private var server: ProxyServer? = null

    fun getProxyUrl(url: String, headers: Map<String, String>, source: ContentSource? = null): String {
        val normalizedHeaders = normalizeHeaders(headers)
        val key = buildKey(url, normalizedHeaders)
        sourceMap[key] = SourceEntry(url = url, headers = normalizedHeaders, source = source)
        if (url.lowercase(Locale.ROOT).contains(".m3u8")) {
            // Playlists are rewritten dynamically; stale cached playlist content can break child URL proxying.
            File(cacheRoot, "$key.bin").delete()
            File(cacheRoot, "$key.meta").delete()
        }
        val runningServer = ensureServer()
        Log.d(TAG, "register source key=$key url=$url")
        return "http://127.0.0.1:${runningServer.listeningPort}/video/$key"
    }

    /**
     * Returns a proxy URL accessible from the local network (for DLNA casting).
     * Returns null if the device's WiFi IP cannot be determined.
     */
    fun getLanProxyUrl(url: String, headers: Map<String, String>, source: ContentSource? = null): String? {
        val lanIp = NetworkUtils.getWifiIpAddress(appContext) ?: return null
        val normalizedHeaders = normalizeHeaders(headers)
        val key = buildKey(url, normalizedHeaders)
        sourceMap[key] = SourceEntry(url = url, headers = normalizedHeaders, source = source)
        if (url.lowercase(java.util.Locale.ROOT).contains(".m3u8")) {
            java.io.File(cacheRoot, "$key.bin").delete()
            java.io.File(cacheRoot, "$key.meta").delete()
        }
        val runningServer = ensureServer()
        Log.d(TAG, "register LAN source key=$key url=$url lanIp=$lanIp")
        return "http://$lanIp:${runningServer.listeningPort}/video/$key"
    }

    fun getDynamicProxyUrl(id: String, handler: DynamicSourceHandler): String {
        val key = sha256("dynamic:$id")
        dynamicSourceMap[key] = DynamicSourceEntry(handler = handler)
        val runningServer = ensureServer()
        Log.d(TAG, "register dynamic source key=$key id=$id")
        return "http://127.0.0.1:${runningServer.listeningPort}/dynamic/$key"
    }

    fun resetSessionStats(reason: String) {
        sessionCacheHitCount.set(0)
        sessionCacheMissCount.set(0)
        sessionCacheWriteCount.set(0)
        sessionCacheWriteBytes.set(0)
        Log.d(TAG, "session stats reset: $reason")
    }

    fun logSessionStats(reason: String) {
        val hits = sessionCacheHitCount.get()
        val misses = sessionCacheMissCount.get()
        val writes = sessionCacheWriteCount.get()
        val bytes = sessionCacheWriteBytes.get()
        Log.d(
            TAG,
            "session stats [$reason]: hit=$hits miss=$misses writeCount=$writes writeBytes=$bytes",
        )
    }

    fun getSessionStatsSnapshot(): SessionStats {
        return SessionStats(
            hit = sessionCacheHitCount.get(),
            miss = sessionCacheMissCount.get(),
            writeCount = sessionCacheWriteCount.get(),
            writeBytes = sessionCacheWriteBytes.get(),
        )
    }

    @Synchronized
    private fun ensureServer(): ProxyServer {
        server?.let { return it }
        if (!cacheRoot.exists()) cacheRoot.mkdirs()
        return ProxyServer().also {
            it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = it
            Log.d(TAG, "proxy server started at 127.0.0.1:${it.listeningPort}")
        }
    }

    private fun normalizeHeaders(headers: Map<String, String>): Map<String, String> {
        val normalized = headers
            .filterKeys { key ->
                val lower = key.lowercase(Locale.ROOT)
                lower != "host" &&
                    lower != "range" &&
                    lower != "connection" &&
                    lower != "content-length" &&
                    lower != "content-encoding"
            }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        if (normalized.keys.none { it.equals("Accept", ignoreCase = true) }) {
            normalized["Accept"] = "application/vnd.apple.mpegurl, application/x-mpegURL, video/*, */*;q=0.8"
        }
        return normalized
    }

    private fun buildKey(url: String, headers: Map<String, String>): String {
        val canonical = buildString {
            append(url)
            headers.forEach { (k, v) ->
                append('\n').append(k).append(':').append(v)
            }
        }
        return sha256(canonical)
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun parseRangeHeader(rangeHeader: String?): RangeRequest? {
        if (rangeHeader.isNullOrBlank()) return null
        val value = rangeHeader.trim()
        if (!value.startsWith("bytes=")) return null
        val body = value.removePrefix("bytes=")
        val parts = body.split('-', limit = 2)
        if (parts.size != 2) return null
        val start = parts[0].toLongOrNull() ?: return null
        val end = parts[1].takeIf { it.isNotBlank() }?.toLongOrNull()
        if (end != null && end < start) return null
        return RangeRequest(start = start, end = end)
    }

    private fun parseContentRange(contentRange: String?): ContentRangeInfo? {
        if (contentRange.isNullOrBlank()) return null
        // bytes 0-1023/2048
        val regex = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
        val match = regex.find(contentRange) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        return ContentRangeInfo(start = start, end = end, total = total)
    }

    private fun getLock(key: String): Any = fileLocks.computeIfAbsent(key) { Any() }

    private fun maxCacheBytes(): Long = settings.videoProxyCacheSizeMb * 1024L * 1024L

    private fun touchCacheFiles(vararg files: File) {
        val timestamp = System.currentTimeMillis()
        files.forEach { file ->
            if (file.exists()) {
                file.setLastModified(timestamp)
            }
        }
    }

    private fun trimCache() {
        val limitBytes = maxCacheBytes()
        if (limitBytes <= 0L || !cacheRoot.exists()) return
        data class CacheEntry(
            val dataFile: File,
            val metaFile: File?,
            val lastModified: Long,
            val totalSize: Long,
        )

        val entries = cacheRoot.listFiles()
            .orEmpty()
            .filter { it.extension == "bin" }
            .map { dataFile ->
                val metaFile = File(cacheRoot, "${dataFile.nameWithoutExtension}.meta").takeIf { it.exists() }
                CacheEntry(
                    dataFile = dataFile,
                    metaFile = metaFile,
                    lastModified = maxOf(dataFile.lastModified(), metaFile?.lastModified() ?: 0L),
                    totalSize = dataFile.length() + (metaFile?.length() ?: 0L),
                )
            }
            .sortedBy { it.lastModified }
            .toMutableList()

        var currentSize = entries.sumOf { it.totalSize }
        if (currentSize <= limitBytes) return

        entries.forEach { entry ->
            if (currentSize <= limitBytes) return
            val removedSize = entry.totalSize
            entry.dataFile.delete()
            entry.metaFile?.delete()
            currentSize -= removedSize
        }
    }

    private fun resolveAbsoluteUrl(baseUrl: String, rawUri: String): String {
        return runCatching { URI(baseUrl).resolve(rawUri).toString() }.getOrDefault(rawUri)
    }

    private fun mapChildUrl(parent: SourceEntry, rawUri: String, hostOverride: String? = null): String {
        val abs = resolveAbsoluteUrl(parent.url, rawUri)
        val childKey = buildKey(abs, parent.headers)
        sourceMap[childKey] = SourceEntry(url = abs, headers = parent.headers, source = parent.source)
        val runningServer = ensureServer()
        val host = hostOverride ?: "127.0.0.1"
        return "http://$host:${runningServer.listeningPort}/video/$childKey"
    }

    private fun rewritePlaylistContent(parent: SourceEntry, rawContent: String, hostOverride: String? = null): String {
        return rawContent
            .lineSequence()
            .map { line ->
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> line
                    trimmed.startsWith("#EXT-X-KEY", ignoreCase = true) ||
                        trimmed.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                        Regex("""URI="([^"]+)"""").replace(line) { m ->
                            val proxied = mapChildUrl(parent, m.groupValues[1], hostOverride)
                            "URI=\"$proxied\""
                        }
                    }
                    trimmed.startsWith("#") -> line
                    else -> mapChildUrl(parent, trimmed, hostOverride)
                }
            }
            .joinToString("\n")
    }

    private fun isLikelyPlaylist(sourceUrl: String, contentType: String?): Boolean {
        val lowerUrl = sourceUrl.lowercase(Locale.ROOT)
        if (lowerUrl.contains(".m3u8")) return true
        val lowerCt = contentType?.lowercase(Locale.ROOT).orEmpty()
        return lowerCt.contains("mpegurl")
    }

    /**
     * Determines the host to use for proxy URLs based on the client's remote IP.
     * If the client is a LAN device (non-localhost), use the device's LAN IP so
     * that m3u8 child URLs are reachable from the LAN device.
     */
    private fun resolveHostForClient(remoteIp: String?): String? {
        if (remoteIp.isNullOrBlank()) return null
        // localhost clients use default 127.0.0.1
        if (remoteIp == "127.0.0.1" || remoteIp == "0:0:0:0:0:0:0:1" || remoteIp == "::1") return null
        // Remote / LAN client — return this device's LAN IP
        return NetworkUtils.getWifiIpAddress(appContext)
    }

    private inner class ProxyServer : NanoHTTPD("0.0.0.0", 0) {
        override fun serve(session: IHTTPSession): Response {
            if (session.method != Method.GET && session.method != Method.HEAD) {
                return newFixedLengthResponse(
                    Response.Status.METHOD_NOT_ALLOWED,
                    "text/plain",
                    "Method not allowed",
                )
            }
            if (session.uri.startsWith("/dynamic/")) {
                return serveDynamic(session)
            }
            if (!session.uri.startsWith("/video/")) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            val key = session.uri.removePrefix("/video/")
            val source = sourceMap[key]
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video key not found")

            val cacheFile = File(cacheRoot, "$key.bin")
            val metaFile = File(cacheRoot, "$key.meta")
            val lock = getLock(key)
            val requestRange = parseRangeHeader(session.headers["range"])
            val isHead = session.method == Method.HEAD
            val isPlaylistByUrl = source.url.lowercase(Locale.ROOT).contains(".m3u8")

            val cachedMeta = synchronized(lock) { CacheMeta.load(metaFile) }
            val bypassCacheForPlaylist = source.url.lowercase(Locale.ROOT).contains(".m3u8")
            val canServeFromCache = synchronized(lock) {
                if (bypassCacheForPlaylist) return@synchronized false
                val total = cachedMeta.totalLength
                if (requestRange != null && total > 0) {
                    val start = requestRange.start
                    val end = requestRange.end ?: (total - 1)
                    end >= start && cachedMeta.isCovered(start, end) && cacheFile.exists() && cacheFile.length() >= end + 1
                } else if (requestRange == null && total > 0) {
                    cachedMeta.isCovered(0, total - 1) && cacheFile.exists() && cacheFile.length() >= total
                } else {
                    false
                }
            }
            if (canServeFromCache) {
                sessionCacheHitCount.incrementAndGet()
                touchCacheFiles(cacheFile, metaFile)
                Log.d(TAG, "cache hit key=$key range=${requestRange?.start}-${requestRange?.end ?: "end"}")
                return buildCachedResponse(
                    cacheFile = cacheFile,
                    meta = cachedMeta,
                    requestRange = requestRange,
                    isHead = isHead,
                )
            }

            sessionCacheMissCount.incrementAndGet()
            Log.d(TAG, "cache miss key=$key fetch=${source.url} range=${requestRange?.start}-${requestRange?.end ?: "end"}")

            val upstreamRequest = Request.Builder().url(source.url).apply {
                tag(GZipOptions::class.java, GZipOptions(skip = true))
                source.source?.let { tag(ContentSource::class.java, it) }
                source.headers.forEach { (k, v) -> header(k, v) }
                if (!isPlaylistByUrl) {
                    requestRange?.let {
                        header("Range", if (it.end != null) "bytes=${it.start}-${it.end}" else "bytes=${it.start}-")
                    }
                }
                // Override Referer to the target URL's origin. When Video.headers is
                // null (common for Aniyomi extensions), CommonHeadersInterceptor would
                // fall back to the source's domain (e.g. jkanime.net) as Referer, which
                // CDNs reject. Using the URL's own origin is always safe: CDNs accept
                // same-origin Referer, and for the source itself it's equivalent.
                val urlOrigin = runCatching {
                    val uri = URI(source.url)
                    "${uri.scheme}://${uri.host}"
                }.getOrNull()
                if (urlOrigin != null) {
                    header("Referer", urlOrigin)
                }
            }.build()
            val upstreamResponse = runCatching { okHttpClient.newCall(upstreamRequest).execute() }.getOrNull()
                ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Upstream error")

            if (!upstreamResponse.isSuccessful && upstreamResponse.code !in listOf(200, 206)) {
                val errorBody = runCatching { upstreamResponse.body.string().take(300) }.getOrDefault("")
                Log.w(
                    TAG,
                    "upstream failed key=$key code=${upstreamResponse.code} url=${source.url} body=$errorBody",
                )
                upstreamResponse.close()
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Upstream failed: ${upstreamResponse.code}")
            }

            val body = upstreamResponse.body
            val contentTypeHeader = upstreamResponse.header("Content-Type")
            if (!isHead && (isPlaylistByUrl || isLikelyPlaylist(source.url, contentTypeHeader))) {
                val playlistRaw = runCatching { body.string() }.getOrNull()
                upstreamResponse.close()
                if (playlistRaw == null) {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to read playlist")
                }
                // If the request is from a LAN device (e.g. Kodi DLNA), use the LAN IP
                // so that child URLs in the playlist are reachable from that device.
                val clientIp = session.headers["remote-addr"]
                    ?: session.headers["http-client-ip"]
                val hostOverride = resolveHostForClient(clientIp)
                val rewritten = rewritePlaylistContent(source, playlistRaw, hostOverride)
                Log.d(TAG, "rewrite playlist key=$key size=${playlistRaw.length} -> ${rewritten.length} hostOverride=$hostOverride clientIp=$clientIp")
                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/vnd.apple.mpegurl; charset=utf-8",
                    rewritten,
                )
                response.addHeader("Cache-Control", "no-cache")
                return response
            }

            val upstreamContentType = body.contentType()?.toString() ?: "video/mp4"
            val contentRangeInfo = parseContentRange(upstreamResponse.header("Content-Range"))
            val writeStart = contentRangeInfo?.start ?: requestRange?.start ?: 0L
            val contentLength = body.contentLength()
            val totalLength = contentRangeInfo?.total
                ?: if (upstreamResponse.code == 200 && contentLength >= 0) contentLength else null
            synchronized(lock) {
                val meta = CacheMeta.load(metaFile)
                if (meta.mimeType.isBlank()) {
                    meta.mimeType = upstreamContentType
                }
                if (totalLength != null && totalLength > 0) {
                    meta.totalLength = totalLength
                }
                meta.save(metaFile)
            }

            if (isHead) {
                upstreamResponse.close()
                val response = newFixedLengthResponse(
                    if (upstreamResponse.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
                    upstreamContentType,
                    "",
                )
                response.addHeader("Accept-Ranges", "bytes")
                if (contentRangeInfo != null) {
                    response.addHeader("Content-Range", "bytes ${contentRangeInfo.start}-${contentRangeInfo.end}/${contentRangeInfo.total ?: "*"}")
                }
                if (contentLength >= 0) {
                    response.addHeader("Content-Length", contentLength.toString())
                }
                return response
            }

            val sourceStream = body.byteStream()
            val cachingStream = CachingInputStream(
                upstream = sourceStream,
                cacheFile = cacheFile,
                offset = writeStart,
                lock = lock,
                onClosed = { bytesWritten ->
                    synchronized(lock) {
                        val meta = CacheMeta.load(metaFile)
                        if (bytesWritten > 0) {
                            meta.addInterval(writeStart, writeStart + bytesWritten - 1)
                        }
                        meta.save(metaFile)
                    }
                    if (bytesWritten > 0) {
                        sessionCacheWriteCount.incrementAndGet()
                        sessionCacheWriteBytes.addAndGet(bytesWritten)
                        touchCacheFiles(cacheFile, metaFile)
                        trimCache()
                    }
                    Log.d(TAG, "cache write key=$key bytes=$bytesWritten start=$writeStart")
                    upstreamResponse.close()
                },
            )

            val status = if (upstreamResponse.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val response = if (contentLength >= 0) {
                newFixedLengthResponse(status, upstreamContentType, cachingStream, contentLength)
            } else {
                newChunkedResponse(status, upstreamContentType, cachingStream)
            }
            response.addHeader("Accept-Ranges", "bytes")
            upstreamResponse.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
            if (contentLength >= 0) {
                response.addHeader("Content-Length", contentLength.toString())
            }
            return response
        }

        private fun serveDynamic(session: IHTTPSession): Response {
            val key = session.uri.removePrefix("/dynamic/")
            val dynamicSource = dynamicSourceMap[key]
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Dynamic key not found")
            val request = DynamicRequest(
                url = session.uri,
                pathSegments = session.uri.split('/').filter { it.isNotBlank() },
                queryParameters = session.parameters.mapValues { (_, values) -> values.firstOrNull().orEmpty() },
                headers = session.headers.toMap(),
                method = session.method.name,
            )
            val dynamicResponse = runCatching { dynamicSource.handler.handle(request) }.getOrElse { error ->
                Log.w(TAG, "dynamic source failed key=$key", error)
                DynamicResponse(
                    statusCode = 500,
                    contentType = "text/plain; charset=utf-8",
                    body = (error.message ?: error.toString()).toByteArray(Charsets.UTF_8),
                )
            }
            return buildDynamicResponse(dynamicResponse, session.method == Method.HEAD)
        }
    }

    private fun buildCachedResponse(
        cacheFile: File,
        meta: CacheMeta,
        requestRange: RangeRequest?,
        isHead: Boolean,
    ): NanoHTTPD.Response {
        val total = meta.totalLength
        val mime = if (meta.mimeType.isBlank()) "video/mp4" else meta.mimeType
        val start = requestRange?.start ?: 0L
        val end = requestRange?.end ?: (total - 1)
        val length = (end - start + 1).coerceAtLeast(0)
        val status = if (start == 0L && end == total - 1) NanoHTTPD.Response.Status.OK else NanoHTTPD.Response.Status.PARTIAL_CONTENT

        val response = if (isHead) {
            NanoHTTPD.newFixedLengthResponse(status, mime, "")
        } else {
            val fis = FileInputStream(cacheFile).apply { channel.position(start) }
            NanoHTTPD.newFixedLengthResponse(status, mime, fis, length)
        }
        response.addHeader("Accept-Ranges", "bytes")
        if (status == NanoHTTPD.Response.Status.PARTIAL_CONTENT) {
            response.addHeader("Content-Range", "bytes $start-$end/$total")
        }
        response.addHeader("Content-Length", length.toString())
        return response
    }

    private data class SourceEntry(
        val url: String,
        val headers: Map<String, String>,
        val source: ContentSource?,
    )

    private data class DynamicSourceEntry(
        val handler: DynamicSourceHandler,
    )

    private data class RangeRequest(
        val start: Long,
        val end: Long?,
    )

    private data class ContentRangeInfo(
        val start: Long,
        val end: Long,
        val total: Long?,
    )

    private class CacheMeta(
        var totalLength: Long = -1L,
        var mimeType: String = "",
        private val intervals: MutableList<LongRange> = mutableListOf(),
    ) {
        fun isCovered(start: Long, end: Long): Boolean {
            if (end < start) return false
            var cursor = start
            for (interval in intervals.sortedBy { it.first }) {
                if (interval.last < cursor) continue
                if (interval.first > cursor) return false
                cursor = (interval.last + 1).coerceAtLeast(cursor)
                if (cursor > end) return true
            }
            return cursor > end
        }

        fun addInterval(start: Long, end: Long) {
            if (end < start) return
            intervals.add(start..end)
            val merged = mutableListOf<LongRange>()
            for (range in intervals.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last == null || range.first > last.last + 1) {
                    merged.add(range)
                } else {
                    merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
                }
            }
            intervals.clear()
            intervals.addAll(merged)
        }

        fun save(file: File) {
            file.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
            val lines = mutableListOf<String>()
            lines += "total=$totalLength"
            lines += "mime=$mimeType"
            intervals.forEach { lines += "${it.first}-${it.last}" }
            file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        }

        companion object {
            fun load(file: File): CacheMeta {
                if (!file.exists()) return CacheMeta()
                val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
                val meta = CacheMeta()
                lines.forEach { line ->
                    when {
                        line.startsWith("total=") -> {
                            meta.totalLength = line.removePrefix("total=").toLongOrNull() ?: -1L
                        }
                        line.startsWith("mime=") -> {
                            meta.mimeType = line.removePrefix("mime=")
                        }
                        '-' in line -> {
                            val p = line.split('-', limit = 2)
                            val start = p.getOrNull(0)?.toLongOrNull()
                            val end = p.getOrNull(1)?.toLongOrNull()
                            if (start != null && end != null && end >= start) {
                                meta.addInterval(start, end)
                            }
                        }
                    }
                }
                return meta
            }
        }
    }

    private class CachingInputStream(
        upstream: InputStream,
        private val cacheFile: File,
        offset: Long,
        private val lock: Any,
        private val onClosed: (Long) -> Unit,
    ) : FilterInputStream(upstream) {
        private var writePos = offset
        private var totalWritten = 0L
        private var closed = false
        private var raf: java.io.RandomAccessFile? = null

        init {
            synchronized(lock) {
                cacheFile.parentFile?.let { parent ->
                    if (!parent.exists()) parent.mkdirs()
                }
                if (!cacheFile.exists()) cacheFile.createNewFile()
                runCatching {
                    raf = java.io.RandomAccessFile(cacheFile, "rw")
                    raf?.seek(writePos)
                }
            }
        }

        override fun read(): Int {
            val value = `in`.read()
            if (value >= 0) {
                writeBytes(byteArrayOf(value.toByte()), 0, 1)
            }
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = `in`.read(b, off, len)
            if (read > 0) {
                writeBytes(b, off, read)
            }
            return read
        }

        private fun writeBytes(buffer: ByteArray, off: Int, count: Int) {
            synchronized(lock) {
                runCatching {
                    raf?.write(buffer, off, count)
                }
            }
            writePos += count
            totalWritten += count
        }

        override fun close() {
            if (closed) return
            closed = true
            synchronized(lock) {
                runCatching { raf?.close() }
                raf = null
            }
            runCatching { super.close() }
            onClosed(totalWritten)
        }
    }

    private fun buildDynamicResponse(
        response: DynamicResponse,
        isHead: Boolean,
    ): NanoHTTPD.Response {
        val status = responseStatus(response.statusCode)
        val output = when {
            response.redirectUrl != null -> {
                NanoHTTPD.newFixedLengthResponse(status, response.contentType, "")
            }
            isHead -> {
                NanoHTTPD.newFixedLengthResponse(status, response.contentType, "")
            }
            response.bodyStream != null -> {
                val contentLengthStr = response.headers.entries.firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }?.value
                val contentLength = contentLengthStr?.toLongOrNull() ?: -1L
                if (contentLength >= 0L) {
                    NanoHTTPD.newFixedLengthResponse(
                        status,
                        response.contentType,
                        response.bodyStream,
                        contentLength,
                    )
                } else {
                    NanoHTTPD.newChunkedResponse(
                        status,
                        response.contentType,
                        response.bodyStream,
                    )
                }
            }
            else -> {
                NanoHTTPD.newFixedLengthResponse(
                    status,
                    response.contentType,
                    ByteArrayInputStream(response.body),
                    response.body.size.toLong(),
                )
            }
        }
        response.redirectUrl?.let { output.addHeader("Location", it) }
        response.headers.forEach { (key, value) ->
            if (!key.equals("Content-Length", ignoreCase = true)) {
                output.addHeader(key, value)
            }
        }
        if (response.headers.keys.none { it.equals("Connection", ignoreCase = true) }) {
            output.addHeader("Connection", "close")
        }
        if (response.bodyStream == null) {
            output.addHeader("Content-Length", response.body.size.toString())
        }
        return output
    }

    private fun responseStatus(code: Int): NanoHTTPD.Response.IStatus {
        return object : NanoHTTPD.Response.IStatus {
            override fun getRequestStatus(): Int = code

            override fun getDescription(): String = "$code ${reasonPhrase(code)}"
        }
    }

    private fun reasonPhrase(code: Int): String {
        return when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            301 -> "Moved Permanently"
            302 -> "Found"
            307 -> "Temporary Redirect"
            308 -> "Permanent Redirect"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            504 -> "Gateway Timeout"
            else -> "OK"
        }
    }
}
