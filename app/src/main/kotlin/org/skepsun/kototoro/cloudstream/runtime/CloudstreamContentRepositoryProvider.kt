package org.skepsun.kototoro.cloudstream.runtime

import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.network.cookies.MutableCookieJar
import org.skepsun.kototoro.core.network.webview.WebViewExecutor
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ContentRepositoryProvider
import org.skepsun.kototoro.parsers.model.ContentSource
import javax.inject.Inject

class CloudstreamContentRepositoryProvider @Inject constructor(
	private val contentCache: MemoryContentCache,
	private val webViewExecutor: WebViewExecutor,
	private val cookieJar: MutableCookieJar,
) : ContentRepositoryProvider {

	override fun supports(source: ContentSource): Boolean = source is CloudstreamSource

	override fun create(source: ContentSource): ContentRepository? {
		if (source !is CloudstreamSource) return null
		return CloudstreamContentRepository(
			source = source,
			cache = contentCache,
			webViewExecutor = webViewExecutor,
			cookieJar = cookieJar,
		)
	}
}
