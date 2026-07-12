package org.skepsun.kototoro.core.network.webview

import android.content.Context
import android.util.AndroidRuntimeException
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.network.proxy.ProxyProvider
import org.skepsun.kototoro.core.network.webview.adblock.AdBlock
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserRepository
import org.skepsun.kototoro.core.parser.tvbox.TVBoxPlayback
import org.skepsun.kototoro.core.parser.legado.LegadoNetworkUtils
import org.skepsun.kototoro.core.ui.util.ForegroundActivityHolder
import org.skepsun.kototoro.core.util.ext.configureForParser
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.browser.cloudflare.CloudFlareClient
import org.skepsun.kototoro.browser.cloudflare.CloudFlareInterceptClient
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.lang.ref.WeakReference
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Cookie
import java.util.concurrent.TimeUnit
import org.json.JSONObject

@Singleton
class WebViewExecutor @Inject constructor(
	@ApplicationContext private val context: Context,
	private val proxyProvider: ProxyProvider,
	private val cookieJar: MutableCookieJar,
    private val adBlock: AdBlock,
    private val foregroundActivityHolder: ForegroundActivityHolder,
	private val mangaRepositoryFactoryProvider: Provider<ContentRepository.Factory>,
) {
    data class WebViewSniffResult(
        val url: String,
        val body: String,
        val code: Int = 200,
        val headers: Map<String, String> = emptyMap(),
    )

    data class WebViewOverrideResult(
        val url: String,
        val body: String,
        val code: Int = 200,
        val headers: Map<String, String> = emptyMap(),
    )

    data class WebViewSniffConfig(
        val sourceRegex: Regex?,
        val overrideUrlRegex: Regex?,
        val javaScript: String?,
        val delayMs: Long,
    )

	private var webViewCached: WeakReference<WebView>? = null
	private val mutex = Mutex()
    private val recentFailureUntil = ConcurrentHashMap<String, Long>()

	val defaultUserAgent: String? by lazy {
		try {
			org.skepsun.kototoro.core.network.UserAgentProvider.get(context)
		} catch (e: Exception) {
			null
		}
	}

	/**
	 * Execute a same-origin GET request in a real WebView context and return response data.
	 * Useful for sources where Cloudflare still challenges OkHttp even with valid cookies.
	 */
	suspend fun fetchWithBrowserContext(
		url: String,
		userAgent: String? = null,
		headers: Map<String, String> = emptyMap(),
		settleDelayMs: Long = 1200,
		timeoutMs: Long = 30000,
	): BrowserFetchResult? = mutex.withLock {
		withContext(Dispatchers.Main.immediate) {
			val target = url.toHttpUrlOrNull() ?: return@withContext null
			val webView = obtainWebView()
			try {
				android.util.Log.d("WebViewExecutor", "fetchWithBrowserContext start: $url")
				webView.configureForParser(userAgent, blockImages = true)
				withTimeoutOrNull(timeoutMs) {
					// Ensure browser context is established on the same origin first.
					suspendCancellableCoroutine<Unit> { cont ->
						webView.webViewClient = object : WebViewClient() {
							override fun onPageFinished(view: WebView?, loadedUrl: String?) {
								android.util.Log.d(
									"WebViewExecutor",
									"fetchWithBrowserContext base page finished: $loadedUrl",
								)
								if (cont.isActive) {
									cont.resume(Unit)
								}
							}
						}
						val baseUrl = target.newBuilder()
							.encodedPath("/")
							.query(null)
							.fragment(null)
							.build()
							.toString()
						webView.loadUrl(baseUrl)
					}
					kotlinx.coroutines.delay(settleDelayMs)

					val resolvedOriginHost = webView.url?.toHttpUrlOrNull()?.host ?: target.host
					val targetUrlToFetch = if (resolvedOriginHost != target.host) {
						target.newBuilder().host(resolvedOriginHost).build().toString()
					} else {
						url
					}
					android.util.Log.d(
						"WebViewExecutor",
						"fetchWithBrowserContext origin ready: currentUrl=${webView.url} targetUrl=$targetUrlToFetch",
					)

					val allowedHeaders = headers.filterKeys { key ->
						!key.equals("Referer", ignoreCase = true) && !key.equals("Origin", ignoreCase = true)
					}
					val jsHeaders = JSONObject(allowedHeaders).toString()
					val js = """
						window.__kototoroFetchResult = null;
						(async () => {
						  try {
						  	// Wait for document to be ready if it's not already
						    if (document.readyState === 'loading') {
						        await new Promise(resolve => {
						            document.addEventListener('DOMContentLoaded', resolve);
						            setTimeout(resolve, 3000);
						        });
						    }
						    const response = await fetch(${JSONObject.quote(targetUrlToFetch)}, {
						      method: 'GET',
						      credentials: 'include',
						      headers: $jsHeaders,
						    });
						    const text = await response.text();
						    const responseHeaders = {};
						    response.headers.forEach((value, key) => { responseHeaders[key] = value; });
						    window.__kototoroFetchResult = JSON.stringify({
						      ok: true,
						      status: response.status,
						      statusText: response.statusText || '',
						      url: response.url || ${JSONObject.quote(url)},
						      headers: responseHeaders,
						      body: text || '',
						    });
						  } catch (e) {
						    window.__kototoroFetchResult = JSON.stringify({
						      ok: false,
						      error: String(e),
						      errorName: e?.name ? String(e.name) : '',
						      errorMessage: e?.message ? String(e.message) : '',
						      errorStack: e?.stack ? String(e.stack) : '',
						    });
						  }
						})();
					""".trimIndent()

					webView.evaluateJavascript(js, null)
					var raw = ""
					while (isActive) {
						val pollResult = suspendCancellableCoroutine<String> { cont ->
							webView.evaluateJavascript("window.__kototoroFetchResult") { result ->
								if (cont.isActive) {
									cont.resume(decodeJavascriptString(result))
								}
							}
						}
						if (pollResult.isNotBlank() && pollResult != "null") {
							raw = pollResult
							webView.evaluateJavascript("window.__kototoroFetchResult = null;", null)
							break
						}
						kotlinx.coroutines.delay(100)
					}
					if (raw.isBlank()) {
						android.util.Log.w(
							"WebViewExecutor",
							"fetchWithBrowserContext empty JS result"
						)
						return@withTimeoutOrNull tryNavigationFetchFallback(webView, targetUrlToFetch, headers)
					}
					val json = runCatching { JSONObject(raw) }.onFailure {
						android.util.Log.w(
							"WebViewExecutor",
							"fetchWithBrowserContext JSON parse failed: ${it.message}; rawPreview=${raw.take(200)}",
						)
					}.getOrNull() ?: return@withTimeoutOrNull tryNavigationFetchFallback(webView, targetUrlToFetch, headers)
					val fetchStatus = json.optInt("status")
					val fetchBody = json.optString("body")
					val isCloudflareBlock = (fetchStatus == 403 || fetchStatus == 503) && 
						(fetchBody.contains("cf-browser-verification") || 
						 fetchBody.contains("Just a moment") || 
						 fetchBody.contains("__cf_chl_opt") ||
						 fetchBody.contains("Adscore"))

					if (!json.optBoolean("ok") || isCloudflareBlock) {
						android.util.Log.w(
							"WebViewExecutor",
							"fetchWithBrowserContext failed or hit WAF (ok=${json.optBoolean("ok")}, status=$fetchStatus, isCF=$isCloudflareBlock). Falling back to navigation.",
						)
						return@withTimeoutOrNull tryNavigationFetchFallback(webView, targetUrlToFetch, headers)
					}
					val responseHeadersObj = json.optJSONObject("headers")
					val responseHeaders = linkedMapOf<String, String>()
					if (responseHeadersObj != null) {
						val keys = responseHeadersObj.keys()
						while (keys.hasNext()) {
							val key = keys.next()
							responseHeaders[key] = responseHeadersObj.optString(key)
						}
					}
					BrowserFetchResult(
						status = json.optInt("status"),
						statusText = json.optString("statusText"),
						url = json.optString("url"),
						headers = responseHeaders,
						body = json.optString("body"),
					)
				} ?: snapshotCurrentPage(webView, webView.url ?: url, "timeout")
			} finally {
				android.util.Log.d("WebViewExecutor", "fetchWithBrowserContext end: $url")
				webView.reset()
			}
		}
	}

	private suspend fun snapshotCurrentPage(
		webView: WebView,
		url: String,
		reason: String,
	): BrowserFetchResult? {
		android.util.Log.w(
			"WebViewExecutor",
			"fetchWithBrowserContext snapshot current page: reason=$reason currentUrl=${webView.url}",
		)
		val raw = suspendCancellableCoroutine<String> { cont ->
			webView.evaluateJavascript(
				"""(function() {
					const html = document.documentElement ? document.documentElement.outerHTML : '';
					return JSON.stringify({
						href: location.href || '',
						title: document.title || '',
						readyState: document.readyState || '',
						contentType: document.contentType || '',
						bodyText: document.body ? (document.body.innerText || document.body.textContent || '').trim().slice(0, 1000) : '',
						html: html || ''
					});
				})()"""
			) { result ->
				if (cont.isActive) {
					cont.resume(decodeJavascriptString(result))
				}
			}
		}
		val json = runCatching { JSONObject(raw) }.getOrNull()
		val body = json?.optString("html").orEmpty()
		val bodyText = json?.optString("bodyText").orEmpty()
		if (body.isBlank() && bodyText.isBlank()) {
			android.util.Log.w("WebViewExecutor", "fetchWithBrowserContext snapshot produced empty body")
			return null
		}
		val contentType = json?.optString("contentType").orEmpty()
		val responseHeaders = linkedMapOf<String, String>()
		if (contentType.isNotBlank()) {
			responseHeaders["content-type"] = contentType
		}
		responseHeaders["x-kototoro-snapshot-reason"] = reason
		responseHeaders["x-kototoro-snapshot-title"] = json?.optString("title").orEmpty()
		responseHeaders["x-kototoro-snapshot-ready-state"] = json?.optString("readyState").orEmpty()
		val snapshotBody = body.ifBlank { bodyText }
		android.util.Log.w(
			"WebViewExecutor",
			"fetchWithBrowserContext snapshot success: href=${json?.optString("href")} " +
				"title=${json?.optString("title")} readyState=${json?.optString("readyState")} " +
				"contentType=$contentType bodyLength=${snapshotBody.length}",
		)
		return BrowserFetchResult(
			status = 0,
			statusText = reason,
			url = json?.optString("href").orEmpty().ifBlank { url },
			headers = responseHeaders,
			body = snapshotBody,
		)
	}

	private suspend fun tryNavigationFetchFallback(
		webView: WebView,
		url: String,
		headers: Map<String, String>,
	): BrowserFetchResult? {
		android.util.Log.i("WebViewExecutor", "fetchWithBrowserContext fallback to navigation: $url")
		var statusCode: Int? = null
		var statusText: String? = null
		suspendCancellableCoroutine<Unit> { cont ->
			webView.webViewClient = object : WebViewClient() {
				override fun onReceivedHttpError(
					view: WebView?,
					request: WebResourceRequest?,
					errorResponse: android.webkit.WebResourceResponse?
				) {
					if (request?.isForMainFrame == true) {
						statusCode = errorResponse?.statusCode
						statusText = errorResponse?.reasonPhrase
					}
				}

				override fun onPageFinished(view: WebView?, loadedUrl: String?) {
					if (cont.isActive) {
						cont.resume(Unit)
					}
				}
			}
			if (headers.isNotEmpty()) {
				webView.loadUrl(url, headers)
			} else {
				webView.loadUrl(url)
			}
		}
		kotlinx.coroutines.delay(500)

		val contentType = suspendCancellableCoroutine<String> { cont ->
			webView.evaluateJavascript("document.contentType || ''") { result ->
				cont.resume(decodeJavascriptString(result))
			}
		}
		val body = suspendCancellableCoroutine<String> { cont ->
			webView.evaluateJavascript(
				"""(function() {
					const html = document.documentElement ? document.documentElement.outerHTML : '';
					if (html.includes('cf-browser-verification') || html.includes('__cf_chl_opt') || html.includes('turnstile') || html.includes('cf_chl') || html.includes('Cloudflare') || html.includes('Ray ID')) {
						return html;
					}
					
					// Detect if the response is actually JSON dumped into the browser
					const text = document.body ? (document.body.innerText || document.body.textContent || '').trim() : '';
					if ((text.startsWith('{') && text.endsWith('}')) || (text.startsWith('[') && text.endsWith(']'))) {
						try {
							JSON.parse(text);
							return text; // It's valid JSON, return stripped of WebView HTML wrappers
						} catch(e) { }
					}
					
					return html; // Return full HTML for JSoup parsers
				})()"""
			) { result ->
				cont.resume(decodeJavascriptString(result))
			}
		}
		if (body.isBlank()) {
			android.util.Log.w("WebViewExecutor", "navigation fallback produced empty body")
			return null
		}
		val responseHeaders = linkedMapOf<String, String>()
		if (contentType.isNotBlank()) {
			responseHeaders["content-type"] = contentType
		}
		val isCloudflare = body.contains("cf-browser-verification") || body.contains("__cf_chl_opt") || body.contains("turnstile") || body.contains("cf_chl", ignoreCase = true) || body.contains("Cloudflare") || body.contains("Ray ID")
		if (isCloudflare) {
			responseHeaders["server"] = "cloudflare"
		}
		val code = if (isCloudflare) 403 else (statusCode ?: 200)
		val message = statusText.orEmpty()
		android.util.Log.i(
			"WebViewExecutor",
			"navigation fallback success: status=$code contentType=${contentType.ifBlank { "<empty>" }} bodyLength=${body.length} isCloudflare=$isCloudflare",
		)
		return BrowserFetchResult(
			status = code,
			statusText = message,
			url = url,
			headers = responseHeaders,
			body = body,
		)
	}

	suspend fun evaluateJs(baseUrl: String?, script: String): String? = mutex.withLock {
		withContext(Dispatchers.Main.immediate) {
			val webView = obtainWebView()
			try {
				if (!baseUrl.isNullOrEmpty()) {
					suspendCoroutine { cont ->
						webView.webViewClient = ContinuationResumeWebViewClient(cont)
						webView.loadDataWithBaseURL(baseUrl, " ", "text/html", null, null)
					}
				}
				suspendCoroutine { cont ->
					webView.evaluateJavascript(script) { result ->
						cont.resume(result?.takeUnless { it == "null" })
					}
				}
			} finally {
				webView.reset()
			}
		}
	}

	suspend fun tryResolveCaptcha(exception: CloudFlareException, timeout: Long): Boolean {
        val cooldownHost = runCatching { URI(exception.url).host?.lowercase() }.getOrNull()
        if (cooldownHost != null) {
            val now = System.currentTimeMillis()
            val skipUntil = recentFailureUntil[cooldownHost]
            if (skipUntil != null) {
                if (skipUntil > now) {
                    Log.d(TAG, "Skipping captcha auto-resolve for $cooldownHost (cooled down for ${skipUntil - now}ms)")
                    return false
                }
                recentFailureUntil.remove(cooldownHost)
            }
        }
        val resolved = mutex.withLock {
            if (cooldownHost != null) {
                val skipUntil = recentFailureUntil[cooldownHost]
                if (skipUntil != null && skipUntil > System.currentTimeMillis()) {
                    return@withLock false
                }
            }
            runCatchingCancellable { proxyProvider.applyWebViewConfig() }.onFailure { it.printStackTraceDebug() }
            withContext(Dispatchers.Main.immediate) {
                val activity = foregroundActivityHolder.current
                val webView: WebView
                val host: ViewGroup?
                val isThrowaway: Boolean
                if (activity != null) {
                    webView = WebView(context).apply { configureForParser(null) }
                    host = attachToHost(webView, activity)
                    isThrowaway = true
                } else {
                    webView = obtainWebView()
                    host = null
                    isThrowaway = false
                }
                try {
                    exception.source.getUserAgent()?.let {
                        webView.settings.userAgentString = it
                    }
                    val useInterception = shouldUseCloudFlareInterception(exception.source)
                    val resolved = withTimeoutOrNull(timeout) {
                        suspendCancellableCoroutine { cont ->
                            webView.webViewClient = createCloudFlareClient(
                                webView = webView,
                                exception = exception,
                                continuation = cont,
                                useInterception = useInterception,
                            )
                            webView.loadUrl(exception.url)
                        }
                    }
                    if (resolved == null) {
                        Log.w(TAG, "Captcha auto-resolve timed out for ${exception.url}, dumping page HTML:")
                        dumpPageHtml(webView)
                    }
                    resolved == true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    exception.addSuppressed(e)
                    e.printStackTraceDebug()
                    false
                } finally {
                    if (isThrowaway) {
                        runCatching { webView.stopLoading() }
                        webView.webViewClient = WebViewClient()
                        host?.let { detachFromHost(webView, it) }
                        runCatching { webView.destroy() }
                    } else {
                        webView.reset()
                    }
                }
            }
        }
        if (cooldownHost != null) {
            if (resolved) {
                recentFailureUntil.remove(cooldownHost)
            } else {
                recentFailureUntil[cooldownHost] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
            }
        }
        return resolved
	}

	/**
	 * Load a URL via WebView and return the page HTML after JavaScript execution.
	 * Used for sources that require webView: true.
	 *
	 * @param url The URL to load
	 * @param headers Optional headers to set
	 * @param delayMs Delay in milliseconds to wait after page load for JS execution
	 * @param timeoutMs Total timeout in milliseconds
	 * @param webJs Optional custom JavaScript to execute instead of outerHTML
	 * @param blockImages Whether to block images to speed up loading
	 * @return The page HTML content (or JS result)
	 */
	suspend fun loadPageHtml(
		url: String,
		headers: Map<String, String>? = null,
		delayMs: Long = 2500,
		timeoutMs: Long = 60000,
		webJs: String? = null,
		blockImages: Boolean = true
	): String = mutex.withLock {
		withContext(Dispatchers.Main.immediate) {
			val webView = obtainWebView()
			try {
				// Configure with common browser settings plus image blocking
				webView.configureForParser(headers?.get("User-Agent"), blockImages = blockImages)

				withTimeout(timeoutMs) {
					// Load the page and wait for it to finish
					suspendCancellableCoroutine<Unit> { cont ->
						webView.webViewClient = object : WebViewClient() {
							override fun onPageFinished(view: WebView?, loadedUrl: String?) {
								if (cont.isActive) {
									cont.resume(Unit)
								}
							}
						}
						if (headers != null && headers.isNotEmpty()) {
							webView.loadUrl(url, headers)
						} else {
							webView.loadUrl(url)
						}
					}

					// Wait for initial JavaScript to execute
					kotlinx.coroutines.delay(delayMs)
					
					val extractionJs = webJs?.takeIf { it.isNotBlank() } ?: "document.documentElement.outerHTML"
					
					// Poll for the actual content to be available (some sites use anti-adblock that takes time)
					// Match Legado's retry mechanism: up to 30 attempts
					var result = ""
					var attempts = 0
					val maxAttempts = 30
					while (attempts < maxAttempts) {
						result = suspendCancellableCoroutine<String> { cont ->
							webView.evaluateJavascript(extractionJs) { jsResult ->
								val unescaped = jsResult?.let {
									if (it == "null") ""
									else if (it.startsWith("\"") && it.endsWith("\"")) {
										// Basic JSON unescaping for the string result
										it.substring(1, it.length - 1)
											.replace("\\u003C", "<")
											.replace("\\u003E", ">")
											.replace("\\n", "\n")
											.replace("\\t", "\t")
											.replace("\\\"", "\"")
											.replace("\\\\", "\\")
									} else it
								} ?: ""
								cont.resume(unescaped)
							}
						}
						
						// If user provided custom JS, we don't know the "ready" condition, just return it
						if (webJs != null && webJs.isNotBlank()) break
						
						// Default extraction: Check if content element has actual text
						val hasContent = suspendCancellableCoroutine<Boolean> { cont ->
							webView.evaluateJavascript(
								"""(function() {
									var el = document.getElementById('TextContent') || document.querySelector('#TextContent') || document.querySelector('.content') || document.querySelector('#content');
									if (!el) return false;
									var text = el.innerText || el.textContent || '';
									return text.trim().length > 100;
								})()"""
							) { jsResult ->
								cont.resume(jsResult == "true")
							}
						}
						
						if (hasContent) {
							android.util.Log.d("WebViewExecutor", "[WebView] Content ready after ${attempts + 1} attempts")
							break
						}
						
						attempts++
						if (attempts < maxAttempts) {
							android.util.Log.d("WebViewExecutor", "[WebView] Content not ready, waiting... (attempt $attempts/$maxAttempts)")
							kotlinx.coroutines.delay(1000)
						}
					}
					
					android.util.Log.d("WebViewExecutor", "[WebView] Extracted length=${result.length}, preview=${result.take(200).replace("\n", " ")}")
					result
				}
			} finally {
				webView.reset()
			}
		}
	}

	suspend fun loadHtml(
		html: String,
		baseUrl: String,
		delayMs: Long = 2500,
		webJs: String? = null,
		userAgent: String? = null,
	): String = mutex.withLock {
		withContext(Dispatchers.Main.immediate) {
			val webView = obtainWebView()
			try {
				webView.configureForParser(userAgent, blockImages = true)
				withTimeout(60000L) {
					suspendCancellableCoroutine<Unit> { cont ->
						webView.webViewClient = object : WebViewClient() {
							override fun onPageFinished(view: WebView?, loadedUrl: String?) {
								if (cont.isActive) {
									cont.resume(Unit)
								}
							}
						}
						webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
					}
					kotlinx.coroutines.delay(delayMs)
					val extractionJs = webJs?.takeIf { it.isNotBlank() } ?: "document.documentElement.outerHTML"
					suspendCancellableCoroutine<String> { cont ->
						webView.evaluateJavascript(extractionJs) { result ->
							cont.resume(decodeJavascriptString(result))
						}
					}
				}
			} finally {
				webView.reset()
			}
		}
	}

    suspend fun sniff(
        url: String,
        headers: Map<String, String>? = null,
        delayMs: Long = 2500,
        timeoutMs: Long = 60000,
        sourceRegex: String? = null,
        overrideUrlRegex: String? = null,
        javaScript: String? = null,
        blockImages: Boolean = true,
    ): WebViewSniffResult? = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val webView = obtainWebView()
            try {
                webView.configureForParser(headers?.get(CommonHeaders.USER_AGENT), blockImages = blockImages)
                val config = WebViewSniffConfig(
                    sourceRegex = sourceRegex?.takeIf { it.isNotBlank() }?.let(::Regex),
                    overrideUrlRegex = overrideUrlRegex?.takeIf { it.isNotBlank() }?.let(::Regex),
                    javaScript = javaScript?.takeIf { it.isNotBlank() },
                    delayMs = delayMs,
                )
                withTimeout(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        val finished = AtomicBoolean(false)

                        fun tryResume(result: WebViewSniffResult?) {
                            if (finished.compareAndSet(false, true) && cont.isActive) {
                                cont.resume(result)
                            }
                        }

                        webView.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val candidate = request?.url?.toString().orEmpty()
                                if (config.overrideUrlRegex?.matches(candidate) == true) {
                                    tryResume(
                                        WebViewSniffResult(
                                            url = url,
                                            body = candidate,
                                            code = 200,
                                        ),
                                    )
                                    return true
                                }
                                return super.shouldOverrideUrlLoading(view, request)
                            }

                            override fun onLoadResource(view: WebView?, resUrl: String?) {
                                val candidate = resUrl ?: return
                                if (config.sourceRegex?.matches(candidate) == true) {
                                    tryResume(
                                        WebViewSniffResult(
                                            url = url,
                                            body = candidate,
                                            code = 200,
                                        ),
                                    )
                                }
                            }

                            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                if (config.javaScript != null) {
                                    webView.loadUrl("javascript:${config.javaScript}")
                                }
                                kotlinx.coroutines.CoroutineScope(cont.context).launch(Dispatchers.Main.immediate) {
                                    kotlinx.coroutines.delay(1000L + config.delayMs)
                                    tryResume(null)
                                }
                            }
                        }

                        if (!headers.isNullOrEmpty()) {
                            webView.loadUrl(url, headers)
                        } else {
                            webView.loadUrl(url)
                        }
                    }
                }
            } finally {
                webView.reset()
            }
        }
    }

    suspend fun sniffResource(
        url: String,
        headers: Map<String, String>? = null,
        delayMs: Long = 2500,
        timeoutMs: Long = 60000,
        sourceRegex: String,
        javaScript: String? = null,
        blockImages: Boolean = true,
    ): WebViewSniffResult? {
        return sniff(
            url = url,
            headers = headers,
            delayMs = delayMs,
            timeoutMs = timeoutMs,
            sourceRegex = sourceRegex,
            overrideUrlRegex = null,
            javaScript = javaScript,
            blockImages = blockImages,
        )
    }

    suspend fun sniffOverrideUrl(
        url: String,
        headers: Map<String, String>? = null,
        delayMs: Long = 2500,
        timeoutMs: Long = 60000,
        overrideUrlRegex: String,
        javaScript: String? = null,
        blockImages: Boolean = true,
    ): WebViewOverrideResult? = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val webView = obtainWebView()
            try {
                webView.configureForParser(headers?.get(CommonHeaders.USER_AGENT), blockImages = blockImages)
                withTimeout(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        val finished = AtomicBoolean(false)

                        fun tryResume(result: WebViewOverrideResult?) {
                            if (finished.compareAndSet(false, true) && cont.isActive) {
                                cont.resume(result)
                            }
                        }

                        webView.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val candidate = request?.url?.toString().orEmpty()
                                if (candidate.matches(overrideUrlRegex.toRegex())) {
                                    tryResume(
                                        WebViewOverrideResult(
                                            url = url,
                                            body = candidate,
                                            code = 200,
                                        ),
                                    )
                                    return true
                                }
                                return super.shouldOverrideUrlLoading(view, request)
                            }

                            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                if (!javaScript.isNullOrBlank()) {
                                    webView.loadUrl("javascript:$javaScript")
                                }
                                kotlinx.coroutines.CoroutineScope(cont.context).launch(Dispatchers.Main.immediate) {
                                    kotlinx.coroutines.delay(1000L + delayMs)
                                    tryResume(null)
                                }
                            }
                        }

                        if (!headers.isNullOrEmpty()) {
                            webView.loadUrl(url, headers)
                        } else {
                            webView.loadUrl(url)
                        }
                    }
                }
            } finally {
                webView.reset()
            }
        }
    }

	suspend fun sniffMediaUrl(
		url: String,
		headers: Map<String, String>? = null,
		delayMs: Long = 2500,
		timeoutMs: Long = 20000,
	): SniffedMediaResult? = mutex.withLock {
		withContext(Dispatchers.Main.immediate) {
			val webView = obtainWebView()
			try {
				webView.configureForParser(headers?.get(CommonHeaders.USER_AGENT), blockImages = true)
				withTimeout(timeoutMs) {
					suspendCancellableCoroutine { cont ->
						val pageFinished = AtomicBoolean(false)
						val candidateUrl = AtomicReference<String?>(null)

						fun tryResume(result: SniffedMediaResult?) {
							if (cont.isActive) {
								cont.resume(result)
							}
						}

						fun captureCandidate(rawUrl: String?) {
							val normalized = rawUrl?.takeIf(TVBoxPlayback::looksLikeDirectPlaybackUrl) ?: return
							if (candidateUrl.compareAndSet(null, normalized)) {
								val mergedHeaders = headers.orEmpty().toMutableMap()
								if (!mergedHeaders.keys.any { it.equals(CommonHeaders.REFERER, ignoreCase = true) }) {
									mergedHeaders[CommonHeaders.REFERER] = url
								}
								CookieManager.getInstance().getCookie(normalized)?.takeIf { it.isNotBlank() }?.let { cookie ->
									mergedHeaders[CommonHeaders.COOKIE] = cookie
								}
								tryResume(SniffedMediaResult(url = normalized, headers = mergedHeaders))
							}
						}

						webView.webViewClient = object : WebViewClient() {
							override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
								captureCandidate(request?.url?.toString())
								return null
							}

							override fun onPageFinished(view: WebView?, loadedUrl: String?) {
								if (!pageFinished.compareAndSet(false, true)) {
									return
								}
								kotlinx.coroutines.CoroutineScope(cont.context).launch(Dispatchers.Main.immediate) {
									kotlinx.coroutines.delay(delayMs)
									if (!cont.isActive || candidateUrl.get() != null) {
										return@launch
									}
									val html = suspendCancellableCoroutine<String> { htmlCont ->
										webView.evaluateJavascript("document.documentElement.outerHTML") { jsResult ->
											htmlCont.resume(decodeJavascriptString(jsResult))
										}
									}
									val embeddedUrl = TVBoxPlayback.extractEmbeddedMediaUrl(html)
									if (embeddedUrl != null) {
										captureCandidate(embeddedUrl)
									} else {
										tryResume(null)
									}
								}
							}
						}
						if (!headers.isNullOrEmpty()) {
							webView.loadUrl(url, headers)
						} else {
							webView.loadUrl(url)
						}
					}
				}
			} finally {
				webView.reset()
			}
		}
	}

	private suspend fun obtainWebView(): WebView {
		webViewCached?.get()?.let {
			return it
		}
		return withContext(Dispatchers.Main.immediate) {
			webViewCached?.get()?.let {
				return@withContext it
			}
			WebView(context).also {
				it.configureForParser(null)
				webViewCached = WeakReference(it)
				proxyProvider.applyWebViewConfig()
				it.onResume()
				it.resumeTimers()
			}
		}
	}

    @MainThread
    private fun attachToHost(
		webView: WebView,
		activity: android.app.Activity,
		width: Int = CLOUDFLARE_WEBVIEW_WIDTH,
		height: Int = CLOUDFLARE_WEBVIEW_HEIGHT,
	): ViewGroup? {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            // Turnstile needs a real viewport, but using the full physical display can crash the
            // WebView renderer on high-resolution devices due to tile memory pressure.
            webView.alpha = 0.01f
            webView.visibility = View.VISIBLE
            webView.translationY = 0f
            content.addView(
                webView,
                0, // Add at index 0 (behind other child views) to avoid showing on screen
                ViewGroup.LayoutParams(width, height),
            )
        }.onFailure {
            it.printStackTraceDebug()
            return null
        }
        return content
    }

    @MainThread
    private fun detachFromHost(webView: WebView, host: ViewGroup) {
        runCatching { host.removeView(webView) }.onFailure { it.printStackTraceDebug() }
    }

	private fun ContentSource.getUserAgent(): String? {
		val repository = mangaRepositoryFactoryProvider.get().create(this) as? ParserContentRepository
        return repository?.getRequestHeaders()?.get(CommonHeaders.USER_AGENT)
            ?: (mangaRepositoryFactoryProvider.get().create(this) as? KotatsuParserRepository)
                ?.getRequestHeaders()
                ?.get(CommonHeaders.USER_AGENT)
	}

    @MainThread
    private fun createCloudFlareClient(
        webView: WebView,
        exception: CloudFlareException,
        continuation: kotlin.coroutines.Continuation<Boolean>,
        useInterception: Boolean,
    ): CloudFlareClient {
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val resumeOnce: (Boolean) -> Unit = { result ->
            if (!finished) {
                finished = true
                handler.removeCallbacksAndMessages(null)
                continuation.resume(result)
            }
        }
		val initialClearance = CloudFlareHelper.getClearanceCookie(cookieJar, exception.url)
		val challengeDeadline = System.currentTimeMillis() + MAX_CHALLENGE_MS
		val check = object : Runnable {
			override fun run() {
				if (finished) return
				val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, exception.url)
				webView.evaluateJavascript(CF_STATE_JS) { raw ->
					if (finished) return@evaluateJavascript
	                    val state = raw?.removeSurrounding("\"")
	                    when (state) {
	                        "ok" -> resumeOnce(true)
	                        "error" -> resumeOnce(false)
	                        else -> if (System.currentTimeMillis() >= challengeDeadline) {
	                            Log.w(
									TAG,
									"Captcha auto-resolve deadline reached for ${exception.url}, " +
										"state=$state hasNewClearance=${clearance != null && clearance != initialClearance}",
								)
	                            resumeOnce(false)
	                        } else {
                            handler.removeCallbacks(this)
                            handler.postDelayed(this, CHALLENGE_POLL_INTERVAL_MS)
                        }
                    }
                }
            }
        }
        val callback = object : org.skepsun.kototoro.browser.cloudflare.CloudFlareCallback {
            override fun onLoadingStateChanged(isLoading: Boolean) = Unit
            override fun onHistoryChanged() = Unit
            override fun onPageFinished(webView: android.webkit.WebView, url: String) = Unit

            override fun onPageLoaded() {
                if (finished) return
                handler.removeCallbacks(check)
                handler.postDelayed(check, 100L)
            }

            override fun onCheckPassed() {
				if (finished) return
				handler.removeCallbacks(check)
				handler.postDelayed(check, 100L)
			}

            override fun onLoopDetected() = Unit
        }
        return if (useInterception) {
            CloudFlareInterceptClient(
                cookieJar = cookieJar,
                callback = callback,
                adBlock = adBlock,
                targetUrl = exception.url,
            )
        } else {
            CloudFlareClient(
                cookieJar = cookieJar,
                callback = callback,
                adBlock = adBlock,
                targetUrl = exception.url,
            )
        }
    }

    private suspend fun shouldUseCloudFlareInterception(source: ContentSource): Boolean {
        val repository = mangaRepositoryFactoryProvider.get().create(source) as? ParserContentRepository ?: return false
        val key = repository.getConfigKeys()
            .filterIsInstance<ConfigKey.InterceptCloudflare>()
            .firstOrNull()
            ?: return false
        return repository.getConfig()[key]
    }

    @MainThread
    private suspend fun dumpPageHtml(webView: WebView) {
        runCatchingCancellable {
            val html = withTimeoutOrNull(2_000L) {
                suspendCancellableCoroutine<String?> { cont ->
                    webView.evaluateJavascript("document.documentElement.outerHTML") { result ->
                        cont.resume(result)
                    }
                }
            }
            Log.w(TAG, html.orEmpty())
        }.onFailure { it.printStackTraceDebug() }
    }

	suspend fun loginAndCheck(
		loginUrl: String,
		checkStatus: suspend (url: String, title: String) -> Boolean,
		onSuccess: (() -> Unit)? = null,
		cookiesDomain: String? = null,
		timeoutMs: Long = TimeUnit.SECONDS.toMillis(20),
		userAgent: String? = null,
		headers: Map<String, String> = emptyMap(),
		clearCookieNames: Set<String> = emptySet(),
		clearAllWebViewCookies: Boolean = false,
	): Boolean = mutex.withLock {
		return runCatching {
			withContext(Dispatchers.Main.immediate) {
				runCatchingCancellable { proxyProvider.applyWebViewConfig() }.onFailure { it.printStackTraceDebug() }
				val activity = foregroundActivityHolder.current
				val webView = if (activity != null) WebView(activity) else WebView(context)
				val webViewHost: ViewGroup? = if (activity != null) attachToHost(webView, activity) else null
				try {
					Log.i(
						TAG,
						"loginAndCheck start: url=$loginUrl, userAgentPresent=${!userAgent.isNullOrBlank()}, " +
							"headerNames=${headers.keys}, attached=${webViewHost != null}, throwaway=true, " +
							"context=${context.javaClass.name}, appContext=${context.applicationContext.javaClass.name}, " +
							"webViewContext=${webView.context.javaClass.name}, webViewUserAgentBefore=${webView.settings.userAgentString?.take(80)}",
					)
					webView.configureForMihonCloudflare(userAgent)
					Log.i(
						TAG,
						"loginAndCheck configured: url=$loginUrl, " +
							"webViewUserAgentAfter=${webView.settings.userAgentString?.take(120)}",
					)
					webView.webChromeClient = object : WebChromeClient() {
						override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
							val message = consoleMessage?.message().orEmpty()
							val shouldLog = consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
								consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.WARNING ||
								shouldLogCloudflareDiagnostic(message)
							if (shouldLog) {
								Log.d(
									TAG,
									"loginAndCheck console: level=${consoleMessage?.messageLevel()}, " +
										"line=${consoleMessage?.lineNumber()}, " +
										"source=${consoleMessage?.sourceId()?.take(180)}, " +
										"message=${message.take(500)}",
								)
							}
							return false
						}
					}
					val result = try {
						withTimeout(timeoutMs) {
							suspendCancellableCoroutine<Boolean> { cont ->
								val loggedChallengeRequests = ConcurrentHashMap.newKeySet<String>()
								webView.webViewClient = object : WebViewClient() {
									override fun shouldInterceptRequest(
										view: WebView?,
										request: WebResourceRequest?,
									): WebResourceResponse? {
										val requestUrl = request?.url?.toString().orEmpty()
										if (
											shouldLogCloudflareDiagnostic(requestUrl) &&
											loggedChallengeRequests.add(requestUrl)
										) {
											Log.d(
												TAG,
												"loginAndCheck resource: method=${request?.method}, " +
													"isMainFrame=${request?.isForMainFrame}, url=${requestUrl.take(240)}",
											)
										}
										return null
									}

									override fun onReceivedError(
										view: WebView?,
										request: WebResourceRequest?,
										error: WebResourceError?,
									) {
										val requestUrl = request?.url?.toString().orEmpty()
										if (request?.isForMainFrame == true || shouldLogCloudflareDiagnostic(requestUrl)) {
											Log.w(
												TAG,
												"loginAndCheck resource error: code=${error?.errorCode}, " +
													"description=${error?.description?.take(240)}, " +
													"isMainFrame=${request?.isForMainFrame}, url=${requestUrl.take(240)}",
											)
										}
									}

									override fun onReceivedHttpError(
										view: WebView?,
										request: WebResourceRequest?,
										errorResponse: WebResourceResponse?,
									) {
										val requestUrl = request?.url?.toString().orEmpty()
										if (request?.isForMainFrame == true || shouldLogCloudflareDiagnostic(requestUrl)) {
											Log.w(
												TAG,
												"loginAndCheck http error: status=${errorResponse?.statusCode}, " +
													"reason=${errorResponse?.reasonPhrase}, " +
													"isMainFrame=${request?.isForMainFrame}, url=${requestUrl.take(240)}",
											)
										}
									}

									override fun onPageFinished(view: WebView?, url: String?) {
										val currentUrl = url ?: ""
										val title = view?.title ?: ""
										kotlinx.coroutines.CoroutineScope(cont.context).launch {
											val ok = runCatching { checkStatus(currentUrl, title) }.getOrDefault(false)
											Log.d(
												TAG,
												"loginAndCheck pageFinished: requested=$loginUrl, current=${currentUrl.take(180)}, " +
													"title=${title.take(120)}, ok=$ok, rawCookies=[${webViewCookieDebugString(loginUrl)}]",
											)
											logLoginPageState(webView, loginUrl, "pageFinished")
											if (ok && cont.isActive) {
												Log.i(
													TAG,
													"loginAndCheck check passed: requested=$loginUrl, current=${currentUrl.take(180)}, " +
														"rawCookies=[${webViewCookieDebugString(loginUrl)}]",
												)
												cont.resume(true)
											}
										}
									}
								}
								kotlinx.coroutines.CoroutineScope(cont.context).launch {
									if (clearAllWebViewCookies) {
										// removeAllCookies is the only reliable way to clear
										// HttpOnly+Secure cookies — setCookie-based approaches
										// are silently ignored by Chromium for such cookies.
										suspendCancellableCoroutine<Boolean> { c ->
											android.webkit.CookieManager.getInstance().removeAllCookies { c.resume(it) }
										}
										android.webkit.CookieManager.getInstance().flush()
										Log.i(TAG, "loginAndCheck cleared all WebView cookies for url=$loginUrl")
									} else if (clearCookieNames.isNotEmpty()) {
										clearWebViewCookies(loginUrl, clearCookieNames)
									}
									if (headers.isNotEmpty()) {
										webView.loadUrl(loginUrl, headers)
									} else {
										webView.loadUrl(loginUrl)
									}
								}
							}
						}
					} catch (error: kotlinx.coroutines.TimeoutCancellationException) {
						logLoginPageState(webView, loginUrl, "timeout")
						throw error
					}
					Log.i(
						TAG,
						"loginAndCheck wait result: url=$loginUrl, result=$result, rawCookies=[${webViewCookieDebugString(loginUrl)}]",
					)
					if (!result) return@withContext false
					val domain = cookiesDomain ?: loginUrl.toHttpUrlOrNull()?.host ?: return@withContext true
					val rootDomain = LegadoNetworkUtils.getSubDomain("https://$domain")
					val rawCookies = android.webkit.CookieManager.getInstance().getCookie(loginUrl)
					// 同步 WebView Cookie 到应用 CookieJar — use removeAllCookies on the jar to avoid
					// duplicate entries (removeCookies can't delete HttpOnly cookies, leaving stale values).
					val loginHttpUrl = loginUrl.toHttpUrlOrNull() ?: return@withContext true
					val allJarCookies = cookieJar.loadForRequest(loginHttpUrl)
					if (allJarCookies.isNotEmpty()) {
						cookieJar.removeCookies(loginHttpUrl) { true }
					}
					rawCookies?.let { raw ->
						val httpUrl = "https://$rootDomain".toHttpUrlOrNull() ?: return@let
						Log.i(
							TAG,
							"loginAndCheck sync cookies: url=$loginUrl, rootDomain=$rootDomain, " +
								"rawCookieNames=[${cookieNamesFromRaw(raw)}]",
						)
						raw.split(";").map { it.trim() }.forEach { line ->
							val parts = line.split("=", limit = 2)
							if (parts.size == 2) {
								val name = parts[0]
								val value = parts[1]
								val c = runCatching {
									Cookie.Builder()
										.domain(httpUrl.host)
										.path("/")
										.name(name)
										.value(value)
										.secure()
										.build()
								}.getOrNull()
								if (c != null) {
									cookieJar.saveFromResponse(httpUrl, listOf(c))
								}
							}
						}
					}
					onSuccess?.invoke()
					true
				} finally {
					Log.d(TAG, "loginAndCheck cleanup WebView: url=$loginUrl, throwaway=true")
					runCatching { webView.stopLoading() }
					webView.webChromeClient = WebChromeClient()
					webView.webViewClient = WebViewClient()
					webViewHost?.let { detachFromHost(webView, it) }
					runCatching { webView.destroy() }
				}
			}
		}.onFailure { error ->
			Log.w(TAG, "loginAndCheck failed: url=$loginUrl, error=${error::class.java.simpleName}: ${error.message}")
		}.getOrDefault(false)
	}

	private suspend fun logLoginPageState(webView: WebView, loginUrl: String, reason: String) {
		runCatchingCancellable {
			val state = withTimeoutOrNull(2_000L) {
				suspendCancellableCoroutine<String> { cont ->
					webView.evaluateJavascript(LOGIN_PAGE_STATE_SCRIPT) { result ->
						if (cont.isActive) {
							cont.resume(decodeJavascriptString(result))
						}
					}
				}
			}.orEmpty()
			Log.w(
				TAG,
				"loginAndCheck pageState: reason=$reason, requested=$loginUrl, state=${state.take(1200)}",
			)
		}.onFailure { error ->
			Log.w(TAG, "loginAndCheck pageState failed: reason=$reason, error=${error.message}")
		}
	}

	private fun shouldLogCloudflareDiagnostic(value: String): Boolean {
		if (value.isBlank()) return false
		val lower = value.lowercase()
		return CLOUDFLARE_DIAGNOSTIC_MARKERS.any(lower::contains)
	}

	private fun webViewCookieNames(url: String): String {
		return cookieNamesFromRaw(android.webkit.CookieManager.getInstance().getCookie(url).orEmpty())
	}

	private fun webViewCookieDebugString(url: String): String {
		return android.webkit.CookieManager.getInstance().getCookie(url)
			.orEmpty()
			.split(";")
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

	private suspend fun clearWebViewCookies(url: String, names: Set<String>) {
		val httpUrl = url.toHttpUrlOrNull()
		val host = httpUrl?.host.orEmpty()
		val rootDomain = host.takeIf(String::isNotBlank)
			?.let { runCatching { LegadoNetworkUtils.getSubDomain("https://$it") }.getOrNull() }
			?.takeIf { it.isNotBlank() }
		val domains = buildSet {
			if (host.isNotBlank()) {
				add(host)
				add(".$host")
			}
			if (!rootDomain.isNullOrBlank()) {
				add(rootDomain)
				add(".$rootDomain")
			}
		}
		val before = webViewCookieDebugString(url)
		val cookieManager = android.webkit.CookieManager.getInstance()
		val rawNames = android.webkit.CookieManager.getInstance().getCookie(url)
			.orEmpty()
			.split(";")
			.mapNotNull { rawCookie ->
				val rawName = rawCookie.substringBefore("=")
				rawName.takeIf { it.trim() in names }
			}
			.ifEmpty { names.toList() }
		rawNames.forEach { rawName ->
			cookieManager.setCookieAwait(url, "$rawName=;Max-Age=0")
			cookieManager.setCookieAwait(url, "${rawName.trim()}=;Max-Age=0")
			cookieManager.setCookieAwait(url, "${rawName.trim()}=;Max-Age=0;Path=/")
			cookieManager.setCookieAwait(url, "${rawName.trim()}=;Expires=Thu, 01 Jan 1970 00:00:00 GMT;Path=/")
			cookieManager.setCookieAwait(url, "${rawName.trim()}=;Max-Age=0;Path=/;Secure")
			cookieManager.setCookieAwait(url, "${rawName.trim()}=;Max-Age=0;Path=/;Secure;HttpOnly")
			cookieManager.setCookieAwait(url, "${rawName.trim()}=;Max-Age=0;Path=/;Secure;HttpOnly;SameSite=None")
			domains.forEach { domain ->
				cookieManager.setCookieAwait(
					url,
					"$rawName=;Max-Age=0;Domain=$domain",
				)
				cookieManager.setCookieAwait(
					url,
					"${rawName.trim()}=;Max-Age=0;Domain=$domain",
				)
				cookieManager.setCookieAwait(
					url,
					"${rawName.trim()}=;Max-Age=0;Domain=$domain;Path=/",
				)
				cookieManager.setCookieAwait(
					url,
					"${rawName.trim()}=;Expires=Thu, 01 Jan 1970 00:00:00 GMT;Domain=$domain;Path=/",
				)
				cookieManager.setCookieAwait(
					url,
					"${rawName.trim()}=;Max-Age=0;Domain=$domain;Path=/;Secure",
				)
				cookieManager.setCookieAwait(
					url,
					"${rawName.trim()}=;Max-Age=0;Domain=$domain;Path=/;Secure;HttpOnly",
				)
				cookieManager.setCookieAwait(
					url,
					"${rawName.trim()}=;Max-Age=0;Domain=$domain;Path=/;Secure;HttpOnly;SameSite=None",
				)
			}
		}
		cookieManager.flush()
		Log.i(
			TAG,
			"loginAndCheck cleared WebView cookies: url=$url, names=$names, rootDomain=${rootDomain ?: "<none>"}, " +
				"before=[$before], after=[${webViewCookieDebugString(url)}], matrix=[${webViewCookieMatrix(url)}]",
		)
	}

	private fun webViewCookieMatrix(url: String): String {
		val httpUrl = url.toHttpUrlOrNull() ?: return "$url=[${webViewCookieDebugString(url)}]"
		val host = httpUrl.host
		val rootDomain = runCatching { LegadoNetworkUtils.getSubDomain("https://$host") }
			.getOrNull()
			?.takeIf { it.isNotBlank() }
		val urls = buildSet {
			add(httpUrl.newBuilder().encodedPath("/").query(null).fragment(null).build().toString())
			add(httpUrl.newBuilder().scheme("http").encodedPath("/").query(null).fragment(null).build().toString())
			if (!rootDomain.isNullOrBlank() && rootDomain != host) {
				add(httpUrl.newBuilder().host(rootDomain).encodedPath("/").query(null).fragment(null).build().toString())
				add(httpUrl.newBuilder().scheme("http").host(rootDomain).encodedPath("/").query(null).fragment(null).build().toString())
			}
		}
		return urls.joinToString("|") { candidate ->
			"$candidate=[${webViewCookieDebugString(candidate)}]"
		}
	}

	private suspend fun android.webkit.CookieManager.setCookieAwait(url: String, value: String) {
		suspendCancellableCoroutine<Unit> { cont ->
			setCookie(url, value) {
				if (cont.isActive) {
					cont.resume(Unit)
				}
			}
		}
	}

	private fun maskCookieValue(value: String?): String {
		if (value.isNullOrEmpty()) return "<empty>"
		return if (value.length <= 8) "***" else "${value.take(4)}...${value.takeLast(4)}"
	}

	private fun cookieNamesFromRaw(raw: String): String {
		return raw.split(";")
			.mapNotNull { it.trim().substringBefore("=").takeIf(String::isNotBlank) }
			.joinToString(",")
			.ifBlank { "<none>" }
	}

	@MainThread
	private fun WebView.configureForMihonCloudflare(userAgent: String?) {
		with(settings) {
			javaScriptEnabled = true
			domStorageEnabled = true
			useWideViewPort = true
			loadWithOverviewMode = true
			cacheMode = WebSettings.LOAD_DEFAULT
			setSupportMultipleWindows(true)
			setSupportZoom(true)
			builtInZoomControls = true
			displayZoomControls = false
			userAgentString = userAgent ?: defaultUserAgent
		}
		CookieManager.getInstance().acceptThirdPartyCookies(this)
	}

	@MainThread
	private fun WebView.reset() {
		stopLoading()
		webChromeClient = WebChromeClient()
		webViewClient = WebViewClient()
		settings.userAgentString = defaultUserAgent
		loadDataWithBaseURL(null, " ", "text/html", null, null)
		clearHistory()
	}

    companion object {
	        private const val TAG = "WebViewExecutor"
	        private const val CHALLENGE_POLL_INTERVAL_MS = 700L
		        private const val MAX_CHALLENGE_MS = 30_000L
	        private const val FAILURE_COOLDOWN_MS = 30_000L
	        private const val CLOUDFLARE_WEBVIEW_WIDTH = 1024
	        private const val CLOUDFLARE_WEBVIEW_HEIGHT = 768
			private val CLOUDFLARE_DIAGNOSTIC_MARKERS = listOf(
			"cloudflare",
			"challenge",
			"turnstile",
			"captcha",
			"cdn-cgi",
			"cf_chl",
			"__cf",
		)
		private val LOGIN_PAGE_STATE_SCRIPT = """
			JSON.stringify({
			  readyState: document.readyState || '',
			  href: location.href || '',
			  title: document.title || '',
			  visibilityState: document.visibilityState || '',
			  userAgent: navigator.userAgent || '',
			  webdriver: navigator.webdriver === undefined ? 'undefined' : String(navigator.webdriver),
			  language: navigator.language || '',
			  platform: navigator.platform || '',
			  cookieEnabled: String(navigator.cookieEnabled),
			  challengeErrorTitle: document.querySelector('#challenge-error-title')?.textContent?.trim() || '',
			  challengeErrorText: document.querySelector('#challenge-error-text')?.textContent?.trim() || '',
			  bodyText: document.body?.innerText?.trim()?.slice(0, 500) || ''
			})
		""".trimIndent()
    }

	data class SniffedMediaResult(
		val url: String,
		val headers: Map<String, String>,
	)

	data class BrowserFetchResult(
		val status: Int,
		val statusText: String,
		val url: String,
		val headers: Map<String, String>,
		val body: String,
	)

	private fun decodeJavascriptString(value: String?): String {
		if (value.isNullOrBlank() || value == "null") {
			return ""
		}
		return try {
			org.json.JSONTokener(value).nextValue() as? String ?: value
		} catch (e: Exception) {
			value
		}
	}
}
