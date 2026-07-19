package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.skepsun.kototoro.mihon.compat.KotoNetworkHelper
import org.skepsun.kototoro.mihon.compat.MihonRequestContext
import org.skepsun.kototoro.parsers.model.ContentSource
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/**
 * A simple implementation for sources from a website.
 * Ported from Mihon source-api for extension compatibility.
 */
@Suppress("unused")
abstract class HttpSource : CatalogueSource {

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
     *
     * Legacy Mihon sources historically received Brotli decoding through
     * NetworkHelper.cloudflareClient. KeiSource overrides this property and
     * uses the Brotli-free NetworkHelper.client required by its own
     * CompressionInterceptor contract.
     */
    open val client: OkHttpClient
        get() = network.cloudflareClient

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

    /**
     * URL opened when browsing the source in a WebView.
     */
    open fun getHomeUrl(): String = baseUrl

    // ======== Popular manga ========

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPopularManga"))
    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return legacyFetch(
            request = { popularMangaRequest(page) },
            parser = ::popularMangaParse,
        )
    }

    @Suppress("DEPRECATION")
    override suspend fun getPopularManga(page: Int): MangasPage {
        if (overridesFetchWithoutRequestHelper("fetchPopularManga", "popularMangaRequest", Integer.TYPE)) {
            return fetchPopularManga(page).toBlocking().first()
        }
        return try {
            val request = tagRequest(popularMangaRequest(page))
            client.newCall(request).awaitSuccess().use { response ->
                popularMangaParse(response)
            }
        } catch (e: UnsupportedOperationException) {
            customFetchFallback(e, "fetchPopularManga", Integer.TYPE) {
                fetchPopularManga(page).toBlocking().first()
            }
        }
    }

    protected abstract fun popularMangaRequest(page: Int): Request

    protected abstract fun popularMangaParse(response: Response): MangasPage

    // ======== Search manga ========

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getSearchManga"))
    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        return legacyFetch(
            request = { searchMangaRequest(page, query, filters) },
            parser = ::parseSearchResponse,
        )
    }

    @Suppress("DEPRECATION")
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (
            overridesFetchWithoutRequestHelper(
                "fetchSearchManga",
                "searchMangaRequest",
                Integer.TYPE,
                String::class.java,
                FilterList::class.java,
            )
        ) {
            return fetchSearchManga(page, query, filters).toBlocking().first()
        }
        return try {
            val request = tagRequest(searchMangaRequest(page, query, filters))
            client.newCall(request).awaitSuccess().use { response ->
                parseSearchResponse(response)
            }
        } catch (e: UnsupportedOperationException) {
            customFetchFallback(e, "fetchSearchManga", Integer.TYPE, String::class.java, FilterList::class.java) {
                fetchSearchManga(page, query, filters).toBlocking().first()
            }
        }
    }

    protected abstract fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request

    protected abstract fun searchMangaParse(response: Response): MangasPage

    private fun parseSearchResponse(response: Response): MangasPage {
        val responseBody = response.body.string()
        val contentType = response.body.contentType()
        val parseResponse = response.newBuilder()
            .body(responseBody.toResponseBody(contentType))
            .build()
        return runCatching {
            searchMangaParse(parseResponse)
        }.getOrElse { error ->
            val fallbackResponse = response.newBuilder()
                .body(responseBody.toResponseBody(contentType))
                .build()
            parseSearchRedirectedToDetails(fallbackResponse, error)
        }
    }

    private fun parseSearchRedirectedToDetails(response: Response, error: Throwable): MangasPage {
        val finalUrl = response.header(KotoNetworkHelper.WEBVIEW_FINAL_URL_HEADER)
            ?: response.request.url.toString()
        val finalPath = runCatching { URI(finalUrl.replace(" ", "%20")).path.orEmpty() }.getOrDefault("")
        if (!response.request.url.encodedPath.startsWith("/search/") || !finalPath.startsWith("/detail/")) {
            throw error
        }
        val manga = try {
            mangaDetailsParse(response).apply {
                setUrlWithoutDomain(finalUrl)
                initialized = true
            }
        } catch (_: Throwable) {
            SManga.create().apply {
                setUrlWithoutDomain(finalUrl)
                title = finalPath.substringAfterLast('/').substringBefore('.').ifBlank { finalUrl }
                initialized = true
            }
        }
        return MangasPage(listOf(manga), hasNextPage = false)
    }

    // ======== Latest updates ========

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getLatestUpdates"))
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return legacyFetch(
            request = { latestUpdatesRequest(page) },
            parser = ::latestUpdatesParse,
        )
    }

    @Suppress("DEPRECATION")
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (overridesFetchWithoutRequestHelper("fetchLatestUpdates", "latestUpdatesRequest", Integer.TYPE)) {
            return fetchLatestUpdates(page).toBlocking().first()
        }
        return try {
            val request = tagRequest(latestUpdatesRequest(page))
            client.newCall(request).awaitSuccess().use { response ->
                latestUpdatesParse(response)
            }
        } catch (e: UnsupportedOperationException) {
            customFetchFallback(e, "fetchLatestUpdates", Integer.TYPE) {
                fetchLatestUpdates(page).toBlocking().first()
            }
        }
    }

    protected abstract fun latestUpdatesRequest(page: Int): Request

	protected abstract fun latestUpdatesParse(response: Response): MangasPage

	// ======== Related manga ========

	override val supportsRelatedMangas: Boolean
		get() = false

	protected open fun relatedMangaListRequest(manga: SManga): Request {
		throw UnsupportedOperationException("Related manga request is not implemented")
	}

	protected open fun relatedMangaListParse(response: Response): List<SManga> {
		throw UnsupportedOperationException("Related manga parser is not implemented")
	}

    // ======== Content details ========

    @Suppress("DEPRECATION")
    override suspend fun getMangaDetails(manga: SManga): SManga {
        if (overridesFetchWithoutRequestHelper("fetchMangaDetails", "mangaDetailsRequest", SManga::class.java)) {
            return fetchMangaDetails(manga).toBlocking().first()
        }
        return try {
            val request = tagRequest(mangaDetailsRequest(manga))
            client.newCall(request).awaitSuccess().use { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
        } catch (e: UnsupportedOperationException) {
            customFetchFallback(e, "fetchMangaDetails", SManga::class.java) {
                fetchMangaDetails(manga).toBlocking().first()
            }
        }
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getMangaDetails"))
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return legacyFetch(
            request = { mangaDetailsRequest(manga) },
            parser = { response -> mangaDetailsParse(response).apply { initialized = true } },
        )
    }

    open fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    protected abstract fun mangaDetailsParse(response: Response): SManga

    // ======== Chapter list ========

    @Suppress("DEPRECATION")
    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        if (overridesFetchChapterList()) {
            return fetchChapterList(manga).toBlocking().first()
        }
        return try {
            val request = tagRequest(chapterListRequest(manga))
            client.newCall(request).awaitSuccess().use { response ->
                chapterListParse(response)
            }
        } catch (e: UnsupportedOperationException) {
            customFetchFallback(e, "fetchChapterList", SManga::class.java) {
                fetchChapterList(manga).toBlocking().first()
            }
        }
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) getMangaDetails(manga) else manga
        val updatedChapters = if (fetchChapters) getChapterList(manga) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getChapterList"))
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return legacyFetch(
            request = { chapterListRequest(manga) },
            parser = ::chapterListParse,
        )
    }

    protected open fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    protected abstract fun chapterListParse(response: Response): List<SChapter>

    // ======== Page list ========

    @Suppress("DEPRECATION")
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (overridesFetchWithoutRequestHelper("fetchPageList", "pageListRequest", SChapter::class.java)) {
            return fetchPageList(chapter).toBlocking().first()
        }
        return try {
            val request = tagRequest(pageListRequest(chapter))
            client.newCall(request).awaitSuccess().use { response ->
                pageListParse(response)
            }
        } catch (e: UnsupportedOperationException) {
            customFetchFallback(e, "fetchPageList", SChapter::class.java) {
                fetchPageList(chapter).toBlocking().first()
            }
        }
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPageList"))
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return legacyFetch(
            request = { pageListRequest(chapter) },
            parser = ::pageListParse,
        )
    }

    protected open fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    protected abstract fun pageListParse(response: Response): List<Page>

    // ======== Image URL ========

    @Suppress("DEPRECATION")
    open suspend fun getImageUrl(page: Page): String {
        if (overridesFetchWithoutRequestHelper("fetchImageUrl", "imageUrlRequest", Page::class.java)) {
            return fetchImageUrl(page).toBlocking().first()
        }
        return try {
            val request = tagRequest(imageUrlRequest(page))
            client.newCall(request).awaitSuccess().use { response ->
                imageUrlParse(response)
            }
        } catch (e: UnsupportedOperationException) {
            customFetchFallback(e, "fetchImageUrl", Page::class.java) {
                fetchImageUrl(page).toBlocking().first()
            }
        }
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getImageUrl"))
    open fun fetchImageUrl(page: Page): Observable<String> {
        return legacyFetch(
            request = { imageUrlRequest(page) },
            parser = ::imageUrlParse,
        )
    }

    protected open fun imageUrlRequest(page: Page): Request {
        return GET(page.url, headers)
    }

    protected abstract fun imageUrlParse(response: Response): String

    private fun tagRequest(request: Request): Request {
        if (request.tag(ContentSource::class.java) != null) {
            return request
        }
        return request.newBuilder()
            .tag(
                ContentSource::class.java,
                mihonContentSource(),
            )
            .build()
    }

    private fun <T> sourceObservable(block: () -> T): Observable<T> {
        return Observable.fromCallable {
            MihonRequestContext.withSourceBlocking(mihonContentSource(), block)
        }
    }

    private fun <T> legacyFetch(
        request: () -> Request,
        parser: (Response) -> T,
    ): Observable<T> {
        return sourceObservable {
            client.newCall(tagRequest(request())).execute().use { response ->
                if (!response.isSuccessful) {
                    throw HttpException(response.code)
                }
                parser(response)
            }
        }
    }

    private fun mihonContentSource(): ContentSource {
        return org.skepsun.kototoro.core.model.ContentSource("MIHON_$id")
    }

    @Suppress("DEPRECATION")
    private fun <T> customFetchFallback(
        error: UnsupportedOperationException,
        methodName: String,
        vararg parameterTypes: Class<*>,
        fallback: () -> T,
    ): T {
        val declaringClass = findMethodDeclaringClass(methodName, *parameterTypes)
        if (declaringClass != null && declaringClass != HttpSource::class.java) {
            return fallback()
        }
        throw error
    }

    private fun overridesMethod(methodName: String, vararg parameterTypes: Class<*>): Boolean {
        val declaringClass = findMethodDeclaringClass(methodName, *parameterTypes)
        return declaringClass != null && declaringClass != HttpSource::class.java
    }

    private fun overridesFetchWithoutRequestHelper(
        fetchMethodName: String,
        requestMethodName: String,
        vararg parameterTypes: Class<*>,
    ): Boolean {
        return overridesMethod(fetchMethodName, *parameterTypes) &&
            !overridesMethod(requestMethodName, *parameterTypes)
    }

    // Some legacy sources keep pagination or normalization in fetchChapterList while still
    // exposing chapterListRequest/chapterListParse helpers. Preserve that legacy entry point.
    private fun overridesFetchChapterList(): Boolean {
        return overridesMethod("fetchChapterList", SManga::class.java)
    }

    private fun findMethodDeclaringClass(methodName: String, vararg parameterTypes: Class<*>): Class<*>? {
        var current: Class<*>? = javaClass
        while (current != null && current != Any::class.java) {
            val method = current.declaredMethods.firstOrNull { candidate ->
                candidate.name == methodName && candidate.parameterTypes.contentEquals(parameterTypes)
            }
            if (method != null) {
                return current
            }
            current = current.superclass
        }
        return null
    }

    // ======== Image request ========

    open suspend fun getImage(page: Page): Response {
        val request = tagRequest(imageRequest(page))
        return client.newCachelessCallWithProgress(request, page).awaitSuccess()
    }

    open fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, headers)
    }

    /**
     * Public helper to get headers for a page.
     */
    fun getPageHeaders(page: Page): Headers {
        return imageRequest(page).headers
    }

    // ======== URL helpers ========

    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SManga.setUrlWithoutDomain(url: String) {
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

    open fun getMangaUrl(manga: SManga): String {
        return mangaDetailsRequest(manga).url.toString()
    }

    open fun getChapterUrl(chapter: SChapter): String {
        return pageListRequest(chapter).url.toString()
    }

    open fun prepareNewChapter(chapter: SChapter, manga: SManga) {}

    override fun getFilterList() = FilterList()
}
