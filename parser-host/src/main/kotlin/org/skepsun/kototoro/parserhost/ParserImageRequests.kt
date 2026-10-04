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
import java.net.IDN
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Image requests for parser sources. A parser's `intercept` is where descrambling and per-site headers live, so every
 * request runs through it; a client shared with the platform keeps cookies and Cloudflare clearance common to all
 * ecosystems.
 */
internal class ParserImageRequests(private val platform: ParserPlatform) {

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
        val headers = explicit.headers.newBuilder()
        val parserHeaders = parser.getRequestHeaders()
        for (index in 0 until parserHeaders.size) {
            val name = parserHeaders.name(index)
            if (explicit.header(name) == null) headers[name] = parserHeaders.value(index)
        }
        if (explicit.header("User-Agent") == null && headers["User-Agent"] == null) {
            headers["User-Agent"] = platform.defaultUserAgent
        }
        if (headers["Referer"] == null) {
            runCatching { headers["Referer"] = "https://${IDN.toASCII(parser.domain)}/" }
        }
        return explicit.newBuilder().headers(headers.build()).build()
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
