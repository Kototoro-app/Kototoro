package org.skepsun.kototoro.browser

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.core.network.proxy.ProxyProvider
import org.skepsun.kototoro.core.network.webview.adblock.AdBlock
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.configureForParser
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.nullIfEmpty
import javax.inject.Inject

@AndroidEntryPoint
abstract class BaseBrowserActivity : BaseComposeActivity(), BrowserCallback {

	@Inject
	lateinit var proxyProvider: ProxyProvider

	@Inject
	lateinit var mangaRepositoryFactory: ContentRepository.Factory

	@Inject
	lateinit var adBlock: AdBlock

	private lateinit var onBackPressedCallback: WebViewBackPressedCallback
	protected lateinit var browserWebView: WebView

	private var isBrowserLoading by mutableStateOf(false)
	private var shouldShowBrowserToolbar by mutableStateOf(true)
	private var webViewContentAlpha by mutableFloatStateOf(1f)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		try {
			browserWebView = WebView(this)
		} catch (e: Exception) {
			if (e is android.util.AndroidException || e.cause is android.util.AndroidException) {
				android.widget.Toast.makeText(this, org.skepsun.kototoro.R.string.web_view_unavailable, android.widget.Toast.LENGTH_LONG).show()
				finishAfterTransition()
				return
			}
			throw e
		}
		setComposeContent {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.windowInsetsPadding(WindowInsets.statusBars),
			) {
				if (shouldShowBrowserToolbar) {
					AndroidView(
						factory = { browserToolbar },
						modifier = Modifier
							.fillMaxWidth()
							.height(64.dp),
					)
				}
				Box(modifier = Modifier.fillMaxSize()) {
					AndroidView(
						factory = { browserWebView },
						modifier = Modifier.fillMaxSize(),
					)
					if (isBrowserLoading) {
						LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
					}
				}
			}
		}
		setSupportActionBar(browserToolbar)
		browserWebView.alpha = webViewContentAlpha
		browserWebView.webViewClient = android.webkit.WebViewClient()
		browserWebView.webChromeClient = android.webkit.WebChromeClient()
		onBackPressedCallback = WebViewBackPressedCallback(browserWebView)
		onBackPressedDispatcher.addCallback(onBackPressedCallback)

		val mangaSource = ContentSource(intent?.getStringExtra(AppRouter.KEY_SOURCE))
		val repository = mangaRepositoryFactory.create(mangaSource) as? ParserContentRepository
		val userAgent = intent?.getStringExtra(AppRouter.KEY_USER_AGENT)?.nullIfEmpty()
			?: repository?.getRequestHeaders()?.get(CommonHeaders.USER_AGENT)
		browserWebView.configureForParser(userAgent)

		onCreate2(savedInstanceState, mangaSource, repository)
	}

	private val browserToolbar by lazy { com.google.android.material.appbar.MaterialToolbar(this) }

	protected fun setBrowserToolbarVisible(visible: Boolean) {
		shouldShowBrowserToolbar = visible
	}

	protected fun setBrowserContentAlpha(alpha: Float) {
		webViewContentAlpha = alpha
		if (::browserWebView.isInitialized) {
			browserWebView.alpha = alpha
		}
	}

	protected fun setBrowserProgressVisible(visible: Boolean) {
		isBrowserLoading = visible
	}

	protected fun setDisplayHomeAsUp(isEnabled: Boolean, showUpAsClose: Boolean) {
		supportActionBar?.run {
			setDisplayHomeAsUpEnabled(isEnabled)
			if (showUpAsClose) {
				setHomeAsUpIndicator(androidx.appcompat.R.drawable.abc_ic_clear_material)
			}
		}
	}

	protected abstract fun onCreate2(
		savedInstanceState: Bundle?,
		source: ContentSource,
		repository: ParserContentRepository?
	)

	override fun onPause() {
		if (::browserWebView.isInitialized) {
			browserWebView.onPause()
		}
		super.onPause()
	}

	override fun onResume() {
		super.onResume()
		if (::browserWebView.isInitialized) {
			browserWebView.onResume()
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		if (::browserWebView.isInitialized) {
			with(browserWebView) {
				stopLoading()
				loadUrl("about:blank")
				onPause()
				clearHistory()
				removeAllViews()
				(parent as? ViewGroup)?.removeView(this)
				destroy()
			}
		}
	}

	override fun onLoadingStateChanged(isLoading: Boolean) {
		isBrowserLoading = isLoading
	}

	override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
		this.title = title
		supportActionBar?.subtitle = subtitle
	}

	override fun onPageFinished(webView: android.webkit.WebView, url: String) = Unit

	override fun onHistoryChanged() {
		onBackPressedCallback.onHistoryChanged()
	}
}
