package com.lagradost.cloudstream3.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import com.lagradost.api.getContext
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.debugException
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.nicehttp.requestCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Android WebView resolver used by Cloudstream plugins and CloudflareKiller. */
class WebViewResolver(
	val interceptUrl: Regex,
	val additionalUrls: List<Regex> = emptyList(),
	val userAgent: String? = com.lagradost.cloudstream3.USER_AGENT,
	val useOkhttp: Boolean = true,
	val script: String? = null,
	val scriptCallback: ((String) -> Unit)? = null,
	val timeout: Long = DEFAULT_TIMEOUT,
) : Interceptor {

	companion object {
		var webViewUserAgent: String? = null

		val DEFAULT_TIMEOUT: Long = 60_000L
		private const val TAG = "WebViewResolver"

		@JvmName("getWebViewUserAgent1")
		fun getWebViewUserAgent(): String? {
			return webViewUserAgent ?: (getContext() as? Context)?.let { context ->
				runBlocking(Dispatchers.Main.immediate) {
					WebView(context).settings.userAgentString.also { userAgent ->
						webViewUserAgent = userAgent
					}
				}
			}
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		return runBlocking {
			val fixedRequest = resolveUsingWebView(request).first
			chain.proceed(fixedRequest ?: request)
		}
	}

	suspend fun resolveUsingWebView(
		url: String,
		referer: String? = null,
		method: String = "GET",
		requestCallBack: (Request) -> Boolean = { false },
	): Pair<Request?, List<Request>> = resolveUsingWebView(
		url = url,
		referer = referer,
		headers = emptyMap(),
		method = method,
		requestCallBack = requestCallBack,
	)

	suspend fun resolveUsingWebView(
		url: String,
		referer: String? = null,
		headers: Map<String, String> = emptyMap(),
		method: String = "GET",
		requestCallBack: (Request) -> Boolean = { false },
	): Pair<Request?, List<Request>> {
		return try {
			resolveUsingWebView(
				requestCreator(method, url, referer = referer, headers = headers),
				requestCallBack,
			)
		} catch (e: IllegalArgumentException) {
			logError(e)
			debugException { "ILLEGAL URL IN resolveUsingWebView!" }
			null to emptyList()
		}
	}

	@SuppressLint("SetJavaScriptEnabled")
	suspend fun resolveUsingWebView(
		request: Request,
		requestCallBack: (Request) -> Boolean = { false },
	): Pair<Request?, List<Request>> {
		val url = request.url.toString()
		val headers = request.headers
		Log.i(TAG, "Initial web-view request: $url")
		var webView: WebView? = null
		val shouldExit = AtomicBoolean(false)
		val mainHandler = Handler(Looper.getMainLooper())

		fun destroyWebView() {
			mainHandler.post {
				webView?.stopLoading()
				webView?.destroy()
				webView = null
				shouldExit.set(true)
				Log.i(TAG, "Destroyed webview")
			}
		}

		val fixedRequest = AtomicReference<Request?>()
		val extraRequestList = atomicListOf<Request>()

		withContext(Dispatchers.Main.immediate) {
			try {
				webView = WebView(
					(getContext() as? Context)
						?: throw RuntimeException("No base context in WebViewResolver"),
				).apply {
					settings.javaScriptEnabled = true
					settings.domStorageEnabled = true

					webViewUserAgent = settings.userAgentString
					if (userAgent != null) {
						settings.userAgentString = userAgent
					}
				}

				webView?.webViewClient = object : WebViewClient() {
					override fun shouldInterceptRequest(
						view: WebView,
						request: WebResourceRequest,
					): WebResourceResponse? = runBlocking {
						val webViewUrl = request.url.toString()
						Log.i(TAG, "Loading WebView URL: $webViewUrl")

						if (script != null) {
							mainHandler.post {
								if (webView === view) {
									view.evaluateJavascript(script) { scriptCallback?.invoke(it) }
								}
							}
						}

						if (interceptUrl.containsMatchIn(webViewUrl)) {
							fixedRequest.set(request.toRequest()?.also { requestCallBack(it) })
							Log.i(TAG, "Web-view request finished: $webViewUrl")
							destroyWebView()
							return@runBlocking null
						}

						if (additionalUrls.any { it.containsMatchIn(webViewUrl) }) {
							request.toRequest()?.also {
								if (requestCallBack(it)) destroyWebView()
							}?.let(extraRequestList::add)
						}

						val blacklistedFiles = listOf(
							".jpg", ".png", ".webp", ".mpg", ".mpeg", ".jpeg", ".webm",
							".mp4", ".mp3", ".gifv", ".flv", ".asf", ".mov", ".mng", ".mkv",
							".ogg", ".avi", ".wav", ".woff2", ".woff", ".ttf", ".css", ".vtt",
							".srt", ".ts", ".gif", "wss://",
						)

						return@runBlocking try {
							when {
								blacklistedFiles.any { URI(webViewUrl).path.contains(it) } ||
									webViewUrl.endsWith("/favicon.ico") -> {
									WebResourceResponse("image/png", null, null)
								}

								webViewUrl.contains("recaptcha") || webViewUrl.contains("/cdn-cgi/") -> {
									super.shouldInterceptRequest(view, request)
								}

								useOkhttp && request.method == "GET" -> app.get(
									webViewUrl,
									headers = request.requestHeaders,
								).okhttpResponse.toWebResourceResponse()

								useOkhttp && request.method == "POST" -> app.post(
									webViewUrl,
									headers = request.requestHeaders,
								).okhttpResponse.toWebResourceResponse()

								else -> super.shouldInterceptRequest(view, request)
							}
						} catch (_: Exception) {
							null
						}
					}

					@SuppressLint("WebViewClientOnReceivedSslError")
					override fun onReceivedSslError(
						view: WebView?,
						handler: SslErrorHandler?,
						error: SslError?,
					) {
						handler?.proceed()
					}
				}
				webView?.loadUrl(url, headers.toMap())
			} catch (e: Exception) {
				shouldExit.set(true)
				logError(e)
			}
		}

		var loop = 0
		val delayTime = 100L
		while (loop < timeout / delayTime && !shouldExit.get()) {
			fixedRequest.get()?.let { return it to extraRequestList }
			delay(delayTime)
			loop += 1
		}

		Log.i(TAG, "Web-view timeout after ${timeout / 1000}s")
		destroyWebView()
		return fixedRequest.get() to extraRequestList
	}
}

fun WebResourceRequest.toRequest(): Request? {
	val webViewUrl = url.toString()
	return safe {
		requestCreator(method, webViewUrl, requestHeaders)
	}
}

fun Response.toWebResourceResponse(): WebResourceResponse {
	val contentTypeValue = header("Content-Type")
	val typeRegex = Regex("""(.*);(?:.*charset=(.*)(?:|;)|)""")
	return if (contentTypeValue != null) {
		val found = typeRegex.find(contentTypeValue)
		val contentType = found?.groupValues?.getOrNull(1)?.ifBlank { null } ?: contentTypeValue
		val charset = found?.groupValues?.getOrNull(2)?.ifBlank { null }
		WebResourceResponse(contentType, charset, body.byteStream())
	} else {
		WebResourceResponse("application/octet-stream", null, body.byteStream())
	}
}
