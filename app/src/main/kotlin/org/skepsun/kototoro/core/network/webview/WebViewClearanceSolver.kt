package org.skepsun.kototoro.core.network.webview

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.Request
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

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
 *
 * 自 2026-08 起为 [suspend] 函数：可被 [CloudflareSolveCoordinator] 取消 —— 当该 host 的
 * 最后一个等待者被取消时，求解协程被取消，WebView 会停止加载并销毁；超时同样走取消路径。
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
     *
     * 超时（[WAIT_TIMEOUT_MS]）或协程取消时返回 false 并销毁 WebView；
     * WebView 的创建、停止与销毁始终发生在主线程。
     */
    suspend fun solve(request: Request): Boolean {
        val url = request.url.toString()
        val oldClearance = CloudFlareHelper.getClearanceCookie(cookieJar, url)
        removeClearance(request)
        val headers = parseHeaders(request.headers)

        return withTimeoutOrNull(WAIT_TIMEOUT_MS) {
            var session: SolveSession? = null
            try {
                suspendCancellableCoroutine { continuation ->
                    val s = SolveSession(url, oldClearance, request, headers) { result ->
                        continuation.resume(result)
                    }
                    session = s
                    continuation.invokeOnCancellation { s.destroy() }
                    s.start()
                }
            } finally {
                session?.destroy()
            }
        } ?: false
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

    /**
     * Owns one off-screen WebView solve: create/load on the main thread, settle exactly once,
     * destroy at most once. Destroy is safe to call from any thread and before/after creation.
     */
    private inner class SolveSession(
        private val url: String,
        private val oldClearance: String?,
        private val request: Request,
        private val headers: Map<String, String>,
        private val onSettled: (Boolean) -> Unit,
    ) {
        @Volatile
        var webView: WebView? = null

        @Volatile
        var challengeFound = false

        private val settled = AtomicBoolean(false)
        private val destroyed = AtomicBoolean(false)

        fun start() {
            executor.execute {
                if (destroyed.get()) {
                    // 求解在创建 WebView 之前已被取消。
                    return@execute
                }
                val created = try {
                    createWebView(request)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "createWebView failed: $url", e)
                    settle(false)
                    return@execute
                }
                webView = created
                if (destroyed.get()) {
                    // destroy 与创建发生竞态，立即在主线程销毁。
                    runCatching {
                        created.stopLoading()
                        created.loadUrl("about:blank")
                        created.destroy()
                    }
                    return@execute
                }
                created.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        if (hasNewClearance()) {
                            settle(true)
                            return
                        }
                        if (finishedUrl == url && !challengeFound) {
                            // 首个请求直接加载完成且没有出现挑战，放弃等待。
                            settle(false)
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
                                settle(false)
                            }
                        }
                    }
                }
                created.loadUrl(url, headers)
            }
        }

        private fun hasNewClearance(): Boolean {
            val current = CloudFlareHelper.getClearanceCookie(cookieJar, url)
            return current != null && current != oldClearance
        }

        /** 幂等；把 stop+destroy 投递到主线程执行。 */
        fun destroy() {
            if (!destroyed.compareAndSet(false, true)) {
                return
            }
            executor.execute {
                val wv = webView
                if (wv != null) {
                    runCatching {
                        wv.stopLoading()
                        wv.loadUrl("about:blank")
                        wv.destroy()
                    }
                }
            }
        }

        private fun settle(result: Boolean) {
            if (settled.compareAndSet(false, true)) {
                onSettled(result)
            }
        }
    }

    private companion object {
        const val TAG = "WebViewClearanceSolver"
        const val WAIT_TIMEOUT_MS = 30_000L
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
