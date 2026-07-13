package org.skepsun.kototoro.ireader

import ireader.core.source.CatalogSource
import ireader.core.source.Source
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.ImageUrl
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.PageUrl
import ireader.core.source.model.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.parser.CachingContentRepository
import org.skepsun.kototoro.core.parser.RelatedContentSearchFallback
import org.skepsun.kototoro.ireader.model.IReaderMangaSource
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder

class IReaderMangaRepository(
    override val source: IReaderMangaSource,
    cache: MemoryContentCache,
) : CachingContentRepository(cache) {

    private val ireaderSource: Source = source.catalogueSource
    private val catalogSource: CatalogSource? = ireaderSource as? CatalogSource

    override val sortOrders: Set<SortOrder> = setOf(SortOrder.POPULARITY, SortOrder.UPDATED)

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = catalogSource != null,
            isMultipleTagsSupported = false,
            isSearchWithFiltersSupported = false,
        )

    override var defaultSortOrder: SortOrder = SortOrder.POPULARITY

    override suspend fun getList(
        offset: Int,
        order: SortOrder?,
        filter: ContentListFilter?
    ): List<Content> = withContext(Dispatchers.IO) {
        val catalog = catalogSource ?: return@withContext emptyList()
        try {
            val page = offset + 1 // IReader uses 1-based pages
            val query = filter?.query
            val result = if (!query.isNullOrBlank()) {
                // Search: use filters with query
                val filters = catalog.getFilters().toMutableList()
                var titleFilterIndex = -1
                for (i in filters.indices) {
                    if (filters[i] is ireader.core.source.model.Filter.Title) {
                        titleFilterIndex = i
                        break
                    }
                }
                
                if (titleFilterIndex != -1) {
                    val filter = filters[titleFilterIndex] as ireader.core.source.model.Filter.Title
                    filter.value = query
                } else {
                    val newFilter = ireader.core.source.model.Filter.Title()
                    newFilter.value = query
                    filters.add(0, newFilter)
                }
                
                catalog.getMangaList(
                    filters = filters,
                    page = page
                )
            } else {
                // Browse: use listings
                val listings = catalog.getListings()
                val listing = when (order) {
                    SortOrder.UPDATED -> listings.getOrNull(1) ?: listings.firstOrNull()
                    else -> listings.firstOrNull()
                }
                catalog.getMangaList(sort = listing, page = page)
            }
            android.util.Log.d("IReaderRepo", "getList: page=$page query=$query results=${result.mangas.size} hasNext=${result.hasNextPage}")
            result.mangas.map { manga -> manga.toContent() }
        } catch (e: Exception) {
            android.util.Log.e("IReaderRepo", "getList failed", e)
            emptyList()
        }
    }

    override suspend fun getDetailsImpl(manga: Content): Content = withContext(Dispatchers.IO) {
        try {
            val mangaInfo = MangaInfo(key = manga.url, title = manga.title, cover = manga.coverUrl.orEmpty())
            val details = ireaderSource.getMangaDetails(mangaInfo, emptyList())
            val chapters = fetchChapters(mangaInfo)
            android.util.Log.d("IReaderRepo", "getDetails: title=${details.title} chapters=${chapters.size}")
            details.toContent(originalManga = manga).copy(
                chapters = chapters.mapIndexed { index, ch -> ch.toContentChapter(index, originalUrl = manga.url) },
            )
        } catch (e: Exception) {
            android.util.Log.e("IReaderRepo", "getDetails failed", e)
            manga
        }
    }

    override suspend fun getPagesImpl(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> = withContext(Dispatchers.IO) {
        try {
            val chapterInfo = ChapterInfo(key = chapter.url, name = chapter.title.orEmpty())
            val pages = ireaderSource.getPageList(chapterInfo, emptyList())
            android.util.Log.d("IReaderRepo", "getPages: chapter=${chapter.title} pages=${pages.size}")
            pages.mapIndexedNotNull { index, page ->
                when (page) {
                    is ImageUrl -> ContentPage(
                        id = index.toLong(),
                        url = page.url,
                        preview = null,
                        source = source,
                    )
                    is PageUrl -> ContentPage(
                        id = index.toLong(),
                        url = page.url,
                        preview = null,
                        source = source,
                    )
                    is Text -> {
                        // Encode the full text content as an HTML data URL.
                        // NovelContentLoader.decodeChapterHtml() already handles data:text/html;base64,... 
                        val htmlContent = "<p>${page.text}</p>"
                        val encoded = android.util.Base64.encodeToString(
                            htmlContent.toByteArray(Charsets.UTF_8),
                            android.util.Base64.NO_WRAP
                        )
                        ContentPage(
                            id = index.toLong(),
                            url = "data:text/html;base64,$encoded",
                            preview = null,
                            source = source,
                        )
                    }
                    else -> null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("IReaderRepo", "getPages failed", e)
            emptyList()
        }
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        return ContentListFilterOptions(emptySet(), emptyList())
    }

    override suspend fun getRelatedContentImpl(seed: Content): List<Content> {
        return RelatedContentSearchFallback.find(seed) { query ->
            getList(
                offset = 0,
                order = defaultSortOrder,
                filter = ContentListFilter(query = query),
            )
        }
    }

    private suspend fun fetchChapters(mangaInfo: MangaInfo): List<ChapterInfo> {
        val reportedPageCount = runCatching { ireaderSource.getChapterPageCount(mangaInfo) }
            .getOrElse {
                android.util.Log.w("IReaderRepo", "fetchChapters: getChapterPageCount failed", it)
                1
            }
            .coerceAtLeast(1)
        val supportsPagination = runCatching { ireaderSource.supportsPaginatedChapters() }
            .getOrDefault(false)
        if (!supportsPagination && reportedPageCount <= 1) {
            return ireaderSource.getChapterList(mangaInfo, emptyList())
        }

        return runCatching {
            val chaptersByKey = LinkedHashMap<String, ChapterInfo>()
            val firstPage = ireaderSource.getChapterListPaged(mangaInfo, 1, emptyList())
            firstPage.chapters.forEach { chapter -> chaptersByKey.putIfAbsent(chapter.key, chapter) }
            val totalPages = maxOf(firstPage.totalPages, reportedPageCount, 1)
            android.util.Log.d(
                "IReaderRepo",
                "fetchChapters: supportsPagination=$supportsPagination reportedPages=$reportedPageCount resolvedPages=$totalPages firstPage=${firstPage.chapters.size}",
            )
            for (page in 2..totalPages) {
                val pageInfo = ireaderSource.getChapterListPaged(mangaInfo, page, emptyList())
                android.util.Log.d(
                    "IReaderRepo",
                    "fetchChapters: page=$page/${totalPages} chapters=${pageInfo.chapters.size}",
                )
                pageInfo.chapters.forEach { chapter -> chaptersByKey.putIfAbsent(chapter.key, chapter) }
            }
            chaptersByKey.values.toList()
        }.getOrElse { error ->
            android.util.Log.w("IReaderRepo", "fetchChapters: paginated load failed, falling back to first page API", error)
            ireaderSource.getChapterList(mangaInfo, emptyList())
        }
    }

    // --- Model Mapping ---

    private fun MangaInfo.toContent(originalUrl: String? = null, originalManga: Content? = null): Content {
        val id = key.hashCode().toLong()
        val baseAbsoluteUrl = originalUrl ?: originalManga?.url
        val finalUrl = if (!key.startsWith("http") && baseAbsoluteUrl?.startsWith("http") == true) {
            runCatching { java.net.URI(baseAbsoluteUrl).resolve(key).toString() }.getOrDefault(key)
        } else key
        return Content(
            id = id,
            title = title.takeIf { it.isNotBlank() } ?: originalManga?.title ?: "",
            altTitles = originalManga?.altTitles ?: emptySet(),
            url = finalUrl,
            publicUrl = finalUrl,
            rating = originalManga?.rating ?: RATING_UNKNOWN,
            contentRating = originalManga?.contentRating,
            coverUrl = cover.takeIf { it.isNotBlank() } ?: originalManga?.coverUrl,
            tags = genres.map { it.trim() }.filter { it.isNotEmpty() }.map { ContentTag(it, it, source) }.toSet().takeIf { it.isNotEmpty() } ?: originalManga?.tags ?: emptySet(),
            state = when (status) {
                MangaInfo.ONGOING -> ContentState.ONGOING
                MangaInfo.COMPLETED, MangaInfo.PUBLISHING_FINISHED -> ContentState.FINISHED
                MangaInfo.ON_HIATUS -> ContentState.PAUSED
                MangaInfo.CANCELLED -> ContentState.ABANDONED
                else -> originalManga?.state
            },
            authors = buildSet {
                if (author.isNotBlank()) add(author)
                if (artist.isNotBlank() && artist != author) add(artist)
            }.takeIf { it.isNotEmpty() } ?: originalManga?.authors ?: emptySet(),
            description = description.takeIf { it.isNotBlank() } ?: originalManga?.description,
            source = source,
        )
    }

    private fun ChapterInfo.toContentChapter(index: Int, originalUrl: String? = null): ContentChapter {
        val id = key.hashCode().toLong()
        val finalUrl = if (!key.startsWith("http") && originalUrl?.startsWith("http") == true) {
            runCatching { java.net.URI(originalUrl).resolve(key).toString() }.getOrDefault(key)
        } else key
        return ContentChapter(
            id = id,
            title = name,
            number = if (number >= 0f) number else (index + 1).toFloat(),
            volume = 0,
            url = finalUrl,
            scanlator = scanlator.ifBlank { null },
            uploadDate = dateUpload,
            branch = null,
            source = source,
        )
    }
}
