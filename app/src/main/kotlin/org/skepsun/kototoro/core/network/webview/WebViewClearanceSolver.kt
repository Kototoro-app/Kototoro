package org.skepsun.kototoro.core.network.webview

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.Request
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mihon/Komikku 风格的 Cloudflare 求解器（用于 CloudflareStrategy.MIHON）。
 *
 * 与原流程（transport / activity）不同，这是 OkHttp 拦截器级别的离屏求解：
 * 1. 用 [Request] 的 URL 与安全头在离屏 [WebView] 中加载同一页面；
 * 2. Cloudflare 在此 WebView 内下发并执行挑战；
 * 3. 挑战通过后 WebView 与 OkHttp 共享同一 CookieManager（[org.skepsun.kototoro.core.network.cookies.AndroidCookieJar]），
 *    新的 `cf_clearance` 写入共享 Cookie 存储；
 * 4. 调用方检测到新 clearance 后重试原请求。
 *
 * 移植自 komikku 的 CloudflareInterceptor / WebViewInterceptor（约 240 行）。
 * 注意：本类的方法必须在工作线程调用（OkHttp 拦截器场景），WebView 操作会投递到主线程。
 */
@Singleton
class WebViewClearanceSolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cookieJar: CookieJar,
) {
    private val executor = ContextCompat.getMainExecutor(context)

    /**
     * 在离屏 WebView 中求解 [request] 的 Cloudflare 挑战。
     * 返回 true 当且仅当检测到新的 `cf_clearance`（与求解前不同）。
     * 求解前会移除旧的 `cf_clearance`，以便把“新 cookie 出现”当作成功信号。
     */
    fun solve(request: Request): Boolean {
        val url = request.url.toString()
        val oldClearance = CloudFlareHelper.getClearanceCookie(cookieJar, url)
        removeClearance(request)

        val latch = CountDownLatch(1)
        val headers = parseHeaders(request.headers)
        var webView: WebView? = null
        var cloudflareBypassed = false
        var challengeFound = false

        executor.execute {
            webView = try {
                createWebView(request)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "createWebView failed: " + url, e)
                latch.countDown()
                return@execute
            }
            webView!!.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    if (hasNewClearance(url, oldClearance)) {
                        cloudflareBypassed = true
                        latch.countDown()
                        return
                    }
                    if (finishedUrl == url && !challengeFound) {
                        // 首个请求直接加载完成且没有出现挑战，放弃等待。
                        latch.countDown()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        if (errorResponse?.statusCode in ERROR_CODES) {
                            // 主框架返回 CF 挑战页，继续等待 JS 求解。
                            challengeFound = true
                        } else {
                            // 非 Cloudflare 错误，放弃等待。
                            latch.countDown()
                        }
                    }
                }
            }
            webView!!.loadUrl(url, headers)
        }

        latch.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        executor.execute {
            webView?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                }
            }
        }
        return cloudflareBypassed
    }

    private fun hasNewClearance(url: String, oldClearance: String?): Boolean {
        val current = CloudFlareHelper.getClearanceCookie(cookieJar, url)
        return current != null && current != oldClearance
    }

    private fun removeClearance(request: Request) {
        (cookieJar as? MutableCookieJar)?.removeCookies(
            request.url,
            androidx.core.util.Predicate { it.name in CLOUDFLARE_COOKIE_NAMES },
        )
    }

    private fun createWebView(request: Request): WebView {
        return WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            // 沿用请求的 UA，保证 WebView 与 OkHttp 指纹一致（Cloudflare 按 UA 绑定 cookie）。
            request.header("User-Agent")?.let { settings.userAgentString = it }
        }
    }

    /**
     * 仅保留 WebView 接受的安全请求头，避免抛 net::ERR_INVALID_ARGUMENT。
     * 移植自 komikku WebViewInterceptor.parseHeaders。
     */
    private fun parseHeaders(headers: Headers): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = headers.value(i)
            if (isRequestHeaderSafe(name, value)) {
                result.putIfAbsent(name, value)
            }
        }
        return result
    }

    private companion object {
        const val TAG = "WebViewClearanceSolver"
        const val WAIT_TIMEOUT_SECONDS = 30L
        val ERROR_CODES = listOf(403, 503)
        val CLOUDFLARE_COOKIE_NAMES = listOf("cf_clearance")

        // 移植自 Chromium header_util.cc IsRequestHeaderSafe（komikku 同源）。
        val UNSAFE_HEADER_NAMES = listOf(
            "content-length", "host", "trailer", "te", "upgrade",
            "cookie2", "keep-alive", "transfer-encoding", "set-cookie",
        )

        fun isRequestHeaderSafe(rawName: String, rawValue: String): Boolean {
            val name = rawName.lowercase(Locale.ENGLISH)
            val value = rawValue.lowercase(Locale.ENGLISH)
            if (name in UNSAFE_HEADER_NAMES || name.startsWith("proxy-")) return false
            if (name == "connection" && value == "upgrade") return false
            return true
        }
    }
}
