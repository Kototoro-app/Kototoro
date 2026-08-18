package org.skepsun.kototoro.core.network.webview

/**
 * 返回值：
 * - "normal"：已进入真实页面
 * - "loading"：仍在加载或状态未知
 * - "managed"：Cloudflare 非交互 challenge（通常可自动通过）
 * - "interactive"：需要勾选的交互式 challenge（必须人工）
 * - "hard_block"：被明确阻断
 */
internal enum class CloudFlarePageState {
	NORMAL,
	LOADING,
	MANAGED_CHALLENGE,
	INTERACTIVE_CHALLENGE,
	HARD_BLOCK,
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
		val isStableCandidate = pageState == CloudFlarePageState.NORMAL &&
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
	"normal" -> CloudFlarePageState.NORMAL
	"hard_block" -> CloudFlarePageState.HARD_BLOCK
	"interactive" -> CloudFlarePageState.INTERACTIVE_CHALLENGE
	"managed" -> CloudFlarePageState.MANAGED_CHALLENGE
	else -> CloudFlarePageState.LOADING
}

internal const val CF_STATE_JS = """
	(function(){
		try {
			var href = (document.location && document.location.href) || '';
			if (href === '' || href === 'about:blank') return 'loading';
			if (document.readyState !== 'interactive' && document.readyState !== 'complete') return 'loading';
			var t = (document.title || '').toLowerCase();
			if (t.indexOf('attention required') !== -1 || t.indexOf('access denied') !== -1) return 'hard_block';
			var challenge = document.querySelector('#challenge-running, #challenge-stage, #cf-challenge-running, ' +
				'.cf-browser-verification, #turnstile-wrapper, .cf-turnstile, #cf-please-wait, #challenge-form');
			if (challenge) {
				// checkbox（interactive）challenge 的识别：
				// 1) widget iframe 的 title 是 "Widget containing a Cloudflare security challenge"（Cloudflare 官方源码）
				// 2) 显式渲染（render=explicit）会在主文档创建 hidden input cf-turnstile-response 用于接收 token
				// 用 DOM 存在性判断，而非可见性——隐藏 WebView 里 getBoundingClientRect() 恒为 0。
				var checkboxWidget = document.querySelector(
					'iframe[title*="Widget containing a Cloudflare security challenge"], input[name="cf-turnstile-response"]'
				);
				if (checkboxWidget) {
					return 'interactive';
				}
				return 'managed';
			}
			if (t.indexOf('just a moment') !== -1 || t.indexOf('un instant') !== -1 ||
				t.indexOf('einen moment') !== -1 || t.indexOf('un momento') !== -1 ||
				t.indexOf('один момент') !== -1 || t.indexOf('请稍候') !== -1 ||
				t.indexOf('請稍候') !== -1) return 'managed';
			if (document.readyState !== 'complete') return 'loading';
			if (!document.body || document.body.children.length === 0) return 'loading';
			return 'normal';
		} catch (e) { return 'loading'; }
	})()
"""
