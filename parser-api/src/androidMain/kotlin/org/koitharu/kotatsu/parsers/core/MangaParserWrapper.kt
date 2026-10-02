package org.koitharu.kotatsu.parsers.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.util.mergeWith

internal class MangaParserWrapper(
	private val delegate: MangaParser,
) : MangaParser by delegate {

	override val authorizationProvider: MangaParserAuthProvider?
		get() = delegate as? MangaParserAuthProvider

	@Deprecated("Too complex. Use getList with filter instead")
	override suspend fun getList(query: MangaSearchQuery): List<Manga> = withContext(Dispatchers.Default) {
		if (!query.skipValidation) {
			searchQueryCapabilities.validate(query)
		}
		delegate.getList(query)
	}

	override suspend fun getList(
		offset: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> = withContext(Dispatchers.Default) {
		delegate.getList(offset, order, filter)
	}

	override suspend fun getDetails(manga: Manga): Manga = withContext(Dispatchers.Default) {
		delegate.getDetails(manga)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.Default) {
		delegate.getPages(chapter)
	}

	override suspend fun getPageUrl(page: MangaPage): String = withContext(Dispatchers.Default) {
		delegate.getPageUrl(page)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = withContext(Dispatchers.Default) {
		delegate.getFilterOptions()
	}

	override suspend fun getFavicons(): Favicons = withContext(Dispatchers.Default) {
		delegate.getFavicons()
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = withContext(Dispatchers.Default) {
		delegate.getRelatedManga(seed)
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val headers = request.headers.newBuilder()
			.mergeWith(delegate.getRequestHeaders(), replaceExisting = false)
			.build()
		val newRequest = request.newBuilder().headers(headers).build()
		return delegate.intercept(ProxyChain(chain, newRequest))
	}

	private class ProxyChain(
		private val delegate: Interceptor.Chain,
		private val request: Request,
	) : Interceptor.Chain by delegate {

		override fun request(): Request = request
	}
}
