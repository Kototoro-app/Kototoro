package org.skepsun.kototoro.parserhost

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.skepsun.kototoro.core.source.SourceInvalidArgumentException
import org.skepsun.kototoro.parsers.ContentParser
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Image requests for parser sources. A parser's `intercept` is where descrambling and per-site headers live, so every
 * request runs through it; a client shared with the platform keeps cookies and Cloudflare clearance common to all
 * ecosystems.
 */
internal class ParserImageRequests(private val platform: ParserPlatform) {

    /** Some parsers decrypt images themselves and return a temporary file or an inline image. */
    suspend fun <T> readImage(
        parser: ContentParser, url: String, headers: Map<String, String>?, read: (InputStream, Long) -> T,
    ): T = when {
        url.startsWith("file:") -> {
            val file = try {
                val uri = URI(url)
                if (!uri.rawAuthority.isNullOrEmpty() || uri.query != null || uri.fragment != null) {
                    throw SourceInvalidArgumentException()
                }
                Path.of(uri)
            } catch (_: IllegalArgumentException) {
                throw SourceInvalidArgumentException()
            } catch (_: java.net.URISyntaxException) {
                throw SourceInvalidArgumentException()
            }
            if (!Files.isRegularFile(file)) throw SourceInvalidArgumentException()
            Files.newInputStream(file).use { read(it, Files.size(file)) }
        }
        url.startsWith("data:") -> {
            val comma = url.indexOf(',')
            if (comma < 5) throw SourceInvalidArgumentException()
            val type = url.substring(5, comma)
            if (!type.startsWith("image/") || !type.endsWith(";base64")) throw SourceInvalidArgumentException()
            Base64.getDecoder().wrap(url.substring(comma + 1).byteInputStream(Charsets.US_ASCII)).use { read(it, -1) }
        }
        else -> execute(parser, request(parser, url, headers)).use { response ->
            if (response.code !in 200..299) throw IOException("Image response failed")
            read(response.body.byteStream(), response.body.contentLength())
        }
    }

    fun request(parser: ContentParser, url: String, pageHeaders: Map<String, String>?): Request {
        val target = url.toHttpUrlOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }
            ?: throw SourceInvalidArgumentException()
        val explicit = try {
            Request.Builder().url(target).get().header("Accept", IMAGE_ACCEPT).cacheControl(NO_STORE)
                .apply { pageHeaders?.forEach { (name, value) -> header(name, value) } }
                .build()
        } catch (_: IllegalArgumentException) {
            throw SourceInvalidArgumentException()
        }
        return explicit.newBuilder().headers(parserRequestHeaders(parser, explicit.headers, platform.defaultUserAgent)).build()
    }

    suspend fun execute(parser: ContentParser, request: Request): Response {
        val client = platform.httpClient.newBuilder().addInterceptor { chain -> parser.intercept(chain) }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    // A response that arrives after cancellation has no owner; close it here.
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }
    }

    private companion object {
        const val IMAGE_ACCEPT = "image/avif,image/jxl,image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8"
        val NO_STORE: CacheControl = CacheControl.Builder().noStore().build()
    }
}
