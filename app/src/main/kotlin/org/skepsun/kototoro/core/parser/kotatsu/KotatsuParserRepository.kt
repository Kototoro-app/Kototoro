package org.skepsun.kototoro.core.parser.kotatsu

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaParser as KTMangaParser
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.parser.CachingContentRepository
import org.skepsun.kototoro.parsers.config.ConfigKey
import org.skepsun.kototoro.parsers.model.Favicons
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.SortOrder

class KotatsuParserRepository(
    private val parser: KTMangaParser,
    private val kotatsuSource: KotatsuParserSource,
    private val loaderContext: org.skepsun.kototoro.parsers.ContentLoaderContext,
    cache: MemoryContentCache,
) : CachingContentRepository(cache), Interceptor {

    override val source: org.skepsun.kototoro.parsers.model.ContentSource
        get() = kotatsuSource

    override val sortOrders: Set<SortOrder> =
        parser.availableSortOrders.map { it.toKototoro() }.toSet()

    override val filterCapabilities: ContentListFilterCapabilities =
        parser.filterCapabilities.toKototoro()

    override var defaultSortOrder: SortOrder
        get() = sortOrders.first()
        set(@Suppress("UNUSED_PARAMETER") value) {}

    override suspend fun getList(offset: Int, order: SortOrder?, filter: ContentListFilter?): List<Content> =
        parser.getList(offset, (order ?: sortOrders.first()).toKotatsu(), (filter ?: ContentListFilter.EMPTY).toKotatsu(kotatsuSource))
            .map { it.toKototoro(kotatsuSource) }

    override suspend fun getDetailsImpl(manga: Content): Content =
        parser.getDetails(manga.toKotatsu(kotatsuSource)).toKototoro(kotatsuSource)

    override suspend fun getPagesImpl(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> {
        if (chapter.url.startsWith("file://") || chapter.url.startsWith("zip://") || chapter.url.startsWith("content://")) {
            return org.skepsun.kototoro.local.data.input.LocalContentParser(android.net.Uri.parse(chapter.url)).getPages(chapter)
        }
        return parser.getPages(chapter.toKotatsu(kotatsuSource)).map { it.toKototoro(kotatsuSource) }
    }

    override suspend fun getPageUrl(page: ContentPage): String {
        if (page.url.startsWith("file://") || page.url.startsWith("zip://") || page.url.startsWith("data:") || page.url.startsWith("content://")) {
            return page.url
        }
        return parser.getPageUrl(page.toKotatsu(kotatsuSource))
    }

    override suspend fun getFilterOptions(): ContentListFilterOptions =
        parser.getFilterOptions().toKototoro(kotatsuSource)

    override suspend fun getRelatedContentImpl(seed: Content): List<Content> =
        parser.getRelatedManga(seed.toKotatsu(kotatsuSource)).map { it.toKototoro(kotatsuSource) }

    suspend fun getFavicons(): Favicons = parser.getFavicons().toKototoro()

    override fun getRequestHeaders(): Map<String, String> {
        val headers = parser.getRequestHeaders()
        val map = mutableMapOf<String, String>()
        for (i in 0 until headers.size) {
            map[headers.name(i)] = headers.value(i)
        }
        return map
    }

    override fun intercept(chain: Interceptor.Chain): Response = parser.intercept(chain)

    fun getConfig() = loaderContext.getConfig(source) as org.skepsun.kototoro.core.prefs.SourceSettings

    var domain: String
        get() = parser.domain
        set(value) {
            parser.configKeyDomain.toKototoro()?.let {
                getConfig()[it] = value
            }
        }

    override suspend fun getConfigKeys(): List<ConfigKey<*>> =
        ArrayList<org.koitharu.kotatsu.parsers.config.ConfigKey<*>>().also {
            parser.onCreateConfig(it)
        }.mapNotNull { it.toKototoro() }
}
