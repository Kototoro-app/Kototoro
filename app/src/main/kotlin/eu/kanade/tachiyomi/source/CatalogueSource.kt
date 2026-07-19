package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable

/**
 * Mihon-compatible CatalogueSource interface.
 * A source that supports browsing and searching.
 */
interface CatalogueSource : Source {

	/**
	 * Whether the source provides related manga directly.
	 *
	 * Keiyoushi's v16 API uses this contract; legacy sources keep the default
	 * disabled value and continue through Kototoro's title-search fallback.
	 */
	open val supportsRelatedMangas: Boolean
		get() = false

	open val disableRelatedMangasBySearch: Boolean
		get() = false

	open val disableRelatedMangas: Boolean
		get() = false

	open suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = emptyList()

    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    override val lang: String

    /**
     * Whether the source has support for latest updates.
     */
    override val supportsLatest: Boolean

    /**
     * Get a page with a list of manga.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     */
    @Suppress("DEPRECATION")
    override suspend fun getPopularManga(page: Int): MangasPage {
        return fetchPopularManga(page).toBlocking().first()
    }

    /**
     * Get a page with a list of manga.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    @Suppress("DEPRECATION")
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        return fetchSearchManga(page, query, filters).toBlocking().first()
    }

    /**
     * Get a page with a list of latest manga updates.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     */
    @Suppress("DEPRECATION")
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        return fetchLatestUpdates(page).toBlocking().first()
    }

    /**
     * Returns the list of filters for the source.
     */
    override fun getFilterList(): FilterList

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPopularManga"),
    )
    fun fetchPopularManga(page: Int): Observable<MangasPage> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getSearchManga"),
    )
    fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getLatestUpdates"),
    )
    fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
        throw IllegalStateException("Not used")
}
