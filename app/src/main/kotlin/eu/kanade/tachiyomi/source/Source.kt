package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.RefreshContext
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import rx.Observable

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc.
 * Ported from Mihon source-api for extension compatibility.
 */
interface Source {

    /**
     * ID for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Whether this source provides novel (text-based) content instead of manga (image-based).
     * Novel sources should return text content via [fetchPageText].
     *
     * Declared as a default *method* (not a property) so the host's `-Xjvm-default` bridges
     * (`NovelSource$-CC.$default$isNovelSource`) match the real Tsundoku/NovelSourcery
     * source-api, whose `NovelSource` interface carries `isNovelSource()` as a default method.
     * This is what lets extensions compiled against extensions-lib 1.6/tsundoku link and load.
     *
     * @since extensions-lib 1.5
     */
    fun isNovelSource(): Boolean = false

    /**
     * Whether the source has support for latest updates.
     *
     * @since tachiyomix 1.6
     */
    val supportsLatest: Boolean
        get() = false

    /**
     * Returns the list of filters for the source.
     *
     * @since tachiyomix 1.6
     */
    fun getFilterList(): FilterList = FilterList()

    /**
     * Get a page with a list of manga.
     *
     * @since tachiyomix 1.6
     */
    suspend fun getPopularManga(page: Int): MangasPage {
        throw IllegalStateException("Not used")
    }

    /**
     * Get a page with a list of latest manga updates.
     *
     * @since tachiyomix 1.6
     */
    suspend fun getLatestUpdates(page: Int): MangasPage {
        throw IllegalStateException("Not used")
    }

    /**
     * Get a page with a list of manga.
     *
     * @since tachiyomix 1.6
     */
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        throw IllegalStateException("Not used")
    }

    /**
     * Get the updated details for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the updated manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getMangaDetails(manga: SManga): SManga {
        return fetchMangaDetails(manga).toBlocking().first()
    }

    /**
     * Get all the available chapters for a manga.
     *
     * @since extensions-lib 1.5
     * @param manga the manga to update.
     * @return the chapters for the manga.
     */
    @Suppress("DEPRECATION")
    suspend fun getChapterList(manga: SManga): List<SChapter> {
        return fetchChapterList(manga).toBlocking().first()
    }

    /**
     * Get all the available chapters for a manga with a [RefreshContext].
     *
     * Fork-only API from the real Tsundoku source-api (present in extensions-lib 1.4
     * and tsundoku main). Sources can use the provided context to avoid redundant requests
     * and implement intelligent delta refresh logic. The default implementation ignores the
     * context and falls back to the plain [getChapterList], so 1.4-compiled extensions that
     * override this keep working and everything else is unaffected.
     *
     * @since extensions-lib 1.6 (tsundoku fork only, superseded by [getMangaUpdate])
     * @param manga the manga to update.
     * @param context refresh context containing existing local state
     * @return the chapters for the manga.
     */
    @Deprecated(
        "Fork-only API superseded by upstream's getMangaUpdate, which now accepts existing chapters directly. " +
            "Kept temporarily so already-published extensions keep working; migrate to getMangaUpdate.",
        ReplaceWith("getMangaUpdate"),
    )
    @Suppress("DEPRECATION")
    suspend fun getChapterList(manga: SManga, context: RefreshContext): List<SChapter> {
        // Default implementation falls back to original method for backwards compatibility
        return getChapterList(manga)
    }

    /**
     * Fetch updated manga details and/or chapters using the TachiyomiX 1.6 combined API.
     *
     * @since tachiyomix 1.6
     */
    suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) getMangaDetails(manga) else manga
        val updatedChapters = if (fetchChapters) getChapterList(manga) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    /**
     * Get the list of pages a chapter has. Pages should be returned
     * in the expected order; the index is ignored.
     *
     * @since extensions-lib 1.5
     * @param chapter the chapter.
     * @return the pages for the chapter.
     */
    @Suppress("DEPRECATION")
    suspend fun getPageList(chapter: SChapter): List<Page> {
        return fetchPageList(chapter).toBlocking().first()
    }

    /**
     * Fetches the text content for a novel page. Only meaningful when [isNovelSource] is true;
     * manga sources never call this. A novel chapter is a single [Page] whose text is returned
     * here, so the one content fetch happens in this method.
     *
     * The default throws for non-novel sources, preserving manga behavior; novel extensions
     * override this to return the chapter body. The default also keeps extensions compiled
     * against extensions-lib 1.4 (which predates this member) fully compatible.
     *
     * @since extensions-lib 1.5
     * @param page the page to fetch; use [Page.url] to make the request.
     * @return the HTML or text content to display.
     */
    suspend fun fetchPageText(page: Page): String =
        throw UnsupportedOperationException("Not a novel source")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getMangaDetails"),
    )
    fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getChapterList"),
    )
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPageList"),
    )
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        throw IllegalStateException("Not used")
}
