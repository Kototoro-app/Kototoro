package org.skepsun.kototoro.home.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType

@Immutable
data class HomeRecentItem(
    val content: Content,
    val groupKey: Long = content.id,
    val counter: Int = 0,
    val progress: ReadingProgress? = null,
) {
    val title: String
        get() = content.title

    @get:StringRes
    val typeLabelResId: Int?
        get() = content.source.getContentType().toHomeTab()?.titleResId
}

@Immutable
data class HomeUpdateItem(
    val content: Content,
    val newChapters: Int,
    val groupKey: Long = content.id,
    val counter: Int = newChapters,
    val progress: ReadingProgress? = null,
) {
    val title: String
        get() = content.title
}

@Immutable
data class HomeRecommendationItem(
    val content: Content,
    val groupKey: Long = content.id,
    val counter: Int = 0,
    val progress: ReadingProgress? = null,
) {
    val title: String
        get() = content.title
}

@Immutable
data class HomeRecentSearchItem(
    val query: String,
)

@Immutable
data class HomeResumeState(
    val content: Content? = null,
    val progressPercent: Int? = null,
    val entityId: Long? = null,
    val preferredLocalMangaId: Long? = null,
    val groupKey: Long? = content?.id,
) {
    val isAvailable: Boolean
        get() = content != null
}

enum class HomeContentTab(@StringRes val titleResId: Int) {
    MANGA(R.string.manga),
    NOVEL(R.string.novel),
    VIDEO(R.string.video),
}

enum class HomeSourceOrigin {
    BUILT_IN,
    MIHON,
    ANIYOMI,
    LEGADO,
    TVBOX,
    EXTERNAL,
    IREADER,
}

@Immutable
data class HomeSourceBreakdown(
    val origin: HomeSourceOrigin,
    val count: Int,
)

@Immutable
data class HomeSummaryState(
    val selectedTab: HomeContentTab? = null,
    val recentHistoryCount: Int = 0,
    val recentHistoryItems: List<HomeRecentItem> = emptyList(),
    val resumeState: HomeResumeState = HomeResumeState(),
    val favoritesCount: Int = 0,
    val favoriteCategoriesCount: Int = 0,
    val unreadUpdatesCount: Int = 0,
    val recentUpdates: List<HomeUpdateItem> = emptyList(),
    val recommendationsCount: Int = 0,
    val recommendations: List<HomeRecommendationItem> = emptyList(),
    val recentSearches: List<HomeRecentSearchItem> = emptyList(),
    val enabledSourcesCount: Int = 0,
    val sourceBreakdown: List<HomeSourceBreakdown> = emptyList(),
    val selectedSourceTags: Set<SourceTag> = emptySet(),
    val isInitialized: Boolean = false,
)

internal fun ContentType.toHomeTab(): HomeContentTab? = when (this) {
    ContentType.NOVEL,
    ContentType.HENTAI_NOVEL -> HomeContentTab.NOVEL

    ContentType.VIDEO,
    ContentType.HENTAI_VIDEO -> HomeContentTab.VIDEO

    ContentType.MANGA,
    ContentType.HENTAI_MANGA,
    ContentType.COMICS,
    ContentType.MANHWA,
    ContentType.MANHUA,
    ContentType.ONE_SHOT,
    ContentType.DOUJINSHI,
    ContentType.IMAGE_SET,
    ContentType.ARTIST_CG,
    ContentType.GAME_CG,
    ContentType.OTHER -> HomeContentTab.MANGA
}
