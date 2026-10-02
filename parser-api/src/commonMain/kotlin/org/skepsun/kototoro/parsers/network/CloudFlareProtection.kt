package org.skepsun.kototoro.parsers.network

/**
 * Persisted Cloudflare protection states (the `sources.cf_state` column). They live here, separate from the
 * OkHttp/Jsoup based [CloudFlareHelper], so that code which only stores the state can be shared with non-JVM targets.
 */
public object CloudFlareProtection {

	public const val NOT_DETECTED: Int = 0
	public const val CAPTCHA: Int = 1
	public const val BLOCKED: Int = 2
}
