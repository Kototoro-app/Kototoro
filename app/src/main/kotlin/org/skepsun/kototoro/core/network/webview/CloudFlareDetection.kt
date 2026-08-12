package org.skepsun.kototoro.core.network.webview

/**
 * 返回值：
 * - "ok"：已进入真实页面
 * - "error"：被明确阻断
 * - "wait"：仍在等待或仍处于 Cloudflare challenge
 */
internal enum class CloudFlarePageState {
	OK,
	ERROR,
	INTERACTIVE,
	WAIT,
}

internal class BrowserDocumentReadinessTracker(
	private val quietWindowMs: Long,
) {
	private var stableSince: Long? = null
	private var lastResourceCount: Int? = null

	fun observe(
		pageState: CloudFlarePageState,
		readyState: String,
		url: String,
		resourceCount: Int,
		nowMs: Long,
	): Boolean {
		val resourceChanged = lastResourceCount != null && lastResourceCount != resourceCount
		lastResourceCount = resourceCount
		val isStableCandidate = pageState == CloudFlarePageState.OK &&
			readyState == "complete" &&
			!url.contains("__cf_chl_", ignoreCase = true)
		if (!isStableCandidate || resourceChanged) {
			stableSince = null
			return false
		}
		val since = stableSince ?: nowMs.also { stableSince = it }
		return nowMs - since >= quietWindowMs
	}
}

internal const val CF_CLEARANCE_COOKIE = "cf_clearance"

internal const val CF_CHALLENGE_SELECTOR =
	"#challenge-running, #challenge-stage, #cf-challenge-running, .cf-browser-verification, " +
		"#turnstile-wrapper, .cf-turnstile, #cf-please-wait, #challenge-form, " +
		"iframe[src*='challenges.cloudflare.com'], iframe[title*='Cloudflare'], " +
		"input[name='cf-turnstile-response']"

internal fun parseCloudFlarePageState(raw: String?): CloudFlarePageState = when (raw?.removeSurrounding("\"")) {
	"ok" -> CloudFlarePageState.OK
	"error" -> CloudFlarePageState.ERROR
	"interactive" -> CloudFlarePageState.INTERACTIVE
	else -> CloudFlarePageState.WAIT
}

internal const val CF_STATE_JS = """
	(function(){
		try {
			var href = (document.location && document.location.href) || '';
			if (href === '' || href === 'about:blank') return 'wait';
			if (document.readyState !== 'interactive' && document.readyState !== 'complete') return 'wait';
			var t = (document.title || '').toLowerCase();
			if (t.indexOf('attention required') !== -1 || t.indexOf('access denied') !== -1) return 'error';
			var challenge = document.querySelector('#challenge-running, #challenge-stage, #cf-challenge-running, ' +
				'.cf-browser-verification, #turnstile-wrapper, .cf-turnstile, #cf-please-wait, #challenge-form');
			var widget = document.querySelector('.cf-turnstile, #turnstile-wrapper, ' +
				'iframe[src*="challenges.cloudflare.com"], iframe[title*="Cloudflare"], ' +
				'input[name="cf-turnstile-response"]');
			if (challenge && widget) {
				var rect = widget.getBoundingClientRect();
				var style = window.getComputedStyle(widget);
				if (rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden') {
					return 'interactive';
				}
			}
			if (t.indexOf('just a moment') !== -1 || t.indexOf('un instant') !== -1 ||
				t.indexOf('einen moment') !== -1 || t.indexOf('un momento') !== -1 ||
				t.indexOf('один момент') !== -1) return 'wait';
			if (document.querySelector('#challenge-running, #challenge-stage, #cf-challenge-running, ' +
				'.cf-browser-verification, #cf-please-wait, #challenge-form')) return 'wait';
			if (!document.body || document.body.children.length === 0) return 'wait';
			return 'ok';
		} catch (e) { return 'wait'; }
	})()
"""
