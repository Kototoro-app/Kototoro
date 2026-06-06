package org.skepsun.kototoro.details.ui.pager.pages

import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.NetworkResponse
import coil3.network.NetworkResponseBody
import coil3.request.Options
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.skepsun.kototoro.core.network.ContentHttpClient
import org.skepsun.kototoro.core.network.imageproxy.ImageProxyInterceptor
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.getAvailableRepositoryOrNull
import org.skepsun.kototoro.core.parser.requireAvailableRepository
import org.skepsun.kototoro.core.util.MimeTypes
import org.skepsun.kototoro.core.util.ext.fetch
import org.skepsun.kototoro.core.util.ext.isNetworkUri
import org.skepsun.kototoro.core.util.ext.toMimeTypeOrNull
import org.skepsun.kototoro.local.data.LocalStorageCache
import org.skepsun.kototoro.local.data.PageCache
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.util.mimeType
import org.skepsun.kototoro.parsers.util.requireBody
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.reader.domain.PageLoader
import javax.inject.Inject

class ContentPageFetcher(
	private val okHttpClient: OkHttpClient,
	private val pagesCache: LocalStorageCache,
	private val options: Options,
	private val page: ContentPage,
	private val mangaRepositoryFactory: ContentRepository.Factory,
	private val imageProxyInterceptor: ImageProxyInterceptor,
	private val imageLoader: ImageLoader,
) : Fetcher {

	override suspend fun fetch(): FetchResult? {
		// 预览图不携带 headers 会导致拦截器无法注入来源，避免在有 headers 时走无头请求
		val repo = mangaRepositoryFactory.createWithDiagnostics(page.source).getAvailableRepositoryOrNull(
			tag = "ContentPageFetcher",
			prefix = "repository_unavailable",
		) ?: return null
		val pageUrl = repo.getPageUrl(page)

		if (pageUrl.isBlank()) {
			android.util.Log.e("ContentPageFetcher", "Obtained empty page URL for source: ${page.source.name}")
			return null
		}

		// 支持 data URL：直接写入缓存并返回，避免走网络请求
		if (pageUrl.startsWith("data:", ignoreCase = true)) {
			val mime = pageUrl.substringAfter("data:", "").substringBefore(";").ifEmpty { null }
			val base64 = pageUrl.substringAfter("base64,", "")
			val bytes = runCatchingCancellable {
				if (base64.isNotEmpty()) android.util.Base64.decode(base64, android.util.Base64.DEFAULT) else ByteArray(0)
			}.getOrElse { ByteArray(0) }
			if (bytes.isNotEmpty()) {
				val buffer = okio.Buffer().write(bytes)
				val file = pagesCache.set(pageUrl, buffer, mime?.toMimeTypeOrNull())
				return SourceFetchResult(
					source = ImageSource(file.toOkioPath(), options.fileSystem),
					mimeType = mime,
					dataSource = DataSource.MEMORY,
				)
			}
		}

		if (options.diskCachePolicy.readEnabled) {
			pagesCache[pageUrl]?.let { file ->
				return SourceFetchResult(
					source = ImageSource(file.toOkioPath(), options.fileSystem),
					mimeType = MimeTypes.getMimeTypeFromExtension(file.name)?.toString(),
					dataSource = DataSource.DISK,
				)
			}
		}
		return loadPage(pageUrl)
	}

	private suspend fun loadPage(pageUrl: String): FetchResult? {
		val uri = pageUrl.toUri()
		return if (uri.isNetworkUri() || uri.scheme.isNullOrEmpty()) {
			fetchPage(pageUrl)
		} else {
			imageLoader.fetch(pageUrl, options)
		}
	}

	private suspend fun fetchPage(pageUrl: String): FetchResult {
		val repo = mangaRepositoryFactory.createWithDiagnostics(page.source).requireAvailableRepository(
			tag = "ContentPageFetcher",
			prefix = "fetchPage_repository_unavailable",
		) { "Page source ${page.source.name} is not available" }
		val response = repo.fetchPageResponse(pageUrl, page)
			?: run {
				val request = repo.createPageRequest(pageUrl, page)
				val imageClient = repo.getImageClient() ?: okHttpClient
				imageProxyInterceptor.interceptPageRequest(request, imageClient)
			}
		android.util.Log.d(
			"ContentPageFetcher",
			"fetchPage response: source=${page.source.name}, pageId=${page.id}, code=${response.code}, finalUrl=${response.request.url}, server=${response.header("server")}, cf-ray=${response.header("cf-ray")}, cf-mitigated=${response.header("cf-mitigated")}",
		)
		return response.use {
			if (!response.isSuccessful) {
				throw HttpException(response.toNetworkResponse())
			}
			val mimeType = response.mimeType?.toMimeTypeOrNull()
			val file = response.requireBody().use {
				pagesCache.set(pageUrl, it.source(), mimeType)
			}
			SourceFetchResult(
				source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
				mimeType = mimeType?.toString(),
				dataSource = DataSource.NETWORK,
			)
		}
	}

	private fun Response.toNetworkResponse() = NetworkResponse(
		code = code,
		requestMillis = sentRequestAtMillis,
		responseMillis = receivedResponseAtMillis,
		headers = headers.toNetworkHeaders(),
		body = body?.source()?.let(::NetworkResponseBody),
		delegate = this,
	)

	private fun Headers.toNetworkHeaders(): NetworkHeaders {
		val headers = NetworkHeaders.Builder()
		for ((key, values) in this) {
			headers.add(key, values)
		}
		return headers.build()
	}

	class Factory @Inject constructor(
		@ContentHttpClient private val okHttpClient: OkHttpClient,
		@PageCache private val pagesCache: LocalStorageCache,
		private val mangaRepositoryFactory: ContentRepository.Factory,
		private val imageProxyInterceptor: ImageProxyInterceptor,
	) : Fetcher.Factory<ContentPage> {

		override fun create(data: ContentPage, options: Options, imageLoader: ImageLoader) = ContentPageFetcher(
			okHttpClient = okHttpClient,
			pagesCache = pagesCache,
			options = options,
			page = data,
			mangaRepositoryFactory = mangaRepositoryFactory,
			imageProxyInterceptor = imageProxyInterceptor,
			imageLoader = imageLoader,
		)
	}
}
