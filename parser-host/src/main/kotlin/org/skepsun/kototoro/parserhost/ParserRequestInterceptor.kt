package org.skepsun.kototoro.parserhost

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.skepsun.kototoro.parsers.ContentParser
import org.skepsun.kototoro.parsers.model.ContentSource
import java.io.IOException
import java.net.IDN
import kotlin.coroutines.cancellation.CancellationException
import org.koitharu.kotatsu.parsers.model.MangaSource as KTMangaSource

/**
 * What Android's `CommonHeadersInterceptor` does for a parser's own requests: a request tagged with its source gets
 * the parser's headers it does not set itself (an `Authorization` token, the source's user agent and referer) and then
 * goes through the parser's interceptor. Without it a logged-in parser still calls its API as a guest.
 */
internal class ParserRequestInterceptor(
    private val defaultUserAgent: () -> String,
    private val parserOf: (String) -> ContentParser,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val name = request.tag(ContentSource::class.java)?.name
            ?: request.tag(KTMangaSource::class.java)?.name
            ?: return chain.proceed(request)
        val parser = try {
            parserOf(name)
        } catch (_: Exception) {
            // A source that is gone (or cannot be built) keeps the request as the parser sent it.
            return chain.proceed(request)
        }
        val next = request.newBuilder().headers(parserRequestHeaders(parser, request.headers, defaultUserAgent())).build()
        return try {
            parser.intercept(ProxyChain(chain, next))
        } catch (e: IOException) {
            throw e
        } catch (e: CancellationException) {
            throw IOException("Canceled", e)
        } catch (e: Exception) {
            // OkHttp only forwards IOExceptions from an interceptor to the caller; the message is what the UI shows.
            throw IOException(e.message, e)
        }
    }

    private class ProxyChain(private val delegate: Interceptor.Chain, private val request: Request) :
        Interceptor.Chain by delegate {
        override fun request(): Request = request
    }
}

/** [explicit] plus the parser's headers it lacks, a user agent and the source's referer. */
internal fun parserRequestHeaders(parser: ContentParser, explicit: Headers, defaultUserAgent: String): Headers {
    val headers = explicit.newBuilder()
    val parserHeaders = parser.getRequestHeaders()
    for (index in 0 until parserHeaders.size) {
        val name = parserHeaders.name(index)
        if (explicit[name] == null) headers[name] = parserHeaders.value(index)
    }
    if (headers["User-Agent"] == null) headers["User-Agent"] = defaultUserAgent
    if (headers["Referer"] == null) {
        runCatching { headers["Referer"] = "https://${IDN.toASCII(parser.domain)}/" }
    }
    return headers.build()
}
