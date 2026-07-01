package org.skepsun.kototoro.cloudstream.runtime

import android.util.Log
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.skepsun.kototoro.cloudstream.model.CloudstreamSource
import org.skepsun.kototoro.core.network.CloudFlareHandlingPolicy
import org.skepsun.kototoro.core.network.CommonHeaders
import org.skepsun.kototoro.parsers.model.ContentSource

internal object CloudstreamRequestContext {

	private val currentSource = ThreadLocal<CloudstreamSource?>()
	private val currentPolicy = ThreadLocal<CloudFlareHandlingPolicy?>()
	@Volatile
	var userAgent: String? = null

	suspend fun <T> withSource(source: CloudstreamSource, block: suspend () -> T): T {
		return withContext(currentSource.asContextElement(source)) {
			block()
		}
	}

	suspend fun <T> withLoadLinksCompatibility(block: suspend () -> T): T {
		return withContext(currentPolicy.asContextElement(CloudFlareHandlingPolicy(allowBlockedResponse = true))) {
			block()
		}
	}

	fun interceptor(): Interceptor = Interceptor { chain ->
		val source = currentSource.get()
		val policy = currentPolicy.get()
		val originalRequest = chain.request()
		val request = if (source != null) {
			val fallbackReferer = source.api.mainUrl.trimEnd('/') + "/"
			val requestReferer = originalRequest.header(CommonHeaders.REFERER)
				?.takeIf { it.isNotBlank() }
				?: fallbackReferer
			originalRequest.newBuilder()
				.tag(ContentSource::class.java, source)
				.apply {
					if (policy != null) {
						tag(CloudFlareHandlingPolicy::class.java, policy)
					}
				}
				.header(CommonHeaders.MANGA_SOURCE, source.name)
				.apply {
					val configuredUserAgent = userAgent
					if (originalRequest.header(CommonHeaders.USER_AGENT).isNullOrBlank() && !configuredUserAgent.isNullOrBlank()) {
						header(CommonHeaders.USER_AGENT, configuredUserAgent)
					}
					if (originalRequest.header(CommonHeaders.REFERER).isNullOrBlank()) {
						header(CommonHeaders.REFERER, requestReferer)
					}
					if (shouldInferOrigin(originalRequest) && originalRequest.header(ORIGIN).isNullOrBlank()) {
						requestReferer.toOrigin()?.let { origin ->
							header(ORIGIN, origin)
						}
					}
				}
				.build()
		} else {
			originalRequest
		}
		val response = chain.proceed(request)
		if (source != null && policy?.allowBlockedResponse == true) {
			Log.d(
				TAG,
				"loadLinks request source=${source.displayName} code=${response.code} url=${request.url} " +
					"referer=${request.header(CommonHeaders.REFERER)?.take(160)} origin=${request.header(ORIGIN)}",
			)
			if (shouldLogLoadLinksBody(request.url.toString())) {
				val bodyPreview = runCatching {
					response.peekBody(LOAD_LINKS_BODY_PREVIEW_BYTES).string()
				}.getOrElse { error ->
					"<peek failed: ${error::class.simpleName}>"
				}
				Log.d(
					TAG,
					"loadLinks response source=${source.displayName} code=${response.code} url=${request.url} " +
						"iframes=${extractIframeUrls(bodyPreview)} preview=${sanitizePreview(bodyPreview)}",
				)
			}
		}
		response
	}

	private fun shouldInferOrigin(request: okhttp3.Request): Boolean {
		return when (request.method.uppercase()) {
			"GET", "HEAD" -> false
			else -> true
		}
	}

	private fun String.toOrigin(): String? {
		val url = toHttpUrlOrNull() ?: return null
		return "${url.scheme}://${url.host}"
	}

	private fun shouldLogLoadLinksBody(url: String): Boolean {
		return url.contains("wp-admin/admin-ajax.php", ignoreCase = true) ||
			url.contains("/video-frame/", ignoreCase = true) ||
			url.contains("/video-embed/", ignoreCase = true) ||
			url.contains("sbface.com", ignoreCase = true) ||
			url.contains("voe.sx", ignoreCase = true) ||
			url.contains("nontonanimeid.bio", ignoreCase = true)
	}

	private fun extractIframeUrls(body: String): List<String> {
		return Regex("""<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
			.findAll(body)
			.mapNotNull { it.groupValues.getOrNull(1) }
			.take(8)
			.toList()
	}

	private fun sanitizePreview(body: String): String {
		return body
			.replace(Regex("\\s+"), " ")
			.take(800)
	}

	private const val TAG = "CloudstreamRequest"
	private const val ORIGIN = "Origin"
	private const val LOAD_LINKS_BODY_PREVIEW_BYTES = 4096L
}
