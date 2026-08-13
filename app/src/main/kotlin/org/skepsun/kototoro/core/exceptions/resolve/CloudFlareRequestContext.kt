package org.skepsun.kototoro.core.exceptions.resolve

import org.skepsun.kototoro.core.exceptions.CloudFlareProtectedException
import org.skepsun.kototoro.parsers.model.ContentSource
import java.net.URI

/**
 * 统一的 Cloudflare 求解上下文，贯通自动/人工 resolver 入参。
 * [challengeUrl] 用于 WebView 导航，[originalRequestUrl] 用于求解后的真实 probe 复验。
 */
internal data class CloudFlareRequestContext(
	val source: ContentSource,
	val host: String,
	val challengeUrl: String,
	val originalRequestUrl: String,
	val userAgent: String?,
	val headers: Map<String, String>,
	val method: String,
	val body: String?,
) {
	companion object {
		fun from(exception: CloudFlareProtectedException): CloudFlareRequestContext {
			val headers = buildMap {
				for (i in 0 until exception.headers.size) {
					val name = exception.headers.name(i)
					if (name !in this) {
						put(name, exception.headers.value(i))
					}
				}
			}
			return CloudFlareRequestContext(
				source = exception.source,
				host = resolveHost(exception.url),
				challengeUrl = exception.url,
				originalRequestUrl = exception.originalUrl,
				userAgent = exception.headers["User-Agent"],
				headers = headers,
				method = exception.method,
				body = exception.body,
			)
		}

		private fun resolveHost(url: String): String = runCatching {
			URI(url).host?.lowercase()
		}.getOrNull().orEmpty().ifBlank { url }
	}
}
