package org.skepsun.kototoro.core.network.webview

internal data class BrowserChallengeContext(
	val origin: String,
	val requestUrl: String,
	val method: String,
	val navigationUrl: String?,
	val responseHtmlSnippet: String,
) {
	companion object {
		const val MAX_HTML_SNIPPET_CHARS = 64 * 1024

		fun create(
			requestUrl: String,
			method: String,
			responseHtml: String,
		): BrowserChallengeContext? {
			val originPolicy = BrowserOriginPolicy.create(requestUrl) ?: return null
			val normalizedMethod = method.uppercase().takeIf { it == "GET" || it == "POST" } ?: return null
			return BrowserChallengeContext(
				origin = originPolicy.primaryOrigin,
				requestUrl = requestUrl,
				method = normalizedMethod,
				navigationUrl = requestUrl.takeIf { normalizedMethod == "GET" },
				responseHtmlSnippet = responseHtml.take(MAX_HTML_SNIPPET_CHARS),
			)
		}
	}
}

internal enum class BrowserResolutionEvidence {
	CLEARANCE_AND_NON_CHALLENGE_PAGE,
}

internal class BrowserChallengeResolutionTracker {
	private var interactiveChallengeObserved = false
	private var challengeNavigationObserved = false

	/**
	 * @param requiresInteractiveResolution when true (POST challenges), evidence is only produced
	 * after the visible resolver observed either the interactive widget or a real Cloudflare token
	 * navigation, then the page reached `OK` with a clearance. Some WebView providers complete
	 * Turnstile between DOM samples and report `WAIT -> OK` without exposing `INTERACTIVE`.
	 */
	fun observe(
		pageState: CloudFlarePageState,
		hasClearance: Boolean,
		clearanceChanged: Boolean,
		currentUrl: String? = null,
		requiresInteractiveResolution: Boolean = false,
	): BrowserResolutionEvidence? {
		if (pageState == CloudFlarePageState.INTERACTIVE) {
			interactiveChallengeObserved = true
		}
		if (currentUrl?.contains("__cf_chl_", ignoreCase = true) == true) {
			challengeNavigationObserved = true
		}
		if (requiresInteractiveResolution) {
			// This tracker exists only after the interactive BrowserSession is attached. A token
			// navigation or observed widget prevents an initial WAIT -> OK with old clearance from
			// being accepted while tolerating providers that miss the short INTERACTIVE state.
			if (!interactiveChallengeObserved && !challengeNavigationObserved) return null
			return BrowserResolutionEvidence.CLEARANCE_AND_NON_CHALLENGE_PAGE.takeIf {
				pageState == CloudFlarePageState.OK && hasClearance
			}
		}
		return BrowserResolutionEvidence.CLEARANCE_AND_NON_CHALLENGE_PAGE.takeIf {
			pageState == CloudFlarePageState.OK && hasClearance &&
				(interactiveChallengeObserved || challengeNavigationObserved || clearanceChanged)
		}
	}
}
