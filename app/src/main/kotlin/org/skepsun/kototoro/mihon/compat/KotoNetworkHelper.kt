package org.skepsun.kototoro.mihon.compat

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import okhttp3.brotli.BrotliInterceptor
import okhttp3.zstd.Zstd
import okio.IOException
import org.skepsun.kototoro.core.exceptions.CloudFlareBlockedException
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.network.CloudFlareInterceptor as KototoroCloudFlareInterceptor
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
                .withCurrentSourceTagIfCompatible()
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
                        source = request.tag(ContentSource::class.java),
                    ),
                )

                CloudFlareHelper.PROTECTION_CAPTCHA -> response.closeThrowing(
                    CloudFlareProtectedException(
                        url = request.toBrowserChallengeUrlForSource(),
                        source = request.tag(ContentSource::class.java),
                        headers = request.headers,
                    ),
                )

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
    override fun defaultUserAgentProvider(): String = defaultUserAgent

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

    private fun Request.toBrowserChallengeUrlForSource(): String {
        return CloudFlareHelper.getBrowserChallengeUrl(url.toString())
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

    companion object {
        const val WEBVIEW_FINAL_URL_HEADER = "X-Kototoro-WebView-Final-Url"
        private val acceptedCloudflareUserAgents = ConcurrentHashMap<String, String>()

        private fun protectionLabel(protection: Int): String = when (protection) {
            CloudFlareHelper.PROTECTION_CAPTCHA -> "captcha"
            CloudFlareHelper.PROTECTION_BLOCKED -> "blocked"
            else -> "none"
        }
    }
}
