package org.skepsun.kototoro.parsers

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.config.ContentSourceConfig
import org.skepsun.kototoro.parsers.model.*
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.parsers.model.search.ContentSearchQuery
import org.skepsun.kototoro.parsers.model.search.ContentSearchQueryCapabilities
import org.skepsun.kototoro.parsers.util.LinkResolver
import org.skepsun.kototoro.parsers.util.convertToContentSearchQuery
import org.skepsun.kototoro.parsers.util.toContentListFilterCapabilities
import java.util.*

public interface ContentParser : Interceptor {

	public val source: ContentSource

	/**
	 * Supported [SortOrder] variants. Must not be empty.
	 *
	 * For better performance use [EnumSet] for more than one item.
	 */
	public val availableSortOrders: Set<SortOrder>

	@Deprecated("Too complex. Use filterCapabilities instead")
	public val searchQueryCapabilities: ContentSearchQueryCapabilities

	public val filterCapabilities: ContentListFilterCapabilities

	public val config: ContentSourceConfig

	public val authorizationProvider: ContentParserAuthProvider?
		get() = this as? ContentParserAuthProvider

	/**
	 * Provide default domain and available alternatives, if any.
	 *
	 * Never hardcode domain in requests, use [domain] instead.
	 */
	public val configKeyDomain: ConfigKey.Domain

	public val domain: String

	@Deprecated("Too complex. Use getList with filter instead")
	public suspend fun getList(query: ContentSearchQuery): List<Content>

	public suspend fun getList(offset: Int, order: SortOrder, filter: ContentListFilter): List<Content>

	/**
	 * Parse details for [Content]: chapters list, description, large cover, etc.
	 * Must return the same content, may change any fields excepts id, url and source
	 * @see Content.copy
	 */
	public suspend fun getDetails(manga: Content): Content

	/**
	 * Parse pages list for specified chapter.
	 * @see ContentPage for details
	 */
	public suspend fun getPages(chapter: ContentChapter): List<ContentPage>

	/**
	 * Fetch direct link to the page image.
	 */
	public suspend fun getPageUrl(page: ContentPage): String

	/**
	 * 可选：返回小说章节的完整 HTML 与图片资源，用于离线下载。默认返回 null。
	 */
	public suspend fun getChapterContent(chapter: ContentChapter): NovelChapterContent? = null

	public suspend fun getFilterOptions(): ContentListFilterOptions

	/**
	 * Parse favicons from the main page of the source`s website
	 */
	public suspend fun getFavicons(): Favicons

	public fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>)

	public suspend fun getRelatedContent(seed: Content): List<Content>

	public fun getRequestHeaders(): Headers

	/**
	 * Return [Content] object by web link to it
	 * @see [Content.publicUrl]
	 */
	@InternalParsersApi
	public suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Content?
}
