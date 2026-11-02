package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * Tsundoku source-api (extensions-lib 1.6) optional reading/library callbacks.
 *
 * The Suwayomi API the Windows and iOS hosts compile against has no such interface, so Tsundoku extensions that
 * implement it (NovelSourcery's keiyoushi base, e.g. meionovel) failed construction with `NoClassDefFoundError`.
 * Extensions call these defaults through `invokespecial`, so they must stay concrete interface methods. The hosts
 * do not dispatch the callbacks yet; the Android copy in `app` carries the dispatcher.
 */
interface SourceTracker {

    val supportsChapterTracking: Boolean
        get() = true

    val supportsFavoritesTracking: Boolean
        get() = false

    suspend fun onChaptersRead(
        manga: SManga,
        changedChapters: List<SChapter>,
        allChapters: List<SChapter>,
        categories: List<String>,
    ) = Unit

    suspend fun onChaptersUnread(
        manga: SManga,
        changedChapters: List<SChapter>,
        allChapters: List<SChapter>,
        categories: List<String>,
    ) = Unit

    suspend fun onFavorited(
        manga: SManga,
        categories: List<String>,
    ) = Unit

    suspend fun onUnfavorited(
        manga: SManga,
        categories: List<String>,
    ) = Unit
}
