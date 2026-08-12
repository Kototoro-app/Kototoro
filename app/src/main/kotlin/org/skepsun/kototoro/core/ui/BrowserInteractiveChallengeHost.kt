package org.skepsun.kototoro.core.ui

import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.skepsun.kototoro.core.network.webview.BrowserInteractiveChallengeState
import org.skepsun.kototoro.core.network.webview.WebViewExecutor

internal fun AppCompatActivity.observeBrowserInteractiveChallenges(webViewExecutor: WebViewExecutor) {
	lifecycleScope.launch {
		repeatOnLifecycle(Lifecycle.State.RESUMED) {
			webViewExecutor.interactiveChallenges.collect { challenge ->
				if (challenge.state != BrowserInteractiveChallengeState.PENDING) return@collect
				val host = webViewExecutor.attachBrowserSession(challenge.sessionId, this@observeBrowserInteractiveChallenges)
					?: return@collect
				webViewExecutor.acknowledgeInteractiveChallenge(challenge.challengeId, challenge.sessionId)
				val backCallback = object : OnBackPressedCallback(true) {
					override fun handleOnBackPressed() {
						webViewExecutor.cancelInteractiveChallenge(challenge.challengeId, challenge.sessionId)
					}
				}
				onBackPressedDispatcher.addCallback(this@observeBrowserInteractiveChallenges, backCallback)
				try {
					withTimeoutOrNull(WebViewExecutor.DEFAULT_CAPTCHA_TIMEOUT_MS * 2) {
						while (webViewExecutor.isBrowserSessionAttached(challenge.sessionId)) delay(250L)
					}
				} finally {
					backCallback.remove()
					webViewExecutor.detachBrowserSession(challenge.sessionId, host)
				}
			}
		}
	}
}
