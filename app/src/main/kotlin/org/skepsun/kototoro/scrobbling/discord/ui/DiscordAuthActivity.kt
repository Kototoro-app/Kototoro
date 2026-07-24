package org.skepsun.kototoro.scrobbling.discord.ui

import android.os.Bundle
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebStorage
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.browser.BaseBrowserActivity
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

@AndroidEntryPoint
class DiscordAuthActivity : BaseBrowserActivity(), DiscordTokenWebClient.Callback {

	@Inject
	lateinit var settings: AppSettings

	override fun onCreate2(
		savedInstanceState: Bundle?,
		source: ContentSource,
		repository: ParserContentRepository?
	) {
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		browserWebView.settings.userAgentString = USER_AGENT
		browserWebView.webViewClient = DiscordTokenWebClient(this)
		if (savedInstanceState == null) {
			resetDiscordWebSession()
			browserWebView.loadUrl(BASE_URL)
		}
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			browserWebView.stopLoading()
			finishAfterTransition()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun onTokenObtained(token: String) {
		settings.discordToken = token
		setResult(RESULT_OK)
		finish()
	}

	private fun resetDiscordWebSession() {
		browserWebView.stopLoading()
		browserWebView.clearHistory()
		browserWebView.clearCache(true)
		browserWebView.evaluateJavascript(
			"""
			(function() {
				try { window.localStorage.removeItem('token'); } catch (e) {}
				try { window.sessionStorage.removeItem('token'); } catch (e) {}
			})();
			""".trimIndent(),
			null,
		)

		val webStorage = WebStorage.getInstance()
		runCatching { webStorage.deleteOrigin(DISCORD_ORIGIN) }
		runCatching { webStorage.deleteOrigin(DISCORD_WWW_ORIGIN) }

		val cookieManager = CookieManager.getInstance()
		cookieManager.removeSessionCookies(null)
		cookieManager.removeAllCookies(null)
		cookieManager.flush()
	}

	private companion object {

		const val BASE_URL = "https://discord.com/login"
		private const val DISCORD_ORIGIN = "https://discord.com"
		private const val DISCORD_WWW_ORIGIN = "https://www.discord.com"
		private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.363"
	}
}
