package org.skepsun.kototoro.settings.sources.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.browser.BaseBrowserActivity
import org.skepsun.kototoro.browser.BrowserCallback
import org.skepsun.kototoro.browser.BrowserClient
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.ContentParserAuthProvider
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

@AndroidEntryPoint
class SourceAuthActivity : BaseBrowserActivity(), BrowserCallback {

	private lateinit var authProvider: ContentParserAuthProvider

	private var authCheckJob: Job? = null
	private var lastUsernameVerifyUptimeMs: Long = 0L

	override fun onCreate2(savedInstanceState: Bundle?, source: ContentSource, repository: ParserContentRepository?) {
		val authCandidate = repository?.getAuthProvider()
			?: (mangaRepositoryFactory.create(source) as? ContentParserAuthProvider)
		if (authCandidate == null) {
			Toast.makeText(
				this,
				getString(R.string.auth_not_supported_by, source.getTitle(this)),
				Toast.LENGTH_SHORT,
			).show()
			finishAfterTransition()
			return
		}
		authProvider = authCandidate
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		browserWebView.webViewClient = BrowserClient(this, adBlock)
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				Snackbar.make(browserWebView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			if (savedInstanceState == null) {
				val url = authProvider.authUrl
				onTitleChanged(
					source.getTitle(this@SourceAuthActivity),
					getString(R.string.loading_),
				)
				browserWebView.loadUrl(url)
			}
		}
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			browserWebView.stopLoading()
			setResult(RESULT_CANCELED)
			finishAfterTransition()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun onLoadingStateChanged(isLoading: Boolean) {
		super.onLoadingStateChanged(isLoading)
		if (isLoading) {
			return
		}
		val prevJob = authCheckJob
		authCheckJob = lifecycleScope.launch {
			prevJob?.join()
			val isAuthorized = runCatchingCancellable {
				authProvider.isAuthorized()
			}.getOrDefault(false)
			if (!isAuthorized) {
				return@launch
			}

			// isAuthorized() 在部分站点可能仅依赖 Cookie，存在误判风险；这里用 getUsername() 做一次更强校验。
			// 避免 WebView 频繁触发校验导致过多网络请求，做轻量节流。
			val now = SystemClock.elapsedRealtime()
			if (now - lastUsernameVerifyUptimeMs < 1500L) {
				return@launch
			}
			lastUsernameVerifyUptimeMs = now

			val isVerified = runCatchingCancellable { authProvider.getUsername() }
				.fold(
					onSuccess = { it.isNotBlank() },
					onFailure = { e -> e !is AuthRequiredException },
				)

			if (isVerified) {
				Toast.makeText(this@SourceAuthActivity, R.string.auth_complete, Toast.LENGTH_SHORT).show()
				setResult(RESULT_OK)
				finishAfterTransition()
			}
		}
	}

	class Contract : ActivityResultContract<ContentSource, Boolean>() {

		override fun createIntent(context: Context, input: ContentSource) = AppRouter.sourceAuthIntent(context, input)

		override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == RESULT_OK
	}

	companion object {
		const val TAG = "SourceAuthActivity"
	}
}
