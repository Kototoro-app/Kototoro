package org.skepsun.kototoro.video.player

import eu.kanade.tachiyomi.network.await
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.video.data.isTorrentLocator

/**
 * Best-effort byte size of a playable stream.
 *
 * Only progressive HTTP(S) resources and local files can be measured; HLS/DASH manifests,
 * torrents and URLs without a usable `Content-Length` return `null`, and the caller must fall
 * back to hiding the size. The probe never throws and never reads a body: a `HEAD` request is
 * tried first and a one-byte ranged `GET` is only issued when `HEAD` cannot tell the length.
 * Every call is bounded by [callTimeoutMs] so a hanging CDN cannot keep the UI waiting.
 */
internal class VideoStreamSizeProbe(
    private val httpClient: OkHttpClient,
    callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
) {

    private val client: OkHttpClient = httpClient.newBuilder()
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): Long? {
        val target = url.trim()
        if (target.isEmpty()) return null
        localFileSize(target)?.let { return it }
        if (!isHttpUrl(target)) return null
        if (looksLikeAdaptiveManifest(target)) return null
        return when (val head = runCatchingCancellable { headProbe(target, headers) }.getOrNull()) {
            is HeadProbe.Length -> head.bytes
            HeadProbe.Unknown -> runCatchingCancellable { rangeProbe(target, headers) }.getOrNull()
            HeadProbe.Adaptive, HeadProbe.Missing, null -> null
        }
    }

    private suspend fun headProbe(url: String, headers: Map<String, String>): HeadProbe {
        val request = requestBuilder(url, headers).head().build()
        client.newCall(request).await().use { response ->
            if (isAdaptiveContentType(response.header("Content-Type"))) return HeadProbe.Adaptive
            if (response.code == 404 || response.code == 410) return HeadProbe.Missing
            if (!response.isSuccessful) {
                // Some CDNs reject HEAD (405/501) or gate it behind an authenticated POST (403);
                // a ranged GET is still worth a try. A missing resource is not.
                return HeadProbe.Unknown
            }
            // Head responses carry the length in the header only; OkHttp reports an empty body.
            return declaredContentLength(response)
                ?.let(HeadProbe::Length)
                ?: HeadProbe.Unknown
        }
    }

    private suspend fun rangeProbe(url: String, headers: Map<String, String>): Long? {
        val request = requestBuilder(url, headers)
            .header("Range", "bytes=0-0")
            .get()
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) return null
            if (isAdaptiveContentType(response.header("Content-Type"))) return null
            totalFromContentRange(response.header("Content-Range"))?.let { return it }
            // Server ignored the range and advertised the whole body instead.
            return declaredContentLength(response)
        }
    }

    private fun requestBuilder(url: String, headers: Map<String, String>): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .apply {
                headers.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) header(name, value)
                }
            }
    }

    private sealed interface HeadProbe {
        data class Length(val bytes: Long) : HeadProbe
        data object Adaptive : HeadProbe
        data object Missing : HeadProbe
        data object Unknown : HeadProbe
    }
}

private const val DEFAULT_CALL_TIMEOUT_MS = 6_000L

private const val CONTENT_RANGE_PREFIX = "bytes"

internal fun totalFromContentRange(header: String?): Long? {
    val value = header?.trim().orEmpty()
    if (!value.startsWith(CONTENT_RANGE_PREFIX, ignoreCase = true)) return null
    val total = value.substringAfter('/', missingDelimiterValue = "").trim()
    if (total.isEmpty() || total == "*") return null
    return total.toLongOrNull()?.takeIf { it > 0 }
}

internal fun isAdaptiveContentType(contentType: String?): Boolean {
    val normalized = contentType?.lowercase().orEmpty()
    if (normalized.isEmpty()) return false
    return normalized.contains("mpegurl") ||
        normalized.contains("m3u8") ||
        normalized.contains("dash+xml")
}

/** Declared total size of a response, ignoring the empty body OkHttp reports for `HEAD`. */
private fun declaredContentLength(response: Response): Long? {
    val fromHeader = response.header("Content-Length")?.trim()?.toLongOrNull()
    val bytes = fromHeader ?: response.body.contentLength()
    return bytes.takeIf { it > 0 }
}

internal fun looksLikeAdaptiveManifest(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd")
}

internal fun isHttpUrl(url: String): Boolean {
    if (url.isTorrentLocator()) return false
    return url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}

private fun localFileSize(url: String): Long? {
    val path = when {
        url.startsWith("file://", ignoreCase = true) -> runCatching { URI(url).path }.getOrNull()
        url.startsWith("/") -> url
        else -> null
    } ?: return null
    return runCatching { File(path).takeIf { it.isFile }?.length() }.getOrNull()?.takeIf { it > 0 }
}
