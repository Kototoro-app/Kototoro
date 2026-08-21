package org.skepsun.kototoro.tracking.discovery.data

import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ContentRepositoryProvider
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.scrobbling.bangumi.data.BangumiRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackingContentRepositoryProvider @Inject constructor(
    private val bangumiRepository: BangumiRepository,
) : ContentRepositoryProvider {

    override fun supports(source: ContentSource): Boolean {
        return source.name.startsWith("TRACKING_")
    }

    override fun create(source: ContentSource): ContentRepository? {
        if (!supports(source)) return null
        return TrackingContentRepository(source, bangumiRepository)
    }
}

class TrackingContentRepository(
    override val source: ContentSource,
    private val bangumiRepository: BangumiRepository,
) : ContentRepository {

    override val sortOrders: Set<SortOrder> = linkedSetOf(
        SortOrder.RATING,
        SortOrder.POPULARITY,
        SortOrder.ADDED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override var defaultSortOrder: SortOrder = SortOrder.RATING

    override val filterCapabilities = ContentListFilterCapabilities(
        isMultipleTagsSupported = true,
        isSearchWithFiltersSupported = false,
        isAuthorSearchSupported = false,
    )

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val category = source.name.substringAfter("TRACKING_BANGUMI_").lowercase()
        return bangumiRepository.getBrowserFilterOptions(category, source)
    }

    override suspend fun getList(offset: Int, order: SortOrder?, filter: ContentListFilter?): List<Content> = emptyList()

    override suspend fun getDetails(manga: Content): Content = manga
    override suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> = emptyList()
    override suspend fun getPageUrl(page: ContentPage): String = ""
    override suspend fun getRelated(seed: Content): List<Content> = emptyList()
}
