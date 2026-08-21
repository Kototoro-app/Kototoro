package org.skepsun.kototoro.core.network.jsonsource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.CacheControl
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.parser.legado.runtime.LegadoHttpResponse
import org.skepsun.kototoro.parsers.model.ContentSource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP client specifically for Legado JSON sources
 * Provides GET/POST methods with User-Agent management and cookie support
 */
@Singleton
class LegadoHttpClient @Inject constructor(
    @JsonSourceHttpClient private val okHttpClient: OkHttpClient,
    private val cookieJar: MutableCookieJar,
    private val persistentCookieJar: PersistentCookieJar,
    private val userAgentManager: UserAgentManager,
    private val webViewExecutor: org.skepsun.kototoro.core.network.webview.WebViewExecutor,
) {

    /**
     * Execute a GET request
     * @param url Target URL
     * @param headers Optional custom headers
     * @param source Optional ContentSource for request tagging
     * @return HTTP response
     */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), source: ContentSource? = null): Response {
        return withContext(Dispatchers.IO) {
            val request = buildRequest(url, headers, source = source)
            okHttpClient.newCall(request).execute()
        }
    }

    suspend fun get(
        url: String,
        headers: Map<String, String>,
        source: ContentSource?,
        proxy: String?,
        dnsIp: String?,
        enableCookieJar: Boolean = true,
        readTimeoutMs: Long? = null,
        callTimeoutMs: Long? = null,
    ): Response {
        return withContext(Dispatchers.IO) {
            val request = buildRequest(url, headers, source = source)
            clientForRequest(
                proxy = proxy,
                dnsIp = dnsIp,
                url = url,
                enableCookieJar = enableCookieJar,
                readTimeoutMs = readTimeoutMs,
                callTimeoutMs = callTimeoutMs,
            ).newCall(request).execute()
        }
    }

    /**
     * Execute a GET request using WebView for JavaScript execution.
     * Used for sources with webView: true that require JS to render content.
     * @param url Target URL
     * @param headers Optional custom headers
     * @param delayMs Delay in ms to wait for JS execution after page load
     * @return HTML content as string
     */
    suspend fun getWithWebView(
        url: String,
        headers: Map<String, String> = emptyMap(),
        delayMs: Long = 1500,
        webJs: String? = null,
        sourceRegex: String? = null,
        overrideUrlRegex: String? = null,
        blockImages: Boolean = true
    ): LegadoHttpResponse {
        android.util.Log.d("LegadoHttpClient", "[WebView] Loading URL: $url")
        val allHeaders = mutableMapOf<String, String>()
        if (!headers.containsKey("User-Agent")) {
            allHeaders["User-Agent"] = userAgentManager.getUserAgent()
        }
        allHeaders.putAll(headers)

        return if (!sourceRegex.isNullOrBlank() || !overrideUrlRegex.isNullOrBlank()) {
            val sniffed = webViewExecutor.sniff(
                url = url,
                headers = allHeaders,
                delayMs = delayMs,
                sourceRegex = sourceRegex,
                overrideUrlRegex = overrideUrlRegex,
                javaScript = webJs,
                blockImages = blockImages,
            )
            if (sniffed == null) {
                LegadoHttpResponse(
                    url = url,
                    body = "",
                    code = 500,
                )
            } else {
                LegadoHttpResponse(
                    url = sniffed.url,
                    body = sniffed.body,
                    code = sniffed.code,
                    headers = sniffed.headers,
                )
            }
        } else {
            val html = webViewExecutor.loadPageHtml(url, allHeaders, delayMs, webJs = webJs, blockImages = blockImages)
            LegadoHttpResponse(
                url = url,
                body = html,
                code = 200,
            )
        }
    }

    suspend fun loadHtmlWithWebView(
        html: String,
        baseUrl: String,
        delayMs: Long = 1500,
        webJs: String? = null,
        userAgent: String? = null,
    ): String {
        return webViewExecutor.loadHtml(html, baseUrl, delayMs, webJs, userAgent)
    }

    suspend fun getWebViewOverrideUrl(
        url: String,
        headers: Map<String, String> = emptyMap(),
        delayMs: Long = 1500,
        webJs: String? = null,
        overrideUrlRegex: String,
        blockImages: Boolean = true,
    ): LegadoHttpResponse? {
        val allHeaders = mutableMapOf<String, String>()
        if (!headers.containsKey("User-Agent")) {
            allHeaders["User-Agent"] = userAgentManager.getUserAgent()
        }
        allHeaders.putAll(headers)

        val result = webViewExecutor.sniffOverrideUrl(
            url = url,
            headers = allHeaders,
            delayMs = delayMs,
            overrideUrlRegex = overrideUrlRegex,
            javaScript = webJs,
            blockImages = blockImages,
        ) ?: return null

        return LegadoHttpResponse(
            url = result.url,
            body = result.body,
            code = result.code,
            headers = result.headers,
        )
    }

    /**
     * Execute a POST request with form data
     */
    suspend fun post(
        url: String,
        formData: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        source: ContentSource? = null
    ): Response {
        val formBody = FormBody.Builder().apply {
            formData.forEach { (key, value) ->
                add(key, value)
            }
        }.build()
        return post(url, formBody, headers, source)
    }

    /**
     * Execute a POST request with a raw body
     */
    suspend fun post(
        url: String,
        body: okhttp3.RequestBody,
        headers: Map<String, String> = emptyMap(),
        source: ContentSource? = null
    ): Response {
        return withContext(Dispatchers.IO) {
            val request = buildRequest(url, headers, method = "POST", body = body, source = source)
            okHttpClient.newCall(request).execute()
        }
    }

    suspend fun post(
        url: String,
        body: okhttp3.RequestBody,
        headers: Map<String, String>,
        source: ContentSource?,
        proxy: String?,
        dnsIp: String?,
        enableCookieJar: Boolean = true,
        readTimeoutMs: Long? = null,
        callTimeoutMs: Long? = null,
    ): Response {
        return withContext(Dispatchers.IO) {
            val request = buildRequest(url, headers, method = "POST", body = body, source = source)
            clientForRequest(
                proxy = proxy,
                dnsIp = dnsIp,
                url = url,
                enableCookieJar = enableCookieJar,
                readTimeoutMs = readTimeoutMs,
                callTimeoutMs = callTimeoutMs,
            ).newCall(request).execute()
        }
    }

    suspend fun head(
        url: String,
        headers: Map<String, String> = emptyMap(),
        source: ContentSource? = null,
        callTimeoutMs: Long? = null,
    ): Response {
        return withContext(Dispatchers.IO) {
            val request = buildRequest(url, headers, method = "HEAD", source = source)
            clientForRequest(
                proxy = null,
                dnsIp = null,
                url = url,
                enableCookieJar = true,
                callTimeoutMs = callTimeoutMs,
            ).newCall(request).execute()
        }
    }

    /**
     * Build an HTTP request with appropriate headers
     */
    private fun buildRequest(
        url: String,
        customHeaders: Map<String, String>,
        method: String = "GET",
        body: okhttp3.RequestBody? = null,
        source: ContentSource? = null
    ): Request {
        val finalUrl = url
        val headersBuilder = Headers.Builder()

        // Add User-Agent if not provided (case-insensitive check)
        val hasUserAgent = customHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }
        if (!hasUserAgent) {
            // Use mobile User-Agent for better compatibility with mobile-first web sources
            headersBuilder.add("User-Agent", userAgentManager.getUserAgent())
        }

        // Add Referer if not provided (many sites require this)
        if (!customHeaders.containsKey("Referer")) {
            try {
                val parsedUrl = finalUrl.toHttpUrlOrNull() ?: URL(finalUrl).let {
                    HttpUrl.Builder()
                        .scheme(it.protocol)
                        .host(it.host)
                        .build()
                }
                headersBuilder.add("Referer", "${parsedUrl.scheme}://${parsedUrl.host}/")
            } catch (_: Exception) {
                // Ignore if URL parsing fails
            }
        }

        // Add custom headers
        customHeaders.forEach { (key, value) ->
            headersBuilder.add(key, value)
        }

        // Follow legado-with-MD3 default behavior: prefer fresh network responses.
        // Do not set "Connection"/"Keep-Alive" here; our network stack intentionally strips them.
        val hasCacheControl = customHeaders.keys.any { it.equals("Cache-Control", ignoreCase = true) }
        if (!hasCacheControl) {
            headersBuilder.add("Cache-Control", "no-cache")
        }
        val hasPragma = customHeaders.keys.any { it.equals("Pragma", ignoreCase = true) }
        if (!hasPragma) {
            headersBuilder.add("Pragma", "no-cache")
        }

        // Add Cookie header explicitly for Legado sources to enforce header-length limits (<= 4096).
        // OkHttp will skip adding cookies if the request already has a Cookie header.
        val hasCookieHeader = customHeaders.keys.any { it.equals("Cookie", ignoreCase = true) }
        if (!hasCookieHeader) {
            val cookieHeader = persistentCookieJar.getCookieHeader(finalUrl)
            if (cookieHeader.isNotBlank()) {
                headersBuilder.add("Cookie", cookieHeader)
            }
        }

        val requestBuilder = Request.Builder()
            .url(finalUrl)
            .headers(headersBuilder.build())
            .method(method, body)

        // JSON 源的部分接口会返回带时效 token（例如图片 auth_key）；
        // 若被 OkHttp/中间缓存命中旧响应，会拿到过期链接导致 403。
        // 对所有 JSON_* 源强制走网络，行为更接近 legado-with-MD3（全局 no-cache）。
        if (source?.name?.startsWith("JSON_", ignoreCase = true) == true) {
            requestBuilder.cacheControl(CacheControl.FORCE_NETWORK)
        }

        // Tag the request with the source if provided
        if (source != null) {
            requestBuilder.tag(ContentSource::class.java, source)
        }

        // Log cookies for debugging
        try {
            val httpUrl = url.toHttpUrlOrNull()
            if (httpUrl != null) {
                val cookies = cookieJar.loadForRequest(httpUrl)
                if (cookies.isNotEmpty()) {
                    android.util.Log.d("LegadoHttpClient", "Cookies for $url: ${cookies.map { "${it.name}=${it.value.take(20)}..." }}")
                } else {
                    android.util.Log.d("LegadoHttpClient", "No cookies for $url")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("LegadoHttpClient", "Failed to log cookies", e)
        }

        return requestBuilder.build()
    }

    private fun clientForRequest(
        proxy: String?,
        dnsIp: String?,
        url: String,
        enableCookieJar: Boolean,
        readTimeoutMs: Long? = null,
        callTimeoutMs: Long? = null,
    ): OkHttpClient {
        if (
            proxy.isNullOrBlank() &&
            dnsIp.isNullOrBlank() &&
            enableCookieJar &&
            readTimeoutMs == null &&
            callTimeoutMs == null
        ) {
            return okHttpClient
        }
        return okHttpClient.newBuilder().apply {
            if (!enableCookieJar) {
                cookieJar(CookieJar.NO_COOKIES)
            }
            readTimeoutMs?.takeIf { it > 0 }?.let {
                readTimeout(it, TimeUnit.MILLISECONDS)
                if (callTimeoutMs == null) {
                    callTimeout(maxOf(60_000L, it * 2), TimeUnit.MILLISECONDS)
                }
            }
            callTimeoutMs?.takeIf { it > 0 }?.let {
                callTimeout(it, TimeUnit.MILLISECONDS)
            }
            parseProxy(proxy)?.let { configuredProxy ->
                proxy(configuredProxy)
                proxySelector(object : java.net.ProxySelector() {
                    override fun select(uri: java.net.URI?): MutableList<Proxy> = mutableListOf(configuredProxy)
                    override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) = Unit
                })
            }
            if (!dnsIp.isNullOrBlank()) {
                val targetHost = url.toHttpUrlOrNull()?.host
                if (!targetHost.isNullOrBlank()) {
                    val addresses = dnsIp.split(",", ";", " ")
                        .mapNotNull { it.trim().takeIf(String::isNotBlank) }
                        .mapNotNull { raw -> runCatching { InetAddress.getByName(raw) }.getOrNull() }
                    if (addresses.isNotEmpty()) {
                        dns(object : Dns {
                            override fun lookup(hostname: String): List<InetAddress> {
                                return if (hostname.equals(targetHost, ignoreCase = true)) {
                                    addresses
                                } else {
                                    Dns.SYSTEM.lookup(hostname)
                                }
                            }
                        })
                    }
                }
            }
        }.build()
    }

    private fun parseProxy(proxy: String?): Proxy? {
        if (proxy.isNullOrBlank()) return null
        val normalized = proxy.trim()
        return runCatching {
            val uri = if (normalized.contains("://")) java.net.URI(normalized) else java.net.URI("http://$normalized")
            val host = uri.host ?: return@runCatching null
            val port = if (uri.port != -1) uri.port else if (uri.scheme.equals("socks", ignoreCase = true)) 1080 else 8080
            val type = if (uri.scheme.equals("socks", ignoreCase = true)) Proxy.Type.SOCKS else Proxy.Type.HTTP
            Proxy(type, InetSocketAddress(host, port))
        }.getOrNull()
    }

    /**
     * Get the current cookie jar for manual cookie management
     */
    fun getCookieJar(): MutableCookieJar = cookieJar

    fun getPersistentCookieHeader(url: String): String = persistentCookieJar.getCookieHeader(url)
}
