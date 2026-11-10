package org.skepsun.kototoro.desktop.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.skepsun.kototoro.core.model.jsonsource.LegadoBookSource
import org.skepsun.kototoro.core.parser.legado.ConcurrentRateLimiter
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpExecutor
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpResponse
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoPlanHttpExecutor
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoPlanTransport
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoVariableStore
import org.skepsun.kototoro.parsers.network.UserAgents
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Android's request rules (header and cookie merging, retries, the source's rate limit, charset detection) over the
 * desktop's HTTP client; the source's own headers, its stored login header and its agent are supplied here.
 */
internal fun desktopLegadoExecutor(
    client: OkHttpClient,
    source: LegadoBookSource,
    variables: LegadoVariableStore?,
): LegadoHttpExecutor {
    val configHeaders = { LegadoPlanHttpExecutor.parseHeaderJson(source.header) }
    return LegadoPlanHttpExecutor(
        config = source,
        httpClient = DesktopLegadoTransport(client),
        rateLimiter = ConcurrentRateLimiter(source.bookSourceUrl, source.concurrentRate),
        configHeadersProvider = configHeaders,
        loginHeadersProvider = { LegadoPlanHttpExecutor.parseHeaderJson(variables?.getLoginHeader(source.bookSourceUrl)) },
        sourceUserAgentProvider = {
            configHeaders().entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                ?: UserAgents.CHROME_DESKTOP
        },
    )
}

/**
 * The desktop's shared client under the Legado request rules. Web challenges are that client's own business (its
 * interceptors solve them and retry), so nothing is checked here; WebView loading and per-request proxy/DNS have no
 * desktop counterpart and fail the request.
 */
internal class DesktopLegadoTransport(private val client: OkHttpClient) : LegadoPlanTransport {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        proxy: String?,
        dnsIp: String?,
        enableCookieJar: Boolean,
        readTimeoutMs: Long?,
        callTimeoutMs: Long?,
    ): Response = send(Request.Builder().url(url), headers, proxy, dnsIp, enableCookieJar, readTimeoutMs, callTimeoutMs)

    override suspend fun post(
        url: String,
        body: RequestBody,
        headers: Map<String, String>,
        proxy: String?,
        dnsIp: String?,
        enableCookieJar: Boolean,
        readTimeoutMs: Long?,
        callTimeoutMs: Long?,
    ): Response = send(Request.Builder().url(url).post(body), headers, proxy, dnsIp, enableCookieJar, readTimeoutMs, callTimeoutMs)

    override suspend fun getWithWebView(
        url: String,
        headers: Map<String, String>,
        delayMs: Long,
        webJs: String?,
        sourceRegex: String?,
        overrideUrlRegex: String?,
    ): LegadoHttpResponse = throw UnsupportedOperationException("Legado sources cannot load pages in a WebView on the desktop")

    suspend fun send(
        request: Request.Builder,
        headers: Map<String, String>,
        proxy: String? = null,
        dnsIp: String? = null,
        enableCookieJar: Boolean = true,
        readTimeoutMs: Long? = null,
        callTimeoutMs: Long? = null,
    ): Response = withContext(Dispatchers.IO) {
        if (proxy != null || dnsIp != null) {
            throw UnsupportedOperationException("Legado sources cannot choose a proxy or DNS per request on the desktop")
        }
        headers.forEach { (name, value) -> request.header(name, value) }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            request.header("User-Agent", UserAgents.CHROME_DESKTOP)
        }
        val ownCookies = headers.keys.any { it.equals("Cookie", ignoreCase = true) }
        val active = if (!enableCookieJar || ownCookies || readTimeoutMs != null || callTimeoutMs != null) {
            client.newBuilder().apply {
                when {
                    !enableCookieJar -> cookieJar(CookieJar.NO_COOKIES)
                    // OkHttp replaces a request's Cookie header with the jar's; the rules already merged both.
                    ownCookies -> cookieJar(StoreOnly(client.cookieJar))
                }
                readTimeoutMs?.let { readTimeout(it, TimeUnit.MILLISECONDS) }
                callTimeoutMs?.let { callTimeout(it, TimeUnit.MILLISECONDS) }
            }.build()
        } else client
        active.newCall(request.build()).execute()
    }

    /**
     * Android parses whatever a failed request returned and ends up with an empty list. Whatever challenge the shared
     * client could solve is solved by now, so a remaining error status is reported as what it is.
     */
    override fun checkProtection(response: Response, finalUrl: String, requestHeaders: Map<String, String>) {
        if (response.code < 400) return
        response.close()
        throw HttpStatusException(response.code)
    }

    override fun isProtectionError(error: Exception): Boolean = error is HttpStatusException

    private class HttpStatusException(code: Int) : java.io.IOException("HTTP $code")

    override fun persistentCookieHeader(url: String): String {
        val target = url.toHttpUrlOrNull() ?: return ""
        return client.cookieJar.loadForRequest(target).joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** What the document declares, else UTF-8; Android additionally guesses from the bytes. */
    override fun detectHtmlEncoding(bytes: ByteArray): String =
        META_CHARSET.find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1) ?: "UTF-8"

    override fun decodeBase64(value: String): ByteArray = Base64.getMimeDecoder().decode(value)

    private class StoreOnly(private val jar: CookieJar) : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = jar.saveFromResponse(url, cookies)
    }

    private companion object {
        val META_CHARSET = Regex("<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
    }
}
