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
import android.webkit.SslErrorHandler
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.net.http.SslError
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.JavaScriptExecutionWorld
import androidx.webkit.JavaScriptExecutionException
import androidx.webkit.WebMessageCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewRenderProcessClient
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.model.UnknownContentSource
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Headers
import okhttp3.Cookie
import java.util.concurrent.TimeUnit
import org.json.JSONObject

enum class CaptchaAutoResolveResult {
    SOLVED,
    INTERACTIVE_REQUIRED,
    HARD_BLOCKED,
    TIMED_OUT,
    COOLDOWN,
    FAILED,
}

@Singleton
class WebViewExecutor @Inject constructor(
	@ApplicationContext private val context: Context,
	private val proxyProvider: ProxyProvider,
	private val cookieJar: MutableCookieJar,
    private val adBlock: AdBlock,
	private val foregroundActivityHolder: ForegroundActivityHolder,
	private val browserSessionManager: BrowserSessionManager,
	private val mangaRepositoryFactoryProvider: Provider<ContentRepository.Factory>,
) {
	private enum class BrowserSessionState { READY, BUSY, POISONED, DESTROYED }

	private data class BrowserSessionRecord(
		val sessionId: String = UUID.randomUUID().toString(),
		val origin: String,
		val webView: WebView,
		val provider: String?,
		val executionMutex: Mutex = Mutex(),
		var rendererEpoch: Long = 0L,
		var navigationEpoch: Long = 0L,
		var pendingOperations: Int = 0,
		var attachedToUi: Boolean = false,
		var unresponsiveAt: Long? = null,
		var state: BrowserSessionState = BrowserSessionState.READY,
		var activeExecution: BrowserExecution? = null,
		var lastUsedAt: Long = System.currentTimeMillis(),
		var transportInstallation: BrowserTransportInstallation? = null,
	) {
		val canEvict: Boolean
			get() = pendingOperations == 0 && activeExecution == null && !attachedToUi &&
				state == BrowserSessionState.READY

		fun transitionExecutionTo(next: BrowserExecutionState) {
			val execution = checkNotNull(activeExecution) { "BrowserSession has no active execution" }
			execution.transitionTo(next)
			Log.d(
				"WebViewExecutor",
				"BrowserExecution transition: session=$sessionId execution=${execution.executionId} " +
					"origin=$origin state=$next",
			)
		}
	}

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
	private val browserSessionCache = LinkedHashMap<String, BrowserSessionRecord>(BROWSER_SESSION_CACHE_SIZE, 0.75f, true)
	private val browserSessionCreationMutex = Mutex()
	private val browserSessionProfileStore = BrowserSessionProfileStore(context)
    private val captchaMutexes = ConcurrentHashMap<String, Mutex>()
	private val recentFailureUntil = ConcurrentHashMap<String, Long>()
	private val _interactiveChallenges = MutableSharedFlow<BrowserInteractiveChallenge>(
		extraBufferCapacity = 8,
	)
	private val interactiveChallengeStates = ConcurrentHashMap<String, BrowserInteractiveChallenge>()
	private val interactiveChallengeCancellations = ConcurrentHashMap<String, () -> Unit>()

	internal val interactiveChallenges: SharedFlow<BrowserInteractiveChallenge> = _interactiveChallenges.asSharedFlow()

	/** A host calls this after attaching the session WebView for the challenge. */
	internal fun acknowledgeInteractiveChallenge(challengeId: String, sessionId: String) {
		updateInteractiveChallenge(challengeId, sessionId, BrowserInteractiveChallengeState.ATTACHED)
	}

	/** Cancels only the matching active challenge; stale UI events cannot affect a newer operation. */
	internal fun cancelInteractiveChallenge(challengeId: String, sessionId: String): Boolean {
		val current = interactiveChallengeStates[challengeId] ?: return false
		if (current.sessionId != sessionId || current.isTerminal) return false
		updateInteractiveChallenge(challengeId, sessionId, BrowserInteractiveChallengeState.CANCELLED)
		interactiveChallengeCancellations.remove(challengeId)?.invoke()
		return true
	}

	private fun updateInteractiveChallenge(
		challengeId: String,
		sessionId: String,
		state: BrowserInteractiveChallengeState,
	) {
		val current = interactiveChallengeStates[challengeId] ?: return
		if (current.sessionId != sessionId || current.isTerminal) return
		if (current.state == state) return
		val updated = current.transitionTo(state)
		interactiveChallengeStates[challengeId] = updated
		_interactiveChallenges.tryEmit(updated)
	}

	/**
	 * Attaches an existing BrowserSession WebView by stable session id.
	 * The caller must release the returned host with [detachBrowserSession].
	 */
	@MainThread
	internal fun attachBrowserSession(sessionId: String, activity: android.app.Activity): ViewGroup? {
		val session = browserSessionCache.values.firstOrNull { it.sessionId == sessionId } ?: return null
		val content = browserSessionManager.attach(session.sessionId, activity) ?: return null
		session.attachedToUi = true
		return content
	}

	@MainThread
	internal fun detachBrowserSession(sessionId: String, host: ViewGroup): Boolean {
		val session = browserSessionCache.values.firstOrNull { it.sessionId == sessionId } ?: return false
		if (!browserSessionManager.detach(session.sessionId, host)) return false
		session.attachedToUi = false
		session.lastUsedAt = System.currentTimeMillis()
		return true
	}

	internal fun isBrowserSessionAttached(sessionId: String): Boolean =
		browserSessionCache.values.firstOrNull { it.sessionId == sessionId }?.attachedToUi == true

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
		method: String = "GET",
		body: String? = null,
		userAgent: String? = null,
		headers: Map<String, String> = emptyMap(),
		allowedOrigins: Set<String> = emptySet(),
		allowInteractiveChallenge: Boolean = false,
		settleDelayMs: Long = 1200,
		timeoutMs: Long = 30000,
	): BrowserFetchResult? {
		val target = url.toHttpUrlOrNull() ?: return null
		val normalizedMethod = method.uppercase().takeIf { it == "GET" || it == "POST" } ?: return null
		val originPolicy = BrowserOriginPolicy.create(url, allowedOrigins) ?: return null
		val sessionKey = originPolicy.primaryOrigin
		return withContext(Dispatchers.Main.immediate) {
				val session = obtainBrowserSession(sessionKey)
				session.executionMutex.withLock {
				val webView = session.webView
				val rendererEpoch = session.rendererEpoch
				val execution = BrowserExecution(requestUrl = url, method = normalizedMethod)
				check(session.activeExecution == null) { "BrowserSession already has an active execution" }
				session.activeExecution = execution
				session.pendingOperations++
				session.state = BrowserSessionState.BUSY
				Log.d(
					TAG,
					"BrowserExecution started: session=${session.sessionId} execution=${execution.executionId} " +
						"origin=${session.origin} method=$normalizedMethod url=$url",
				)
				val messageBridge = session.transportInstallation?.messageBridge?.bridge
			try {
				android.util.Log.d("WebViewExecutor", "fetchWithBrowserContext start: $url")
				configureBrowserSession(webView)
				val transportResult = withTimeoutOrNull(timeoutMs) {
					check(session.rendererEpoch == rendererEpoch && session.state != BrowserSessionState.POISONED) {
						"BrowserSession renderer changed before request"
					}
					val currentUrl = webView.url
					val needsOriginNavigation = currentUrl.isNullOrBlank() || currentUrl == "about:blank" ||
						!originPolicy.allowsDocument(currentUrl)
					if (needsOriginNavigation) suspendCancellableCoroutine<Unit> { cont ->
						val completed = AtomicBoolean(false)
						val apiObservationSequence = AtomicInteger(0)
						fun fail(cause: Throwable) {
							if (completed.compareAndSet(false, true) && cont.isActive) {
								cont.resumeWithException(cause)
							}
						}
						webView.webViewClient = object : WebViewClient() {
							override fun shouldInterceptRequest(
								view: WebView?,
								request: WebResourceRequest?,
							): WebResourceResponse? {
								request?.takeIf { shouldObserveBrowserApiRequest(originPolicy, it.url.toString()) }
									?.let { observed ->
										logObservedBrowserApiRequest(
											sequence = apiObservationSequence.incrementAndGet(),
											request = observed,
										)
									}
								return null
							}

							override fun onPageFinished(view: WebView?, loadedUrl: String?) {
								android.util.Log.d(
									"WebViewExecutor",
									"fetchWithBrowserContext base page finished: $loadedUrl",
								)
								if (completed.compareAndSet(false, true) && cont.isActive) {
									cont.resume(Unit)
								}
							}

							override fun onReceivedError(
								view: WebView?,
								request: WebResourceRequest?,
								error: WebResourceError?,
							) {
								if (request?.isForMainFrame == true) {
									fail(
										BrowserPageLoadException(
											"Main-frame WebView load failed: code=${error?.errorCode}, " +
												"description=${error?.description}",
										),
									)
								}
							}

							override fun onReceivedSslError(
								view: WebView?,
								handler: SslErrorHandler?,
								error: SslError?,
							) {
								handler?.cancel()
								fail(BrowserPageLoadException("Main-frame WebView SSL error: ${error?.primaryError}"))
							}

							override fun onReceivedHttpError(
								view: WebView?,
								request: WebResourceRequest?,
								errorResponse: WebResourceResponse?,
							) {
								val requestUrl = request?.url?.toString().orEmpty()
								if (shouldObserveBrowserApiRequest(originPolicy, requestUrl)) {
									Log.d(
										TAG,
										"Observed browser API response: method=${request?.method} " +
											"status=${errorResponse?.statusCode} url=${requestUrl.take(240)}",
									)
								}
							}

							 override fun onRenderProcessGone(
								view: WebView?,
								detail: android.webkit.RenderProcessGoneDetail?,
							): Boolean {
								messageBridge?.failAll(BrowserPageLoadException("WebView renderer exited; didCrash=${detail?.didCrash()}"))
								discardWebView(view)
								fail(BrowserPageLoadException("WebView renderer exited; didCrash=${detail?.didCrash()}"))
								return true
							}
						}
						cont.invokeOnCancellation { webView.stopLoading() }
						val baseUrl = target.newBuilder()
							.encodedPath("/")
							.query(null)
							.fragment(null)
							.build()
							.toString()
						advanceNavigationEpoch(webView)
						webView.loadUrl(baseUrl)
					} else {
						Log.d(TAG, "Reusing BrowserSession document: currentUrl=$currentUrl target=$url")
					}
						if (needsOriginNavigation) {
							awaitBrowserDocumentReady(
								webView = webView,
								maxWaitMs = BROWSER_DOCUMENT_READY_TIMEOUT_MS,
								quietWindowMs = settleDelayMs.coerceAtLeast(BROWSER_DOCUMENT_QUIET_WINDOW_MS),
							)
						} else {
							kotlinx.coroutines.delay(settleDelayMs)
						}

					val resolvedOriginHost = webView.url?.toHttpUrlOrNull()?.host ?: target.host
					val resolvedPageUrl = webView.url ?: target.newBuilder().encodedPath("/").build().toString()
					if (!originPolicy.allowsDocument(resolvedPageUrl)) {
						throw BrowserPageLoadException("Browser page redirected outside origin policy: $resolvedPageUrl")
					}
					val targetUrlToFetch = if (resolvedOriginHost != target.host) {
						val redirectedTarget = target.newBuilder().host(resolvedOriginHost).build().toString()
						if (!originPolicy.allowsFetch(redirectedTarget)) {
							throw BrowserPageLoadException("Browser request redirected outside origin policy: $redirectedTarget")
						}
						redirectedTarget
					} else {
						url
					}
					android.util.Log.d(
						"WebViewExecutor",
						"fetchWithBrowserContext origin ready: currentUrl=${webView.url} targetUrl=$targetUrlToFetch",
					)

					val allowedHeaders = browserFetchHeaders(headers)
					logBrowserRpcRequest(
						method = normalizedMethod,
						headers = allowedHeaders,
						body = body,
						executionWorld = BrowserFetchExecutionWorld.PREFER_ISOLATED,
					)
					val raw = fetchViaWebMessage(
						webView = webView,
						bridge = messageBridge,
						originPolicy = originPolicy,
						url = targetUrlToFetch,
						fallbackUrl = url,
						method = normalizedMethod,
						body = body,
						headers = allowedHeaders,
						executionWorld = BrowserFetchExecutionWorld.PREFER_ISOLATED,
					) ?: fetchViaJavascriptPolling(
						webView,
						targetUrlToFetch,
						url,
						normalizedMethod,
						body,
						allowedHeaders,
					)
					check(session.rendererEpoch == rendererEpoch && session.state != BrowserSessionState.POISONED) {
						"BrowserSession renderer changed during request"
					}
					if (raw.isBlank()) {
						android.util.Log.w(
							"WebViewExecutor",
							"fetchWithBrowserContext empty JS result"
						)
						return@withTimeoutOrNull tryNavigationFetchFallback(
							webView, targetUrlToFetch, headers, normalizedMethod,
						)
					}
					val json = runCatching { JSONObject(raw) }.onFailure {
						android.util.Log.w(
							"WebViewExecutor",
							"fetchWithBrowserContext JSON parse failed: ${it.message}; rawPreview=${raw.take(200)}",
						)
						}.getOrNull() ?: return@withTimeoutOrNull tryNavigationFetchFallback(
							webView, targetUrlToFetch, headers, normalizedMethod,
						)
					if (json.optString("body").length > MAX_BROWSER_TEXT_BYTES) {
						throw BrowserPageLoadException("Browser response exceeds text transport limit")
					}
					var activeJson = json
					var fetchStatus = activeJson.optInt("status")
					var fetchBody = activeJson.optString("body")
					var fetchHeaders = activeJson.optJSONObject("headers")
					val isManagedChallenge = fetchHeaders?.optString("cf-mitigated")
						.equals("challenge", ignoreCase = true)
					var isCloudflareBlock = (fetchStatus == 403 || fetchStatus == 503) &&
						(fetchBody.contains("cf-browser-verification") ||
							fetchBody.contains("Just a moment") ||
							fetchBody.contains("__cf_chl_opt") ||
							fetchBody.contains("challenge-platform") ||
							fetchBody.contains("turnstile") ||
							fetchBody.contains("Adscore") ||
							isManagedChallenge)
					logBrowserRpcResponse(BrowserFetchExecutionWorld.PREFER_ISOLATED, activeJson, isCloudflareBlock)

					if (isCloudflareBlock) {
						logBrowserRpcRequest(
							method = normalizedMethod,
							headers = allowedHeaders,
							body = body,
							executionWorld = BrowserFetchExecutionWorld.PAGE,
						)
						val pageWorldRaw = fetchViaWebMessage(
							webView = webView,
							bridge = messageBridge,
							originPolicy = originPolicy,
							url = targetUrlToFetch,
							fallbackUrl = url,
							method = normalizedMethod,
							body = body,
							headers = allowedHeaders,
							executionWorld = BrowserFetchExecutionWorld.PAGE,
						)
						val pageWorldJson = pageWorldRaw?.let { runCatching { JSONObject(it) }.getOrNull() }
						if (pageWorldJson != null) {
							val pageStatus = pageWorldJson.optInt("status")
							val pageBody = pageWorldJson.optString("body")
							val pageHeaders = pageWorldJson.optJSONObject("headers")
							val pageIsCloudflareBlock = isCloudflareChallenge(pageStatus, pageBody, pageHeaders)
							logBrowserRpcResponse(BrowserFetchExecutionWorld.PAGE, pageWorldJson, pageIsCloudflareBlock)
							activeJson = pageWorldJson
							fetchStatus = pageStatus
							fetchBody = pageBody
							fetchHeaders = pageHeaders
							isCloudflareBlock = pageIsCloudflareBlock
						}
					}

						if (!activeJson.optBoolean("ok") || isCloudflareBlock) {
							android.util.Log.w(
								"WebViewExecutor",
								"fetchWithBrowserContext failed or hit WAF " +
									"(ok=${activeJson.optBoolean("ok")}, status=$fetchStatus, isCF=$isCloudflareBlock, " +
									"error=${activeJson.optString("error").take(240)}). Falling back to navigation.",
							)
						if (isCloudflareBlock) {
							val challengeHeaders = Headers.Builder().apply {
								headers.forEach { (name, value) ->
									if (name.isNotBlank() && value.isNotBlank()) add(name, value)
								}
							}.build()
							val challengeContext = BrowserChallengeContext.create(
								requestUrl = targetUrlToFetch,
								method = normalizedMethod,
								responseHtml = fetchBody,
							) ?: throw BrowserPageLoadException("Invalid browser challenge context")
							execution.challengeDetected(challengeContext)
							Log.d(
								TAG,
								"BrowserExecution transition: session=${session.sessionId} " +
									"execution=${execution.executionId} origin=${session.origin} " +
									"state=${BrowserExecutionState.CHALLENGE_DETECTED}",
							)
							val challenge = CloudFlareProtectedException(
								url = targetUrlToFetch,
								source = UnknownContentSource,
								headers = challengeHeaders,
								method = normalizedMethod,
								body = body,
							)
							session.transitionExecutionTo(BrowserExecutionState.RESOLVING_AUTOMATIC)
							var validatedRetryJson: JSONObject? = null
							val resolutionEvidence = if (allowInteractiveChallenge) {
								resolveChallengeInSession(
									webView = webView,
									exception = challenge,
									context = challengeContext,
									challengeDocumentHtml = fetchBody,
									validateOriginalRequest = {
										val validationRaw = fetchViaWebMessage(
											webView, messageBridge, originPolicy, targetUrlToFetch, url,
											normalizedMethod, body, allowedHeaders, BrowserFetchExecutionWorld.PAGE,
										) ?: fetchViaJavascriptPolling(
											webView, targetUrlToFetch, url, normalizedMethod, body, allowedHeaders,
										)
										val validationJson = runCatching { JSONObject(validationRaw) }.getOrNull()
										val validated = validationJson?.optBoolean("ok") == true && !isCloudflareChallenge(
											validationJson.optInt("status"),
											validationJson.optString("body"),
											validationJson.optJSONObject("headers"),
										)
										if (validated) validatedRetryJson = validationJson
										validated
									},
								)
							} else {
								Log.i(
									TAG,
									"Browser interactive challenge deferred to source UI: " +
										"method=$normalizedMethod url=$targetUrlToFetch",
								)
								null
							}
							if (resolutionEvidence != null) {
								session.transitionExecutionTo(BrowserExecutionState.VALIDATING)
								session.transitionExecutionTo(BrowserExecutionState.RETRYING_REQUEST)
								logBrowserRpcRequest(
									method = normalizedMethod,
									headers = allowedHeaders,
									body = body,
									executionWorld = BrowserFetchExecutionWorld.PAGE,
								)
								val retryJson = validatedRetryJson
								if (retryJson != null) {
									logBrowserRpcResponse(
										BrowserFetchExecutionWorld.PAGE,
										retryJson,
										isCloudflareChallenge(
											retryJson.optInt("status"),
											retryJson.optString("body"),
											retryJson.optJSONObject("headers"),
										),
									)
								}
								if (retryJson?.optBoolean("ok") == true) {
									session.transitionExecutionTo(BrowserExecutionState.COMPLETED)
									return@withTimeoutOrNull BrowserFetchResult(
										status = retryJson.optInt("status"),
										statusText = retryJson.optString("statusText"),
										url = retryJson.optString("url", url),
										headers = retryJson.optJSONObject("headers")?.let { obj ->
											buildMap { obj.keys().forEach { key -> put(key, obj.optString(key)) } }
										} ?: emptyMap(),
										body = retryJson.optString("body"),
									)
								}
								session.transitionExecutionTo(BrowserExecutionState.FAILED)
							} else {
								session.transitionExecutionTo(BrowserExecutionState.FAILED)
							}
						}
						val failedHeaders = fetchHeaders?.let { obj ->
							buildMap { obj.keys().forEach { key -> put(key, obj.optString(key)) } }
						} ?: emptyMap()
						return@withTimeoutOrNull BrowserFetchResult(
							status = fetchStatus,
							statusText = activeJson.optString("statusText"),
							url = activeJson.optString("url", url),
							headers = failedHeaders,
							body = fetchBody,
						)
					}
					val responseHeadersObj = activeJson.optJSONObject("headers")
					val responseHeaders = linkedMapOf<String, String>()
					if (responseHeadersObj != null) {
						val keys = responseHeadersObj.keys()
						while (keys.hasNext()) {
							val key = keys.next()
							responseHeaders[key] = responseHeadersObj.optString(key)
						}
					}
					BrowserFetchResult(
						status = activeJson.optInt("status"),
						statusText = activeJson.optString("statusText"),
						url = activeJson.optString("url"),
						headers = responseHeaders,
						body = activeJson.optString("body"),
					)
				}
					if (transportResult == null) {
						snapshotCurrentPage(webView, webView.url ?: url, "timeout")
					}
					val result = transportResult?.takeIf { it.status in 100..599 }
				if (!execution.isTerminal) {
					session.transitionExecutionTo(
						if (result != null && result.status != 0) {
							BrowserExecutionState.COMPLETED
						} else {
							BrowserExecutionState.FAILED
						},
					)
				}
				result
			} catch (error: CancellationException) {
				if (!execution.isTerminal) session.transitionExecutionTo(BrowserExecutionState.CANCELLED)
				throw error
			} catch (error: BrowserPageLoadException) {
				if (!execution.isTerminal) session.transitionExecutionTo(BrowserExecutionState.FAILED)
				Log.w(TAG, "Browser transport page initialization failed: $url", error)
				null
			} catch (error: Exception) {
				if (!execution.isTerminal) session.transitionExecutionTo(BrowserExecutionState.FAILED)
				Log.w(TAG, "Browser transport execution failed: $url", error)
				null
			} finally {
				android.util.Log.d("WebViewExecutor", "fetchWithBrowserContext end: $url")
					if (execution.state == BrowserExecutionState.FAILED) {
						Log.w(TAG, "Discarding BrowserSession after failed execution: session=${session.sessionId} url=$url")
						discardWebView(webView)
					} else {
						runCatching { prepareBrowserSessionForReuse(webView) }.onFailure { discardWebView(webView) }
					}
				session.pendingOperations = (session.pendingOperations - 1).coerceAtLeast(0)
				if (session.state == BrowserSessionState.BUSY) {
					session.state = BrowserSessionState.READY
				}
				if (session.activeExecution === execution) session.activeExecution = null
				session.lastUsedAt = System.currentTimeMillis()
				trimBrowserSessionCache()
			}
		}
	}
	}

	@MainThread
	private fun prepareBrowserSessionForReuse(webView: WebView) {
		webView.stopLoading()
		webView.webChromeClient = WebChromeClient()
		webView.webViewClient = WebViewClient()
		(webView.parent as? ViewGroup)?.removeView(webView)
		webView.alpha = 0.01f
		// Keep the current same-origin document, CookieManager state, storage and
		// renderer alive. Navigating to an empty document here would defeat the
		// per-origin BrowserSession contract.
	}

	private suspend fun resolveChallengeInSession(
		webView: WebView,
		exception: CloudFlareProtectedException,
		context: BrowserChallengeContext,
		challengeDocumentHtml: String,
		validateOriginalRequest: suspend () -> Boolean,
	): BrowserResolutionEvidence? {
		val activity = foregroundActivityHolder.current ?: return null
		val session = browserSessionCache.values.firstOrNull { it.webView === webView } ?: return null
		val host = attachBrowserSession(session.sessionId, activity) ?: return null
		session.transitionExecutionTo(BrowserExecutionState.WAITING_FOR_USER)
		val interactiveChallenge = BrowserInteractiveChallenge(
			sessionId = session.sessionId,
			origin = context.origin,
			requestUrl = context.requestUrl,
			method = context.method,
			displayUrl = context.navigationUrl ?: context.requestUrl,
		)
		interactiveChallengeStates[interactiveChallenge.challengeId] = interactiveChallenge
		var terminalState = BrowserInteractiveChallengeState.FAILED
		val evidence = try {
			// Keep any browser state issued by the homepage. The original API request,
			// not the presence of a particular cookie, is the final success criterion.
			val initialClearance = CloudFlareHelper.getClearanceCookie(cookieJar, exception.url)
			withTimeoutOrNull(DEFAULT_CAPTCHA_TIMEOUT_MS * 2) {
				suspendCancellableCoroutine<BrowserResolutionEvidence?> { cont ->
					val validationInFlight = AtomicBoolean(false)
					interactiveChallengeCancellations[interactiveChallenge.challengeId] = {
						if (cont.isActive) cont.resume(null) {}
					}
					if (!_interactiveChallenges.tryEmit(interactiveChallenge)) {
						interactiveChallengeCancellations.remove(interactiveChallenge.challengeId)
						cont.resume(null) {}
						return@suspendCancellableCoroutine
					}
					Log.i(
						TAG,
						"Interactive challenge attached to BrowserSession: session=${interactiveChallenge.sessionId} " +
							"challenge=${interactiveChallenge.challengeId} origin=${context.origin} " +
							"url=${context.requestUrl} method=${context.method} " +
							"snippetLength=${context.responseHtmlSnippet.length}",
					)
					val resolutionTracker = BrowserChallengeResolutionTracker()
					fun inspectState() {
						webView.evaluateJavascript(CF_STATE_JS) { raw ->
							val state = parseCloudFlarePageState(raw)
							val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, exception.url)
							val evidence = resolutionTracker.observe(
								pageState = state,
								hasClearance = clearance != null,
								clearanceChanged = clearance != null && clearance != initialClearance,
								currentUrl = webView.url,
								requiresInteractiveResolution = context.method == "POST",
							)
							Log.d(
								TAG,
								"Session challenge state=$state url=${webView.url} " +
									"hasClearance=${clearance != null} evidence=$evidence",
							)
							if (evidence != null && cont.isActive && validationInFlight.compareAndSet(false, true)) {
								kotlinx.coroutines.CoroutineScope(cont.context).launch {
									val validated = runCatchingCancellable { validateOriginalRequest() }
										.onFailure { Log.w(TAG, "Interactive challenge validation failed", it) }
										.getOrDefault(false)
									Log.d(
										TAG,
										"Interactive challenge original request validation: " +
											"url=${context.requestUrl} validated=$validated",
									)
									if (validated && cont.isActive) {
										cont.resume(evidence) {}
									} else {
										validationInFlight.set(false)
										if (cont.isActive) {
											kotlinx.coroutines.delay(INTERACTIVE_VALIDATION_RETRY_MS)
											if (cont.isActive) inspectState()
										}
									}
								}
							}
						}
					}
					val client = CloudFlareClient(cookieJar, object : org.skepsun.kototoro.browser.cloudflare.CloudFlareCallback {
						override fun onLoadingStateChanged(isLoading: Boolean) = Unit
						override fun onHistoryChanged() = Unit
						override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) = Unit
						override fun onPageFinished(webView: WebView, url: String) = Unit
						override fun onPageLoaded() = inspectState()
						override fun onLoopDetected() {
							if (cont.isActive) cont.resume(null) {}
						}

						override fun onCheckPassed() {
							// This callback is resolution evidence, not transport success.
							// Re-check the actual page and cookie before retrying the original request.
							inspectState()
						}
					}, adBlock, exception.url)
					webView.webViewClient = client
					webView.alpha = 1f
					webView.visibility = View.VISIBLE
					// POST challenges use the real origin homepage, matching normal browsing;
					// opening an API endpoint as a GET creates a different Turnstile flow.
					advanceNavigationEpoch(webView)
					val navigationUrl = context.navigationUrl ?: context.requestUrl
					webView.loadUrl(navigationUrl)
					Log.i(
						TAG,
						"Interactive challenge navigated to real HTTPS origin: url=$navigationUrl " +
							"method=${context.method} htmlSnippetLength=${challengeDocumentHtml.length}",
					)
					cont.invokeOnCancellation { webView.stopLoading() }
				}
			}.also { result ->
				terminalState = if (result != null) BrowserInteractiveChallengeState.RESOLVED
				else BrowserInteractiveChallengeState.FAILED
			}
		} catch (error: CancellationException) {
			terminalState = BrowserInteractiveChallengeState.CANCELLED
			throw error
		} catch (error: Exception) {
			terminalState = BrowserInteractiveChallengeState.FAILED
			throw error
		} finally {
			interactiveChallengeCancellations.remove(interactiveChallenge.challengeId)
			val currentState = interactiveChallengeStates[interactiveChallenge.challengeId]?.state
			if (currentState != BrowserInteractiveChallengeState.CANCELLED) {
				updateInteractiveChallenge(interactiveChallenge.challengeId, interactiveChallenge.sessionId, terminalState)
			}
			interactiveChallengeStates.remove(interactiveChallenge.challengeId)
			withContext(Dispatchers.Main.immediate) {
				detachBrowserSession(session.sessionId, host)
				webView.alpha = 0.01f
			}
		}
		return evidence
	}

	@MainThread
	private fun installBrowserFetchExecutor(
		webView: WebView,
		originPolicy: BrowserOriginPolicy,
	): ScriptHandler? {
		if (WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)) {
			Log.d(TAG, "WebView provider supports isolated JavaScript worlds; using capability-aware fallback")
		}
		if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
		val script = """
			(() => {
			  const key = '__kototoroFetchExecutor';
			  if (Object.prototype.hasOwnProperty.call(window, key)) return;
			  const nativeFetch = window.fetch.bind(window);
			  const executor = Object.freeze({ fetch: (input, init) => nativeFetch(input, init) });
			  Object.defineProperty(window, key, {
			    value: executor, writable: false, configurable: false, enumerable: false
			  });
			})();
		""".trimIndent()
		return WebViewCompat.addDocumentStartJavaScript(webView, script, originPolicy.documentOrigins)
	}

	/** Uses the AndroidX origin-scoped bridge; returns null when the WebView lacks the feature. */
	@MainThread
	private fun installBrowserMessageBridge(
		webView: WebView,
		session: BrowserSessionRecord,
		sessionOriginPolicy: BrowserOriginPolicy,
	): BrowserMessageBridgeInstallation? {
		if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return null
		val bridge = BrowserMessageBridge(
			sessionOriginPolicy = sessionOriginPolicy,
			currentRendererEpoch = { session.rendererEpoch },
			currentNavigationEpoch = { session.navigationEpoch },
		)
		val isolatedListener = WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, replyProxy ->
			bridge.onIsolatedPostMessage(message, sourceOrigin, isMainFrame, replyProxy)
		}
		val isolatedWorld = if (WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)) {
			val world = WebViewCompat.getExecutionWorld(webView, "KototoroTransport")
			WebViewCompat.addWebMessageListener(
				webView,
				ISOLATED_TRANSPORT_BRIDGE_NAME,
				sessionOriginPolicy.documentOrigins,
				world,
				isolatedListener,
			)
			val scriptHandler = WebViewCompat.addJavaScriptOnEvent(
				webView,
				"window.$ISOLATED_TRANSPORT_BRIDGE_NAME?.postMessage(JSON.stringify({type:'isolated-ready'}));",
				WebViewCompat.INJECTION_EVENT_DOCUMENT_START,
				sessionOriginPolicy.documentOrigins,
				world,
			)
			IsolatedWorldInstallation(world, scriptHandler)
		} else null
		WebViewCompat.addWebMessageListener(
			webView,
			TRANSPORT_BRIDGE_NAME,
			sessionOriginPolicy.documentOrigins,
			bridge,
		)
		return BrowserMessageBridgeInstallation(bridge, isolatedWorld)
	}

	private data class IsolatedWorldInstallation(
		val world: JavaScriptExecutionWorld,
		val scriptHandler: ScriptHandler,
	)

	private data class BrowserMessageBridgeInstallation(
		val bridge: BrowserMessageBridge,
		val isolatedWorld: IsolatedWorldInstallation?,
	) {
		@MainThread
		fun remove(webView: WebView) {
			bridge.clearIsolatedExecutor()
			WebViewCompat.removeWebMessageListener(webView, TRANSPORT_BRIDGE_NAME)
			isolatedWorld?.let { installation ->
				WebViewCompat.removeWebMessageListener(
					webView,
					installation.world,
					ISOLATED_TRANSPORT_BRIDGE_NAME,
				)
				installation.scriptHandler.remove()
			}
		}
	}

	private data class BrowserTransportInstallation(
		val fetchScriptHandler: ScriptHandler?,
		val messageBridge: BrowserMessageBridgeInstallation?,
	) {
		@MainThread
		fun remove(webView: WebView) {
			messageBridge?.remove(webView)
			fetchScriptHandler?.remove()
		}
	}

	private suspend fun fetchViaWebMessage(
		webView: WebView,
		bridge: BrowserMessageBridge?,
		originPolicy: BrowserOriginPolicy,
		url: String,
		fallbackUrl: String,
		method: String,
		body: String?,
		headers: Map<String, String>,
		executionWorld: BrowserFetchExecutionWorld,
	): String? {
		bridge ?: return null
		url.toHttpUrlOrNull() ?: return null
		val result = CompletableDeferred<String>()
		val requestId = UUID.randomUUID().toString()
		val generation = UUID.randomUUID().toString()
		bridge.register(requestId, generation, originPolicy, result)
		try {
			val jsHeaders = JSONObject(headers).toString()
			val jsBody = body?.let(JSONObject::quote) ?: "undefined"
			fun fetchScript(bridgeName: String) = """
				(async () => {
				  const id = ${JSONObject.quote(requestId)};
				  const generation = ${JSONObject.quote(generation)};
				  const controllers = window.__kototoroFetchControllers || (window.__kototoroFetchControllers = {});
				  const controller = new AbortController();
				  controllers[id] = controller;
				  const post = value => window[${JSONObject.quote(bridgeName)}]?.postMessage(JSON.stringify(value));
				  const executeFetch = window.__kototoroFetchExecutor?.fetch || window.fetch.bind(window);
				  try {
				    const response = await executeFetch(${JSONObject.quote(url)}, {
				      method: ${JSONObject.quote(method)}, credentials: 'include', headers: $jsHeaders,
				      body: $jsBody, signal: controller.signal
				    });
				    if (Number(response.headers.get('content-length') || 0) > $MAX_BROWSER_TEXT_BYTES) {
				      throw new Error('Browser response exceeds text transport limit');
				    }
				    const responseHeaders = {};
				    response.headers.forEach((value, key) => { responseHeaders[key] = value; });
				    const body = await response.text();
				    if (body.length > $MAX_BROWSER_TEXT_BYTES) throw new Error('Browser response exceeds text transport limit');
				    post({
				      id, generation, ok: response.ok, status: response.status,
				      statusText: response.statusText || '', url: response.url || ${JSONObject.quote(fallbackUrl)},
				      headers: responseHeaders, body
				    });
				  } catch (e) {
				    post({ id, generation, ok: false, status: 0, error: String(e) });
				  } finally {
				    delete controllers[id];
				  }
				})();
			""".trimIndent()
			if (executionWorld == BrowserFetchExecutionWorld.PAGE ||
				!bridge.executeInIsolatedWorld(fetchScript(ISOLATED_TRANSPORT_BRIDGE_NAME))
			) {
				Log.d(TAG, "Browser RPC executing in page world: request=$requestId url=$url")
				webView.evaluateJavascript(fetchScript(TRANSPORT_BRIDGE_NAME), null)
			} else {
				Log.d(TAG, "Browser RPC executing in isolated world: request=$requestId url=$url")
			}
			return withTimeoutOrNull(WEB_MESSAGE_RESPONSE_TIMEOUT_MS) {
				result.await()
			} ?: run {
				Log.w(TAG, "WebMessage fetch response timed out; falling back to JS polling: $url")
				null
			}
		} finally {
			val abortScript =
				"window.__kototoroFetchControllers?.[${JSONObject.quote(requestId)}]?.abort(); " +
					"delete window.__kototoroFetchControllers?.[${JSONObject.quote(requestId)}];"
			if (executionWorld == BrowserFetchExecutionWorld.PAGE || !bridge.executeInIsolatedWorld(abortScript)) {
				webView.evaluateJavascript(abortScript, null)
			}
			bridge.unregister(requestId, result)
		}
	}

	private fun logBrowserRpcRequest(
		method: String,
		headers: Map<String, String>,
		body: String?,
		executionWorld: BrowserFetchExecutionWorld,
	) {
		val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
		Log.d(
			TAG,
			"Browser RPC request: world=$executionWorld method=$method " +
				"headerNames=${headers.keys.sortedBy(String::lowercase)} " +
				"contentType=${contentType ?: "<none>"} bodyLength=${body?.length ?: 0}",
		)
	}

	private fun shouldObserveBrowserApiRequest(originPolicy: BrowserOriginPolicy, url: String): Boolean {
		val parsed = url.toHttpUrlOrNull() ?: return false
		return parsed.encodedPath.startsWith("/api/") && originPolicy.allowsFetch(url)
	}

	private fun logObservedBrowserApiRequest(sequence: Int, request: WebResourceRequest) {
		val headers = request.requestHeaders.orEmpty()
		val contentType = headers.entries
			.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
			?.value
		Log.d(
			TAG,
			"Observed browser API request: sequence=$sequence method=${request.method} " +
				"isMainFrame=${request.isForMainFrame} headerNames=${headers.keys.sortedBy(String::lowercase)} " +
				"contentType=${contentType ?: "<none>"} url=${request.url.toString().take(240)}",
		)
	}

	private fun logBrowserRpcResponse(
		executionWorld: BrowserFetchExecutionWorld,
		response: JSONObject,
		isCloudflare: Boolean,
	) {
		val responseHeaders = response.optJSONObject("headers")
		Log.d(
			TAG,
			"Browser RPC response: world=$executionWorld status=${response.optInt("status")} " +
				"cfMitigated=${responseHeaders?.optString("cf-mitigated").orEmpty().ifBlank { "<none>" }} " +
				"isCF=$isCloudflare url=${response.optString("url").take(240)}",
		)
	}

	private fun isCloudflareChallenge(status: Int, body: String, headers: JSONObject?): Boolean {
		val isManagedChallenge = headers?.optString("cf-mitigated").equals("challenge", ignoreCase = true)
		return (status == 403 || status == 503) &&
			(body.contains("cf-browser-verification") ||
				body.contains("Just a moment") ||
				body.contains("__cf_chl_opt") ||
				body.contains("challenge-platform") ||
				body.contains("turnstile") ||
				body.contains("Adscore") ||
				isManagedChallenge)
	}

	private enum class BrowserFetchExecutionWorld {
		PREFER_ISOLATED,
		PAGE,
	}

	private suspend fun fetchViaJavascriptPolling(
		webView: WebView,
		url: String,
		fallbackUrl: String,
		method: String,
		body: String?,
		headers: Map<String, String>,
	): String {
		val requestId = UUID.randomUUID().toString()
		val jsHeaders = JSONObject(headers).toString()
		val jsBody = body?.let(JSONObject::quote) ?: "undefined"
		val script = """
			window.__kototoroPollingResults = window.__kototoroPollingResults || {};
			window.__kototoroPollingControllers = window.__kototoroPollingControllers || {};
			(async () => {
			  const id = ${JSONObject.quote(requestId)};
			  const controller = new AbortController();
			  window.__kototoroPollingControllers[id] = controller;
			  try {
			    const executeFetch = window.__kototoroFetchExecutor?.fetch || window.fetch.bind(window);
			    const response = await executeFetch(${JSONObject.quote(url)}, {
			      method: ${JSONObject.quote(method)}, credentials: 'include', headers: $jsHeaders, body: $jsBody,
			      signal: controller.signal
			    });
			    const responseHeaders = {};
			    response.headers.forEach((value, key) => { responseHeaders[key] = value; });
			    window.__kototoroPollingResults[id] = JSON.stringify({ ok: response.ok, status: response.status,
			      statusText: response.statusText || '', url: response.url || ${JSONObject.quote(fallbackUrl)},
			      headers: responseHeaders, body: await response.text() });
			  } catch (e) {
			    window.__kototoroPollingResults[id] = JSON.stringify({ ok: false, status: 0, error: String(e) });
			  } finally {
			    delete window.__kototoroPollingControllers[id];
			  }
			})();
		""".trimIndent()
		webView.evaluateJavascript(script, null)
		try {
			while (kotlinx.coroutines.currentCoroutineContext().isActive) {
				val value = suspendCancellableCoroutine<String> { cont ->
					webView.evaluateJavascript(
						"window.__kototoroPollingResults?.[${JSONObject.quote(requestId)}] ?? null",
					) { raw ->
						if (cont.isActive) cont.resume(decodeJavascriptString(raw))
					}
				}
				if (value.isNotBlank() && value != "null") {
					return value
				}
				kotlinx.coroutines.delay(100)
			}
			return ""
		} finally {
			webView.evaluateJavascript(
				"window.__kototoroPollingControllers?.[${JSONObject.quote(requestId)}]?.abort(); " +
					"delete window.__kototoroPollingControllers?.[${JSONObject.quote(requestId)}]; " +
					"delete window.__kototoroPollingResults?.[${JSONObject.quote(requestId)}];",
				null,
			)
		}
	}

	private class BrowserMessageBridge(
		private val sessionOriginPolicy: BrowserOriginPolicy,
		private val currentRendererEpoch: () -> Long,
		private val currentNavigationEpoch: () -> Long,
	) : WebViewCompat.WebMessageListener {
		private data class PendingRequest(
			val generation: String,
			val rendererEpoch: Long,
			val navigationEpoch: Long,
			val originPolicy: BrowserOriginPolicy,
			val result: CompletableDeferred<String>,
		)

		private val pending = ConcurrentHashMap<String, PendingRequest>()
		@Volatile private var isolatedExecutor: IsolatedExecutor? = null

		private data class IsolatedExecutor(
			val replyProxy: JavaScriptReplyProxy,
			val rendererEpoch: Long,
			val navigationEpoch: Long,
		)

		fun executeInIsolatedWorld(script: String): Boolean {
			val executor = isolatedExecutor ?: return false
			if (executor.rendererEpoch != currentRendererEpoch() ||
				executor.navigationEpoch != currentNavigationEpoch()
			) {
				isolatedExecutor = null
				return false
			}
			executor.replyProxy.executeJavaScript(
				script,
				object : WebViewOutcomeReceiver<String, JavaScriptExecutionException> {
					override fun onResult(result: String) = Unit

					override fun onError(error: JavaScriptExecutionException) {
						Log.w(TAG, "Isolated-world JavaScript execution failed", error)
					}
				},
			)
			return true
		}

		fun clearIsolatedExecutor() {
			isolatedExecutor = null
		}

		fun register(
			requestId: String,
			generation: String,
			originPolicy: BrowserOriginPolicy,
			result: CompletableDeferred<String>,
		) {
			pending[requestId] = PendingRequest(
				generation = generation,
				rendererEpoch = currentRendererEpoch(),
				navigationEpoch = currentNavigationEpoch(),
				originPolicy = originPolicy,
				result = result,
			)
		}

		fun unregister(requestId: String, result: CompletableDeferred<String>) {
			if (pending[requestId]?.result === result) pending.remove(requestId)
		}

		fun failAll(cause: Throwable) {
			pending.values.forEach { request ->
				if (request.result.isActive) request.result.completeExceptionally(cause)
			}
			pending.clear()
		}

			override fun onPostMessage(
				view: WebView,
			message: WebMessageCompat,
			sourceOrigin: Uri,
			isMainFrame: Boolean,
				replyProxy: JavaScriptReplyProxy,
			) {
				handleMessage(message, sourceOrigin, isMainFrame, replyProxy, acceptIsolatedReady = false)
			}

		fun onIsolatedPostMessage(
			message: WebMessageCompat,
				sourceOrigin: Uri,
				isMainFrame: Boolean,
				replyProxy: JavaScriptReplyProxy,
			) {
				handleMessage(message, sourceOrigin, isMainFrame, replyProxy, acceptIsolatedReady = true)
			}

			private fun handleMessage(
				message: WebMessageCompat,
				sourceOrigin: Uri,
				isMainFrame: Boolean,
				replyProxy: JavaScriptReplyProxy,
				acceptIsolatedReady: Boolean,
			) {
			if (!isMainFrame || !sessionOriginPolicy.allowsDocument(sourceOrigin.toString())) return
				val payload = message.data ?: return
				val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
				if (json.optString("type") == "isolated-ready") {
					if (!acceptIsolatedReady) return
					isolatedExecutor = IsolatedExecutor(
						replyProxy = replyProxy,
						rendererEpoch = currentRendererEpoch(),
						navigationEpoch = currentNavigationEpoch(),
					)
					Log.d(
						TAG,
						"Browser isolated RPC ready: origin=$sourceOrigin navigationEpoch=${currentNavigationEpoch()}",
					)
					return
				}
			val requestId = json.optString("id")
			val request = pending[requestId] ?: return
			if (json.optString("generation") != request.generation) return
			if (request.rendererEpoch != currentRendererEpoch()) return
			if (request.navigationEpoch != currentNavigationEpoch()) return
			if (json.optBoolean("ok") && !request.originPolicy.allowsRedirect(json.optString("url"))) return
			if (request.result.isActive) request.result.complete(payload)
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

	@MainThread
	private suspend fun awaitBrowserDocumentReady(
		webView: WebView,
		maxWaitMs: Long,
		quietWindowMs: Long,
	) {
		val tracker = BrowserDocumentReadinessTracker(quietWindowMs)
		val startedAt = System.currentTimeMillis()
		var lastState = CloudFlarePageState.WAIT
		var lastReadyState = ""
		var lastUrl = webView.url.orEmpty()
		var lastResourceCount = 0
		val ready = withTimeoutOrNull(maxWaitMs) {
			while (kotlinx.coroutines.currentCoroutineContext().isActive) {
				val raw = suspendCancellableCoroutine<String> { cont ->
					webView.evaluateJavascript(
						"""(function() {
							return JSON.stringify({
								cfState: $CF_STATE_JS,
								readyState: document.readyState || '',
								href: location.href || '',
								resourceCount: performance.getEntriesByType('resource').length
							});
						})()""",
					) { result ->
						if (cont.isActive) cont.resume(decodeJavascriptString(result))
					}
				}
				val state = runCatching { JSONObject(raw) }.getOrNull()
				lastState = parseCloudFlarePageState(state?.optString("cfState")?.let(JSONObject::quote))
				lastReadyState = state?.optString("readyState").orEmpty()
				lastUrl = state?.optString("href").orEmpty().ifBlank { webView.url.orEmpty() }
				lastResourceCount = state?.optInt("resourceCount") ?: 0
				if (tracker.observe(
						pageState = lastState,
						readyState = lastReadyState,
						url = lastUrl,
						resourceCount = lastResourceCount,
						nowMs = System.currentTimeMillis(),
					)
				) {
					return@withTimeoutOrNull true
				}
				kotlinx.coroutines.delay(BROWSER_DOCUMENT_READY_POLL_MS)
			}
			false
		} == true
		Log.i(
			TAG,
			"Browser document readiness: ready=$ready durationMs=${System.currentTimeMillis() - startedAt} " +
				"state=$lastState readyState=$lastReadyState resources=$lastResourceCount url=$lastUrl",
		)
		if (!ready) {
			throw BrowserPageLoadException(
				"Browser origin did not become stable: state=$lastState readyState=$lastReadyState " +
					"resources=$lastResourceCount url=$lastUrl",
			)
		}
	}

	private suspend fun tryNavigationFetchFallback(
		webView: WebView,
		url: String,
		headers: Map<String, String>,
		method: String,
		allowAutomaticChallengeResolve: Boolean = true,
	): BrowserFetchResult? {
		if (method != "GET") return null
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
		if (isCloudflare && allowAutomaticChallengeResolve) {
			val challengeHeaders = Headers.Builder().apply {
				headers.forEach { (name, value) ->
					if (name.isNotBlank() && value.isNotBlank()) add(name, value)
				}
			}.build()
			val resolved = runCatching {
				resolveCaptchaAutomatically(
					CloudFlareProtectedException(
						url = url,
						source = UnknownContentSource,
						headers = challengeHeaders,
					),
					DEFAULT_CAPTCHA_TIMEOUT_MS,
				) == CaptchaAutoResolveResult.SOLVED
			}.onFailure { error ->
				Log.w(TAG, "Automatic API challenge resolve failed: $url", error)
			}.getOrDefault(false)
			if (resolved) {
				Log.i(TAG, "Automatic API challenge resolved; retrying in the same browser session: $url")
				return tryNavigationFetchFallback(
					webView = webView,
					url = url,
					headers = headers,
					method = method,
					allowAutomaticChallengeResolve = false,
				)
			}
		}
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

    suspend fun tryResolveCaptcha(exception: CloudFlareException, timeout: Long): Boolean =
        resolveCaptchaAutomatically(exception, timeout) == CaptchaAutoResolveResult.SOLVED

	suspend fun resolveCaptchaAutomatically(
        exception: CloudFlareException,
        timeout: Long,
    ): CaptchaAutoResolveResult {
        val cooldownHost = runCatching { URI(exception.url).host?.lowercase() }.getOrNull()
        if (cooldownHost != null) {
            val now = System.currentTimeMillis()
            val skipUntil = recentFailureUntil[cooldownHost]
            if (skipUntil != null) {
                if (skipUntil > now) {
                    Log.d(TAG, "Skipping captcha auto-resolve for $cooldownHost (cooled down for ${skipUntil - now}ms)")
                    return CaptchaAutoResolveResult.COOLDOWN
                }
                recentFailureUntil.remove(cooldownHost)
            }
        }
        val captchaMutex = captchaMutexes.getOrPut(cooldownHost ?: exception.url) { Mutex() }
        val result = captchaMutex.withLock {
            if (cooldownHost != null) {
                val skipUntil = recentFailureUntil[cooldownHost]
                if (skipUntil != null && skipUntil > System.currentTimeMillis()) {
                    return@withLock CaptchaAutoResolveResult.COOLDOWN
                }
            }
            exception.url.toHttpUrlOrNull()?.let { challengeUrl ->
                withContext(Dispatchers.IO) {
                    cookieJar.removeCookies(challengeUrl) { cookie ->
                        cookie.name == CF_CLEARANCE_COOKIE
                    }
                }
            }
            runCatchingCancellable { proxyProvider.applyWebViewConfig() }.onFailure { it.printStackTraceDebug() }
            withContext(Dispatchers.Main.immediate) {
                val activity = foregroundActivityHolder.current
                val webView: WebView
                val host: ViewGroup?
                val isThrowaway: Boolean
                if (activity != null) {
                    webView = WebView(activity).apply { configureForParser(null) }
                    host = attachToHost(webView, activity)
                    isThrowaway = true
                } else {
                    webView = WebView(context).apply { configureForParser(null) }
                    host = null
                    isThrowaway = true
                }
                try {
                    val protectedHeaders = (exception as? CloudFlareProtectedException)?.headers
                    val userAgent = protectedHeaders?.get(CommonHeaders.USER_AGENT)
                        ?: exception.source.getUserAgent()
                    userAgent?.let {
                        webView.settings.userAgentString = it
                    }
                    val requestHeaders = protectedHeaders?.toCloudFlareWebViewHeaders().orEmpty()
                    val useInterception = shouldUseCloudFlareInterception(exception.source)
                    val resolved = withTimeoutOrNull(timeout) {
                        suspendCancellableCoroutine { cont ->
                            webView.webViewClient = createCloudFlareClient(
                                webView = webView,
                                exception = exception,
                                continuation = cont,
                                useInterception = useInterception,
                            )
                            if (requestHeaders.isEmpty()) {
                                webView.loadUrl(exception.url)
                            } else {
                                webView.loadUrl(exception.url, requestHeaders)
                            }
                        }
                    }
                    if (resolved == null) {
                        Log.w(TAG, "Captcha auto-resolve timed out")
                    }
                    resolved ?: CaptchaAutoResolveResult.TIMED_OUT
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    exception.addSuppressed(e)
                    e.printStackTraceDebug()
                    CaptchaAutoResolveResult.FAILED
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
            if (result == CaptchaAutoResolveResult.SOLVED) {
                recentFailureUntil.remove(cooldownHost)
            } else {
                recentFailureUntil[cooldownHost] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
            }
        }
        return result
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
	private suspend fun obtainBrowserSession(host: String): BrowserSessionRecord = browserSessionCreationMutex.withLock {
		browserSessionCache[host]?.let { return@withLock it }
		ensureBrowserSessionProfile(host)
		if (browserSessionCache.size >= BROWSER_SESSION_CACHE_SIZE) {
			val eldest = browserSessionCache.entries.firstOrNull { it.value.canEvict }
			if (eldest != null) {
				browserSessionCache.remove(eldest.key)
				discardWebView(eldest.value.webView)
			}
		}
		val webView = WebView(context).also { webView ->
			configureBrowserSession(webView)
			proxyProvider.applyWebViewConfig()
			webView.onResume()
			webView.resumeTimers()
		}
		return@withLock BrowserSessionRecord(
			origin = host,
			webView = webView,
			provider = WebViewCompat.getCurrentWebViewPackage(context)?.let { "${it.packageName}/${it.versionName}" },
		).also { session ->
			val sessionOriginPolicy = checkNotNull(BrowserOriginPolicy.create(session.origin))
			session.transportInstallation = BrowserTransportInstallation(
				fetchScriptHandler = installBrowserFetchExecutor(webView, sessionOriginPolicy),
				messageBridge = installBrowserMessageBridge(webView, session, sessionOriginPolicy),
			)
			WebViewCompat.setWebViewRenderProcessClient(webView, object : WebViewRenderProcessClient() {
				override fun onRenderProcessUnresponsive(view: WebView, renderer: androidx.webkit.WebViewRenderProcess?) {
					session.unresponsiveAt = System.currentTimeMillis()
					Log.w(TAG, "BrowserSession renderer unresponsive: id=${session.sessionId} origin=$host pending=${session.pendingOperations}")
				}

				override fun onRenderProcessResponsive(view: WebView, renderer: androidx.webkit.WebViewRenderProcess?) {
					val started = session.unresponsiveAt
					session.unresponsiveAt = null
					Log.i(TAG, "BrowserSession renderer responsive: id=${session.sessionId} origin=$host durationMs=${started?.let { System.currentTimeMillis() - it } ?: 0}")
				}
			})
			browserSessionCache[host] = session
			browserSessionManager.register(session.sessionId, webView)
			Log.i(TAG, "BrowserSession created: id=${session.sessionId} origin=$host provider=${session.provider}")
		}
	}

	@MainThread
	private suspend fun ensureBrowserSessionProfile(origin: String) {
		val storedVersion = browserSessionProfileStore.storedVersion()
		if (!BrowserSessionProfileStore.requiresMigration(storedVersion, BROWSER_SESSION_PROFILE_VERSION)) {
			return
		}
		val originUrl = origin.toHttpUrlOrNull() ?: return
		val hadClearance = hasCookie(CookieManager.getInstance().getCookie(origin), CF_CLEARANCE_COOKIE) ||
			withContext(Dispatchers.IO) {
				cookieJar.loadForRequest(originUrl).any { it.name == CF_CLEARANCE_COOKIE }
			}

		withContext(Dispatchers.IO) { cookieJar.clear() }
		CookieManager.getInstance().flush()

		val cleared = !CookieManager.getInstance().hasCookies() &&
			!hasCookie(CookieManager.getInstance().getCookie(origin), CF_CLEARANCE_COOKIE) &&
			withContext(Dispatchers.IO) {
			cookieJar.loadForRequest(originUrl).none { it.name == CF_CLEARANCE_COOKIE }
		}
		val marked = cleared && withContext(Dispatchers.IO) {
			browserSessionProfileStore.markMigrated(BROWSER_SESSION_PROFILE_VERSION)
		}
		Log.i(
			TAG,
			"BrowserSession global profile migration: triggerOrigin=$origin from=$storedVersion " +
				"to=$BROWSER_SESSION_PROFILE_VERSION hadClearance=$hadClearance " +
				"cleared=$cleared marked=$marked",
		)
	}

	private fun hasCookie(rawCookies: String?, name: String): Boolean = rawCookies
		.orEmpty()
		.split(';')
		.any { it.substringBefore('=').trim() == name }

	@MainThread
	private fun trimBrowserSessionCache() {
		while (browserSessionCache.size > BROWSER_SESSION_CACHE_SIZE) {
			val eldest = browserSessionCache.entries.firstOrNull { it.value.canEvict } ?: return
			browserSessionCache.remove(eldest.key)
			discardWebView(eldest.value.webView)
		}
	}

	@MainThread
	private fun discardWebView(webView: WebView?) {
		if (webView == null) return
		val session = browserSessionCache.values.firstOrNull { it.webView === webView }
		session?.apply {
			activeExecution?.takeUnless { it.isTerminal }?.transitionTo(BrowserExecutionState.FAILED)
			rendererEpoch++
			navigationEpoch++
			state = BrowserSessionState.POISONED
			Log.w(TAG, "BrowserSession poisoned: id=$sessionId origin=$origin rendererEpoch=$rendererEpoch")
		}
		browserSessionCache.entries.removeIf { it.value.webView === webView }
		session?.let { browserSessionManager.unregister(it.sessionId, webView) }
		if (webViewCached?.get() === webView) {
			webViewCached = null
		}
			runCatching {
				session?.transportInstallation?.remove(webView)
				session?.transportInstallation = null
				(webView.parent as? ViewGroup)?.removeView(webView)
			webView.stopLoading()
			webView.destroy()
		}.onFailure { error ->
			Log.w(TAG, "Failed to discard broken WebView", error)
		}
		session?.state = BrowserSessionState.DESTROYED
	}

	@MainThread
	private fun advanceNavigationEpoch(webView: WebView) {
		browserSessionCache.values.firstOrNull { it.webView === webView }?.navigationEpoch++
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
	private fun attachInteractiveToHost(
		webView: WebView,
		activity: android.app.Activity,
	): ViewGroup? {
		val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
		return runCatching {
			(webView.parent as? ViewGroup)?.removeView(webView)
			webView.alpha = 1f
			webView.visibility = View.VISIBLE
			webView.translationY = 0f
			content.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			content
		}.getOrElse {
			Log.w(TAG, "Unable to attach BrowserSession for interactive challenge", it)
			null
		}
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
        continuation: kotlin.coroutines.Continuation<CaptchaAutoResolveResult>,
        useInterception: Boolean,
    ): CloudFlareClient {
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        val resumeOnce: (CaptchaAutoResolveResult) -> Unit = { result ->
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
                if (clearance != null && clearance != initialClearance) {
                    resumeOnce(CaptchaAutoResolveResult.SOLVED)
                    return
                }
                webView.evaluateJavascript(CF_STATE_JS) { raw ->
                    if (finished) return@evaluateJavascript
                    val state = parseCloudFlarePageState(raw)
                    when (state) {
                        CloudFlarePageState.INTERACTIVE -> {
                            Log.i(TAG, "Interactive Cloudflare challenge detected; switching to visible resolver")
                            resumeOnce(CaptchaAutoResolveResult.INTERACTIVE_REQUIRED)
                        }
                        // A normal document can report OK without having solved the
                        // challenge. The old clearance was removed before loading,
                        // so only a newly issued clearance is a valid auto result.
                        CloudFlarePageState.OK -> {
                            if (clearance != null && clearance != initialClearance) {
                                resumeOnce(CaptchaAutoResolveResult.SOLVED)
                            } else if (System.currentTimeMillis() >= challengeDeadline) {
                                Log.w(TAG, "Captcha page is OK but no new clearance was issued")
                                resumeOnce(CaptchaAutoResolveResult.TIMED_OUT)
                            } else {
                                handler.removeCallbacks(this)
                                handler.postDelayed(this, CHALLENGE_POLL_INTERVAL_MS)
                            }
                        }
                        CloudFlarePageState.ERROR -> resumeOnce(CaptchaAutoResolveResult.HARD_BLOCKED)
                        else -> if (System.currentTimeMillis() >= challengeDeadline) {
                            Log.w(
                                TAG,
                                "Captcha auto-resolve deadline reached, " +
                                    "state=$state hasNewClearance=${clearance != null && clearance != initialClearance}",
                            )
                            resumeOnce(CaptchaAutoResolveResult.TIMED_OUT)
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
							Log.i(TAG, "Interactive challenge clearance observed in BrowserSession: url=${exception.url}")
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
			Log.w(TAG, "loginAndCheck failed: ${error::class.java.simpleName}")
		}.getOrDefault(false)
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
	private fun configureBrowserSession(webView: WebView) {
		with(webView.settings) {
			javaScriptEnabled = true
			domStorageEnabled = true
			cacheMode = WebSettings.LOAD_DEFAULT
			blockNetworkImage = false
		}
		CookieManager.getInstance().apply {
			setAcceptCookie(true)
			setAcceptThirdPartyCookies(webView, true)
		}
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
		const val DEFAULT_CAPTCHA_TIMEOUT_MS = 15_000L
		private const val TRANSPORT_BRIDGE_NAME = "KototoroTransport"
		private const val ISOLATED_TRANSPORT_BRIDGE_NAME = "KototoroIsolatedTransport"
		private const val MAX_BROWSER_TEXT_BYTES = 8 * 1024 * 1024
			private const val WEB_MESSAGE_RESPONSE_TIMEOUT_MS = 8_000L
			private const val BROWSER_DOCUMENT_READY_TIMEOUT_MS = 12_000L
			private const val BROWSER_DOCUMENT_QUIET_WINDOW_MS = 1_500L
			private const val BROWSER_DOCUMENT_READY_POLL_MS = 250L
		private const val BROWSER_SESSION_CACHE_SIZE = 3
		private const val BROWSER_SESSION_PROFILE_VERSION = 3
		private const val INTERACTIVE_VALIDATION_RETRY_MS = 2_000L
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

	private class BrowserPageLoadException(message: String) : IllegalStateException(message)

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
