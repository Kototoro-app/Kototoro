package com.lagradost.cloudstream3.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.nicehttp.requestCreator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Cloudstream's WebView resolver on the desktop host: opens the page in the platform browser and reports the first
 * request matching [interceptUrl] (plus those matching [additionalUrls]), as plugins use it to find a stream URL
 * that only the page's scripts compute. Same constructor and members as the official JVM class, which resolves
 * nothing.
 *
 * The desktop browser reports requests but cannot answer them, so unlike Android nothing is replayed through
 * OkHttp ([useOkhttp] is accepted and ignored): the browser loads every request itself. Without a browser (Linux,
 * or a bridge predating request reports) it resolves nothing, as before.
 */
class WebViewResolver(
	val interceptUrl: Regex,
	val additionalUrls: List<Regex> = emptyList(),
	val userAgent: String? = USER_AGENT,
	val useOkhttp: Boolean = true,
	val script: String? = null,
	val scriptCallback: ((String) -> Unit)? = null,
	val timeout: Long = DEFAULT_TIMEOUT,
) : Interceptor {

	companion object {
		var webViewUserAgent: String? = null
		val DEFAULT_TIMEOUT: Long = 60_000L
		private const val START_TIMEOUT = 15_000L
		private const val POLL_INTERVAL = 100L
		private val logger = Logger.getLogger("WebViewResolver")
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val resolved = runBlocking { resolveUsingWebView(request).first }
		return chain.proceed(resolved ?: request)
	}

	suspend fun resolveUsingWebView(
		url: String,
		referer: String? = null,
		method: String = "GET",
		requestCallBack: (Request) -> Boolean = { false },
	): Pair<Request?, List<Request>> = resolveUsingWebView(url, referer, emptyMap(), method, requestCallBack)

	suspend fun resolveUsingWebView(
		url: String,
		referer: String? = null,
		headers: Map<String, String> = emptyMap(),
		method: String = "GET",
		requestCallBack: (Request) -> Boolean = { false },
	): Pair<Request?, List<Request>> = try {
		resolveUsingWebView(requestCreator(method, url, referer = referer, headers = headers), requestCallBack)
	} catch (e: IllegalArgumentException) {
		logger.log(Level.WARNING, "Illegal URL in resolveUsingWebView: $url", e)
		null to emptyList()
	}

	suspend fun resolveUsingWebView(
		request: Request,
		requestCallBack: (Request) -> Boolean = { false },
	): Pair<Request?, List<Request>> {
		val main = Handler(Looper.getMainLooper())
		val fixedRequest = AtomicReference<Request?>()
		val extraRequests = CopyOnWriteArrayList<Request>()
		val finished = AtomicBoolean(false)
		val webView = AtomicReference<WebView?>()
		val started = CompletableDeferred<Boolean>()

		fun finish() {
			if (!finished.compareAndSet(false, true)) return
			main.post {
				webView.getAndSet(null)?.run {
					stopLoading()
					destroy()
				}
			}
		}

		main.post {
			try {
				val context = getContext() as? Context ?: error("No context for WebViewResolver")
				val view = WebView(context)
				webView.set(view)
				view.settings.javaScriptEnabled = true
				view.settings.domStorageEnabled = true
				userAgent?.let { view.settings.userAgentString = it }
				webViewUserAgent = view.settings.userAgentString
				view.webViewClient = object : WebViewClient() {
					override fun shouldInterceptRequest(
						view: WebView,
						request: WebResourceRequest,
					): WebResourceResponse? {
						if (finished.get()) return null
						val url = request.url.toString()
						script?.let { source ->
							main.post {
								if (webView.get() === view) view.evaluateJavascript(source) { scriptCallback?.invoke(it) }
							}
						}
						if (interceptUrl.containsMatchIn(url)) {
							fixedRequest.set(request.toRequest()?.also { requestCallBack(it) })
							finish()
						} else if (additionalUrls.any { it.containsMatchIn(url) }) {
							request.toRequest()?.let { extra ->
								extraRequests += extra
								if (requestCallBack(extra)) finish()
							}
						}
						return null
					}
				}
				view.loadUrl(request.url.toString(), request.headers.toMap())
				started.complete(true)
			} catch (error: Throwable) {
				// No browser on this platform (or it failed to start): resolve nothing, as the JVM library does.
				logger.log(Level.FINE, "WebViewResolver unavailable for ${request.url}", error)
				finished.set(true)
				started.complete(false)
			}
		}

		if (withTimeoutOrNull(START_TIMEOUT) { started.await() } != true) {
			finish()
			return null to emptyList()
		}
		var waited = 0L
		while (waited < timeout && !finished.get()) {
			fixedRequest.get()?.let {
				finish()
				return it to extraRequests.toList()
			}
			delay(POLL_INTERVAL)
			waited += POLL_INTERVAL
		}
		finish()
		return fixedRequest.get() to extraRequests.toList()
	}

	private fun WebResourceRequest.toRequest(): Request? = try {
		requestCreator(method, url.toString(), requestHeaders)
	} catch (_: Exception) {
		null
	}
}
