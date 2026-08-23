package org.skepsun.kototoro.tsundoku

import androidx.collection.LruCache
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.RefreshContext
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.db.dao.SourceRefreshStateDao
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import org.skepsun.kototoro.core.model.isAdultTagKeyword
import org.skepsun.kototoro.core.parser.CachingContentRepository
import org.skepsun.kototoro.core.parser.RelatedContentSearchFallback
import org.skepsun.kototoro.extensions.recovery.NoOpSourceRefreshReporter
import org.skepsun.kototoro.extensions.recovery.SourceRefreshReporter
import org.skepsun.kototoro.mihon.MihonFilterMapper
import org.skepsun.kototoro.mihon.compat.MihonRequestContext
import org.skepsun.kototoro.mihon.model.getPublicContentUrl
import org.skepsun.kototoro.mihon.model.toKotoChapter
import org.skepsun.kototoro.mihon.model.toKotoPage
import org.skepsun.kototoro.mihon.model.toMihonChapter
import org.skepsun.kototoro.mihon.model.toMihonManga
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.parsers.model.RATING_UNKNOWN
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.tsundoku.model.TsundokuNovelSource
import java.net.URLEncoder

/**
 * Repository that adapts a Tsundoku novel source (a Tachiyomi-ABI [Source]) to Kototoro's
 * [ContentRepository] interface.
 *
 * Structure mirrors [org.skepsun.kototoro.mihon.MihonMangaRepository], adapted to
 * [TsundokuNovelSource]: the upstream ABI is reached through `source.upstreamSource` and all
 * catalogue/http facets are accessed via `as?` casts (the concrete upstream type is a real
 * Tsundoku extension instance, so facet presence depends on the extension).
 *
 * Novel semantics are keyed off [Source.isNovelSource]: text pages are base64 data-URLs and
 * chapter bodies come from [getChapterContent], while embedded-image pages fall back to the
 * Mihon-style image pipeline under the `tsundoku://` scheme.
 */
class TsundokuNovelRepository(
    override val source: TsundokuNovelSource,
    cache: MemoryContentCache,
    private val refreshReporter: SourceRefreshReporter = NoOpSourceRefreshReporter,
    /**
     * Optional read side of the per-content refresh bookkeeping (T3B.4 / Phase 5 recovery).
     * When present, its `lastSuccessAt` feeds the [RefreshContext.lastFetchTime] hint so
     * RefreshContext-aware extensions can skip redundant network work.
     */
    private val refreshStateDao: SourceRefreshStateDao? = null,
) : CachingContentRepository(cache) {

    companion object {
        private const val TAG = "TsundokuNovelRepository"
        private const val MANGA_SNAPSHOT_CACHE_SIZE = 100
        private const val CHAPTER_SNAPSHOT_CACHE_SIZE = 500
    }

    private var lastOffset = -1
    private var currentPage = 1
    private val mangaSnapshots = LruCache<String, SManga>(MANGA_SNAPSHOT_CACHE_SIZE)
    private val chapterSnapshots = LruCache<String, SChapter>(CHAPTER_SNAPSHOT_CACHE_SIZE)

    /** Raw ABI source instance; null-safe convenience for the common [HttpSource] facet. */
    private val upstreamSource: Source
        get() = source.upstreamSource

    /** Catalogue facet of the upstream extension, when it implements it. */
    private val catalogueSource: CatalogueSource?
        get() = upstreamSource as? CatalogueSource

    /** Http facet of the upstream extension, when it implements it. */
    private val httpSource: HttpSource?
        get() = upstreamSource as? HttpSource

    /** Whether the wrapped source is a novel (text) source. */
    private val isNovelSource: Boolean
        get() = upstreamSource.isNovelSource

    override val sortOrders: Set<SortOrder> = buildSet {
        add(SortOrder.POPULARITY)
        if (upstreamSource.supportsLatest) {
            add(SortOrder.UPDATED)
        }
    }

    override val filterCapabilities: ContentListFilterCapabilities = ContentListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isSearchWithFiltersSupported = true,
    )

    override var defaultSortOrder: SortOrder = SortOrder.POPULARITY

    // ==================== List ====================

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

        val catalogue = catalogueSource ?: return@withContext emptyList()
        val mangasPage = rethrowTsundokuWrappedExceptions {
            withTsundokuSourceContext {
                when {
                    hasFilters -> {
                        catalogue.getSearchManga(page, query ?: "", filter?.toTsundokuFilterList() ?: FilterList())
                    }
                    order == SortOrder.UPDATED && upstreamSource.supportsLatest -> {
                        catalogue.getLatestUpdates(page)
                    }
                    else -> {
                        catalogue.getPopularManga(page)
                    }
                }
            }
        }

        mangasPage.mangas.map { sContent ->
            rememberManga(sContent)
            sContent.toTsundokuContent(
                source = source,
                publicUrl = httpSource?.getPublicContentUrl(sContent) ?: "",
            )
        }
    }

    // ==================== Details ====================

    override suspend fun getDetailsImpl(manga: Content): Content = withContext(Dispatchers.IO) {
        refreshReporter.recordAttempt(source.sourceKey, manga.id)
        val details = try {
            fetchDetails(manga)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            refreshReporter.recordFailure(source.sourceKey, manga.id, classifyRefreshFailure(e))
            throw e
        }
        refreshReporter.recordSuccess(source.sourceKey, manga.id)
        details
    }

    /**
     * Fetches details + chapters for [manga].
     *
     * - Content that already has chapters goes through `getMangaDetails` plus the
     *   [RefreshContext]-aware `getChapterList` variant (a single ABI round may then be
     *   skipped by extensions that implement delta refresh; plain 1.4 extensions fall back to
     *   the plain `getChapterList` internally).
     * - Brand-new content goes through the combined [Source.getMangaUpdate] round-trip.
     */
    private suspend fun fetchDetails(manga: Content): Content {
        val sContent = mangaSnapshots[manga.url]?.copy() ?: manga.toMihonManga()
        val existingChapters = manga.chapters.orEmpty()

        suspend fun fetchOnce(): Pair<SManga, List<SChapter>> = rethrowTsundokuWrappedExceptions {
            withTsundokuSourceContext {
                if (existingChapters.isNotEmpty()) {
                    val details = upstreamSource.getMangaDetails(sContent)
                    val chapters = fetchChaptersWithRefreshContext(
                        sContent = sContent,
                        contentId = manga.id,
                        existingChapters = existingChapters,
                        forceRefresh = false,
                    )
                    details to chapters
                } else {
                    val update = upstreamSource.getMangaUpdate(
                        manga = sContent,
                        chapters = emptyList(),
                        fetchDetails = true,
                        fetchChapters = true,
                    )
                    update.manga to update.chapters
                }
            }
        }

        val (details, rawChapters) = try {
            fetchOnce()
        } catch (e: Exception) {
            val ioException = when {
                e is java.io.IOException -> e
                e.cause is java.io.IOException -> e.cause as java.io.IOException
                else -> null
            }
            if (ioException != null) {
                // Transient network failure: retry once after a short backoff (mirrors Mihon).
                delay(500)
                fetchOnce()
            } else {
                throw e
            }
        }

        details.applyDetailFallbacks(sContent)
        rememberManga(details)

        val chapters = mapRawChapters(rawChapters, sContent)
        val publicUrl = httpSource?.getPublicContentUrl(details) ?: ""

        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "fetchDetails: source=${source.name} chapters=${chapters.size}")
        }

        return details.toTsundokuContent(
            source = source,
            chapters = chapters,
            publicUrl = publicUrl,
        ).copy(id = manga.id)
    }

    /**
     * Refreshes the chapter list of [content], possibly forcing a full re-fetch.
     *
     * Always uses the [RefreshContext]-aware [Source.getChapterList] variant so the "force vs
     * incremental" semantic is explicit and testable. The regular details refresh keeps
     * `forceRefresh = false` (getDetailsImpl cannot observe [org.skepsun.kototoro.core.parser.CachingContentRepository]
     * callers' cache policy); callers that need a forced refresh use this method with `true`.
     *
     * @return the chapters mapped to Kototoro models, ascending.
     */
    suspend fun refreshChapters(content: Content, forceRefresh: Boolean): List<ContentChapter> =
        withContext(Dispatchers.IO) {
            val sContent = mangaSnapshots[content.url]?.copy() ?: content.toMihonManga()
            val rawChapters = fetchChaptersWithRefreshContext(
                sContent = sContent,
                contentId = content.id,
                existingChapters = content.chapters.orEmpty(),
                forceRefresh = forceRefresh,
            )
            mapRawChapters(rawChapters, sContent)
        }

    @Suppress("DEPRECATION") // RefreshContext-aware getChapterList is the deliberate Tsundoku entry point.
    private suspend fun fetchChaptersWithRefreshContext(
        sContent: SManga,
        contentId: Long,
        existingChapters: List<ContentChapter>,
        forceRefresh: Boolean,
    ): List<SChapter> = rethrowTsundokuWrappedExceptions {
        withTsundokuSourceContext {
            val context = RefreshContext(
                // The local stable id of the refreshed content (Kototoro Content.id; the ABI
                // SManga carries no id of its own).
                mangaId = contentId,
                existingChapters = existingChapters.map(ContentChapter::toMihonChapter),
                lastFetchTime = refreshStateDao?.get(source.sourceKey, contentId)?.lastSuccessAt ?: 0L,
                forceRefresh = forceRefresh,
            )
            upstreamSource.getChapterList(sContent, context)
        }
    }

    private fun mapRawChapters(rawChapters: List<SChapter>, sContent: SManga): List<ContentChapter> {
        return rawChapters.asReversed()
            .mapIndexed { index, sChapter ->
                val chapterNumber = if (sChapter.chapter_number >= 0) {
                    sChapter.chapter_number
                } else {
                    (index + 1).toFloat()
                }
                sChapter.toKotoChapter(source, chapterNumber, sContent.url).also { chapter ->
                    rememberChapter(chapter.id, sChapter)
                }
            }
            .sortedBy { it.number } // Kototoro 内部列表始终保持升序
    }

    // ==================== Pages ====================

    override suspend fun getPagesImpl(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> =
        withContext(Dispatchers.IO) {
            val sChapter = chapterSnapshots[chapter.id.toString()]?.snapshot()
                ?: chapter.toMihonChapter()
            val pages = rethrowTsundokuWrappedExceptions {
                withTsundokuSourceContext {
                    upstreamSource.getPageList(sChapter)
                }
            }

            val http = httpSource
            pages.mapIndexed { index, page ->
                val textContent = page.text?.takeIf { it.isNotBlank() }
                    ?: if (http != null && isNovelSource && page.imageUrl.isNullOrBlank() && page.url.isNotBlank()) {
                        // No inline text: ask the novel upstream for the chapter body.
                        runCatching { upstreamSource.fetchPageText(page) }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }

                if (textContent != null) {
                    ContentPage(
                        id = pageId(sChapter, chapter.id, index),
                        url = encodeChapterHtml(textContent),
                        preview = null,
                        source = source,
                    )
                } else {
                    toImageContentPage(page, sChapter, chapter.id, index)
                }
            }
        }

    /**
     * Novel body for offline download / rendering.
     *
     * Concatenates every page's text (inline `Page.text`, else [Source.fetchPageText]) into a
     * simple escaped `<p>`-wrapped HTML. Returns null when the upstream yields no text at all,
     * letting callers fall back to the [getPagesImpl] path.
     */
    override suspend fun getChapterContent(
        chapter: ContentChapter,
        nextChapterUrl: String?,
    ): NovelChapterContent? = withContext(Dispatchers.IO) {
        if (!isNovelSource) {
            return@withContext null
        }
        val sChapter = chapterSnapshots[chapter.id.toString()]?.snapshot()
            ?: chapter.toMihonChapter()
        val pages = rethrowTsundokuWrappedExceptions {
            withTsundokuSourceContext {
                upstreamSource.getPageList(sChapter)
            }
        }

        val parts = pages.mapNotNull { page ->
            page.text?.takeIf { it.isNotBlank() }
                ?: runCatching { upstreamSource.fetchPageText(page) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
        }
        if (parts.isEmpty()) {
            return@withContext null
        }

        val html = parts.joinToString("\n") { part -> "<p>${escapeHtml(part)}</p>" }
        val images = pages.mapNotNull { page ->
            page.imageUrl?.takeIf { it.isNotBlank() }
                ?.let { imageUrl -> NovelChapterContent.NovelImage(url = imageUrl, headers = pageHeaders(page)) }
        }
        NovelChapterContent(html = html, images = images)
    }

    private fun pageHeaders(page: Page): Map<String, String> {
        return try {
            val http = httpSource ?: return emptyMap()
            if (page.imageUrl.isNullOrBlank()) {
                emptyMap()
            } else {
                val headers = http.getPageHeaders(page)
                val map = mutableMapOf<String, String>()
                for (i in 0 until headers.size) {
                    map[headers.name(i)] = headers.value(i)
                }
                map
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // ==================== Page URL ====================

    override suspend fun getPageUrl(page: ContentPage): String = withContext(Dispatchers.IO) {
        val url = page.url

        if (url.startsWith("data:")) {
            return@withContext url
        }
        if (url.startsWith("tsundoku://")) {
            val uri = android.net.Uri.parse(url)
            if (url.startsWith("tsundoku://image")) {
                val imageUrl = uri.getQueryParameter("image_url")
                if (!imageUrl.isNullOrBlank()) return@withContext imageUrl
            } else if (url.startsWith("tsundoku://resolve")) {
                val pageUrl = uri.getQueryParameter("page_url")
                if (!pageUrl.isNullOrBlank()) {
                    val http = httpSource
                    if (http != null) {
                        return@withContext rethrowTsundokuWrappedExceptions {
                            withTsundokuSourceContext {
                                http.getImageUrl(Page(0, pageUrl))
                            }
                        }
                    }
                    return@withContext pageUrl
                }
            }
            return@withContext url
        }
        url
    }

    // ==================== Filters ====================

    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val filters = try {
            withTsundokuSourceContext {
                catalogueSource?.getFilterList() ?: FilterList()
            }
        } catch (e: Exception) {
            FilterList()
        }

        return MihonFilterMapper.mapOptions(filters, source)
    }

    private fun ContentListFilter.toTsundokuFilterList(): FilterList {
        val filters = try {
            MihonRequestContext.withSourceBlocking(source) {
                catalogueSource?.getFilterList() ?: FilterList()
            }
        } catch (e: Exception) {
            return FilterList()
        }

        MihonFilterMapper.updateMihonFilters(filters, this)
        return filters
    }

    // ==================== Related ====================

    override suspend fun getRelatedContentImpl(seed: Content): List<Content> {
        val catalogue = catalogueSource
        if (catalogue != null && catalogue.supportsRelatedMangas && !catalogue.disableRelatedMangas) {
            val manga = mangaSnapshots[seed.url]?.copy() ?: seed.toMihonManga()
            val related = rethrowTsundokuWrappedExceptions {
                withTsundokuSourceContext {
                    catalogue.fetchRelatedMangaList(manga)
                }
            }
            return related.map { relatedManga ->
                rememberManga(relatedManga)
                relatedManga.toTsundokuContent(
                    source = source,
                    publicUrl = httpSource?.getPublicContentUrl(relatedManga) ?: "",
                )
            }
        }

        if (catalogue != null && catalogue.disableRelatedMangasBySearch) return emptyList()

        return RelatedContentSearchFallback.find(seed) { query ->
            getList(
                offset = 0,
                order = defaultSortOrder,
                filter = ContentListFilter(query = query),
            )
        }
    }

    // ==================== Request plumbing ====================

    override fun getRequestHeaders(): Map<String, String> {
        val http = httpSource ?: return emptyMap()
        val headers = http.headers
        val map = mutableMapOf<String, String>()
        for (i in 0 until headers.size) {
            map[headers.name(i)] = headers.value(i)
        }
        return map
    }

    override fun getImageClient(): okhttp3.OkHttpClient? {
        return httpSource?.client
    }

    override fun createPageRequest(pageUrl: String, page: ContentPage): okhttp3.Request {
        if (pageUrl.isBlank()) return super.createPageRequest(pageUrl, page)
        val http = httpSource ?: return super.createPageRequest(pageUrl, page)
        return http.imageRequest(page.toMihonPage(pageUrl))
    }

    override fun createCoverRequest(imageUrl: String): okhttp3.Request {
        val http = httpSource ?: return super.createCoverRequest(imageUrl)
        return try {
            http.imageRequest(Page(0, imageUrl = imageUrl))
        } catch (e: Throwable) {
            // Some sources assume Page always describes a chapter page and crash otherwise.
            super.createCoverRequest(imageUrl)
        }
    }

    override suspend fun fetchPageResponse(pageUrl: String, page: ContentPage): okhttp3.Response? {
        val http = httpSource ?: return null
        val mihonPage = page.toMihonPage(pageUrl)
        return rethrowTsundokuWrappedExceptions {
            withTsundokuSourceContext {
                http.getImage(mihonPage)
            }
        }
    }

    private fun ContentPage.toMihonPage(imageUrl: String): Page {
        var pUrl = url
        var pImageUrl = imageUrl

        if (url.startsWith("tsundoku://")) {
            val uri = android.net.Uri.parse(url)
            val pageUrl = uri.getQueryParameter("page_url")
            if (!pageUrl.isNullOrBlank()) {
                pUrl = pageUrl
            }
            if (url.startsWith("tsundoku://image")) {
                val originalImageUrl = uri.getQueryParameter("image_url")
                if (!originalImageUrl.isNullOrBlank()) {
                    pImageUrl = originalImageUrl
                }
            }
        }

        return Page(
            index = id.toInt(), // Use id as index
            url = pUrl,
            imageUrl = pImageUrl,
        )
    }

    // ==================== Snapshotting ====================

    private fun rememberManga(manga: SManga) {
        val url = manga.readMihonField("") { url }.takeIf(String::isNotBlank) ?: return
        mangaSnapshots.put(url, manga.snapshot(url))
    }

    private fun SManga.applyDetailFallbacks(original: SManga) {
        val originalUrl = original.readMihonField("") { url }
        url = originalUrl

        if (readMihonField("") { title }.isBlank()) {
            title = original.readMihonField("Unknown") { title }.ifBlank { "Unknown" }
        }

        val detailsThumbnail = readMihonField<String?>(null) { thumbnail_url }
        val originalThumbnail = original.readMihonField<String?>(null) { thumbnail_url }
        if (
            (detailsThumbnail.isNullOrBlank() || detailsThumbnail == originalUrl) &&
            !originalThumbnail.isNullOrBlank()
        ) {
            thumbnail_url = originalThumbnail
        }

        val detailsMemo = readMihonField(JsonObject(emptyMap())) { memo }
        if (detailsMemo.isEmpty()) {
            val originalMemo = original.readMihonField(JsonObject(emptyMap())) { memo }
            if (originalMemo.isNotEmpty()) {
                memo = originalMemo
            }
        }
    }

    private fun SManga.snapshot(url: String): SManga = SManga.create().also { snapshot ->
        snapshot.url = url
        snapshot.title = readMihonField("") { title }
        snapshot.artist = readMihonField<String?>(null) { artist }
        snapshot.author = readMihonField<String?>(null) { author }
        snapshot.description = readMihonField<String?>(null) { description }
        snapshot.genre = readMihonField<String?>(null) { genre }
        snapshot.status = readMihonField(SManga.UNKNOWN) { status }
        snapshot.thumbnail_url = readMihonField<String?>(null) { thumbnail_url }
        snapshot.update_strategy = readMihonField(UpdateStrategy.ALWAYS_UPDATE) { update_strategy }
        snapshot.initialized = readMihonField(false) { initialized }

        copyCompatibleMihonField { snapshot.genres = genres }
        copyCompatibleMihonField { snapshot.altTitles = altTitles }
        copyCompatibleMihonField { snapshot.banner = banner }
        copyCompatibleMihonField { snapshot.contentRating = contentRating }
        copyCompatibleMihonField { snapshot.score = score }
        copyCompatibleMihonField { snapshot.readingMode = readingMode }
        copyCompatibleMihonField { snapshot.memo = memo }
    }

    private inline fun <T> SManga.readMihonField(defaultValue: T, getter: SManga.() -> T): T {
        return try {
            getter()
        } catch (_: UninitializedPropertyAccessException) {
            defaultValue
        } catch (_: AbstractMethodError) {
            defaultValue
        } catch (_: NoSuchMethodError) {
            defaultValue
        }
    }

    private inline fun copyCompatibleMihonField(copy: () -> Unit) {
        try {
            copy()
        } catch (_: UninitializedPropertyAccessException) {
            // Partial legacy model; the snapshot keeps its default value.
        } catch (_: AbstractMethodError) {
            // Extension was compiled against an older source API.
        } catch (_: NoSuchMethodError) {
            // Extension was compiled against an older source API.
        }
    }

    private fun rememberChapter(chapterId: Long, chapter: SChapter) {
        chapterSnapshots.put(chapterId.toString(), chapter.snapshot())
    }

    private fun SChapter.snapshot(): SChapter = SChapter.create().also { it.copyFrom(this) }

    // ==================== Tsundoku conversion helpers ====================
    //
    // Minimal adaptation of the Mihon converters. `SManga.toKotoContent` in the mihon package
    // hard-references `MihonMangaSource` (it reads `catalogueSource` / `isNsfw`), so it cannot
    // be reused for `TsundokuNovelSource`; this private twin keeps the exact same mapping and
    // ID scheme while reading those facets from the Tsundoku wrapper.

    private fun SManga.toTsundokuContent(
        source: TsundokuNovelSource,
        chapters: List<ContentChapter>? = null,
        publicUrl: String = "",
    ): Content {
        val baseUrl = httpSource?.baseUrl ?: ""

        val safeMemo = runCatching { memo }.getOrDefault(JsonObject(emptyMap()))
        val safeUrl = try {
            url
        } catch (e: UninitializedPropertyAccessException) {
            ""
        }
        val safeThumbnail = try {
            thumbnail_url
        } catch (e: UninitializedPropertyAccessException) {
            null
        }
        val absoluteThumbnailUrl = resolveUrl(baseUrl, safeThumbnail)
        val absolutePublicUrl = resolveUrl(baseUrl, safeUrl) ?: safeUrl
        val stableUrl = safeUrl.ifBlank { absolutePublicUrl }

        val safeTitle = try {
            title
        } catch (e: UninitializedPropertyAccessException) {
            "Unknown"
        }

        val safeGenres = try {
            genres
        } catch (e: Exception) {
            null
        }

        val safeAuthor = try {
            author
        } catch (e: UninitializedPropertyAccessException) {
            null
        }
        val safeArtist = try {
            artist
        } catch (e: UninitializedPropertyAccessException) {
            null
        }
        val safeDescription = try {
            description
        } catch (e: UninitializedPropertyAccessException) {
            null
        }
        val safeStatus = try {
            status
        } catch (e: UninitializedPropertyAccessException) {
            SManga.UNKNOWN
        }

        val safeAltTitles = try {
            altTitles.toSet()
        } catch (e: NoSuchMethodError) {
            emptySet()
        }

        val safeBanner = try {
            banner?.let { resolveUrl(baseUrl, it) }
        } catch (e: NoSuchMethodError) {
            null
        }

        val calculatedRating = try {
            val safeScore = score
            if (safeScore != null && safeScore > 0) {
                if (safeScore <= 10) safeScore / 10f
                else if (safeScore <= 100) safeScore / 100f
                else RATING_UNKNOWN
            } else {
                RATING_UNKNOWN
            }
        } catch (e: NoSuchMethodError) {
            RATING_UNKNOWN
        }

        val generatedId = generateContentId(stableUrl, source.name, safeTitle)

        return Content(
            id = generatedId,
            title = safeTitle,
            altTitles = safeAltTitles,
            url = stableUrl,
            publicUrl = if (publicUrl.isNotBlank()) publicUrl else absolutePublicUrl,
            rating = calculatedRating,
            contentRating = run {
                val explicitRating = try {
                    when (contentRating) {
                        SManga.ContentRating.SAFE -> ContentRating.SAFE
                        SManga.ContentRating.SUGGESTIVE -> ContentRating.SUGGESTIVE
                        SManga.ContentRating.ADULT -> ContentRating.ADULT
                        else -> null
                    }
                } catch (e: NoSuchMethodError) {
                    null
                }

                if (source.isNsfw) {
                    ContentRating.ADULT
                } else if (explicitRating != null) {
                    explicitRating
                } else {
                    val safeTags = setOf(
                        "safe", "all ages", "non-h", "sfw", "非h", "正常向", "全年龄", "全年龄向",
                    )
                    val isExplicitlySafe = safeGenres?.any { it.lowercase() in safeTags } == true
                    val isContentNsfw = (!isExplicitlySafe && source.isNsfw) ||
                        safeGenres?.any { it.isAdultTagKeyword() } == true

                    if (isExplicitlySafe) {
                        ContentRating.SAFE
                    } else if (isContentNsfw) {
                        ContentRating.ADULT
                    } else {
                        null
                    }
                }
            },
            coverUrl = absoluteThumbnailUrl,
            largeCoverUrl = safeBanner ?: absoluteThumbnailUrl,
            tags = safeGenres?.mapNotNull { genreName ->
                val clean = genreName.cleanMihonGenre()
                if (clean.isEmpty()) {
                    null
                } else {
                    ContentTag(
                        title = clean,
                        key = clean.lowercase().replace(" ", "_"),
                        source = source,
                    )
                }
            }?.toSet() ?: emptySet(),
            state = when (safeStatus) {
                SManga.ONGOING -> ContentState.ONGOING
                SManga.COMPLETED -> ContentState.FINISHED
                SManga.ON_HIATUS -> ContentState.PAUSED
                SManga.CANCELLED -> ContentState.ABANDONED
                SManga.LICENSED -> ContentState.RESTRICTED
                SManga.PUBLISHING_FINISHED -> ContentState.FINISHED
                else -> null
            },
            authors = buildSet {
                safeAuthor?.takeIf { it.isNotBlank() }?.let { add(it) }
                safeArtist?.takeIf { it.isNotBlank() && it != safeAuthor }?.let { add(it) }
            },
            description = safeDescription,
            chapters = chapters,
            source = source,
            sourceData = safeMemo.takeIf { it.isNotEmpty() }?.toString(),
        )
    }

    private fun generateContentId(url: String, sourceName: String, title: String): Long {
        val identity = url.ifBlank { title.ifBlank { "unknown" } }
        return "$sourceName|manga|$identity".longHashCode() and Long.MAX_VALUE
    }

    private fun resolveUrl(baseUrl: String, url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"

        if (baseUrl.isNotBlank()) {
            return baseUrl.trimEnd('/') + "/" + url.trimStart('/')
        }
        return url
    }

    private fun String.cleanMihonGenre(): String {
        // "ClassName(field=value, ...)" or "ClassName(field=value" (split) → first field value
        val classPattern = Regex("""^\w+\((\w+)=([^,)]+)""")
        val match = classPattern.find(this)
        if (match != null) return match.groupValues[2]
        // Fragment like "field=value)" without a class prefix → discard
        if (this.matches(Regex("""^\w+=[^,)]+\)?$"""))) return ""
        return this
    }

    // ==================== Encoding helpers ====================

    private fun pageId(sChapter: SChapter, chapterId: Long, index: Int): Long {
        // Mirrors Mihon's Page.toKotoPage id hashing so ids stay unique per chapter.
        return "${chapterId}|page|$index".hashCode().toLong() and Long.MAX_VALUE
    }

    private fun encodeChapterHtml(text: String): String {
        // Standard alphabet + padding, no line breaks == android.util.Base64.NO_WRAP wire
        // format, but java.util.Base64 also works in plain JVM unit tests. Decoders use
        // Base64.DEFAULT, which accepts this form.
        val encoded = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        return "data:text/html;base64,$encoded"
    }

    private fun escapeHtml(value: String): String = buildString(value.length + 16) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private fun toImageContentPage(page: Page, sChapter: SChapter, chapterId: Long, index: Int): ContentPage {
        val kotoPage = page.toKotoPage(source, sChapter, chapterId, pageHeaders(page))
        return when {
            page.imageUrl.isNullOrBlank() && page.url.isNotBlank() -> {
                kotoPage.copy(
                    url = "tsundoku://resolve?page_url=${URLEncoder.encode(page.url, "UTF-8")}&index=$index",
                )
            }
            !page.imageUrl.isNullOrBlank() && page.url.isNotBlank() && page.url != page.imageUrl -> {
                kotoPage.copy(
                    url = "tsundoku://image?page_url=${URLEncoder.encode(page.url, "UTF-8")}" +
                        "&image_url=${URLEncoder.encode(page.imageUrl!!, "UTF-8")}&index=$index",
                )
            }
            else -> kotoPage
        }
    }

    // ==================== Error handling ====================

    private inline fun <T> rethrowTsundokuWrappedExceptions(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        if (e is android.os.NetworkOnMainThreadException) throw e
        if (
            e is org.skepsun.kototoro.core.exceptions.CloudFlareException ||
            e is org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
        ) {
            throw e
        }
        if (e is java.io.IOException) throw e
        throw e
    }

    private suspend fun <T> withTsundokuSourceContext(block: suspend () -> T): T =
        MihonRequestContext.withSource(source, block)

    private fun classifyRefreshFailure(error: Throwable): String {
        val message = error.message?.take(200).orEmpty()
        return when (error) {
            is CloudFlareException -> "cloudflare: $message"
            is InteractiveActionRequiredException -> "interactive: $message"
            is java.io.IOException -> "io: $message"
            else -> "${error::class.simpleName}: $message"
        }
    }
}
