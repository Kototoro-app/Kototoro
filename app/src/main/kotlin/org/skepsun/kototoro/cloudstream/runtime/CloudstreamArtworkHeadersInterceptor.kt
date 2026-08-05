package org.skepsun.kototoro.cloudstream.runtime

import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.ImageResult
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.util.ext.mangaKey

internal class CloudstreamArtworkHeadersInterceptor : Interceptor {

	override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
		val content = chain.request.extras[mangaKey]
		if (content?.source !is CloudstreamSource) return chain.proceed()

		val artworkUrl = chain.request.data.toString()
		val persistedHeaders = CloudstreamMetadataCodec.decodeContent(content.sourceData)
			?.posterHeaders
			.orEmpty()
		val posterHeaders = CloudstreamArtworkHeaders.resolve(content.source.name, artworkUrl, persistedHeaders)
		if (posterHeaders.isEmpty()) return chain.proceed()

		val headers = chain.request.httpHeaders.newBuilder().apply {
			posterHeaders.forEach { (name, value) -> set(name, value) }
		}.build()
		val headersFingerprint = posterHeaders.toSortedMap(String.CASE_INSENSITIVE_ORDER)
			.entries
			.joinToString(separator = "\n") { (name, value) -> "$name:$value" }
			.let(::cloudstreamStableId)
		val cacheKey = "$artworkUrl#cloudstream:${content.source.name}:$headersFingerprint"
		val request = chain.request.newBuilder()
			.httpHeaders(headers)
			.memoryCacheKey(cacheKey)
			.diskCacheKey(cacheKey)
			.build()
		return chain.withRequest(request).proceed()
	}
}
