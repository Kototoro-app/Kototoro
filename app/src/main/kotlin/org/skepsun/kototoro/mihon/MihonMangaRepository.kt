package org.skepsun.kototoro.mihon

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import org.skepsun.kototoro.core.parser.CachingContentRepository
import org.skepsun.kototoro.core.parser.RelatedContentSearchFallback
import org.skepsun.kototoro.mihon.compat.MihonRequestContext
import org.skepsun.kototoro.mihon.model.MihonMangaSource
import org.skepsun.kototoro.mihon.model.getPublicContentUrl
import org.skepsun.kototoro.mihon.model.toKotoChapter
import org.skepsun.kototoro.mihon.model.toKotoContent
import org.skepsun.kototoro.mihon.model.toKotoPage
import org.skepsun.kototoro.mihon.model.toMihonChapter
import org.skepsun.kototoro.mihon.model.toMihonManga
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.SortOrder
import okhttp3.Response

/**
 * Repository that adapts a Mihon CatalogueSource to Kototoro's ContentRepository interface.
 */
class MihonMangaRepository(
    override val source: MihonMangaSource,
    cache: MemoryContentCache,
) : CachingContentRepository(cache) {
    
    companion object {
        private const val TAG = "MihonMangaRepository"
        
        private fun extractChapterNumber(name: String): Float {
            // Try Chinese format: 第X话
            val chineseRegex = Regex("""第\s*(\d+(?:\.\d+)?)\s*话""")
            chineseRegex.find(name)?.let {
                return it.groupValues[1].toFloatOrNull() ?: -1f
            }
            
            // Try English format: Chapter X, Ch. X
            val englishRegex = Regex("""(?:Chapter|Ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            englishRegex.find(name)?.let {
                return it.groupValues[1].toFloatOrNull() ?: -1f
            }
            
            // Try pure number
            val numberRegex = Regex("""(\d+(?:\.\d+)?)""")
            numberRegex.find(name)?.let {
                return it.groupValues[1].toFloatOrNull() ?: -1f
            }
            
            return -1f
        }
    }

    private var lastOffset = -1
    private var currentPage = 1
    
    val mihonSource = source.catalogueSource
    
    override val sortOrders: Set<SortOrder> = buildSet {
        add(SortOrder.POPULARITY)
        if (mihonSource.supportsLatest) {
            add(SortOrder.UPDATED)
        }
    }
    
    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isSearchWithFiltersSupported = true,
        )
    
    override var defaultSortOrder: SortOrder = SortOrder.POPULARITY
    
    override suspend fun getList(
        offset: Int,
        order: SortOrder?,
        filter: ContentListFilter?,
    ): List<Content> = withContext(Dispatchers.IO) {
        if (offset == 0) {
            currentPage = 1
        } else if (offset > lastOffset) {
            currentPage++
        }
        lastOffset = offset
        
        val page = currentPage
        val query = filter?.query
        
        val hasFilters = filter?.let { 
            it.query?.isNotBlank() == true || it.tags.isNotEmpty() || it.tagsExclude.isNotEmpty()
        } ?: false
        
        val mangasPage = rethrowMihonWrappedExceptions {
            withMihonSourceContext {
                when {
                    hasFilters -> {
                        mihonSource.getSearchManga(page, query ?: "", filter?.toMihonFilterList() ?: FilterList())
                    }
                    order == SortOrder.UPDATED && mihonSource.supportsLatest -> {
                        mihonSource.getLatestUpdates(page)
                    }
                    else -> {
                        mihonSource.getPopularManga(page)
                    }
                }
            }
        }
        
        mangasPage.mangas.map { sContent ->
            sContent.toKotoContent(
                source = source,
                publicUrl = (mihonSource as? HttpSource)?.getPublicContentUrl(sContent) ?: "",
            )
        }
    }
    
    override suspend fun getDetailsImpl(manga: Content): Content = withContext(Dispatchers.IO) {
        val sContent = manga.toMihonManga()
        
        val details = try {
            rethrowMihonWrappedExceptions {
                withMihonSourceContext {
                    mihonSource.getMangaDetails(sContent)
                }
            }
        } catch (e: Exception) {
            val ioException = when {
                e is java.io.IOException -> e
                e.cause is java.io.IOException -> e.cause as java.io.IOException
                else -> null
            }
            
            if (ioException != null) {
                kotlinx.coroutines.delay(500)
                rethrowMihonWrappedExceptions {
                    withMihonSourceContext {
                        mihonSource.getMangaDetails(sContent)
                    }
                }
            } else {
                throw e
            }
        }
        
        val rawChapters = try {
            rethrowMihonWrappedExceptions {
                withMihonSourceContext {
                    mihonSource.getChapterList(sContent)
                }
            }
        } catch (e: Exception) {
            val ioException = when {
                e is java.io.IOException -> e
                e.cause is java.io.IOException -> e.cause as java.io.IOException
                else -> null
            }
            
            if (ioException != null) {
                kotlinx.coroutines.delay(500)
                rethrowMihonWrappedExceptions {
                    withMihonSourceContext {
                        mihonSource.getChapterList(sContent)
                    }
                }
            } else {
                throw e
            }
        }
        
        val totalChapters = rawChapters.size
        android.util.Log.d("MihonMangaRepository", "rawChapters count: $totalChapters, source: ${source.name}")
        rawChapters.take(15).forEachIndexed { idx, ch ->
            android.util.Log.d("MihonMangaRepository", "  raw[$idx]: ${ch.name}")
        }
        // 采用最直观的策略：直接反转原始列表（假设原始是“最新在前”），并依次分配虚拟编号。
        // 这能确保 Page 1 对应 1.0，Page 15 对应 15.0，解决排序识别反向的问题。
        val chapters = rawChapters.asReversed()
            .mapIndexed { index, sChapter ->
                // 如果插件有提供合法的编号则保留，否则使用我们在反转列表中的索引位置。
                val chapterNumber = if (sChapter.chapter_number >= 0) {
                    sChapter.chapter_number
                } else {
                    (index + 1).toFloat()
                }
                sChapter.toKotoChapter(source, chapterNumber)
            }
            .sortedBy { it.number } // Kototoro 内部列表始终保持升序
        
        // Copy missing fields from original manga to details
        // Some sources don't return all fields in getContentDetails, or return them empty.
        details.url = sContent.url
        
        // Title fallback
        val detailsTitle = try { details.title } catch (e: Exception) { "" }
        if (detailsTitle.isBlank()) {
            details.title = sContent.title
        }
        
        // Thumbnail fallback - IMPORTANT: many sources return empty or same-as-manga-url thumbnail in details
        val detailsThumb = try { details.thumbnail_url } catch (e: Exception) { null }
        val searchThumb = try { sContent.thumbnail_url } catch (e: Exception) { null }
        
        if (detailsThumb.isNullOrBlank() || detailsThumb == details.url || detailsThumb == sContent.url) {
            if (!searchThumb.isNullOrBlank()) {
                android.util.Log.d("MihonMangaRepository", "Detail thumb is invalid/missing, falling back to search thumb: $searchThumb")
                details.thumbnail_url = searchThumb
            }
        }
        
        android.util.Log.d("MihonMangaRepository", "Final details thumbnail: ${try { details.thumbnail_url } catch (e: Exception) { "uninitialized" }}")
        
        val publicUrl = (mihonSource as? HttpSource)?.getPublicContentUrl(details) ?: ""
        
        details.toKotoContent(
            source = source,
            chapters = chapters,
            publicUrl = publicUrl,
        ).copy(id = manga.id)
    }
    
    override suspend fun getPagesImpl(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> = withContext(Dispatchers.IO) {
        val sChapter = chapter.toMihonChapter()
        val pages = rethrowMihonWrappedExceptions {
            withMihonSourceContext {
                mihonSource.getPageList(sChapter)
            }
        }
        
        pages.mapIndexed { index, page ->
            if (mihonSource !is HttpSource) {
                return@mapIndexed page.toKotoPage(source, sChapter)
            }

            val headers = try {
                if (!page.imageUrl.isNullOrBlank()) {
                    val h = mihonSource.getPageHeaders(page)
                    val map = mutableMapOf<String, String>()
                    for (i in 0 until h.size) {
                        map[h.name(i)] = h.value(i)
                    }
                    map
                } else {
                    emptyMap()
                }
            } catch (e: Exception) {
                emptyMap()
            }

            page.toKotoPage(source, sChapter, headers).let { kotoPage ->
                if (page.imageUrl.isNullOrBlank() && page.url.isNotBlank()) {
                    kotoPage.copy(
                        url = "mihon://resolve?page_url=${java.net.URLEncoder.encode(page.url, "UTF-8")}&index=$index"
                    )
                } else if (!page.imageUrl.isNullOrBlank() && page.url.isNotBlank() && page.url != page.imageUrl) {
                    kotoPage.copy(
                        url = "mihon://image?page_url=${java.net.URLEncoder.encode(page.url, "UTF-8")}&image_url=${java.net.URLEncoder.encode(page.imageUrl!!, "UTF-8")}&index=$index"
                    )
                } else {
                    kotoPage
                }
            }
        }
    }
    
    override suspend fun getPageUrl(page: ContentPage): String = withContext(Dispatchers.IO) {
        val url = page.url
        
        if (url.startsWith("mihon://")) {
            val uri = android.net.Uri.parse(url)
            if (url.startsWith("mihon://image")) {
                val imageUrl = uri.getQueryParameter("image_url")
                if (!imageUrl.isNullOrBlank()) return@withContext imageUrl
            } else if (url.startsWith("mihon://resolve")) {
                val pageUrl = uri.getQueryParameter("page_url")
                if (!pageUrl.isNullOrBlank()) {
                    val mihonPage = eu.kanade.tachiyomi.source.model.Page(0, pageUrl)
                    val httpSource = mihonSource as? HttpSource
                    if (httpSource != null) {
                        return@withContext rethrowMihonWrappedExceptions {
                            withMihonSourceContext {
                                httpSource.getImageUrl(mihonPage)
                            }
                        }
                    }
                    return@withContext pageUrl
                }
            }
            return@withContext url
        } else {
            url
        }
    }
    
    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val mihonFilters = try {
            withMihonSourceContext {
                mihonSource.getFilterList()
            }
        } catch (e: Exception) {
            FilterList()
        }
        
        return MihonFilterMapper.mapOptions(mihonFilters, source)
    }

    private fun ContentListFilter.toMihonFilterList(): FilterList {
        val mihonFilters = try {
            MihonRequestContext.withSourceBlocking(source) {
                mihonSource.getFilterList()
            }
        } catch (e: Exception) {
            return FilterList()
        }
        
        MihonFilterMapper.updateMihonFilters(mihonFilters, this)
        return mihonFilters
    }
    
    override fun getRequestHeaders(): Map<String, String> {
        val httpSource = mihonSource as? HttpSource ?: return emptyMap()
        val headers = httpSource.headers
        val map = mutableMapOf<String, String>()
        for (i in 0 until headers.size) {
            map[headers.name(i)] = headers.value(i)
        }
        return map
    }

    override fun getImageClient(): okhttp3.OkHttpClient? {
        return (mihonSource as? HttpSource)?.client
    }
    
    override fun createPageRequest(pageUrl: String, page: ContentPage): okhttp3.Request {
        if (pageUrl.isBlank()) return super.createPageRequest(pageUrl, page)
        val httpSource = mihonSource as? HttpSource ?: return super.createPageRequest(pageUrl, page)
        val sPage = page.toMihonPage(pageUrl)
        return httpSource.imageRequest(sPage)
    }

    override fun createCoverRequest(imageUrl: String): okhttp3.Request {
        val httpSource = mihonSource as? HttpSource ?: return super.createCoverRequest(imageUrl)
        val request = try {
            val sPage = eu.kanade.tachiyomi.source.model.Page(0, imageUrl = imageUrl)
            httpSource.imageRequest(sPage)
        } catch (e: Throwable) {
            // Fallback for sources that assume Page is always a chapter page (e.g. DM5 crashes on missing 'cid')
            return super.createCoverRequest(imageUrl)
        }
        if (request.header("Referer") == null &&
            (imageUrl.contains("hitomi.la") || imageUrl.contains("gold-usergeneratedcontent.net"))
        ) {
            return request.newBuilder().header("Referer", "https://hitomi.la/").build()
        }
        return request
    }

    override suspend fun fetchPageResponse(pageUrl: String, page: ContentPage): Response? {
        val httpSource = mihonSource as? HttpSource ?: return null
        val mihonPage = page.toMihonPage(pageUrl)
        android.util.Log.d(
            TAG,
            "fetchPageResponse: source=${source.name}, pageId=${page.id}, pageUrl=$pageUrl, mihonPage.url=${mihonPage.url}, mihonPage.imageUrl=${mihonPage.imageUrl}",
        )
        return rethrowMihonWrappedExceptions {
            withMihonSourceContext {
                httpSource.getImage(mihonPage).also { response ->
                    android.util.Log.d(
                        TAG,
                        "fetchPageResponse result: source=${source.name}, code=${response.code}, finalUrl=${response.request.url}, server=${response.header("server")}, cf-ray=${response.header("cf-ray")}, cf-mitigated=${response.header("cf-mitigated")}",
                    )
                }
            }
        }
    }

    private fun ContentPage.toMihonPage(imageUrl: String): eu.kanade.tachiyomi.source.model.Page {
        var pUrl = url
        var pImageUrl = imageUrl
        
        if (url.startsWith("mihon://")) {
            val uri = android.net.Uri.parse(url)
            val pageUrl = uri.getQueryParameter("page_url")
            if (!pageUrl.isNullOrBlank()) {
                pUrl = pageUrl
            }
            if (url.startsWith("mihon://image")) {
                val originalImageUrl = uri.getQueryParameter("image_url")
                if (!originalImageUrl.isNullOrBlank()) {
                    pImageUrl = originalImageUrl
                }
            }
        }

        return Page(
            index = id.toInt(), // Use id as index
            url = pUrl,
            imageUrl = pImageUrl
        )
    }

    private inline fun <T> rethrowMihonWrappedExceptions(block: () -> T): T {
        try {
            return block()
        } catch (e: RuntimeException) {
            when (val cause = e.cause) {
                is CloudFlareException -> throw cause
                is InteractiveActionRequiredException -> throw cause
                is java.io.IOException -> throw cause
                else -> throw e
            }
        }
    }

    private suspend fun <T> withMihonSourceContext(block: suspend () -> T): T {
        return MihonRequestContext.withSource(source, block)
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
}
