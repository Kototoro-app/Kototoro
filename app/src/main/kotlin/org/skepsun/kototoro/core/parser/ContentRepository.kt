package org.skepsun.kototoro.core.parser

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.Response
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.TestContentSource
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.parsers.model.SortOrder
import javax.inject.Inject

interface ContentRepository {

	enum class DetailsFetchMode {
		ALLOW_CACHE,
		FORCE_REFRESH,
	}

	enum class ListPagingMode {
		OFFSET,
		PAGE_INDEX,
	}

	val source: ContentSource

	val sortOrders: Set<SortOrder>

	var defaultSortOrder: SortOrder

	val filterCapabilities: ContentListFilterCapabilities

	/**
	 * 列表分页模式：
	 * - OFFSET：`getList(offset)` 的 offset 为"已加载条数"（Kototoro 原语义）。
	 * - PAGE_INDEX：`getList(offset)` 的 offset 视为"页索引(0-based)"，最终请求页号通常为 offset+1（对齐 legado/MD3 的 {{page}}）。
	 *
	 * 默认 OFFSET，避免影响现有解析器/本地库实现。
	 */
	val listPagingMode: ListPagingMode
		get() = ListPagingMode.OFFSET

	suspend fun getList(offset: Int, order: SortOrder?, filter: ContentListFilter?): List<Content>

	suspend fun getDetails(manga: Content): Content

	suspend fun getDetails(manga: Content, fetchMode: DetailsFetchMode): Content = getDetails(manga)

	suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String? = null): List<ContentPage>

	/**
	 * 获取章节页面的流，支持增量加载（如 Legado 多页小说章节）。
	 * 每次 emit 都是目前已获取到的所有页面列表。
	 * @param nextChapterUrl 下一章的 URL。如果加载过程中遇到此 URL，应停止加载，防止"下一章"规则误触导致无限连读。
	 */
	fun getPagesFlow(chapter: ContentChapter, nextChapterUrl: String? = null): Flow<List<ContentPage>> = flow {
		emit(getPages(chapter, nextChapterUrl))
	}

	suspend fun getPageUrl(page: ContentPage): String

	suspend fun getFilterOptions(): ContentListFilterOptions

	/**
	 * 可选：返回小说章节的完整 HTML 与图片资源信息，用于离线下载。
	 * 默认实现返回 null（未实现）。
	 */
	suspend fun getChapterContent(chapter: ContentChapter, nextChapterUrl: String? = null): NovelChapterContent? = null

	/**
	 * Create an OkHttp Request for a specific page.
	 * Default implementation uses PageLoader.createPageRequest with global settings.
	 */
	fun createPageRequest(pageUrl: String, page: ContentPage): okhttp3.Request {
		return org.skepsun.kototoro.reader.domain.PageLoader.createPageRequest(pageUrl, page)
	}

	/**
	 * Create an OkHttp Request for a cover image.
	 */
	fun createCoverRequest(imageUrl: String): okhttp3.Request {
		return org.skepsun.kototoro.reader.domain.PageLoader.createPageRequest(imageUrl, source)
	}

	/**
	 * Returns the request headers for this source (generic headers like UA/Referer).
	 */
	fun getRequestHeaders(): Map<String, String> = emptyMap()

	/**
	 * Returns a source-specific OkHttpClient for image requests (covers & pages), or null to
	 * use the default [ContentHttpClient].
	 *
	 * Mihon extensions override this to return their own client which carries the correct
	 * cookies (cf_clearance), rate limiters, and custom interceptors for the source's CDN.
	 */
	fun getImageClient(): okhttp3.OkHttpClient? = null

	/**
	 * 可选：让源自行执行页面图片请求。
	 *
	 * 主要用于 Mihon 这类宿主需要复用扩展原生取图逻辑的场景，避免宿主重新拼接
	 * request/client 后丢失扩展侧的 token 刷新、重试或特殊拦截行为。
	 *
	 * 返回 null 表示继续走 Kototoro 默认下载链路。
	 */
	suspend fun fetchPageResponse(pageUrl: String, page: ContentPage): Response? = null

	fun isSlowdownEnabled(): Boolean {
		return source != LocalMangaSource && source != TestContentSource && source != org.skepsun.kototoro.core.model.LocalNovelSource
	}

	suspend fun getRelated(seed: Content): List<Content>

	suspend fun getConfigKeys(): List<org.skepsun.kototoro.parsers.config.ConfigKey<*>> = emptyList()

	suspend fun find(manga: Content): Content? {
		val list = getList(0, SortOrder.RELEVANCE, ContentListFilter(query = manga.title))
		return list.find { x -> x.id == manga.id }
	}

	class Factory @Inject constructor(
		private val delegate: ContentRepositoryFactory,
	) {
		fun create(source: ContentSource): ContentRepository = delegate.create(source)

		fun createWithDiagnostics(source: ContentSource): ContentRepositoryFactory.CreationResult {
			return delegate.createWithDiagnostics(source)
		}
	}
}
