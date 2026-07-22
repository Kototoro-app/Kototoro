package org.skepsun.kototoro.mihon.compat

import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
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
import okhttp3.brotli.BrotliInterceptor
import okhttp3.zstd.Zstd
import okio.IOException
import org.skepsun.kototoro.core.exceptions.CloudFlareBlockedException
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import org.skepsun.kototoro.core.network.CloudFlareInterceptor as KototoroCloudFlareInterceptor
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.core.parser.legado.LegadoNetworkUtils
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

    // Dynamically loaded extensions reference this class outside the app's static dex graph.
    private val zstdRuntimeDependency = Zstd
    
    /**
     * The OkHttpClient for Mihon extensions.
     *
     * Start from the application client so proxy, TLS, cache, DNS, and
     * connection settings survive. Only the interceptor lists are rebuilt:
     * Mihon/Keiyoushi sources own response compression.
     */
    override val client: OkHttpClient = run {
        val builder = baseClient.newBuilder().apply {
            interceptors().clear()
            networkInterceptors().clear()
            cookieJar(cookieJar)
        }
        
        // Newer Mihon extensions validate these concrete interceptors and their order.
        builder.addInterceptor(UncaughtExceptionInterceptor())
        builder.addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))
        builder.addInterceptor(CloudflareInterceptor())
        
        // Mihon extensions handle compression and require Brotli to be absent from the default client.
        baseClient.interceptors.forEach { interceptor ->
            if (isCompatibleInterceptor(interceptor) && !isDefaultMihonInterceptor(interceptor)) {
                builder.addInterceptor(interceptor)
            } else {
                android.util.Log.d("KotoNetworkHelper", "Skipping ${interceptor.javaClass.simpleName} for Mihon client")
            }
        }
        
        // Copy compatible network interceptors.
        baseClient.networkInterceptors.forEach { interceptor ->
            if (isCompatibleInterceptor(interceptor)) {
                builder.addNetworkInterceptor(interceptor)
            } else {
                android.util.Log.d("KotoNetworkHelper", "Skipping ${interceptor.javaClass.simpleName} for Mihon client")
            }
        }

        // Add a Mihon-specific fallback detector.
        // Some Mihon sources build their own clients from network.cloudflareClient, and in practice
        // the copied base interceptor chain is not always enough to surface Kototoro's CF flow.
        builder.addInterceptor { chain ->
            val originalRequest = chain.request()
                .withCurrentSourceTagIfCompatible()
                .withCloudflareUserAgent()
            val request = enrichApiRequestHeadersIfNeeded(originalRequest)
            val response = chain.proceed(request)
            val challengeUrl = request.toChallengeUrl()
            val browserChallengeUrl = request.toBrowserChallengeUrlForSource()
            val successCookieUrl = request.toSuccessCookieUrl()
            val protection = CloudFlareHelper.checkResponseForProtection(response)
            rememberAcceptedCloudflareUserAgent(request, response, protection)
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
                    val shouldRefreshMihonClearance = shouldRefreshMihonClearance(request, clearance)
                    android.util.Log.w(
                        "MihonNetwork",
                        "Cloudflare captcha flow start: host=$host, method=${request.method}, " +
                            "sourceTagged=${request.tag(ContentSource::class.java) != null}, " +
                            "challengeUrl=$challengeUrl, browserChallengeUrl=$browserChallengeUrl, successCookieUrl=$successCookieUrl, " +
                            "ua=${maskUserAgent(request.header("User-Agent"))}, " +
                            "oldClearance=${maskCookieValue(clearance)}, refreshMihonClearance=$shouldRefreshMihonClearance, " +
                            "cookiesBefore=[${cookieDebugString(request.url)}]",
                    )
                    if (shouldSkipInteractiveAction(host, clearance)) {
                        android.util.Log.w(
                            "MihonNetwork",
                            "Falling back to manual Cloudflare for host=$host: repeated challenge with same cf_clearance, " +
                                "cookies=[${cookieDebugString(request.url)}]",
                        )
                        val source = request.tag(ContentSource::class.java)
                        if (source != null) {
                            response.closeThrowing(
                                InteractiveActionRequiredException(
                                    source = source,
                                    url = browserChallengeUrl,
                                    userAgent = request.header("User-Agent") ?: defaultUserAgentProvider(),
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
                    }

                    if (shouldRefreshMihonClearance) {
                        clearCloudflareCookieIfPossible(request, clearance)
                    }
                    if (trySolveCloudflareWithWebView(request, browserChallengeUrl, clearance)) {
                        android.util.Log.i(
                            "MihonNetwork",
                            "Cloudflare WebView solve succeeded for host=$host, retrying request=${request.url}, " +
                                "cookiesAfterSolve=[${cookieDebugString(request.url)}]",
                        )
                        response.close()
                        val retriedResponse = chain.proceed(request)
                        val retriedProtection = CloudFlareHelper.checkResponseForProtection(retriedResponse)
                        if (retriedProtection == CloudFlareHelper.PROTECTION_NOT_DETECTED) {
                            recentWebViewSolveSuccessAt[host] = System.currentTimeMillis()
                            rememberAcceptedCloudflareUserAgent(request, retriedResponse, retriedProtection)
                            android.util.Log.i(
                                "MihonNetwork",
                                "Cloudflare retry accepted for host=$host, status=${retriedResponse.code}, " +
                                    "cookiesAfterRetry=[${cookieDebugString(request.url)}]",
                            )
                            return@addInterceptor retriedResponse
                        }
                        android.util.Log.w(
                            "MihonNetwork",
                            "Retry after Cloudflare WebView solve still protected for host=$host, " +
                                "status=${retriedResponse.code}, protection=${protectionLabel(retriedProtection)}, " +
                                "server=${retriedResponse.header("server")}, cf-ray=${retriedResponse.header("cf-ray")}, " +
                                "cookiesAfterRetry=[${cookieDebugString(request.url)}]",
                        )
                        retriedResponse.close()
                    }

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
                                rememberAcceptedCloudflareUserAgent(request, retriedResponse, retriedProtection)
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

                    run {
                        val source = request.tag(ContentSource::class.java)
                        if (source == null) {
                            android.util.Log.w("MihonNetwork", "Missing ContentSource tag, attempting silent Cloudflare solve for host=$host")
                            val executor = webViewExecutor
                            if (executor != null) {
                                val solved = kotlinx.coroutines.runBlocking {
                                    executor.loginAndCheck(
                                        loginUrl = browserChallengeUrl,
                                        checkStatus = { _, title ->
                                            !title.contains("Just a moment", ignoreCase = true) && 
                                            !title.contains("Cloudflare", ignoreCase = true) &&
                                            title.isNotBlank()
                                        },
                                        timeoutMs = 45000,
                                        userAgent = request.header("User-Agent") ?: defaultUserAgentProvider(),
                                        headers = buildWebViewHeaders(request),
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
                            android.util.Log.w(
                                "MihonNetwork",
                                "Falling back to manual Cloudflare for host=$host: auto solve/fetch did not produce usable response, " +
                                    "source=${source.name}, cookies=[${cookieDebugString(request.url)}]",
                            )
                            response.closeThrowing(
                                InteractiveActionRequiredException(
                                    source = source,
                                    url = browserChallengeUrl,
                                    userAgent = request.header("User-Agent") ?: defaultUserAgentProvider(),
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
                "RequestMeta: host=${request.url.host}, ua=${maskUserAgent(request.header("User-Agent"))}, referer=${request.header("Referer")}, origin=${request.header("Origin")}, hasCfClearance=${cfClearanceCookie != null}, cfClearance=${maskCookieValue(cfClearanceCookie)}, cookies=[$cookieNames]",
            )
            android.util.Log.d("MihonNetwork", "Request: ${request.method} ${request.url}")
            
            val response = chain.proceed(request)
            logCloudflareSetCookies(response)
            
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

    private fun isCompatibleInterceptor(interceptor: okhttp3.Interceptor): Boolean {
        return interceptor !== BrotliInterceptor &&
            interceptor.javaClass.simpleName != "GZipInterceptor" &&
            interceptor.javaClass.simpleName != "IgnoreGzipInterceptor" &&
            interceptor !is KototoroCloudFlareInterceptor
    }

    private fun isDefaultMihonInterceptor(interceptor: okhttp3.Interceptor): Boolean {
        return interceptor.javaClass.simpleName in setOf(
            "UncaughtExceptionInterceptor",
            "UserAgentInterceptor",
            "CloudflareInterceptor",
        )
    }

    /**
     * Compatibility client for legacy Mihon sources that relied on Mihon's
     * pre-1.6 default Brotli network interceptor.
     *
     * KeiSource must continue using [client], which intentionally omits this
     * interceptor and installs CompressionInterceptor itself.
     */
    @Deprecated("The regular client handles Cloudflare by default")
    override val cloudflareClient: OkHttpClient = client.newBuilder()
        .addNetworkInterceptor(BrotliInterceptor)
        .build()
    
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

    private fun okhttp3.Request.toBrowserChallengeUrl(): String {
        return url.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun Request.toBrowserChallengeUrlForSource(): String {
        return CloudFlareHelper.getChallengeUrl(url.toString())
    }

    private fun okhttp3.Request.toSuccessCookieUrl(): String {
        return CloudFlareHelper.getChallengeUrl(url.toString())
    }

    private fun Request.withCurrentSourceTagIfCompatible(): Request {
        val source = MihonRequestContext.currentSource() ?: return this
        val taggedSource = tag(ContentSource::class.java)
        if (taggedSource != null && taggedSource.name != source.name) return this
        return newBuilder().tag(ContentSource::class.java, source).build()
    }

    private fun Request.withCloudflareUserAgent(): Request {
        val currentUserAgent = header("User-Agent")?.takeIf { it.isNotBlank() }
        val pinnedUserAgent = if (hasCloudflareClearance()) {
            acceptedCloudflareUserAgents[url.host.lowercase()]
        } else {
            null
        }
        val targetUserAgent = pinnedUserAgent ?: currentUserAgent ?: defaultUserAgentProvider()
        if (currentUserAgent == targetUserAgent) return this
        if (!pinnedUserAgent.isNullOrBlank() && !currentUserAgent.isNullOrBlank()) {
            android.util.Log.d(
                "MihonNetwork",
                "Using accepted Cloudflare UA for host=${url.host}: " +
                    "from=${maskUserAgent(currentUserAgent)} to=${maskUserAgent(targetUserAgent)}",
            )
        }
        return newBuilder()
            .header("User-Agent", targetUserAgent)
            .build()
    }

    private fun Request.hasCloudflareClearance(): Boolean {
        return cookieJar.loadForRequest(url).any { it.name == "cf_clearance" }
    }

    private fun rememberAcceptedCloudflareUserAgent(request: Request, response: Response, protection: Int) {
        if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED || !response.isSuccessful) return
        if (!request.hasCloudflareClearance()) return
        val userAgent = request.header("User-Agent")?.takeIf { it.isNotBlank() } ?: return
        val host = request.url.host.lowercase()
        recentWebViewSolveFailureAt.remove(host)
        val previous = acceptedCloudflareUserAgents.put(host, userAgent)
        if (previous != userAgent) {
            android.util.Log.d(
                "MihonNetwork",
                "Remembered accepted Cloudflare UA for host=$host: ${maskUserAgent(userAgent)}",
            )
        }
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

    private fun shouldRefreshMihonClearance(request: Request, clearance: String?): Boolean {
        if (clearance.isNullOrBlank()) return false
        return request.isMihonRequest()
    }

    private fun Request.isMihonRequest(): Boolean {
        return tag(ContentSource::class.java)?.name?.startsWith("MIHON_") == true
    }

    private fun trySolveCloudflareWithWebView(request: Request, challengeUrl: String, oldClearance: String?): Boolean {
        if (request.method != "GET") {
            android.util.Log.d("MihonNetwork", "Cloudflare WebView solve skipped: non-GET ${request.method}")
            return false
        }
        val executor = webViewExecutor
        if (executor == null) {
            android.util.Log.w("MihonNetwork", "Cloudflare WebView solve skipped: WebViewExecutor is null")
            return false
        }
        val host = request.url.host.lowercase()
        val shouldRefreshMihonClearance = shouldRefreshMihonClearance(request, oldClearance)
        return runBlocking {
            val mutex = webViewFallbackMutexes.computeIfAbsent(host) { Mutex() }
            mutex.withLock {
                if (shouldSkipAutoSolveAfterFailure(request, host)) {
                    android.util.Log.w(
                        "MihonNetwork",
                        "Cloudflare WebView solve skipped: recent auto solve failed for host=$host",
                    )
                    return@withLock false
                }
                if (shouldReuseRecentWebViewSolve(host)) {
                    android.util.Log.i("MihonNetwork", "Cloudflare WebView solve reused recent success for host=$host")
                    return@withLock true
                }
                android.util.Log.i(
                    "MihonNetwork",
                    "Cloudflare WebView solve start: host=$host, challengeUrl=$challengeUrl, " +
                        "ua=${maskUserAgent(request.header("User-Agent"))}, headerNames=${buildWebViewHeaders(request).keys}, " +
                        "oldClearance=${maskCookieValue(oldClearance)}, refreshMihonClearance=$shouldRefreshMihonClearance, " +
                        "cookiesBefore=[${cookieDebugString(request.url)}]",
                )
                val solved = executor.loginAndCheck(
                    loginUrl = challengeUrl,
                    checkStatus = { _, _ ->
                        val currentClearance = cookieJar.loadForRequest(request.url)
                            .firstOrNull { it.name == "cf_clearance" }
                            ?.value
                        android.util.Log.d(
                            "MihonNetwork",
                            "Cloudflare WebView solve check: host=$host, currentClearance=${maskCookieValue(currentClearance)}, " +
                                "changed=${!currentClearance.isNullOrBlank() && currentClearance != oldClearance}, " +
                                "cookies=[${cookieDebugString(request.url)}]",
                        )
                        currentClearance
                            ?.let { it.isNotBlank() && it != oldClearance }
                            ?: false
                    },
                    timeoutMs = CLOUDFLARE_WEBVIEW_SOLVE_TIMEOUT_MS,
                    userAgent = request.header("User-Agent") ?: defaultUserAgentProvider(),
                    headers = buildWebViewHeaders(request),
                    clearCookieNames = if (shouldRefreshMihonClearance) setOf("cf_clearance") else emptySet(),
                    clearAllWebViewCookies = shouldRefreshMihonClearance,
                )
                if (solved) {
                    recentWebViewSolveSuccessAt[host] = System.currentTimeMillis()
                    recentWebViewSolveFailureAt.remove(host)
                } else {
                    recentWebViewSolveFailureAt[host] = System.currentTimeMillis()
                    android.util.Log.w(
                        "MihonNetwork",
                        "Cloudflare WebView solve failed for host=$host, cookiesAfter=[${cookieDebugString(request.url)}]",
                    )
                }
                solved
            }
        }
    }

    private fun buildWebViewHeaders(request: Request): Map<String, String> = buildMap {
        for ((name, value) in request.headers) {
            if (isWebViewRequestHeaderSafe(name, value) && !containsKey(name)) {
                put(name, value)
            }
        }
    }

    private fun isWebViewRequestHeaderSafe(name: String, value: String): Boolean {
        val lowerName = name.lowercase()
        val lowerValue = value.lowercase()
        if (lowerName in WEBVIEW_UNSAFE_HEADER_NAMES || lowerName.startsWith("proxy-")) {
            return false
        }
        if (lowerName == "connection" && lowerValue == "upgrade") {
            return false
        }
        return true
    }

    private fun maskCookieValue(value: String?): String {
        if (value.isNullOrEmpty()) return "<empty>"
        return if (value.length <= 8) "***" else "${value.take(4)}...${value.takeLast(4)}"
    }

    private fun maskUserAgent(value: String?): String {
        return value
            ?.replace(Regex("""Chrome/\d+(\.\d+)*"""), "Chrome/*")
            ?.take(140)
            ?: "<none>"
    }

    private fun cookieDebugString(url: okhttp3.HttpUrl): String {
        return cookieJar.loadForRequest(url)
            .joinToString(",") { cookie -> "${cookie.name}=${maskCookieValue(cookie.value)}" }
            .ifBlank { "<none>" }
    }

    private fun logCloudflareSetCookies(response: Response) {
        val headers = response.headers("Set-Cookie")
            .filter { it.startsWith("cf_clearance=", ignoreCase = true) }
        if (headers.isEmpty()) return
        android.util.Log.i(
            "MihonNetwork",
            "Set-Cookie cf_clearance: status=${response.code}, url=${response.request.url}, " +
                "cf-ray=${response.header("cf-ray")}, headers=${headers.joinToString(" | ", transform = ::summarizeSetCookie)}",
        )
    }

    private fun summarizeSetCookie(header: String): String {
        return header
            .split(";")
            .mapIndexedNotNull { index, part ->
                val trimmed = part.trim()
                if (trimmed.isBlank()) {
                    null
                } else if (index == 0) {
                    val name = trimmed.substringBefore("=")
                    val value = trimmed.substringAfter("=", "")
                    "$name=${maskCookieValue(value)}"
                } else {
                    val attrName = trimmed.substringBefore("=").lowercase()
                    when (attrName) {
                        "domain", "path", "max-age", "expires", "samesite" -> trimmed
                        "secure", "httponly" -> trimmed
                        else -> null
                    }
                }
            }
            .joinToString(";")
    }

    private fun webViewCookieMatrix(url: okhttp3.HttpUrl): String {
        val host = url.host.lowercase()
        val rootDomain = runCatching { LegadoNetworkUtils.getSubDomain("https://$host") }
            .getOrNull()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        val urls = buildSet {
            add(url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString())
            add(url.newBuilder().scheme("http").encodedPath("/").query(null).fragment(null).build().toString())
            if (rootDomain != null && rootDomain != host) {
                add(url.newBuilder().host(rootDomain).encodedPath("/").query(null).fragment(null).build().toString())
                add(url.newBuilder().scheme("http").host(rootDomain).encodedPath("/").query(null).fragment(null).build().toString())
            }
        }
        return urls.joinToString("|") { candidateUrl ->
            val raw = CookieManager.getInstance().getCookie(candidateUrl).orEmpty()
            "$candidateUrl=[${raw.toMaskedCookieList()}]"
        }
    }

    private fun String.toCookieNameList(): String {
        return split(";")
            .mapNotNull { it.trim().substringBefore("=").takeIf(String::isNotBlank) }
            .joinToString(",")
            .ifBlank { "<none>" }
    }

    private fun String.toMaskedCookieList(): String {
        return split(";")
            .mapNotNull { rawCookie ->
                val parts = rawCookie.trim().split("=", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    "${parts[0]}=${maskCookieValue(parts[1])}"
                } else {
                    null
                }
            }
            .joinToString(",")
            .ifBlank { "<none>" }
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

    private fun clearWebViewCloudflareCookie(request: Request) {
        val cookieManager = CookieManager.getInstance()
        val host = request.url.host.lowercase()
        val rootDomain = runCatching { LegadoNetworkUtils.getSubDomain("https://$host") }
            .getOrNull()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        val urls = buildSet {
            add(request.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString())
            if (rootDomain != null && rootDomain != host) {
                add(request.url.newBuilder().host(rootDomain).encodedPath("/").query(null).fragment(null).build().toString())
            }
        }
        val domains = buildSet {
            add(host)
            add(".$host")
            if (rootDomain != null) {
                add(rootDomain)
                add(".$rootDomain")
            }
        }
        val before = urls.joinToString("|") { url ->
            "${url}=[${CookieManager.getInstance().getCookie(url).orEmpty().toCookieNameList()}]"
        }
        urls.forEach { url ->
            cookieManager.setCookie(url, "cf_clearance=;Max-Age=0")
            cookieManager.setCookie(url, "cf_clearance=;Max-Age=0;Path=/")
            cookieManager.setCookie(url, "cf_clearance=;Expires=Thu, 01 Jan 1970 00:00:00 GMT;Path=/")
            cookieManager.setCookie(url, "cf_clearance=;Max-Age=0;Path=/;Secure")
            cookieManager.setCookie(url, "cf_clearance=;Max-Age=0;Path=/;Secure;HttpOnly")
            cookieManager.setCookie(url, "cf_clearance=;Max-Age=0;Path=/;Secure;HttpOnly;SameSite=None")
            domains.forEach { domain ->
                cookieManager.setCookie(
                    url,
                    "cf_clearance=;Max-Age=0;Domain=$domain",
                )
                cookieManager.setCookie(
                    url,
                    "cf_clearance=;Max-Age=0;Domain=$domain;Path=/",
                )
                cookieManager.setCookie(
                    url,
                    "cf_clearance=;Expires=Thu, 01 Jan 1970 00:00:00 GMT;Domain=$domain;Path=/",
                )
                cookieManager.setCookie(
                    url,
                    "cf_clearance=;Max-Age=0;Domain=$domain;Path=/;Secure",
                )
                cookieManager.setCookie(
                    url,
                    "cf_clearance=;Max-Age=0;Domain=$domain;Path=/;Secure;HttpOnly",
                )
                cookieManager.setCookie(
                    url,
                    "cf_clearance=;Max-Age=0;Domain=$domain;Path=/;Secure;HttpOnly;SameSite=None",
                )
            }
        }
        cookieManager.flush()
        val after = urls.joinToString("|") { url ->
            "${url}=[${CookieManager.getInstance().getCookie(url).orEmpty().toCookieNameList()}]"
        }
        android.util.Log.i(
            "MihonNetwork",
            "Cleared WebView cf_clearance for host=$host, rootDomain=${rootDomain ?: "<none>"}, before=$before, after=$after",
        )
        android.util.Log.i(
            "MihonNetwork",
            "WebView cf_clearance matrix for host=$host: ${webViewCookieMatrix(request.url)}",
        )
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
                        userAgent = request.header("User-Agent") ?: defaultUserAgentProvider(),
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

    private fun shouldSkipAutoSolveAfterFailure(request: Request, host: String): Boolean {
        if (!request.isMihonRequest()) return false
        val lastFailureAt = recentWebViewSolveFailureAt[host] ?: return false
        return System.currentTimeMillis() - lastFailureAt < WEBVIEW_SOLVE_FAILURE_COOLDOWN_MS
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
        private const val WEBVIEW_SOLVE_FAILURE_COOLDOWN_MS = 10 * 60 * 1000L
        private const val CLOUDFLARE_WEBVIEW_SOLVE_TIMEOUT_MS = 45_000L
        private val WEBVIEW_UNSAFE_HEADER_NAMES = setOf(
            "content-length",
            "host",
            "trailer",
            "te",
            "upgrade",
            "cookie2",
            "keep-alive",
            "transfer-encoding",
            "set-cookie",
        )
        private val recentChallengeAttempts = ConcurrentHashMap<String, ChallengeAttempt>()
        private val recentWebViewSolveSuccessAt = ConcurrentHashMap<String, Long>()
        private val recentWebViewSolveFailureAt = ConcurrentHashMap<String, Long>()
        private val webViewFallbackMutexes = ConcurrentHashMap<String, Mutex>()
        private val acceptedCloudflareUserAgents = ConcurrentHashMap<String, String>()

        private fun protectionLabel(protection: Int): String = when (protection) {
            CloudFlareHelper.PROTECTION_CAPTCHA -> "captcha"
            CloudFlareHelper.PROTECTION_BLOCKED -> "blocked"
            else -> "none"
        }
    }
}
