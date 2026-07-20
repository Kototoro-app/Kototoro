package org.skepsun.kototoro.core.network

import android.content.Context
import android.util.Log
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Interceptor.Chain
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.parser.ContentLoaderContextImpl
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug

import org.koitharu.kotatsu.parsers.model.MangaSource as KTMangaSource
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.mergeWith
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.net.IDN
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommonHeadersInterceptor @Inject constructor(
	@ApplicationContext private val context: Context,
	private val mangaRepositoryFactoryLazy: Lazy<ContentRepository.Factory>,
	private val mangaLoaderContextLazy: Lazy<ContentLoaderContextImpl>,
) : Interceptor {

	override fun intercept(chain: Chain): Response {
		val request = chain.request()
		val source = request.tag(ContentSource::class.java)
			?: request.tag(KTMangaSource::class.java)?.let { org.skepsun.kototoro.core.model.ContentSource(it.name) }
			?: request.headers[CommonHeaders.MANGA_SOURCE]?.let { ContentSource(it) }
		val repository = if (source != null) {
			mangaRepositoryFactoryLazy.get().create(source)
		} else {
			null
		}
		val headersBuilder = request.headers.newBuilder()
			.removeAll(CommonHeaders.MANGA_SOURCE)
			.removeAll(CommonHeaders.ICY_METADATA)

		// Remove headers that should be handled by OkHttp's internal logic to avoid 400 Bad Request
		headersBuilder.removeAll("Host")
		headersBuilder.removeAll("Connection")
		headersBuilder.removeAll("Content-Length")

		repository?.getRequestHeaders()?.forEach { (name, value) ->
			if (headersBuilder[name] == null) {
				headersBuilder[name] = value
			}
		}
		val userAgent = headersBuilder[CommonHeaders.USER_AGENT] ?: UserAgentProvider.get(context)
		headersBuilder[CommonHeaders.USER_AGENT] = userAgent

		if (userAgent.contains("Chrome", ignoreCase = true)) {
			val chromeVersionRegex = Regex("Chrome/(\\d+)\\.", RegexOption.IGNORE_CASE)
			val majorVersion = chromeVersionRegex.find(userAgent)?.groupValues?.get(1) ?: "124"
			if (headersBuilder["sec-ch-ua"] == null) {
				headersBuilder["sec-ch-ua"] = "\"Chromium\";v=\"$majorVersion\", \"Google Chrome\";v=\"$majorVersion\", \"Not-A.Brand\";v=\"99\""
			}
			if (headersBuilder["sec-ch-ua-mobile"] == null) {
				headersBuilder["sec-ch-ua-mobile"] = "?1"
			}
			if (headersBuilder["sec-ch-ua-platform"] == null) {
				headersBuilder["sec-ch-ua-platform"] = "\"Android\""
			}
		}
		
		// Add Referer header upfront if not already set (like Kotatsu does)
		if (headersBuilder[CommonHeaders.REFERER] == null && repository != null) {
			val domain = when (repository) {
				is ParserContentRepository -> repository.domain
				is org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserRepository -> {
					// Get domain from the underlying Kotatsu parser
					runCatching { 
						(repository as? okhttp3.Interceptor)?.let { _ ->
							// The KotatsuParserRepository wraps a KTMangaParser which has domain
							val parserField = repository.javaClass.getDeclaredField("parser")
							parserField.isAccessible = true
							val parser = parserField.get(repository) as? org.koitharu.kotatsu.parsers.MangaParser
							parser?.domain
						}
					}.getOrNull()
				}
				else -> null
			}
			if (domain != null) {
				val idn = IDN.toASCII(domain)
				headersBuilder.trySet(CommonHeaders.REFERER, "https://$idn/")
			}
		}
		
		val finalSource = repository?.source ?: source
		
		var workingUrl = request.url
		if (workingUrl.scheme == "https" && workingUrl.host.contains('_')) {
			val safeHost = workingUrl.host.replace('_', '-')
			if (safeHost != workingUrl.host) {
				SniBypassHostMap.register(safeHost, workingUrl.host)
				workingUrl = workingUrl.newBuilder().host(safeHost).build()
				headersBuilder[ "Host" ] = request.url.host
				Log.w("CommonHeadersInterceptor", "SNI workaround: ${request.url.host} -> $safeHost")
			}
		}

		val newRequest = request.newBuilder().url(workingUrl).headers(headersBuilder.build()).build()
		val response = (repository as? Interceptor)?.interceptSafe(ProxyChain(chain, newRequest)) ?: chain.proceed(newRequest)

		// Log response for debugging blocked images
		if (!response.isSuccessful) {
			android.util.Log.w("CommonHeadersInterceptor", "Request failed: ${response.code} for ${request.url}")
		}

		return response
	}


	private fun Headers.Builder.trySet(name: String, value: String) = try {
		set(name, value)
	} catch (e: IllegalArgumentException) {
		e.printStackTraceDebug()
	}

	private fun Interceptor.interceptSafe(chain: Chain): Response = runCatchingCancellable {
		intercept(chain)
	}.getOrElse { e ->
		if (e is IOException || e is Error) {
			throw e
		} else {
			// only IOException can be safely thrown from an Interceptor
			throw IOException("Error in interceptor: ${e.message}", e)
		}
	}

	private class ProxyChain(
		private val delegate: Chain,
		private val request: Request,
	) : Chain by delegate {

		override fun request(): Request = request
	}
}
