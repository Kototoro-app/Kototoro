package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.skepsun.kototoro.mihon.compat.MihonRequestContext
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/**
 * A simple implementation for sources from a website.
 * Ported from Aniyomi source-api for extension compatibility.
 */
@Suppress("unused")
abstract class AnimeHttpSource : AnimeCatalogueSource {
    /**
     * Network service.
     */
    protected val network: NetworkHelper by injectLazy()

    /**
     * Base url of the website without the trailing slash, like: http://mysite.com
     */
    abstract val baseUrl: String

    /**
     * Version id used to generate the source id. If the site completely changes and urls are
     * incompatible, you may increase this value and it'll be considered as a new source.
     */
    open val versionId = 1

    /**
     * ID of the source. By default it uses a generated id using the first 16 characters (64 bits)
     * of the MD5 of the string `"${name.lowercase()}/$lang/$versionId"`.
     */
    override val id by lazy { generateId(name, lang, versionId) }

    /**
     * Headers used for requests.
     */
    val headers: Headers by lazy { headersBuilder().build() }

    /**
     * Default network client for doing requests.
     */
    open val client: OkHttpClient
        get() = network.client

    /**
     * Generates a unique ID for the source.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    /**
     * Headers builder for requests. Implementations can override this method for custom headers.
     */
    protected open fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    /**
     * Visible name of the source.
     */
    override fun toString() = "$name (${lang.uppercase()})"

    // ======== Popular anime ========

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getPopularAnime"))
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        return sourceObservable {
            val response = client.newCall(tagRequest(popularAnimeRequest(page))).execute()
            popularAnimeParse(response)
        }
    }

    protected abstract fun popularAnimeRequest(page: Int): Request

    protected abstract fun popularAnimeParse(response: Response): AnimesPage

    // ======== Search anime ========

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getSearchAnime"))
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return Observable.defer {
            try {
                sourceObservable {
                    val response = client.newCall(tagRequest(searchAnimeRequest(page, query, filters))).execute()
                    searchAnimeParse(response)
                }
            } catch (e: NoClassDefFoundError) {
                throw RuntimeException(e)
            }
        }
    }

    protected abstract fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request

    protected abstract fun searchAnimeParse(response: Response): AnimesPage

    // ======== Latest updates ========

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> {
        return sourceObservable {
            val response = client.newCall(tagRequest(latestUpdatesRequest(page))).execute()
            latestUpdatesParse(response)
        }
    }

    protected abstract fun latestUpdatesRequest(page: Int): Request

    protected abstract fun latestUpdatesParse(response: Response): AnimesPage

    // ======== Anime details ========

    @Suppress("DEPRECATION")
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return fetchAnimeDetails(anime).toBlocking().first()
    }

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getAnimeDetails"))
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return sourceObservable {
            val response = client.newCall(tagRequest(animeDetailsRequest(anime))).execute()
            animeDetailsParse(response).apply { initialized = true }
        }
    }

    open fun animeDetailsRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers)
    }

    protected abstract fun animeDetailsParse(response: Response): SAnime

    // ======== Episode list ========

    @Suppress("DEPRECATION")
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return fetchEpisodeList(anime).toBlocking().first()
    }

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getEpisodeList"))
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        return sourceObservable {
            val response = client.newCall(tagRequest(episodeListRequest(anime))).execute()
            episodeListParse(response)
        }
    }

    protected open fun episodeListRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers)
    }

    protected abstract fun episodeListParse(response: Response): List<SEpisode>

    // ======== Season list (extensions-lib 16) ========

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        return try {
            val response = client.newCall(tagRequest(seasonListRequest(anime))).execute()
            seasonListParse(response)
        } catch (e: Exception) {
            emptyList()
        }
    }

    protected open fun seasonListRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers)
    }

    protected open fun seasonListParse(response: Response): List<SAnime> {
        return emptyList()
    }

    // ======== Video list ========

    @Suppress("DEPRECATION")
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return fetchVideoList(episode).toBlocking().first()
    }

    @Deprecated("Use the non-RxJava API instead", ReplaceWith("getVideoList"))
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        return sourceObservable {
            val response = client.newCall(tagRequest(videoListRequest(episode))).execute()
            videoListParse(response)
        }
    }

    protected open fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers)
    }

    protected abstract fun videoListParse(response: Response): List<Video>

    // ======== Hoster list (extensions-lib 16) ========

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        return try {
            val response = client.newCall(tagRequest(hosterListRequest(episode))).execute()
            hosterListParse(response)
        } catch (e: Exception) {
            emptyList()
        }
    }

    protected open fun hosterListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers)
    }

    protected open fun hosterListParse(response: Response): List<Hoster> {
        return emptyList()
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        return try {
            val response = client.newCall(tagRequest(videoListRequest(hoster))).execute()
            videoListParse(response, hoster)
        } catch (e: Exception) {
            emptyList()
        }
    }

    protected open fun videoListRequest(hoster: Hoster): Request {
        return GET(hoster.hosterUrl, headers)
    }

    protected open fun videoListParse(response: Response, hoster: Hoster): List<Video> {
        return emptyList()
    }

    // ======== Video URL resolution ========

    open suspend fun getVideoUrl(video: Video): String {
        return video.videoUrl
    }

    open suspend fun resolveVideo(video: Video): Video? {
        return video
    }

    // ======== URL helpers ========

    fun SEpisode.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SAnime.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = URI(orig.replace(" ", "%20"))
            var out = uri.path
            if (uri.query != null) {
                out += "?" + uri.query
            }
            if (uri.fragment != null) {
                out += "#" + uri.fragment
            }
            out
        } catch (e: URISyntaxException) {
            orig
        }
    }

    open fun getAnimeUrl(anime: SAnime): String {
        return animeDetailsRequest(anime).url.toString()
    }

    open fun getEpisodeUrl(episode: SEpisode): String {
        return episode.url
    }

    open fun prepareNewEpisode(episode: SEpisode, anime: SAnime) {}

    override fun getFilterList() = AnimeFilterList()

    private fun <T> sourceObservable(block: () -> T): Observable<T> {
        return Observable.fromCallable {
            MihonRequestContext.withSourceBlocking(aniyomiContentSource(), block)
        }
    }

    private fun aniyomiContentSource(): org.skepsun.kototoro.parsers.model.ContentSource {
        return org.skepsun.kototoro.core.model.ContentSource("ANIYOMI_$id")
    }

    private fun tagRequest(request: Request): Request {
        if (request.tag(org.skepsun.kototoro.parsers.model.ContentSource::class.java) != null) {
            return request
        }
        return request.newBuilder()
            .tag(
                org.skepsun.kototoro.parsers.model.ContentSource::class.java,
                aniyomiContentSource(),
            )
            .build()
    }
}
