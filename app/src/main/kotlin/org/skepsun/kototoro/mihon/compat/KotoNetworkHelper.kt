package org.skepsun.kototoro.mihon.compat

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.OkHttpClient
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.brotli.BrotliInterceptor
import okhttp3.zstd.Zstd
import okio.IOException
import okio.Buffer
import kotlinx.coroutines.runBlocking
import org.skepsun.kototoro.core.exceptions.CloudFlareBlockedException
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.network.CloudFlareInterceptor as KototoroCloudFlareInterceptor
import org.skepsun.kototoro.core.network.browserTransportHeaders
import org.skepsun.kototoro.core.network.cookies.sensitiveValueFingerprint
import org.skepsun.kototoro.core.network.webview.WebViewClearanceSolver
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.CloudflareStrategy
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
 * access to the network stack, including shared Cloudflare detection and cookie management.
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
    private val defaultUserAgent: String = UserAgents.CHROME_MOBILE,
    private val webViewExecutor: dagger.Lazy<WebViewExecutor>? = null,
    private val settings: AppSettings? = null,
    private val clearanceSolver: WebViewClearanceSolver? = null,
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

        // Mihon extensions require their compatibility interceptor, but the host owns CF resolution.
        // This adapter only enriches the challenge with source/request context for the shared resolver.
        builder.addInterceptor { chain ->
            val originalRequest = chain.request()
				.withSourceRequestContext()
                .withCloudflareUserAgent()
            val request = enrichApiRequestHeadersIfNeeded(originalRequest)
            val response = chain.proceed(request)
            val challengeUrl = request.toChallengeUrl()
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
						source = request.tag(SourceRequestContext::class.java)?.source,
                    ),
                )

                CloudFlareHelper.PROTECTION_CAPTCHA -> {
					val requestContext = request.tag(SourceRequestContext::class.java)
					val requestSource = requestContext?.source
                    val error = CloudFlareProtectedException(
                        url = request.toBrowserChallengeUrlForSource(),
                        source = requestSource,
                        headers = request.headers,
                        method = request.method,
                        body = request.replayableUtf8Body(),
						contentType = request.header("Content-Type"),
                        originalUrl = request.url.toString(),
                    )
                    resolveByStrategy(chain, request, response, error)
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
                "RequestMeta: host=${request.url.host}, ua=${maskUserAgent(request.header("User-Agent"))}, " +
					"uaFingerprint=${sensitiveValueFingerprint(request.header("User-Agent"))}, " +
					"referer=${request.header("Referer")}, origin=${request.header("Origin")}, " +
					"hasCfClearance=${cfClearanceCookie != null}, " +
					"cfClearanceFingerprint=${sensitiveValueFingerprint(cfClearanceCookie)}, cookies=[$cookieNames]",
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
    override fun defaultUserAgentProvider(): String = defaultUserAgent

    private fun Response.closeThrowing(error: Throwable): Nothing {
        try {
            close()
        } catch (e: Exception) {
            error.addSuppressed(e)
        }
        throw error
    }

    private fun resolveByStrategy(
        chain: Interceptor.Chain,
        request: Request,
        response: Response,
        error: CloudFlareProtectedException,
    ): Response {
        val strategy = settings?.cloudflareStrategy ?: CloudflareStrategy.MIHON
        return when (strategy) {
            CloudflareStrategy.MIHON -> {
                val solver = clearanceSolver
                if (solver == null) {
                    response.closeThrowing(error)
                }
                val solved = runCatching { solver.solve(request) }.getOrDefault(false)
                if (solved) {
                    response.close()
                    android.util.Log.i(
                        "MihonNetwork",
                        "WebView clearance solved (MIHON); retrying: " + request.url,
                    )
                    chain.proceed(request)
                } else {
                    android.util.Log.w(
                        "MihonNetwork",
                        "MIHON solve failed; returning error: " + request.url,
                    )
                    response.closeThrowing(error)
                }
            }

            CloudflareStrategy.TRANSPORT -> {
                val browserResponse = response.use { executeWithBrowserTransport(request) }
                if (browserResponse != null) {
                    browserResponse
                } else {
                    response.closeThrowing(error)
                }
            }

            CloudflareStrategy.MANUAL -> response.closeThrowing(error)
        }
    }

    private fun executeWithBrowserTransport(request: Request): Response? {
        val executor = webViewExecutor ?: return null
        if (settings?.cloudflareStrategy != CloudflareStrategy.TRANSPORT) {
            android.util.Log.i(
                "MihonNetwork",
                "WebView transport not selected (strategy=${settings?.cloudflareStrategy}); skipping browser transport " + request.url,
            )
            return null
        }
		val requestContext = request.tag(SourceRequestContext::class.java)
		if (requestContext == null) {
			android.util.Log.w(
				"MihonNetwork",
				"Browser transport denied: missing SourceRequestContext; url=${request.url} " +
					"contentSourceTag=${request.tag(ContentSource::class.java)?.name}",
			)
			return null
		}
		if (!requestContext.allowsBrowserRequest(request.url.toString())) {
			android.util.Log.w(
				"MihonNetwork",
				"Browser transport denied by source origin policy: source=${requestContext.source.name}, url=${request.url}",
			)
			return null
		}
        if (request.method != "GET" && request.method != "POST") return null
        val accept = request.header("Accept")?.lowercase()
        if (accept != null && BINARY_MEDIA_TYPES.any(accept::contains)) return null
        val requestBody = request.body?.let { body ->
            if (body.isDuplex() || body.isOneShot()) return null
            val contentLength = body.contentLength()
            if (contentLength > MAX_BROWSER_REQUEST_BODY_BYTES) return null
            Buffer().use { buffer ->
                body.writeTo(buffer)
                if (buffer.size > MAX_BROWSER_REQUEST_BODY_BYTES) return null
                buffer.readUtf8()
            }
        }
        val browserResult = runCatching {
            runBlocking {
                executor.get().fetchWithBrowserContext(
                    url = request.url.toString(),
                    method = request.method,
                    body = requestBody,
                    userAgent = request.header("User-Agent"),
					headers = request.browserTransportHeaders(),
					allowedOrigins = requestContext.allowedBrowserOrigins,
					allowInteractiveChallenge = false,
                    timeoutMs = BROWSER_TRANSPORT_TIMEOUT_MS,
                )
            }
		}.onFailure { error ->
			android.util.Log.w("MihonNetwork", "Browser transport failed: ${request.url}", error)
		}.getOrNull() ?: return null
		if (browserResult.status !in 100..599) {
			android.util.Log.w(
				"MihonNetwork",
				"Browser transport rejected invalid HTTP status: status=${browserResult.status}, url=${request.url}",
			)
			return null
		}
		val browserProtection = browserResult.toOkHttpResponse(request).use { response ->
            CloudFlareHelper.checkResponseForProtection(response)
        }
		if (browserProtection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
			android.util.Log.w(
				"MihonNetwork",
				"Browser transport exhausted same-session challenge: url=${request.url}, status=${browserResult.status}",
			)
			// Return null so the caller surfaces CloudFlareProtectedException, which drives the
			// CF verification actions (verify / open in browser). The coordinator's cooldown and
			// escalation chain prevent the legacy resolver loop.
			return null
		}
        android.util.Log.i(
            "MihonNetwork",
            "Browser transport accepted: method=${request.method}, status=${browserResult.status}, " +
                "url=${request.url}, bodyLength=${browserResult.body.length}",
        )
        return browserResult.toOkHttpResponse(request)
    }

    private fun WebViewExecutor.BrowserFetchResult.toOkHttpResponse(request: Request): Response {
        val responseHeaders = Headers.Builder()
        headers.forEach { (name, value) ->
            if (!name.equals("content-length", ignoreCase = true) && value.isNotBlank()) {
                runCatching { responseHeaders.add(name, value) }
            }
        }
        responseHeaders.set(WEBVIEW_FINAL_URL_HEADER, url)
        val mediaType = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?.toMediaTypeOrNull()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
			.code(status)
            .message(statusText.ifBlank { "Browser Transport" })
            .headers(responseHeaders.build())
            .body(body.toResponseBody(mediaType))
            .build()
    }

    private fun okhttp3.Request.toChallengeUrl(): String {
        return url.newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun Request.toBrowserChallengeUrlForSource(): String {
        return CloudFlareHelper.getBrowserChallengeUrl(url.toString())
    }

	private fun Request.withSourceRequestContext(): Request {
		tag(SourceRequestContext::class.java)?.let { return this }
		val legacySource = tag(ContentSource::class.java)
			?: MihonRequestContext.currentSource()
			?: MihonRequestContext.sourceForHost(url.host)
			?: return this
		android.util.Log.d(
			"MihonNetwork",
			"Recovered source request context: host=${url.host}, source=${legacySource.name}",
		)
		return newBuilder()
			.tag(ContentSource::class.java, legacySource)
			.tag(SourceRequestContext::class.java, SourceRequestContext.from(legacySource))
			.build()
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
        val previous = acceptedCloudflareUserAgents.put(host, userAgent)
        if (previous != userAgent) {
            android.util.Log.d(
                "MihonNetwork",
                "Remembered accepted Cloudflare UA for host=$host: ${maskUserAgent(userAgent)}",
            )
        }
    }

    private fun Request.replayableUtf8Body(): String? {
        val requestBody = body ?: return null
        if (requestBody.isDuplex() || requestBody.isOneShot() || requestBody.contentLength() > MAX_BROWSER_REQUEST_BODY_BYTES) return null
        return runCatching { Buffer().use { buffer -> requestBody.writeTo(buffer); buffer.readUtf8() } }.getOrNull()
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

    private fun maskUserAgent(value: String?): String {
        return value
            ?.replace(Regex("""Chrome/\d+(\.\d+)*"""), "Chrome/*")
            ?.take(140)
            ?: "<none>"
    }

    private fun cookieDebugString(url: okhttp3.HttpUrl): String {
        return cookieJar.loadForRequest(url)
            .joinToString(",") { cookie -> "${cookie.name}=${sensitiveValueFingerprint(cookie.value)}" }
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
                    "$name=${sensitiveValueFingerprint(value)}"
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

    companion object {
        const val WEBVIEW_FINAL_URL_HEADER = "X-Kototoro-WebView-Final-Url"
        private const val MAX_BROWSER_REQUEST_BODY_BYTES = 2L * 1024L * 1024L
		private const val BROWSER_TRANSPORT_TIMEOUT_MS = WebViewExecutor.DEFAULT_CAPTCHA_TIMEOUT_MS
        private val BINARY_MEDIA_TYPES = setOf("image/", "audio/", "video/", "application/octet-stream")
        private val acceptedCloudflareUserAgents = ConcurrentHashMap<String, String>()

        private fun protectionLabel(protection: Int): String = when (protection) {
            CloudFlareHelper.PROTECTION_CAPTCHA -> "captcha"
            CloudFlareHelper.PROTECTION_BLOCKED -> "blocked"
            else -> "none"
        }
    }
}
