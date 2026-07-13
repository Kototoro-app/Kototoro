package org.skepsun.kototoro.aniyomi

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.skepsun.kototoro.core.exceptions.CloudFlareException
import org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.parser.CachingContentRepository
import org.skepsun.kototoro.core.parser.RelatedContentSearchFallback
import org.skepsun.kototoro.aniyomi.model.AniyomiAnimeSource
import org.skepsun.kototoro.aniyomi.model.getPublicAnimeUrl
import org.skepsun.kototoro.aniyomi.model.toAniyomiAnime
import org.skepsun.kototoro.aniyomi.model.toAniyomiEpisode
import org.skepsun.kototoro.aniyomi.model.toKotoChapter
import org.skepsun.kototoro.aniyomi.model.toKotoContent
import org.skepsun.kototoro.aniyomi.model.toKotoPage
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.SortOrder

/**
 * Repository that adapts an Aniyomi AnimeCatalogueSource to Kototoro's ContentRepository interface.
 */
class AniyomiAnimeRepository(
    override val source: AniyomiAnimeSource,
    cache: MemoryContentCache,
) : CachingContentRepository(cache) {
    
    private var lastOffset = -1
    private var currentPage = 1
    
    val aniyomiSource = source.animeCatalogueSource
    
    override val sortOrders: Set<SortOrder> = buildSet {
        add(SortOrder.POPULARITY)
        if (aniyomiSource.supportsLatest) {
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
        
        val animesPage = when {
            hasFilters -> {
                aniyomiSource.getSearchAnime(page, query ?: "", filter?.toAniyomiFilterList() ?: AnimeFilterList())
            }
            order == SortOrder.UPDATED && aniyomiSource.supportsLatest -> {
                aniyomiSource.getLatestUpdates(page)
            }
            else -> {
                aniyomiSource.getPopularAnime(page)
            }
        }
        
        animesPage.animes.map { sAnime ->
            sAnime.toKotoContent(
                source = source,
                publicUrl = (aniyomiSource as? AnimeHttpSource)?.getPublicAnimeUrl(sAnime) ?: "",
            )
        }
    }
    
    override suspend fun getDetailsImpl(manga: Content): Content = withContext(Dispatchers.IO) {
        val sAnime = manga.toAniyomiAnime()
        
        val details = try {
            aniyomiSource.getAnimeDetails(sAnime)
        } catch (e: Exception) {
            rethrowAniyomiWrappedExceptions(e)
            val ioException = when {
                e is java.io.IOException -> e
                e.cause is java.io.IOException -> e.cause as java.io.IOException
                else -> null
            }
            if (ioException != null) {
                kotlinx.coroutines.delay(500)
                try {
                    aniyomiSource.getAnimeDetails(sAnime)
                } catch (retryError: Exception) {
                    rethrowAniyomiWrappedExceptions(retryError)
                    throw retryError
                }
            } else {
                throw e
            }
        }

        val rawEpisodes = try {
            aniyomiSource.getEpisodeList(sAnime)
        } catch (e: Exception) {
            rethrowAniyomiWrappedExceptions(e)
            val ioException = when {
                e is java.io.IOException -> e
                e.cause is java.io.IOException -> e.cause as java.io.IOException
                else -> null
            }
            if (ioException != null) {
                kotlinx.coroutines.delay(500)
                try {
                    aniyomiSource.getEpisodeList(sAnime)
                } catch (retryError: Exception) {
                    rethrowAniyomiWrappedExceptions(retryError)
                    throw retryError
                }
            } else {
                throw e
            }
        }
        
        // Reverse and assign numbers if missing, like in MihonMangaRepository
        val chapters = rawEpisodes.asReversed()
            .mapIndexed { index, sEpisode ->
                val episodeNumber = if (sEpisode.episode_number > 0) {
                    sEpisode.episode_number
                } else {
                    (index + 1).toFloat()
                }
                sEpisode.toKotoChapter(source, episodeNumber)
            }
            .sortedBy { it.number }
        
        details.url = sAnime.url
        
        // Title fallback
        val detailsTitle = try { details.title } catch (e: Exception) { "" }
        if (detailsTitle.isNullOrBlank()) {
            details.title = sAnime.title
        }
        
        // Thumbnail fallback
        val detailsThumb = try { details.thumbnail_url } catch (e: Exception) { null }
        if (detailsThumb.isNullOrBlank() || detailsThumb == details.url) {
            if (!sAnime.thumbnail_url.isNullOrBlank()) {
                details.thumbnail_url = sAnime.thumbnail_url
            }
        }
        
        val publicUrl = (aniyomiSource as? AnimeHttpSource)?.getPublicAnimeUrl(details) ?: ""
        
        details.toKotoContent(
            source = source,
            chapters = chapters,
            publicUrl = publicUrl,
        ).copy(id = manga.id)
    }
    
    override suspend fun getPagesImpl(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> = withContext(Dispatchers.IO) {
        android.util.Log.d("AniyomiRepo", "getPagesImpl called for chapter: ${chapter.title} (${chapter.url})")
        val sEpisode = chapter.toAniyomiEpisode()
        val videos = fetchVideoList(sEpisode)
        
        videos.mapIndexed { index, video ->
            android.util.Log.d("AniyomiRepo", "Video $index: url=${video.videoUrl}, quality=${video.videoTitle}")
            video.toKotoPage(source, sEpisode, index)
        }
    }

    suspend fun getVideoListForChapter(chapter: ContentChapter): List<Video> = withContext(Dispatchers.IO) {
        val sEpisode = chapter.toAniyomiEpisode()
        fetchVideoList(sEpisode)
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
    
    override suspend fun getPageUrl(page: ContentPage): String = withContext(Dispatchers.IO) {
        // For video, the URL is already the stream URL
        page.url
    }
    
    override suspend fun getFilterOptions(): ContentListFilterOptions {
        val aniyomiFilters = try {
            aniyomiSource.getFilterList()
        } catch (e: Exception) {
            AnimeFilterList()
        }
        
        return AniyomiFilterMapper.mapOptions(aniyomiFilters, source)
    }

    private fun ContentListFilter.toAniyomiFilterList(): AnimeFilterList {
        val aniyomiFilters = try {
            aniyomiSource.getFilterList()
        } catch (e: Exception) {
            return AnimeFilterList()
        }
        
        AniyomiFilterMapper.updateAniyomiFilters(aniyomiFilters, this)
        return aniyomiFilters
    }
    
    override fun getRequestHeaders(): Map<String, String> {
        val httpSource = aniyomiSource as? AnimeHttpSource ?: return emptyMap()
        val headers = httpSource.headers
        val map = mutableMapOf<String, String>()
        for (i in 0 until headers.size) {
            map[headers.name(i)] = headers.value(i)
        }
        return map
    }

    override fun createCoverRequest(imageUrl: String): Request {
        val httpSource = aniyomiSource as? AnimeHttpSource ?: return super.createCoverRequest(imageUrl)
        return GET(imageUrl, httpSource.headers)
            .newBuilder()
            .tag(org.skepsun.kototoro.parsers.model.ContentSource::class.java, source)
            .build()
    }

    override fun getImageClient(): OkHttpClient? {
        return (aniyomiSource as? AnimeHttpSource)?.client
    }

    private suspend fun fetchVideoList(sEpisode: SEpisode): List<Video> {
        return try {
            android.util.Log.d("AniyomiRepo", "Calling getVideoList...")
            val result = aniyomiSource.getCompatibleVideoList(sEpisode)
            android.util.Log.d("AniyomiRepo", "getVideoList returned ${result.size} videos")
            result
        } catch (e: Exception) {
            android.util.Log.e("AniyomiRepo", "getVideoList failed: ${e.message}", e)
            rethrowAniyomiWrappedExceptions(e)
            val ioException = when {
                e is java.io.IOException -> e
                e.cause is java.io.IOException -> e.cause as java.io.IOException
                else -> null
            }
            if (ioException != null) {
                kotlinx.coroutines.delay(500)
                try {
                    aniyomiSource.getCompatibleVideoList(sEpisode)
                } catch (retryError: Exception) {
                    rethrowAniyomiWrappedExceptions(retryError)
                    throw retryError
                }
            } else {
                throw e
            }
        }
    }

    private fun rethrowAniyomiWrappedExceptions(error: Throwable) {
        when (val cause = error.cause) {
            is CloudFlareException -> throw cause
            is InteractiveActionRequiredException -> throw cause
            is java.io.IOException -> throw cause
        }
    }
}

internal suspend fun AnimeSource.getCompatibleVideoList(episode: SEpisode): List<Video> {
    var legacyFailure: Throwable? = null
    val legacyVideos = try {
        getVideoList(episode)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        legacyFailure = e
        emptyList()
    }
    if (legacyVideos.isNotEmpty()) {
        return resolveVideos(legacyVideos)
    }

    val hosters = try {
        getHosterList(episode)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        emptyList()
    }
    if (hosters.isNotEmpty()) {
        val videos = hosters.flatMap { hoster ->
            hoster.videoList ?: getVideoList(hoster)
        }
        return resolveVideos(videos)
    }

    legacyFailure?.let { throw it }
    return emptyList()
}

private suspend fun AnimeSource.resolveVideos(videos: List<Video>): List<Video> {
    val httpSource = this as? AnimeHttpSource ?: return videos
    return videos.mapNotNull { video ->
        if (video.initialized) video else httpSource.resolveVideo(video)
    }
}
