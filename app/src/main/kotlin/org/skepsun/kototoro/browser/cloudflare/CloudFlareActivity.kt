package org.skepsun.kototoro.browser.cloudflare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.skepsun.kototoro.R
import org.skepsun.kototoro.browser.BaseBrowserActivity
import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.core.exceptions.resolve.CaptchaAutoResolveCoordinator
import org.skepsun.kototoro.core.exceptions.resolve.CaptchaHandler
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import javax.inject.Inject

@AndroidEntryPoint
class CloudFlareActivity : BaseBrowserActivity(), CloudFlareCallback {

	private var pendingResult = RESULT_CANCELED
	private val isHidden: Boolean by lazy { intent?.getBooleanExtra(EXTRA_HIDDEN, false) == true }
	private val isAutoResolve: Boolean by lazy { intent?.getBooleanExtra(EXTRA_AUTO_RESOLVE, false) == true }
	private var resultNotified = false
	private var hiddenTimeoutJob: Job? = null
	private var clearanceVerificationJob: Job? = null

	@Inject
	lateinit var cookieJar: MutableCookieJar

	@Inject
	@ContentHttpClient
	lateinit var okHttpClient: OkHttpClient

	@Inject
	lateinit var captchaHandler: CaptchaHandler

	@Inject
	lateinit var captchaAutoResolveCoordinator: CaptchaAutoResolveCoordinator

	private lateinit var cfClient: CloudFlareClient

	override fun onCreate2(savedInstanceState: Bundle?, source: ContentSource, repository: ParserContentRepository?) {
		if (isHidden) {
			supportActionBar?.hide()
			setBrowserToolbarVisible(false)
			setBrowserProgressVisible(false)
			setBrowserContentAlpha(0.01f)
			window.addFlags(
				WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
					WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
			)
			hiddenTimeoutJob = lifecycleScope.launch {
				delay(HIDDEN_TIMEOUT_MS)
				browserWebView.stopLoading()
				finishAfterTransition()
			}
		} else {
			setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		}
		val url = intent?.dataString
		if (url.isNullOrEmpty()) {
			finishAfterTransition()
			return
		}
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				showSnackbar(e.getDisplayMessage(resources))
			}
			cfClient = if (shouldUseInterception(repository)) {
				CloudFlareInterceptClient(cookieJar, this@CloudFlareActivity, adBlock, url)
			} else {
				CloudFlareClient(cookieJar, this@CloudFlareActivity, adBlock, url)
			}
			browserWebView.webViewClient = cfClient
			if (savedInstanceState == null) {
				onTitleChanged(getString(R.string.loading_), url)
				browserWebView.loadUrl(url)
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		if (isHidden) {
			return false
		}
		menuInflater.inflate(R.menu.opt_captcha, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			browserWebView.stopLoading()
			finishAfterTransition()
			true
		}

		R.id.action_retry -> {
			restartCheck()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun finish() {
		hiddenTimeoutJob?.cancel()
		setResult(pendingResult)
		if (isAutoResolve && !resultNotified) {
			resultNotified = true
			intent?.getStringExtra(AppRouter.KEY_SOURCE)?.let { sourceName ->
				captchaAutoResolveCoordinator.notifyResolveResult(
					org.skepsun.kototoro.core.model.ContentSource(sourceName),
					pendingResult == RESULT_OK,
				)
			}
		}
		super.finish()
	}

	override fun onLoadingStateChanged(isLoading: Boolean) = Unit

	override fun onPageLoaded() {
		if (!isHidden) {
			setBrowserProgressVisible(false)
		}
	}

	override fun onLoopDetected() {
		if (isHidden || isAutoResolve) {
			restartCheck()
		} else {
			cfClient.reset()
			android.util.Log.w(TAG, "Cloudflare loop detected; keeping manual browser open for user action")
		}
	}

	override fun onCheckPassed() {
		if (clearanceVerificationJob?.isActive == true) {
			return
		}
		clearanceVerificationJob = lifecycleScope.launch {
			val url = intent?.dataString
			if (url.isNullOrBlank() || !verifyClearance(url)) {
				if (!isHidden) {
					showSnackbar(getString(R.string.captcha_required_message))
				}
				return@launch
			}
			pendingResult = RESULT_OK
			val source = intent?.getStringExtra(AppRouter.KEY_SOURCE)
			if (source != null) {
				runCatchingCancellable {
					captchaHandler.discard(org.skepsun.kototoro.core.model.ContentSource(source))
				}.onFailure {
					it.printStackTraceDebug()
				}
			}
			finishAfterTransition()
		}
	}

	private fun showSnackbar(message: CharSequence) {
		if (isFinishing || isDestroyed || !browserWebView.isAttachedToWindow) {
			return
		}
		Snackbar.make(browserWebView, message, Snackbar.LENGTH_LONG).show()
	}

	private suspend fun verifyClearance(url: String): Boolean = runCatchingCancellable {
		runInterruptible(Dispatchers.IO) {
			val request = Request.Builder()
				.url(url)
				.get()
				.apply {
					intent?.getStringExtra(AppRouter.KEY_USER_AGENT)?.takeIf { it.isNotBlank() }?.let {
						header(CommonHeaders.USER_AGENT, it)
					}
					intent?.getStringExtra(AppRouter.KEY_SOURCE)?.takeIf { it.isNotBlank() }?.let {
						header(CommonHeaders.MANGA_SOURCE, it)
					}
				}
				.build()
			okHttpClient.newCall(request).execute().use { response ->
				val success = response.isSuccessful
				android.util.Log.i(
					TAG,
					"verify clearance: url=$url status=${response.code} success=$success " +
						"source=${intent?.getStringExtra(AppRouter.KEY_SOURCE)} " +
						"hasCfClearance=${url.toHttpUrlOrNull()?.let { httpUrl ->
							cookieJar.loadForRequest(httpUrl).any { it.name == "cf_clearance" }
						}}",
				)
				success
			}
		}
	}.onFailure { error ->
		android.util.Log.w(TAG, "verify clearance failed: url=$url", error)
	}.getOrDefault(false)

	override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
		setTitle(title)
		supportActionBar?.subtitle = subtitle?.toString()?.toHttpUrlOrNull()?.host.ifNullOrEmpty { subtitle }
	}

	private fun restartCheck() {
		lifecycleScope.launch {
			browserWebView.stopLoading()
			yield()
			cfClient.reset()
			val targetUrl = intent?.dataString?.toHttpUrlOrNull()
			if (targetUrl != null) {
				clearCfCookies(targetUrl)
			browserWebView.loadUrl(targetUrl.toString())
			}
		}
	}

	private suspend fun clearCfCookies(url: HttpUrl) = runInterruptible(Dispatchers.Default) {
		cookieJar.removeCookies(url) { cookie ->
			CloudFlareHelper.isCloudFlareCookie(cookie.name)
		}
	}

	private suspend fun shouldUseInterception(repository: ParserContentRepository?): Boolean {
		if (repository == null) {
			return false
		}
		val key = repository.getConfigKeys()
			.filterIsInstance<ConfigKey.InterceptCloudflare>()
			.firstOrNull()
			?: return false
		return repository.getConfig()[key]
	}

	class Contract : ActivityResultContract<CloudFlareProtectedException, Boolean>() {
		override fun createIntent(context: Context, input: CloudFlareProtectedException): Intent {
			return AppRouter.cloudFlareResolveIntent(context, input)
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
			return resultCode == RESULT_OK
		}
	}

	companion object {

		const val TAG = "CloudFlareActivity"
		const val EXTRA_HIDDEN = "hidden"
		const val EXTRA_AUTO_RESOLVE = "auto_resolve"
		private const val HIDDEN_TIMEOUT_MS = 45_000L
	}
}
