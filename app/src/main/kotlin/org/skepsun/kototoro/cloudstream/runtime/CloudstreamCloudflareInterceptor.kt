package org.skepsun.kototoro.cloudstream.runtime

import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.CloudflareStrategy
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap

/** Resolves Cloudflare before Cloudstream failures escape into Kototoro's global captcha flow. */
internal class CloudstreamCloudflareInterceptor(
    private val webViewExecutor: WebViewExecutor,
    private val settings: AppSettings? = null,
) : Interceptor {
    private val resolverMutexes = ConcurrentHashMap<String, Mutex>()
    private val lastResolverAttemptAt = ConcurrentHashMap<String, Long>()
    private val lastResolverFailureAt = ConcurrentHashMap<String, Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request)
        } catch (error: CloudFlareProtectedException) {
            if (request.method != "GET") throw error
            resolveAndRetry(chain, request, error)
        }
    }

    private fun resolveAndRetry(
        chain: Interceptor.Chain,
        request: Request,
        originalError: CloudFlareProtectedException,
    ): Response = runBlocking {
        val host = request.url.host
        resolverMutexes.getOrPut(host) { Mutex() }.withLock {
            val lastFailure = lastResolverFailureAt[host] ?: 0L
            if (System.currentTimeMillis() - lastFailure < RESOLVER_RETRY_COOLDOWN_MS) {
                Log.i(TAG, "Skipping resolver after recent host failure: ${request.url}")
                throw exactRequestError(request, originalError)
            }
            try {
                resolveAndRetryLocked(chain, request, originalError)
            } catch (error: CloudFlareProtectedException) {
                lastResolverFailureAt[host] = System.currentTimeMillis()
                throw exactRequestError(request, error)
            }
        }
    }

    private suspend fun resolveAndRetryLocked(
        chain: Interceptor.Chain,
        request: Request,
        originalError: CloudFlareProtectedException,
    ): Response {
        Log.i(TAG, "Cloudstream resolver start: ${request.url}")
        val hadClearance = hasClearance(request.url.toString())
        val resolvedRequest = prepareBrowserSession(request)
        val hasClearance = hasClearance(request.url.toString())
        if (!hadClearance && hasClearance) {
            try {
                return chain.proceed(resolvedRequest)
            } catch (retryError: CloudFlareProtectedException) {
                Log.w(TAG, "OkHttp retry still protected; using browser response: ${request.url}")
            }
        } else if (hadClearance) {
            Log.i(TAG, "Existing clearance was rejected by OkHttp; using browser response: ${request.url}")
        } else {
            Log.w(TAG, "Cloudstream resolver did not obtain clearance: ${request.url}")
            throw originalError
        }

        if (settings?.cloudflareStrategy != CloudflareStrategy.TRANSPORT) {
            Log.i(
                TAG,
                "WebView transport not selected (strategy=${settings?.cloudflareStrategy}); skipping browser transport fallback: " + request.url,
            )
            throw originalError
        }

        val browserResponse = runCatchingCancellable {
            webViewExecutor.fetchWithBrowserContext(
                url = request.url.toString(),
                userAgent = resolvedRequest.header(CommonHeaders.USER_AGENT)
                    ?: WebViewResolver.getWebViewUserAgent(),
                headers = resolvedRequest.headers.toBrowserHeaders(),
                allowInteractiveChallenge = false,
                settleDelayMs = BROWSER_SETTLE_DELAY_MS,
                timeoutMs = BROWSER_TIMEOUT_MS,
            )
        }.onFailure { failure ->
            Log.w(TAG, "Browser response failed: ${request.url}", failure)
        }.getOrNull()

        if (browserResponse == null || browserResponse.isCloudflareResponse()) {
            throw originalError
        }
        Log.i(
            TAG,
            "Browser response accepted: url=${request.url} status=${browserResponse.status} " +
                "bodyLength=${browserResponse.body.length}",
        )
        return browserResponse.toOkHttpResponse(resolvedRequest)
    }

    private suspend fun prepareBrowserSession(request: Request): Request {
        val host = request.url.host
        if (!hasClearance(request.url.toString())) {
            val now = System.currentTimeMillis()
            val lastAttempt = lastResolverAttemptAt[host] ?: 0L
            if (now - lastAttempt >= RESOLVER_RETRY_COOLDOWN_MS) {
                lastResolverAttemptAt[host] = now
                resolveWithCloudstream(request)
            }
        }
        return request.withWebViewUserAgent()
    }

    private fun exactRequestError(
        request: Request,
        cause: CloudFlareProtectedException,
    ): CloudFlareProtectedException = CloudFlareProtectedException(
        url = request.url.toString(),
        source = cause.source,
        headers = request.headers,
    ).also { error ->
        if (cause !== error) error.addSuppressed(cause)
    }

    private suspend fun resolveWithCloudstream(request: Request): Request {
        var sameHostRequestCount = 0
        val host = request.url.host
        val resolver = WebViewResolver(
            interceptUrl = Regex(".^"),
            userAgent = null,
            useOkhttp = false,
            additionalUrls = listOf(Regex(".")),
            timeout = RESOLVER_TIMEOUT_MS,
        )
        resolver.resolveUsingWebView(request) { webViewRequest ->
            if (webViewRequest.url.host != host || webViewRequest.url.encodedPath.startsWith("/cdn-cgi/")) {
                return@resolveUsingWebView false
            }
            sameHostRequestCount++
            sameHostRequestCount > 1 && hasClearance(request.url.toString())
        }
        return request.withWebViewUserAgent()
    }

    private fun Request.withWebViewUserAgent(): Request {
        val userAgent = WebViewResolver.getWebViewUserAgent()
        return if (userAgent.isNullOrBlank()) this else newBuilder()
            .header(CommonHeaders.USER_AGENT, userAgent)
            .build()
    }

    private fun hasClearance(url: String): Boolean {
        return (
            CookieManager.getInstance().getCookie(url)
                ?.split(';')
                ?.any { it.trim().startsWith("cf_clearance=") }
                == true
        )
    }

    private fun Headers.toBrowserHeaders(): Map<String, String> = names()
        .filterNot { name -> name in BROWSER_MANAGED_HEADERS }
        .associateWith(::get)
        .filterValues { it != null }
        .mapValues { it.value.orEmpty() }

    private fun WebViewExecutor.BrowserFetchResult.isCloudflareResponse(): Boolean {
        if (status == 403 || status == 503) return true
        return CLOUDFLARE_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    private fun WebViewExecutor.BrowserFetchResult.toOkHttpResponse(request: Request): Response {
        val responseHeaders = Headers.Builder()
        headers.forEach { (name, value) ->
            runCatching { responseHeaders.add(name, value) }
        }
        val contentType = headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?.toMediaTypeOrNull()
        val responseCode = status.takeIf { it in 100..599 } ?: 200
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(responseCode)
            .message(statusText.ifBlank { "WebView" })
            .headers(responseHeaders.build())
            .body(body.toResponseBody(contentType))
            .build()
    }

    private companion object {
        const val TAG = "CloudstreamCfResolver"
        const val RESOLVER_TIMEOUT_MS = WebViewExecutor.DEFAULT_CAPTCHA_TIMEOUT_MS
        const val RESOLVER_RETRY_COOLDOWN_MS = 30_000L
        const val BROWSER_TIMEOUT_MS = WebViewExecutor.DEFAULT_CAPTCHA_TIMEOUT_MS
        const val BROWSER_SETTLE_DELAY_MS = 500L
        val BROWSER_MANAGED_HEADERS = setOf(
            "connection",
            "content-length",
            "cookie",
            "host",
            "origin",
            "referer",
            "user-agent",
        )
        val CLOUDFLARE_MARKERS = listOf(
            "cf-browser-verification",
            "__cf_chl_opt",
            "cf_chl",
            "Just a moment",
            "Cloudflare Ray ID",
        )
    }
}
