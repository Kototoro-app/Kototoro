package org.skepsun.kototoro.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import android.webkit.CookieManager
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.network.jsonsource.PersistentCookieJar
import org.skepsun.kototoro.core.network.jsonsource.LegadoHttpClient
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import org.skepsun.kototoro.core.javascript.BrowserVerificationBridge
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.ContentSource
import kotlin.coroutines.resume

@AndroidEntryPoint
class BrowserActivity : BaseBrowserActivity() {

    @javax.inject.Inject
    lateinit var persistentCookieJar: PersistentCookieJar

    @javax.inject.Inject
    lateinit var legadoHttpClient: LegadoHttpClient

	private var pendingResult = RESULT_CANCELED
	private var successCookieUrl: String? = null
	private var successCookieName: String? = null
	private var initialSuccessCookieValue: String? = null
    private var browserWaitToken: String? = null
    private var browserWaitCompleted = false
    private var initialHtml: String? = null
    private var refetchAfterSuccess: Boolean = true
    private var sawChallengePage = false
    private var autoSavingVerificationResult = false

	override fun onCreate2(savedInstanceState: Bundle?, source: ContentSource, repository: ParserContentRepository?) {
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		successCookieUrl = intent?.getStringExtra(AppRouter.KEY_SUCCESS_COOKIE_URL)
		successCookieName = intent?.getStringExtra(AppRouter.KEY_SUCCESS_COOKIE_NAME)
        browserWaitToken = intent?.getStringExtra(AppRouter.KEY_BROWSER_WAIT_TOKEN)
        initialHtml = intent?.getStringExtra(AppRouter.KEY_BROWSER_HTML)
        refetchAfterSuccess = intent?.getBooleanExtra(AppRouter.KEY_BROWSER_REFETCH_AFTER_SUCCESS, true) ?: true
        viewBinding.webView.webViewClient = BrowserClient(this, adBlock)
		initialSuccessCookieValue = getSuccessCookieValue()
		logCookieState("open", initialSuccessCookieValue)
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				e.printStackTraceDebug()
				Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			if (savedInstanceState == null) {
				val url = intent?.dataString
				if (url.isNullOrEmpty()) {
					finishAfterTransition()
				} else {
					onTitleChanged(
						intent?.getStringExtra(AppRouter.KEY_TITLE) ?: getString(R.string.loading_),
						url,
					)
					val html = initialHtml
					if (!html.isNullOrBlank()) {
						viewBinding.webView.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
					} else {
						viewBinding.webView.loadUrl(url)
					}
				}
			}
		}
	}

    override fun onLoadingStateChanged(isLoading: Boolean) {
        super.onLoadingStateChanged(isLoading)
        maybeCompleteAfterVerification()
    }

    override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
        super.onTitleChanged(title, subtitle)
        maybeCompleteAfterVerification()
    }

    override fun onPageFinished(webView: WebView, url: String) {
        syncCookiesToPersistentJar()
        if (browserWaitCompleted || autoSavingVerificationResult) {
            return
        }
        lifecycleScope.launch {
            val hasChallengeMarker = runCatching { detectChallengeMarker(webView) }.getOrDefault(false)
            if (hasChallengeMarker) {
                sawChallengePage = true
                return@launch
            }
            maybeAutoFinishAfterVerification()
        }
    }

	override fun finish() {
        if (browserWaitToken != null && !browserWaitCompleted) {
            browserWaitCompleted = true
            lifecycleScope.launch {
                completeBrowserWait()
                finish()
            }
            return
        }
        val currentValue = getSuccessCookieValue()
        logCookieState("finish", currentValue)
        pendingResult = if (isSuccessCookieSatisfied(currentValue)) RESULT_OK else RESULT_CANCELED
        setResult(pendingResult)
        super.finish()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		super.onCreateOptionsMenu(menu)
		menuInflater.inflate(R.menu.opt_browser, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			viewBinding.webView.stopLoading()
			finishAfterTransition()
			true
		}

		R.id.action_browser -> {
			if (!router.openExternalBrowser(viewBinding.webView.url.orEmpty(), item.title)) {
				Snackbar.make(viewBinding.webView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
			}
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	class Contract : ActivityResultContract<InteractiveActionRequiredException, Boolean>() {
		override fun createIntent(
			context: Context,
			input: InteractiveActionRequiredException
		): Intent = AppRouter.browserIntent(
			context = context,
			url = input.url,
			source = input.source,
			title = null,
		).apply {
			input.userAgent?.let {
				putExtra(AppRouter.KEY_USER_AGENT, it)
			}
			input.successCookieUrl?.let {
				putExtra(AppRouter.KEY_SUCCESS_COOKIE_URL, it)
			}
			input.successCookieName?.let {
				putExtra(AppRouter.KEY_SUCCESS_COOKIE_NAME, it)
			}
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == RESULT_OK
	}

	private fun isSuccessCookieSatisfied(currentValue: String? = null): Boolean {
		val cookieUrl = successCookieUrl ?: return true
		val cookieName = successCookieName ?: return true
		runCatching { CookieManager.getInstance().flush() }
		val resolvedCurrentValue = currentValue ?: getCookieValue(cookieUrl, cookieName)
		val isSatisfied = !resolvedCurrentValue.isNullOrEmpty() && resolvedCurrentValue != initialSuccessCookieValue
		android.util.Log.d(
			TAG,
			"success_check url=$cookieUrl cookie=$cookieName initial=${maskCookieValue(initialSuccessCookieValue)} current=${maskCookieValue(resolvedCurrentValue)} passed=$isSatisfied",
		)
		return isSatisfied
	}

	private fun getSuccessCookieValue(): String? {
		val cookieUrl = successCookieUrl ?: return null
		val cookieName = successCookieName ?: return null
		return getCookieValue(cookieUrl, cookieName)
	}

	private fun getCookieValue(url: String, name: String): String? {
		val raw = CookieManager.getInstance().getCookie(url) ?: return null
		return raw.split(';')
			.asSequence()
			.map { it.trim() }
			.firstOrNull { it.startsWith("$name=") }
			?.substringAfter('=')
			?.takeIf { it.isNotEmpty() }
	}

	private fun logCookieState(stage: String, cookieValue: String?) {
		val cookieUrl = successCookieUrl ?: return
		val cookieName = successCookieName ?: return
		val rawCookie = CookieManager.getInstance().getCookie(cookieUrl)
		android.util.Log.d(
			TAG,
			"cookie_state stage=$stage url=$cookieUrl cookie=$cookieName value=${maskCookieValue(cookieValue)} hasCookie=${!cookieValue.isNullOrEmpty()} rawHasCfClearance=${rawCookie?.contains("$cookieName=") == true}",
		)
	}

	private fun maskCookieValue(value: String?): String {
		if (value.isNullOrEmpty()) return "<empty>"
		return if (value.length <= 8) "***" else "${value.take(4)}...${value.takeLast(4)}"
	}

    private fun maybeCompleteAfterVerification() {
        lifecycleScope.launch {
            maybeAutoFinishAfterVerification()
        }
    }

    private suspend fun maybeAutoFinishAfterVerification() {
        if (browserWaitCompleted || autoSavingVerificationResult) return
        if (!hasSuccessCookieTarget()) return
        val currentValue = getSuccessCookieValue()
        if (!isSuccessCookieSatisfied(currentValue)) return
        logCookieState("auto_complete", currentValue)
        syncCookiesToPersistentJar()
        pendingResult = RESULT_OK
        autoSavingVerificationResult = true
        if (browserWaitToken != null) {
            browserWaitCompleted = true
            completeBrowserWait()
        }
        superFinishAfterVerification()
    }

    private fun shouldAutoCompleteVerification(): Boolean {
        if (browserWaitToken == null || !hasSuccessCookieTarget()) {
            return false
        }
        return sawChallengePage || isSuccessCookieSatisfied()
    }

    private fun hasSuccessCookieTarget(): Boolean {
        return !successCookieUrl.isNullOrBlank() && !successCookieName.isNullOrBlank()
    }

    private fun syncCookiesToPersistentJar() {
        val url = viewBinding.webView.url ?: successCookieUrl ?: return
        val raw = CookieManager.getInstance().getCookie(url) ?: return
        val parsed = raw.split(";")
            .mapNotNull { part ->
                val pieces = part.trim().split("=", limit = 2)
                if (pieces.size != 2) return@mapNotNull null
                runCatching {
                    okhttp3.Cookie.Builder()
                        .name(pieces[0].trim())
                        .value(pieces[1].trim())
                        .domain(org.skepsun.kototoro.core.parser.legado.LegadoNetworkUtils.getSubDomain(url))
                        .path("/")
                        .build()
                }.getOrNull()
            }
        if (parsed.isNotEmpty()) {
            persistentCookieJar.setCookies(url, parsed)
        }
    }

    private fun superFinishAfterVerification() {
        setResult(pendingResult)
        super.finish()
    }

    private suspend fun completeBrowserWait() {
        val token = browserWaitToken ?: return
        val url = viewBinding.webView.url.orEmpty().ifBlank { intent?.dataString.orEmpty() }
        val html = when {
            refetchAfterSuccess -> {
                runCatching { refetchHtml(url) }
                    .getOrElse { runCatching { captureHtml(viewBinding.webView) }.getOrDefault("") }
            }
            else -> runCatching { captureHtml(viewBinding.webView) }.getOrDefault("")
        }
        BrowserVerificationBridge.complete(
            token,
            BrowserVerificationBridge.Result(
                url = url,
                html = html,
            ),
        )
    }

    private suspend fun captureHtml(webView: WebView): String {
        return suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''") { result ->
                val html = result
                    ?.removeSurrounding("\"")
                    ?.replace("\\u003C", "<")
                    ?.replace("\\u003E", ">")
                    ?.replace("\\n", "\n")
                    ?.replace("\\t", "\t")
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    .orEmpty()
                cont.resume(html)
            }
        }
    }

    private suspend fun detectChallengeMarker(webView: WebView): Boolean {
        return suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(
                """
                (function() {
                    try {
                        return !!window._cf_chl_opt
                            || document.documentElement.outerHTML.indexOf('cf-browser-verification') >= 0
                            || document.documentElement.outerHTML.indexOf('__cf_chl_opt') >= 0
                            || document.documentElement.outerHTML.indexOf('turnstile') >= 0;
                    } catch (e) {
                        return false;
                    }
                })()
                """.trimIndent(),
            ) { result ->
                cont.resume(result == "true")
            }
        }
    }

    private suspend fun refetchHtml(url: String): String {
        if (url.isBlank()) return ""
        val headers = buildMap<String, String> {
            val ua = intent?.getStringExtra(AppRouter.KEY_USER_AGENT)
            if (!ua.isNullOrBlank()) {
                put(CommonHeaders.USER_AGENT, ua)
            }
        }
        return withContext(Dispatchers.IO) {
            legadoHttpClient.get(url, headers).use { it.body?.string().orEmpty() }
        }
    }

	companion object {

		const val TAG = "BrowserActivity"
	}
}
