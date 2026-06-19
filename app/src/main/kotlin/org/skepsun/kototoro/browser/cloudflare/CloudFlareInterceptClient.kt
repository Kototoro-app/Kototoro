package org.skepsun.kototoro.browser.cloudflare

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.network.webview.adblock.AdBlock
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

private val BLOCKED_HEADERS = setOf(
    "sec-ch-ua",
    "sec-ch-ua-full-version-list",
    "x-requested-with",
)

class CloudFlareInterceptClient(
    private val cookieJar: MutableCookieJar,
    callback: CloudFlareCallback,
    adBlock: AdBlock,
    targetUrl: String,
) : CloudFlareClient(cookieJar, callback, adBlock = adBlock, targetUrl = targetUrl) {

    private val targetUri = runCatching { URI(targetUrl) }.getOrNull()

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (request == null) {
            return null
        }
        if (!shouldReplayRequest(request)) {
            return super.shouldInterceptRequest(view, request)
        }
        return runCatching {
            val client = OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
            val builder = Request.Builder()
                .url(request.url.toString())
                .method(request.method, null)
            for ((key, value) in request.requestHeaders) {
                if (key.lowercase(Locale.ROOT) !in BLOCKED_HEADERS) {
                    builder.addHeader(key, value)
                }
            }
            val response = client.newCall(builder.build()).execute()
            val contentType = response.header("Content-Type")
            val mimeType: String
            val charset: String?
            if (contentType != null) {
                val parts = contentType.split(";")
                mimeType = parts[0].trim()
                charset = parts.firstOrNull { it.trim().startsWith("charset=", ignoreCase = true) }
                    ?.substringAfter("=")
                    ?.trim()
            } else {
                mimeType = "text/html"
                charset = "UTF-8"
            }
            WebResourceResponse(mimeType, charset, response.body?.byteStream()).apply {
                responseHeaders = buildMap {
                    response.headers.forEach { put(it.first, it.second) }
                }
            }
        }.getOrNull()
    }

    private fun shouldReplayRequest(request: WebResourceRequest): Boolean {
        if (request.method != "GET" || !request.isForMainFrame) {
            return false
        }
        if (request.requestHeaders.keys.none { it.lowercase(Locale.ROOT) in BLOCKED_HEADERS }) {
            return false
        }
        val requestUri = runCatching { URI(request.url.toString()) }.getOrNull() ?: return false
        return requestUri.scheme.equals(targetUri?.scheme, ignoreCase = true) &&
            requestUri.host.equals(targetUri?.host, ignoreCase = true) &&
            normalizedPort(requestUri) == normalizedPort(targetUri)
    }

    private fun normalizedPort(uri: URI?): Int {
        if (uri == null) {
            return -1
        }
        return when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            uri.scheme.equals("http", ignoreCase = true) -> 80
            else -> -1
        }
    }
}
