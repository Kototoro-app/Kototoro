package org.skepsun.kototoro.details.ui


import org.skepsun.kototoro.details.ui.model.LinkedTrackingItemUiModel
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.ui.model.HistoryInfo
import org.skepsun.kototoro.details.ui.model.ContentBranch
import org.skepsun.kototoro.details.ui.model.DetailsSourceOption
import org.skepsun.kototoro.details.ui.model.DetailsChapterSourceTab
import org.skepsun.kototoro.details.ui.pager.EmptyContentReason
import org.skepsun.kototoro.discover.ui.details.LocalSearchState
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItem
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.details.ui.model.EntityRelationSection
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.details.ui.model.DetailsSupplementAction
import org.skepsun.kototoro.filter.ui.model.UiTagGroup
import org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS
import org.skepsun.kototoro.search.domain.ALL_SOURCE_TYPES
import org.skepsun.kototoro.search.domain.SearchContentKind
import java.util.Locale

data class DetailsSupplementUiState(
    val metadataProperties: List<Pair<String, String>> = emptyList(),
    val sections: List<EntityRelationSection> = emptyList(),
    val actions: List<DetailsSupplementAction> = emptyList(),
    val commentThreads: List<TrackingSiteItemDetails.CommentThread> = emptyList(),
    val commentsUrl: String? = null,
    val reviews: List<TrackingSiteItemDetails.ReviewEntry> = emptyList(),
    val reviewsUrl: String? = null,
)

data class MetadataSearchUiState(
    val services: List<ScrobblerService> = emptyList(),
    val authorizedServices: Set<ScrobblerService> = emptySet(),
    val selectedService: ScrobblerService = ScrobblerService.ANILIST,
    val query: String = "",
    val results: List<TrackingSiteItem> = emptyList(),
    val sections: List<MetadataSearchSectionUiState> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val errorMessage: String? = null,
)

data class ReadingSearchUiState(
    val sources: List<ContentSourceInfo> = emptyList(),
    val selectedSource: String? = null,
    val query: String = "",
    val sections: List<ReadingSearchSectionUiState> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val state: LocalSearchState? = null,
    val filterUiState: ReadingSearchFilterUiState = ReadingSearchFilterUiState(),
    val scopeFilterUiState: ReadingSearchScopeFilterUiState = ReadingSearchScopeFilterUiState(),
)

data class MetadataSearchSectionUiState(
    val service: ScrobblerService,
    val items: List<TrackingSiteItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

data class ReadingSearchSectionUiState(
    val source: ContentSourceInfo,
    val items: List<Content> = emptyList(),
    val isPending: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

data class ReadingSearchFilterUiState(
    val hasSelectedSource: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val sortOrders: List<SortOrder> = emptyList(),
    val selectedSortOrder: SortOrder? = null,
    val tagGroups: List<UiTagGroup> = emptyList(),
    val excludedTagGroups: List<UiTagGroup> = emptyList(),
    val contentTypes: List<ContentType> = emptyList(),
    val selectedContentTypes: Set<ContentType> = emptySet(),
    val states: List<ContentState> = emptyList(),
    val selectedStates: Set<ContentState> = emptySet(),
    val locales: List<Locale?> = emptyList(),
    val selectedLocale: Locale? = null,
    val author: String? = null,
    val canSearchByAuthor: Boolean = false,
    val supportsTagExclusion: Boolean = false,
    val appliedFilterCount: Int = 0,
)

data class ReadingSearchScopeFilterUiState(
    val sourceTypes: Set<SourceType> = ALL_SOURCE_TYPES,
    val contentKinds: Set<SearchContentKind> = ALL_SEARCH_CONTENT_KINDS,
    val pinnedOnly: Boolean = false,
    val hideEmpty: Boolean = false,
    /**
     * Tier 3 of the filter state: null follows the global browsing preset for as long as the user has not picked a
     * preset here; [org.skepsun.kototoro.search.domain.SearchFilters.PRESET_ALL] is an explicit "All" for this panel
     * and a positive id a preset the user pinned for this panel only. Never persisted and never written back globally.
     */
    val languagePresetId: Long? = null,
) {
    val appliedFilterCount: Int
        get() {
            var count = 0
            if (sourceTypes != ALL_SOURCE_TYPES) count++
            if (contentKinds != ALL_SEARCH_CONTENT_KINDS) count++
            if (pinnedOnly) count++
            if (hideEmpty) count++
            if (languagePresetId != null) count++
            return count
        }

    /** The preset that actually narrows the sources, given the global browsing preset this panel may still follow. */
    fun resolveLanguagePresetId(globalPresetId: Long): Long = languagePresetId ?: globalPresetId

    /**
     * Every mutation below is local by construction: the panel's state is a value, and nothing here can reach
     * `AppSettings` or the persisted search scope, which is what keeps switching source on the detail page from
     * rewriting the search page's filters.
     */
    fun withSourceTypeToggled(type: SourceType): ReadingSearchScopeFilterUiState {
        val updated = sourceTypes.toMutableSet().apply {
            if (!add(type)) remove(type)
        }.ifEmpty { ALL_SOURCE_TYPES }
        return copy(sourceTypes = updated)
    }

    fun withContentKindToggled(kind: SearchContentKind): ReadingSearchScopeFilterUiState {
        val updated = contentKinds.toMutableSet().apply {
            if (!add(kind)) remove(kind)
        }.ifEmpty { ALL_SEARCH_CONTENT_KINDS }
        return copy(contentKinds = updated)
    }

    fun withPinnedOnly(enabled: Boolean): ReadingSearchScopeFilterUiState = copy(pinnedOnly = enabled)

    fun withHideEmpty(enabled: Boolean): ReadingSearchScopeFilterUiState = copy(hideEmpty = enabled)

    /**
     * Pins a preset for this panel. [org.skepsun.kototoro.search.domain.SearchFilters.PRESET_FOLLOW_GLOBAL] and null
     * both mean "follow the global browsing preset again".
     */
    fun withLanguagePreset(presetId: Long?): ReadingSearchScopeFilterUiState {
        val localPresetId = when (presetId) {
            null, org.skepsun.kototoro.search.domain.SearchFilters.PRESET_FOLLOW_GLOBAL -> null
            else -> presetId
        }
        return if (languagePresetId == localPresetId) this else copy(languagePresetId = localPresetId)
    }
}

data class SourceBindingUiState(
    val metadataSourceOptions: List<DetailsSourceOption> = emptyList(),
    val readingSourceOptions: List<DetailsSourceOption> = emptyList(),
    val metadataChapterTabs: List<DetailsChapterSourceTab> = emptyList(),
    val readingChapterTabs: List<DetailsChapterSourceTab> = emptyList(),
    val resolvedMetadataContentType: ContentType? = null,
    val resolvedMetadataLanguage: String? = null,
    val resolvedReadingLanguage: String? = null,
)

data class TranslationUiState(
    val translatedTitle: String? = null,
    val translatedDescription: String? = null,
    val isShowingTranslation: Boolean = false,
    val hasTranslationCache: Boolean = false,
    val isTranslating: Boolean = false,
    val showTranslateAction: Boolean = false,
)

data class DetailsPrimaryUiState(
    val mangaDetails: ContentDetails? = null,
    val remoteContent: Content? = null,
    val relatedContent: List<ContentListModel> = emptyList(),
    val favouriteCategories: Set<FavouriteCategory> = emptySet(),
    val historyInfo: HistoryInfo = HistoryInfo(null, null, null, false, null),
    val branches: List<ContentBranch> = emptyList(),
    val isStatsAvailable: Boolean = false,
    val trackingSuggestion: TrackingSiteMatchResult? = null,
    val linkedTrackingItems: List<LinkedTrackingItemUiModel> = emptyList(),
    val readingStatus: ScrobblingStatus = ScrobblingStatus.PLANNED,
    val unifiedRating: Float = 0f,
    val canEditUnifiedRating: Boolean = false,
    val isLoading: Boolean = false,
    val entityRelationSections: List<EntityRelationSection> = emptyList(),
    val activeLocalBrowserContent: Content? = null,
    val isWorkDetails: Boolean = true,
)

data class ChaptersPaneControlsUiState(
    val isChaptersReversed: Boolean = false,
    val isChaptersInGridView: Boolean = false,
    val isHideReadChapters: Boolean = false,
    val isKeepReadChaptersDimmed: Boolean = true,
    val isMergeRepeatedChapters: Boolean = false,
    val showMergeRepeatedChapters: Boolean = false,
    val isDownloadedOnly: Boolean = false,
    val emptyReason: EmptyContentReason? = null,
)

