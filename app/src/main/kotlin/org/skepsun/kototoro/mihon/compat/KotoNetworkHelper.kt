package org.skepsun.kototoro.mihon.compat

import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.IOException
import org.skepsun.kototoro.core.exceptions.CloudFlareBlockedException
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.parsers.network.UserAgents
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Kototoro's implementation of Mihon's NetworkHelper interface.
 * 
 * Wraps Kototoro's existing OkHttpClient to provide Mihon extensions with
 * access to the network stack, including CloudFlare bypassing and cookie management.
 * 
 * Note: We create a new client without GZipInterceptor because Mihon extensions
 * handle their own request encoding. Kototoro's GZipInterceptor incorrectly
 * adds Content-Encoding: gzip header without actually compressing the body,
 * which causes server-side decompression errors (e.g., Picacomic login fails with
 * "incorrect header check").
 */
class KotoNetworkHelper(
    baseClient: OkHttpClient,
    val cookieJar: okhttp3.CookieJar,
    private val webViewExecutor: WebViewExecutor? = null,
) : NetworkHelper() {
    
    /**
     * The OkHttpClient for Mihon extensions.
     * We rebuild without GZipInterceptor to prevent incorrect Content-Encoding headers.
     */
    override val client: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
        
        // Copy configuration from base client
        builder.connectTimeout(baseClient.connectTimeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        builder.readTimeout(baseClient.readTimeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        builder.writeTimeout(baseClient.writeTimeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        builder.cookieJar(baseClient.cookieJar)
        builder.dns(baseClient.dns)
        builder.cache(baseClient.cache)
        builder.dispatcher(baseClient.dispatcher)
        builder.connectionPool(baseClient.connectionPool)
        builder.followRedirects(baseClient.followRedirects)
        builder.followSslRedirects(baseClient.followSslRedirects)
        builder.retryOnConnectionFailure(baseClient.retryOnConnectionFailure)
        
        // Wrap exceptions thrown by subsequent interceptors (especially from extensions)
        builder.addInterceptor { chain ->
            try {
                chain.proceed(chain.request())
            } catch (e: Throwable) {
                // OkHttp Dispatcher will crash the app if intercepted throws unchecked exception instead of IOException.
                // Extensions (like Baozi) might throw plain Exceptions for errors like "Socket closed".
                if (e is java.io.IOException) throw e
                throw java.io.IOException(e.message, e)
            }
        }
        
        // Copy interceptors but exclude GZipInterceptor
        baseClient.interceptors.forEach { interceptor ->
            if (interceptor.javaClass.simpleName != "GZipInterceptor") {
                builder.addInterceptor(interceptor)
            } else {
                android.util.Log.d("KotoNetworkHelper", "Skipping GZipInterceptor for Mihon client")
            }
        }
        
        // Copy network interceptors
        baseClient.networkInterceptors.forEach { interceptor ->
            builder.addNetworkInterceptor(interceptor)
        }

        // Add a Mihon-specific fallback detector.
        // Some Mihon sources build their own clients from network.cloudflareClient, and in practice
        // the copied base interceptor chain is not always enough to surface Kototoro's CF flow.
        builder.addInterceptor { chain ->
            val originalRequest = chain.request().withCurrentSourceTagIfAbsent()
            val request = enrichApiRequestHeadersIfNeeded(originalRequest)
            val response = chain.proceed(request)
            val challengeUrl = request.toChallengeUrl()
            val successCookieUrl = request.toSuccessCookieUrl()
            val protection = CloudFlareHelper.checkResponseForProtection(response)
            if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
                android.util.Log.w(
                    "MihonNetwork",
                    "Protection detected: type=${protectionLabel(protection)}, host=${request.url.host}, code=${response.code}, server=${response.header("server")}, cf-ray=${response.header("cf-ray")}, cf-mitigated=${response.header("cf-mitigated")}, url=${request.url}",
                )
            }
            when (protection) {
                CloudFlareHelper.PROTECTION_BLOCKED -> response.closeThrowing(
                    CloudFlareBlockedException(
                        url = challengeUrl,
                        source = request.tag(ContentSource::class.java),
                    ),
                )

                CloudFlareHelper.PROTECTION_CAPTCHA -> {
                    val host = request.url.host.lowercase()
                    val clearance = cookieJar.loadForRequest(request.url)
                        .firstOrNull { it.name == "cf_clearance" }
                        ?.value
                    clearCloudflareCookieIfPossible(request, clearance)

                    when (val webViewResult = tryFetchWithWebView(request)) {
                        is WebViewFallbackResult.BrowserResponse -> {
                            val browserResponse = webViewResult.response
                            val browserProtection = CloudFlareHelper.checkResponseForProtection(browserResponse)
                            if (browserProtection == CloudFlareHelper.PROTECTION_NOT_DETECTED) {
                                android.util.Log.i(
                                    "MihonNetwork",
                                    "WebView fallback succeeded for host=$host, status=${browserResponse.code}",
                                )
                                response.close()
                                return@addInterceptor browserResponse
                            }
                            android.util.Log.w(
                                "MihonNetwork",
                                "WebView fallback still protected for host=$host, status=${browserResponse.code}",
                            )
                            browserResponse.close()
                        }

                        WebViewFallbackResult.RetryRequest -> {
                            android.util.Log.i(
                                "MihonNetwork",
                                "Reusing recent WebView solve for host=$host, retrying request=${request.url}",
                            )
                            response.close()
                            val retriedResponse = chain.proceed(request)
                            val retriedProtection = CloudFlareHelper.checkResponseForProtection(retriedResponse)
                            if (retriedProtection == CloudFlareHelper.PROTECTION_NOT_DETECTED) {
                                return@addInterceptor retriedResponse
                            }
                            android.util.Log.w(
                                "MihonNetwork",
                                "Retry after recent WebView solve still protected for host=$host, status=${retriedResponse.code}",
                            )
                            retriedResponse.close()
                        }

                        WebViewFallbackResult.NotAttempted -> Unit
                    }

                    if (shouldSkipInteractiveAction(host, clearance)) {
                        android.util.Log.w(
                            "MihonNetwork",
                            "Skip interactive action for host=$host: repeated challenge with same cf_clearance",
                        )
                        val source = request.tag(ContentSource::class.java)
                        if (source != null) {
                            response.closeThrowing(
                                InteractiveActionRequiredException(
                                    source = source,
                                    url = challengeUrl,
                                    userAgent = request.header("User-Agent"),
                                    successCookieUrl = successCookieUrl,
                                    successCookieName = "cf_clearance",
                                ),
                            )
                        } else {
                            response.closeThrowing(
                                CloudFlareBlockedException(
                                    url = challengeUrl,
                                    source = null,
                                ),
                            )
                        }
                    } else {
                        val source = request.tag(ContentSource::class.java)
                        if (source == null) {
                            android.util.Log.w("MihonNetwork", "Missing ContentSource tag, attempting silent Cloudflare solve for host=$host")
                            val executor = webViewExecutor
                            if (executor != null) {
                                val solved = kotlinx.coroutines.runBlocking {
                                    executor.loginAndCheck(
                                        loginUrl = challengeUrl,
                                        checkStatus = { _, title ->
                                            !title.contains("Just a moment", ignoreCase = true) && 
                                            !title.contains("Cloudflare", ignoreCase = true) &&
                                            title.isNotBlank()
                                        },
                                        timeoutMs = 15000
                                    )
                                }
                                if (solved) {
                                    android.util.Log.i("MihonNetwork", "Silent solver succeeded, retrying host=$host")
                                    response.close()
                                    return@addInterceptor chain.proceed(request)
                                }
                            }
                            android.util.Log.e("MihonNetwork", "Silent solver failed or executor null, throwing block exception for $host")
                            response.closeThrowing(CloudFlareBlockedException(url = challengeUrl, source = null))
                        } else {
                            response.closeThrowing(
                                InteractiveActionRequiredException(
                                    source = source,
                                    url = challengeUrl,
                                    userAgent = request.header("User-Agent"),
                                    successCookieUrl = successCookieUrl,
                                    successCookieName = "cf_clearance",
                                ),
                            )
                        }
                    }
                }

                else -> response
            }
        }
        
        // Add debug logging interceptor for Mihon extensions
        builder.addInterceptor { chain ->
            val request = chain.request()
            val requestCookies = cookieJar.loadForRequest(request.url)
            val cfClearanceCookie = requestCookies.firstOrNull { it.name == "cf_clearance" }?.value
            val cookieNames = requestCookies.joinToString(",") { it.name }
            android.util.Log.d(
                "MihonNetwork",
                "RequestMeta: host=${request.url.host}, ua=${request.header("User-Agent")}, referer=${request.header("Referer")}, origin=${request.header("Origin")}, hasCfClearance=${cfClearanceCookie != null}, cfClearance=${maskCookieValue(cfClearanceCookie)}, cookies=[$cookieNames]",
            )
            android.util.Log.d("MihonNetwork", "Request: ${request.method} ${request.url}")
            
            val response = chain.proceed(request)
            
            // Log response info
            val responseCode = response.code
            val contentType = response.header("Content-Type")
            android.util.Log.d(
                "MihonNetwork",
                "Response: $responseCode, Content-Type: $contentType, cf-ray=${response.header("cf-ray")}, cf-mitigated=${response.header("cf-mitigated")}, server=${response.header("server")}, URL: ${request.url}",
            )
            
            // If response is not successful, log the first 200 chars of body for debugging
            if (!response.isSuccessful) {
                val source = response.body.source()
                source.request(200)
                val buffer = source.buffer.clone()
                val preview = buffer.readUtf8(minOf(200, buffer.size))
                android.util.Log.w("MihonNetwork", "Non-successful response ($responseCode) preview: $preview")
            }
            
            response
        }
        
        builder.build()
    }
    
    /**
     * @deprecated Since extension-lib 1.5, CloudFlare is handled by the regular client.
     */
    @Deprecated("The regular client handles Cloudflare by default")
    override val cloudflareClient: OkHttpClient = client
    
    /**
     * Returns the default user agent string.
     */
    override fun defaultUserAgentProvider(): String = UserAgents.CHROME_MOBILE

    private fun Response.closeThrowing(error: Throwable): Nothing {
        try {
            close()
        } catch (e: Exception) {
            error.addSuppressed(e)
        }
        throw error
    }

    private fun okhttp3.Request.toChallengeUrl(): String {
        return url.newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun okhttp3.Request.toSuccessCookieUrl(): String {
        return url.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun Request.withCurrentSourceTagIfAbsent(): Request {
        if (tag(ContentSource::class.java) != null) return this
        val source = MihonRequestContext.currentSource() ?: return this
        return newBuilder().tag(ContentSource::class.java, source).build()
    }

    private fun enrichApiRequestHeadersIfNeeded(request: okhttp3.Request): okhttp3.Request {
        if (!request.url.encodedPath.startsWith("/api/")) return request
        val cookies = cookieJar.loadForRequest(request.url)
        val hasCfClearance = cookies.any { it.name == "cf_clearance" }
        if (!hasCfClearance) return request
        val origin = "${request.url.scheme}://${request.url.host}"
        var modified = false
        val builder = request.newBuilder()
        if (request.header("Referer").isNullOrBlank()) {
            builder.header("Referer", "$origin/")
            modified = true
        }
        if (request.header("Origin").isNullOrBlank()) {
            builder.header("Origin", origin)
            modified = true
        }
        if (request.header("Accept").isNullOrBlank()) {
            builder.header("Accept", "application/json, text/plain, */*")
            modified = true
        }
        if (request.header("Accept-Language").isNullOrBlank()) {
            builder.header("Accept-Language", "en-US,en;q=0.9")
            modified = true
        }
        if (request.header("Sec-Fetch-Site").isNullOrBlank()) {
            builder.header("Sec-Fetch-Site", "same-origin")
            modified = true
        }
        if (request.header("Sec-Fetch-Mode").isNullOrBlank()) {
            builder.header("Sec-Fetch-Mode", "cors")
            modified = true
        }
        if (request.header("Sec-Fetch-Dest").isNullOrBlank()) {
            builder.header("Sec-Fetch-Dest", "empty")
            modified = true
        }
        if (request.header("X-Requested-With").isNullOrBlank()) {
            builder.header("X-Requested-With", "XMLHttpRequest")
            modified = true
        }
        if (request.header("X-XSRF-TOKEN").isNullOrBlank()) {
            val xsrf = cookies.firstOrNull { it.name == "XSRF-TOKEN" }?.value
            val decodedXsrf = xsrf?.let {
                runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrDefault(it)
            }
            if (!decodedXsrf.isNullOrBlank()) {
                builder.header("X-XSRF-TOKEN", decodedXsrf)
                modified = true
            }
        }
        return if (modified) builder.build() else request
    }

    private fun maskCookieValue(value: String?): String {
        if (value.isNullOrEmpty()) return "<empty>"
        return if (value.length <= 8) "***" else "${value.take(4)}...${value.takeLast(4)}"
    }

    private fun clearCloudflareCookieIfPossible(request: Request, clearance: String?) {
        if (clearance.isNullOrBlank()) return
        val mutableCookieJar = cookieJar as? MutableCookieJar ?: return
        android.util.Log.i(
            "MihonNetwork",
            "Clearing stale cf_clearance before challenge solve for host=${request.url.host}, value=${maskCookieValue(clearance)}",
        )
        mutableCookieJar.removeCookies(request.url) { cookie ->
            cookie.name == "cf_clearance"
        }
    }

    private fun tryFetchWithWebView(request: Request): WebViewFallbackResult {
        if (request.method != "GET") {
            android.util.Log.d("MihonNetwork", "WebView fallback skipped: non-GET ${request.method}")
            return WebViewFallbackResult.NotAttempted
        }
        val executor = webViewExecutor
        if (executor == null) {
            android.util.Log.w("MihonNetwork", "WebView fallback skipped: WebViewExecutor is null")
            return WebViewFallbackResult.NotAttempted
        }
        val cookies = cookieJar.loadForRequest(request.url)
        val hasCfClearance = cookies.any { it.name == "cf_clearance" }
        if (!hasCfClearance) {
            android.util.Log.d("MihonNetwork", "WebView fallback skipped: no cf_clearance for host=${request.url.host}")
            return WebViewFallbackResult.NotAttempted
        }
        val host = request.url.host.lowercase()
        return runBlocking {
            val mutex = webViewFallbackMutexes.computeIfAbsent(host) { Mutex() }
            mutex.withLock {
                if (shouldReuseRecentWebViewSolve(host)) {
                    return@withLock WebViewFallbackResult.RetryRequest
                }
                android.util.Log.i("MihonNetwork", "WebView fallback start: ${request.url}")

                val fetchHeaders = buildMap<String, String> {
                    request.header("Accept")?.let { put("Accept", it) }
                    request.header("Accept-Language")?.let { put("Accept-Language", it) }
                    request.header("Referer")?.let { put("Referer", it) }
                    request.header("Origin")?.let { put("Origin", it) }
                    request.header("X-Requested-With")?.let { put("X-Requested-With", it) }
                    request.header("X-XSRF-TOKEN")?.let { put("X-XSRF-TOKEN", it) }
                }

                val startMs = System.currentTimeMillis()
                val result = runCatching {
                    executor.fetchWithBrowserContext(
                        url = request.url.toString(),
                        userAgent = request.header("User-Agent"),
                        headers = fetchHeaders,
                    )
                }.onFailure {
                    android.util.Log.w("MihonNetwork", "WebView fallback failed: ${it.message}")
                }.getOrNull()
                if (result == null) {
                    android.util.Log.w(
                        "MihonNetwork",
                        "WebView fallback returned null after ${System.currentTimeMillis() - startMs}ms for ${request.url}",
                    )
                    return@withLock WebViewFallbackResult.NotAttempted
                }

                if (result.status <= 0) {
                    android.util.Log.w("MihonNetwork", "WebView fallback invalid status=${result.status}")
                    return@withLock WebViewFallbackResult.NotAttempted
                }

                android.util.Log.i(
                    "MihonNetwork",
                    "WebView fallback response: status=${result.status}, url=${result.url}, contentType=${result.headers["content-type"] ?: result.headers["Content-Type"]}",
                )
                val contentType = result.headers.entries
                    .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                    ?.value
                val headersBuilder = Headers.Builder()
                result.headers.forEach { (k, v) ->
                    if (k.isNotBlank() && v.isNotBlank()) {
                        runCatching { headersBuilder.add(k, v) }
                    }
                }
                if (result.url.isNotBlank()) {
                    headersBuilder.set(WEBVIEW_FINAL_URL_HEADER, result.url)
                }

                val browserResponse = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(result.status)
                    .message(result.statusText.ifBlank { "WebView fetch" })
                    .headers(headersBuilder.build())
                    .body(result.body.toResponseBody(contentType?.toMediaTypeOrNull()))
                    .build()
                if (CloudFlareHelper.checkResponseForProtection(browserResponse) == CloudFlareHelper.PROTECTION_NOT_DETECTED) {
                    recentWebViewSolveSuccessAt[host] = System.currentTimeMillis()
                }
                WebViewFallbackResult.BrowserResponse(browserResponse)
            }
        }
    }

    private fun shouldReuseRecentWebViewSolve(host: String): Boolean {
        val lastSuccessAt = recentWebViewSolveSuccessAt[host] ?: return false
        return System.currentTimeMillis() - lastSuccessAt < WEBVIEW_SOLVE_REUSE_WINDOW_MS
    }

    private fun shouldSkipInteractiveAction(host: String, clearance: String?): Boolean {
        if (clearance.isNullOrBlank()) return false
        val now = System.currentTimeMillis()
        val last = recentChallengeAttempts[host]
        if (last == null || now - last.timestampMs > INTERACTIVE_RETRY_WINDOW_MS || last.clearance != clearance) {
            recentChallengeAttempts[host] = ChallengeAttempt(
                clearance = clearance,
                timestampMs = now,
                count = 1,
            )
            return false
        }
        val nextCount = last.count + 1
        recentChallengeAttempts[host] = last.copy(
            timestampMs = now,
            count = nextCount,
        )
        return nextCount >= 2
    }

    private data class ChallengeAttempt(
        val clearance: String,
        val timestampMs: Long,
        val count: Int,
    )

    private sealed interface WebViewFallbackResult {
        data class BrowserResponse(val response: Response) : WebViewFallbackResult
        data object RetryRequest : WebViewFallbackResult
        data object NotAttempted : WebViewFallbackResult
    }

    companion object {
        const val WEBVIEW_FINAL_URL_HEADER = "X-Kototoro-WebView-Final-Url"
        private const val INTERACTIVE_RETRY_WINDOW_MS = 10 * 60 * 1000L
        private const val WEBVIEW_SOLVE_REUSE_WINDOW_MS = 10_000L
        private val recentChallengeAttempts = ConcurrentHashMap<String, ChallengeAttempt>()
        private val recentWebViewSolveSuccessAt = ConcurrentHashMap<String, Long>()
        private val webViewFallbackMutexes = ConcurrentHashMap<String, Mutex>()

        private fun protectionLabel(protection: Int): String = when (protection) {
            CloudFlareHelper.PROTECTION_CAPTCHA -> "captcha"
            CloudFlareHelper.PROTECTION_BLOCKED -> "blocked"
            else -> "none"
        }
    }
}
