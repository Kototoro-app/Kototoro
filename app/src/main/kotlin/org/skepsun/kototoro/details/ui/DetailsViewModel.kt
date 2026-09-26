package org.skepsun.kototoro.details.ui


import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.text.parseAsHtml
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import org.skepsun.kototoro.core.util.ext.combine as extCombine
import org.skepsun.kototoro.core.util.ext.sanitize
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.details.ui.model.LinkedTrackingItemUiModel
import org.skepsun.kototoro.bookmarks.domain.BookmarksRepository
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.resolvedContentTypeForSnapshot
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier
import org.skepsun.kototoro.core.nav.ContentIntent
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.TriStateOption
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.awaitCancellable
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.computeSize
import org.skepsun.kototoro.core.util.ext.onEachWhile
import org.skepsun.kototoro.details.data.CachedTranslationEntry
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.data.DetailsTranslationCache
import org.skepsun.kototoro.details.domain.BranchComparator
import org.skepsun.kototoro.details.domain.DetailsInteractor
import org.skepsun.kototoro.details.domain.DetailsLoadUseCase
import org.skepsun.kototoro.details.domain.isDetailsProjectionAllowed
import org.skepsun.kototoro.details.domain.ProgressUpdateUseCase
import org.skepsun.kototoro.details.domain.ReadingTimeUseCase
import org.skepsun.kototoro.details.domain.RelatedContentUseCase
import org.skepsun.kototoro.details.ui.model.HistoryInfo
import org.skepsun.kototoro.details.ui.model.DetailsOrigin
import org.skepsun.kototoro.details.ui.model.ContentBranch
import org.skepsun.kototoro.details.ui.model.DetailsSourceOption
import org.skepsun.kototoro.alternatives.domain.MigrateUseCase
import org.skepsun.kototoro.details.ui.model.DetailsChapterSourceTab
import org.skepsun.kototoro.details.ui.model.ChapterListItem.Companion.FLAG_DOWNLOADED
import org.skepsun.kototoro.details.ui.model.findChapterByHistory
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesViewModel
import org.skepsun.kototoro.discover.ui.details.LocalSearchState
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.explore.data.SourcePresetsRepository
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.core.parser.ContentSourceResolutionPipeline
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.DeleteLocalContentUseCase
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.local.domain.model.computeStoredSize
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.readingrecord.data.ReadingRecordRepository
import org.skepsun.kototoro.readingrecord.data.ReadingRecordSnapshot
import org.skepsun.kototoro.reader.ui.FULLY_READ_CHAPTER_ID
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.tryScrobble
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingInfo
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus
import org.skepsun.kototoro.stats.data.StatsRepository
import org.skepsun.kototoro.video.data.VideoDownloadIndex
import org.skepsun.kototoro.tracking.discovery.domain.TrackingEntitySearchResult
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCatalog
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatcher
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItem
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails
import org.skepsun.kototoro.core.parser.ContentDataRepository.MetadataSourceSelection as PersistedMetadataSourceSelection
import javax.inject.Inject
import kotlin.experimental.or
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.details.ui.model.EntityRelationSection
import org.skepsun.kototoro.details.ui.model.EntityRelationItem
import org.skepsun.kototoro.tracking.discovery.domain.EntityType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.details.ui.model.DetailsSupplementAction
import org.skepsun.kototoro.filter.ui.model.UiTagGroup
import org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS
import org.skepsun.kototoro.search.domain.ALL_SOURCE_TYPES
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.matches
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.space.domain.SpaceId
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.plus
import java.io.File
import java.util.Locale


private const val DETAILS_TRACE_TAG = "DetailsTrace"

private fun Content?.detailsTraceSummary(): String {
    return this?.let {
        "id=${it.id} source=${it.source.name} locale=${it.source.locale} chapters=${it.chapters?.size ?: 0}"
    } ?: "null"
}

private fun DetailsOrigin?.detailsTraceSummary(): String = when (this) {
    null -> "null"
    is DetailsOrigin.LocalMangaId -> "LocalMangaId(mangaId=$mangaId)"
    is DetailsOrigin.LocalMangaContent -> "LocalMangaContent(${manga.detailsTraceSummary()})"
    is DetailsOrigin.TrackingEntity -> "TrackingEntity(service=$serviceId, type=$entityTypeName, remote=$remoteId)"
    is DetailsOrigin.TrackingItem -> "TrackingItem(service=$serviceId, remote=$remoteId)"
}

internal fun List<EntityRelationSection>.deduplicateRelationItems(): List<EntityRelationSection> =
    map { section ->
        section.copy(items = section.items.distinctBy(EntityRelationItem::stableKey))
    }

internal fun DetailsOrigin.initialProjectionLocalMangaIdOrNull(): Long? = when (this) {
    is DetailsOrigin.LocalMangaId -> mangaId
    is DetailsOrigin.LocalMangaContent -> manga.id
    is DetailsOrigin.TrackingEntity,
    is DetailsOrigin.TrackingItem,
    -> null
}

internal fun DetailsOrigin.initialProjectionIntentOrNull(): ContentIntent? = when (this) {
    is DetailsOrigin.LocalMangaContent -> ContentIntent.of(manga)
    else -> initialProjectionLocalMangaIdOrNull()?.let(ContentIntent::of)
}

private const val ENTITY_RELATION_SECTIONS_DEBOUNCE_MS = 120L
private const val TRACKING_SUGGESTION_THRESHOLD = 0.9f
private const val TRACKING_SUGGESTION_GAP_THRESHOLD = 0.03f
private const val TRACKING_SUGGESTION_RESULT_LIMIT = 3
private const val SOURCE_SEARCH_TIMEOUT_MS = 12_000L
private const val READING_SEARCH_MAX_PARALLELISM = 4
private const val READING_SEARCH_LOG_TAG = "ReadingSourceSearch"
private const val ENTITY_TRACKING_SEARCH_RESULT_LIMIT = 3
private const val MAX_DUPLICATE_PROMPT_CANDIDATES = 3
private val ENTITY_TRACKING_SEARCH_SERVICES = listOf(
    ScrobblerService.ANILIST,
    ScrobblerService.BANGUMI,
    ScrobblerService.KITSU,
    ScrobblerService.MAL,
    ScrobblerService.MANGAUPDATES,
    ScrobblerService.SHIKIMORI,
)
private val CHARACTER_VOICE_ACTOR_REGEX = Regex(
    """^\s*(.+?)\s*\((?:cv|cast|voice actor|voice|配音|声优)\s*[:：]?\s*(.+?)\)\s*$""",
    RegexOption.IGNORE_CASE,
)

private data class TrackingCharacterPresentation(
    val coverUrl: String?,
    val role: String?,
    val supportingText: String?,
    val detailLines: List<String>,
    val url: String?,
)

private fun <T> Flow<T>?.orEmptyFlow(fallback: T): Flow<T> = this ?: flowOf(fallback)

private inline fun <T> StateFlow<T>?.safeValueOrNull(): T? = runCatching {
    this?.value
}.getOrNull()

private inline fun <T> flowOrFallback(
    fallback: T,
    block: () -> Flow<T>?,
): Flow<T> = runCatching {
    block()
}.getOrNull().orEmptyFlow(fallback)

private data class WorkProjectionContext(
    val entityId: Long?,
    val requestedMangaId: Long,
    val preferredLocalMangaId: Long?,
    val persistedLocalMangaId: Long,
    val candidateMangaIds: List<Long>,
)

private data class CurrentWorkProjectionSnapshot(
    val activeLocalMangaId: Long?,
    val currentReadingProjectionMangaId: Long?,
)


private data class DetailsDiscussionUiState(
    val commentThreads: List<TrackingSiteItemDetails.CommentThread> = emptyList(),
    val commentsUrl: String? = null,
    val reviews: List<TrackingSiteItemDetails.ReviewEntry> = emptyList(),
    val reviewsUrl: String? = null,
)

private data class MetadataSearchPickerUiState(
    val services: List<ScrobblerService> = emptyList(),
    val authorizedServices: Set<ScrobblerService> = emptySet(),
    val selectedService: ScrobblerService = ScrobblerService.ANILIST,
)

private data class MetadataSearchContentUiState(
    val query: String = "",
    val results: List<TrackingSiteItem> = emptyList(),
    val sections: List<MetadataSearchSectionUiState> = emptyList(),
)

private data class MetadataSearchResultsUiState(
    val query: String = "",
    val results: List<TrackingSiteItem> = emptyList(),
    val sections: List<MetadataSearchSectionUiState> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val errorMessage: String? = null,
)

private data class ReadingSearchPrimaryUiState(
    val sources: List<ContentSourceInfo> = emptyList(),
    val selectedSource: String? = null,
    val query: String = "",
    val sections: List<ReadingSearchSectionUiState> = emptyList(),
)

private data class ReadingSearchFilterState(
    val source: ContentSourceInfo? = null,
    val capabilities: org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities =
        org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities(),
    val filterOptions: org.skepsun.kototoro.parsers.model.ContentListFilterOptions =
        org.skepsun.kototoro.parsers.model.ContentListFilterOptions(),
    val sortOrders: List<SortOrder> = emptyList(),
    val selectedSortOrder: SortOrder? = null,
    val listFilter: ContentListFilter = ContentListFilter.EMPTY,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

private data class SourceOptionsUiState(
    val metadataSourceOptions: List<DetailsSourceOption> = emptyList(),
    val readingSourceOptions: List<DetailsSourceOption> = emptyList(),
)

private data class SourceChapterTabsUiState(
    val metadataChapterTabs: List<DetailsChapterSourceTab> = emptyList(),
    val readingChapterTabs: List<DetailsChapterSourceTab> = emptyList(),
)

private data class SourceResolutionUiState(
    val resolvedMetadataContentType: ContentType? = null,
    val resolvedMetadataLanguage: String? = null,
    val resolvedReadingLanguage: String? = null,
)

private data class TranslationTextUiState(
    val translatedTitle: String? = null,
    val translatedDescription: String? = null,
    val isShowingTranslation: Boolean = false,
    val hasTranslationCache: Boolean = false,
)

private data class DetailsHeaderUiState(
    val mangaDetails: ContentDetails? = null,
    val favouriteCategories: Set<FavouriteCategory> = emptySet(),
    val historyInfo: HistoryInfo = HistoryInfo(null, null, null, false, null),
    val trackingSuggestion: TrackingSiteMatchResult? = null,
    val linkedTrackingItems: List<LinkedTrackingItemUiModel> = emptyList(),
    val readingStatus: ScrobblingStatus = ScrobblingStatus.PLANNED,
    val unifiedRating: Float = 0f,
    val canEditUnifiedRating: Boolean = false,
)

private data class DetailsPaneSummaryUiState(
    val remoteContent: Content? = null,
    val branches: List<ContentBranch> = emptyList(),
    val isStatsAvailable: Boolean = false,
    val isLoading: Boolean = false,
    val activeLocalBrowserContent: Content? = null,
)

@HiltViewModel
class DetailsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val historyRepository: HistoryRepository,
    private val readingRecordRepository: ReadingRecordRepository,
    bookmarksRepository: BookmarksRepository,
    settings: AppSettings,
    private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
    downloadScheduler: DownloadWorker.Scheduler,
    interactor: DetailsInteractor,
    savedStateHandle: SavedStateHandle,
    deleteLocalContentUseCase: DeleteLocalContentUseCase,
    private val relatedContentUseCase: RelatedContentUseCase,
    private val mangaListMapper: ContentListMapper,
    private val detailsLoadUseCase: DetailsLoadUseCase,
    private val progressUpdateUseCase: ProgressUpdateUseCase,
    private val readingTimeUseCase: ReadingTimeUseCase,
    statsRepository: StatsRepository,
    private val epubChapterMappingDao: org.skepsun.kototoro.core.db.dao.EpubChapterMappingDao,
    private val localEpubSource: org.skepsun.kototoro.local.epub.LocalEpubSource,
    private val epubStorageManager: org.skepsun.kototoro.local.epub.EpubStorageManager,
    private val videoDownloadIndex: VideoDownloadIndex,
    private val favouritesRepository: FavouritesRepository,
    mangaRepositoryFactory: org.skepsun.kototoro.core.parser.ContentRepository.Factory,
    private val contentSourcesRepository: ContentSourcesRepository,
    private val mihonExtensionManager: MihonExtensionManager,
    private val aniyomiExtensionManager: AniyomiExtensionManager,
    private val ireaderExtensionManager: IReaderExtensionManager,
    private val contentSourceResolutionPipeline: ContentSourceResolutionPipeline,
    private val sourcePresetsRepository: SourcePresetsRepository,
    private val trackingSiteMatcher: TrackingSiteMatcher,
    private val migrateUseCase: MigrateUseCase,
    private val dataRepository: org.skepsun.kototoro.core.parser.ContentDataRepository,
    private val detailsTranslationCache: DetailsTranslationCache,
    private val db: org.skepsun.kototoro.core.db.MangaDatabase,
    private val trackingSiteCacheRepository: org.skepsun.kototoro.tracking.discovery.data.TrackingSiteCacheRepository,
    private val trackingSiteDiscoveryService: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService,
    private val sourceTypeIdentifier: SourceTypeIdentifier,
    private val trackingRepository: TrackingRepository,
    private val spaceContentPolicy: SpaceContentPolicy,
) : ChaptersPagesViewModel(
    settings = settings,
    interactor = interactor,
    bookmarksRepository = bookmarksRepository,
    historyRepository = historyRepository,
    downloadScheduler = downloadScheduler,
    deleteLocalContentUseCase = deleteLocalContentUseCase,
    mangaRepositoryFactory = mangaRepositoryFactory,
    localStorageChanges = localStorageChanges,
) {

    private val intent = ContentIntent(savedStateHandle)
    val activeExternalOrigin = savedStateHandle.get<org.skepsun.kototoro.details.ui.model.DetailsOrigin>(
        org.skepsun.kototoro.core.nav.AppRouter.KEY_DETAILS_ORIGIN,
    ) ?: org.skepsun.kototoro.core.nav.PendingDetailsNavigation.consume()
    private val isTemporaryReadOnly = savedStateHandle.get<Boolean>(
        org.skepsun.kototoro.core.nav.AppRouter.KEY_TEMPORARY_DETAILS,
    ) == true
    private val originContent = (activeExternalOrigin as? org.skepsun.kototoro.details.ui.model.DetailsOrigin.LocalMangaContent)?.manga
    private val initialProjectionIntentOverride = activeExternalOrigin?.initialProjectionIntentOrNull()
    private var loadingJob: Job = Job()
    private var translateAvailabilityJob: Job? = null
    private var readingSearchJob: Job? = null
    private var sourceBindingsRefreshJob: Job? = null
    private var readingSearchGeneration: Int = 0
    private var allEnabledSourcesLoaded = false
    private var currentLoadIntentOverride: ContentIntent? = initialProjectionIntentOverride
    private var translationCacheSourceLang: String? = null
    private var translationCacheTargetLang: String? = null
    private val activeMangaIdFlow = kotlinx.coroutines.flow.MutableStateFlow(
        activeExternalOrigin?.initialProjectionLocalMangaIdOrNull()
            ?: intent.mangaId.takeIf { it != 0L },
    )
    val mangaId: Long get() = activeMangaIdFlow.value ?: intent.mangaId

    private val pendingEntityRelationSections = MutableSharedFlow<List<EntityRelationSection>>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val entityRelationSections: StateFlow<List<EntityRelationSection>> = pendingEntityRelationSections
        .debounce(ENTITY_RELATION_SECTIONS_DEBOUNCE_MS)
        .distinctUntilChanged()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())
    val metadataSourceOptions = MutableStateFlow<List<DetailsSourceOption>>(emptyList())
    val readingSourceOptions = MutableStateFlow<List<DetailsSourceOption>>(emptyList())
    val metadataChapterTabs = MutableStateFlow<List<DetailsChapterSourceTab>>(emptyList())
    val readingChapterTabs = MutableStateFlow<List<DetailsChapterSourceTab>>(emptyList())
    private var detailsSpaceId: SpaceId? = null
    private var activeProjectionStoredContentType: ContentType? = null
    val supplementalMetadataProperties = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val supplementalSections = MutableStateFlow<List<EntityRelationSection>>(emptyList())
    val supplementalActions = MutableStateFlow<List<DetailsSupplementAction>>(emptyList())
    val supplementalCommentThreads = MutableStateFlow<List<org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails.CommentThread>>(emptyList())
    val supplementalCommentsUrl = MutableStateFlow<String?>(null)
    val supplementalReviews = MutableStateFlow<List<org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails.ReviewEntry>>(emptyList())
    val supplementalReviewsUrl = MutableStateFlow<String?>(null)
    val metadataSearchServices = MutableStateFlow<List<ScrobblerService>>(emptyList())
    val authorizedTrackingServices = MutableStateFlow<Set<ScrobblerService>>(emptySet())
    val selectedMetadataSearchService = MutableStateFlow(ScrobblerService.ANILIST)
    val metadataSearchQuery = MutableStateFlow("")
    val metadataSearchResults = MutableStateFlow<List<TrackingSiteItem>>(emptyList())
    val metadataSearchSections = MutableStateFlow<List<MetadataSearchSectionUiState>>(emptyList())
    val metadataSearchLoading = MutableStateFlow(false)
    val metadataSearchHasSearched = MutableStateFlow(false)
    val metadataSearchError = MutableStateFlow<String?>(null)
    private val detailsDiscussionUiState = combine(
        supplementalCommentThreads,
        supplementalCommentsUrl,
        supplementalReviews,
        supplementalReviewsUrl,
    ) { commentThreads, commentsUrl, reviews, reviewsUrl ->
        DetailsDiscussionUiState(
            commentThreads = commentThreads,
            commentsUrl = commentsUrl,
            reviews = reviews,
            reviewsUrl = reviewsUrl,
        )
    }
    val detailsSupplementUiState: StateFlow<DetailsSupplementUiState> = combine(
        supplementalMetadataProperties,
        supplementalSections,
        supplementalActions,
        detailsDiscussionUiState,
    ) { metadataProperties, sections, actions, discussion ->
        DetailsSupplementUiState(
            metadataProperties = metadataProperties,
            sections = sections,
            actions = actions,
            commentThreads = discussion.commentThreads,
            commentsUrl = discussion.commentsUrl,
            reviews = discussion.reviews,
            reviewsUrl = discussion.reviewsUrl,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DetailsSupplementUiState())
    private val metadataSearchPickerUiState = combine(
        metadataSearchServices,
        authorizedTrackingServices,
        selectedMetadataSearchService,
    ) { services, authorizedServices, selectedService ->
        MetadataSearchPickerUiState(
            services = services,
            authorizedServices = authorizedServices,
            selectedService = selectedService,
        )
    }
    private val metadataSearchContentUiState = combine(
        metadataSearchQuery,
        metadataSearchResults,
        metadataSearchSections,
    ) { query, results, sections ->
        MetadataSearchContentUiState(
            query = query,
            results = results,
            sections = sections,
        )
    }
    private val metadataSearchResultsUiState = combine(
        metadataSearchContentUiState,
        combine(
            metadataSearchLoading,
            metadataSearchHasSearched,
            metadataSearchError,
        ) { isLoading, hasSearched, errorMessage ->
            Triple(isLoading, hasSearched, errorMessage)
        },
    ) { content, status ->
        MetadataSearchResultsUiState(
            query = content.query,
            results = content.results,
            sections = content.sections,
            isLoading = status.first,
            hasSearched = status.second,
            errorMessage = status.third,
        )
    }
    val metadataSearchUiState: StateFlow<MetadataSearchUiState> = combine(
        metadataSearchPickerUiState,
        metadataSearchResultsUiState,
    ) { picker, results ->
        MetadataSearchUiState(
            services = picker.services,
            authorizedServices = picker.authorizedServices,
            selectedService = picker.selectedService,
            query = results.query,
            results = results.results,
            sections = results.sections,
            isLoading = results.isLoading,
            hasSearched = results.hasSearched,
            errorMessage = results.errorMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MetadataSearchUiState())
    val readingSearchSources = MutableStateFlow<List<ContentSourceInfo>>(emptyList())
    val selectedReadingSearchSource = MutableStateFlow<String?>(null)
    val readingSearchQuery = MutableStateFlow("")
    val readingSearchSections = MutableStateFlow<List<ReadingSearchSectionUiState>>(emptyList())
    val readingSearchLoading = MutableStateFlow(false)
    val readingSearchHasSearched = MutableStateFlow(false)
    val readingSearchState = MutableStateFlow<LocalSearchState?>(null)
    private val readingSearchFilterState = MutableStateFlow(ReadingSearchFilterState())
    private val readingSearchScopeFilters = MutableStateFlow(ReadingSearchScopeFilterUiState())
    private val readingSearchPrimaryUiState = combine(
        readingSearchSources,
        selectedReadingSearchSource,
        readingSearchQuery,
        readingSearchSections,
    ) { sources, selectedSource, query, sections ->
        ReadingSearchPrimaryUiState(
            sources = sources,
            selectedSource = selectedSource,
            query = query,
            sections = sections,
        )
    }
    val readingSearchUiState: StateFlow<ReadingSearchUiState> = combine(
        readingSearchPrimaryUiState,
        combine(
            readingSearchLoading,
            readingSearchHasSearched,
            readingSearchState,
        ) { isLoading, hasSearched, state ->
            Triple(isLoading, hasSearched, state)
        },
        readingSearchFilterState,
        readingSearchScopeFilters,
    ) { primary, status, filterState, scopeFilterState ->
        ReadingSearchUiState(
            sources = primary.sources,
            selectedSource = primary.selectedSource,
            query = primary.query,
            sections = primary.sections,
            isLoading = status.first,
            hasSearched = status.second,
            state = status.third,
            filterUiState = filterState.toUiState(),
            scopeFilterUiState = scopeFilterState,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ReadingSearchUiState())
    val languagePresets: StateFlow<List<SourcePreset>> = sourcePresetsRepository.observeAll()
        .stateIn(viewModelScope + Dispatchers.IO, SharingStarted.Eagerly, emptyList())
    val activeLanguagePresetId: StateFlow<Long> = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.IO,
        key = AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID,
        valueProducer = { settings.activeSourcePresetId },
    )
    val chaptersPaneControlsUiState: StateFlow<ChaptersPaneControlsUiState> = extCombine(
        isChaptersReversed,
        isChaptersInGridView,
        isHideReadChapters,
        isMergeRepeatedChapters,
        showMergeRepeatedChapters,
        isDownloadedOnly,
        emptyReason,
    ) { isChaptersReversed, isChaptersInGridView, isHideReadChapters, isMergeRepeatedChapters, showMergeRepeatedChapters, isDownloadedOnly, emptyReason ->
        ChaptersPaneControlsUiState(
            isChaptersReversed = isChaptersReversed,
            isChaptersInGridView = isChaptersInGridView,
            isHideReadChapters = isHideReadChapters,
            isMergeRepeatedChapters = isMergeRepeatedChapters,
            showMergeRepeatedChapters = showMergeRepeatedChapters,
            isDownloadedOnly = isDownloadedOnly,
            emptyReason = emptyReason,
        )
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, ChaptersPaneControlsUiState())
    val resolvedMetadataContentType = MutableStateFlow<ContentType?>(null)
    val resolvedMetadataLanguage = MutableStateFlow<String?>(null)
    val resolvedReadingLanguage = MutableStateFlow<String?>(null)
    private val sourceOptionsUiState = combine(
        metadataSourceOptions,
        readingSourceOptions,
    ) { metadataSourceOptions, readingSourceOptions ->
        SourceOptionsUiState(
            metadataSourceOptions = metadataSourceOptions,
            readingSourceOptions = readingSourceOptions,
        )
    }
    private val sourceChapterTabsUiState = combine(
        metadataChapterTabs,
        readingChapterTabs,
    ) { metadataChapterTabs, readingChapterTabs ->
        SourceChapterTabsUiState(
            metadataChapterTabs = metadataChapterTabs,
            readingChapterTabs = readingChapterTabs,
        )
    }
    private val sourceResolutionUiState = combine(
        resolvedMetadataContentType,
        resolvedMetadataLanguage,
        resolvedReadingLanguage,
    ) { resolvedMetadataContentType, resolvedMetadataLanguage, resolvedReadingLanguage ->
        SourceResolutionUiState(
            resolvedMetadataContentType = resolvedMetadataContentType,
            resolvedMetadataLanguage = resolvedMetadataLanguage,
            resolvedReadingLanguage = resolvedReadingLanguage,
        )
    }
    val sourceBindingUiState: StateFlow<SourceBindingUiState> = combine(
        sourceOptionsUiState,
        sourceChapterTabsUiState,
        sourceResolutionUiState,
    ) { sourceOptions, sourceTabs, sourceResolution ->
        SourceBindingUiState(
            metadataSourceOptions = sourceOptions.metadataSourceOptions,
            readingSourceOptions = sourceOptions.readingSourceOptions,
            metadataChapterTabs = sourceTabs.metadataChapterTabs,
            readingChapterTabs = sourceTabs.readingChapterTabs,
            resolvedMetadataContentType = sourceResolution.resolvedMetadataContentType,
            resolvedMetadataLanguage = sourceResolution.resolvedMetadataLanguage,
            resolvedReadingLanguage = sourceResolution.resolvedReadingLanguage,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SourceBindingUiState())
    val showTranslateAction = MutableStateFlow(false)
    val activeLocalBrowserContent = MutableStateFlow<Content?>(null)
    private val isWorkDetails = MutableStateFlow(initialIsWorkDetails())
    private val allEnabledSourceInfos = MutableStateFlow<List<ContentSourceInfo>>(emptyList())
    private val activeSourcePreset = settings.observeAsFlow(
        AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID,
    ) {
        activeSourcePresetId
    }.mapLatest { presetId ->
        if (presetId > 0L) {
            sourcePresetsRepository.getById(presetId)
        } else {
            null
        }
    }
    private val currentObservedLocalMangaId: StateFlow<Long?> = combine(
        activeMangaIdFlow,
        mangaDetails,
    ) { activeMangaId, details ->
        activeMangaId
            ?: details?.local?.manga?.id
            ?: details?.toContent()?.takeIf { it.isLocal }?.id
    }.distinctUntilChanged()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)
    private val observedVideoDownloadChanges = combine(
        videoDownloadIndex.changes,
        currentObservedLocalMangaId,
    ) { changedContentId, observedLocalMangaId ->
        changedContentId to observedLocalMangaId
    }.onEach { (changedContentId, observedLocalMangaId) ->
        if (changedContentId == observedLocalMangaId) {
            notifyDownloadChanged()
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0L to null)

    private var baseLoadedDetails: ContentDetails? = null
    private val trackingMetadataCandidates = MutableStateFlow<List<TrackingMetadataCandidate>>(emptyList())
    private val selectedMetadataSource = MutableStateFlow<MetadataSourceSelection>(initialMetadataSourceSelection())
    private val cachedTrackingDetails = LinkedHashMap<String, org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails>()
    private val cachedEntityTrackingDetails = LinkedHashMap<String, org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails>()

    private sealed interface MetadataSourceSelection {
        data object Base : MetadataSourceSelection
        data class Tracking(
            val service: ScrobblerService,
            val remoteId: Long,
            val url: String?,
        ) : MetadataSourceSelection
    }

    private data class TrackingMetadataCandidate(
        val service: ScrobblerService,
        val remoteId: Long,
        val url: String? = null,
    )

    private fun String?.normalizedImageUrl(): String? {
        val normalized = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!normalized.startsWith("file://", ignoreCase = true)) {
            return normalized
        }
        val filePath = runCatching {
            Uri.parse(normalized).path
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return normalized.takeIf { File(filePath).exists() }
    }

    private fun resolveScrobblingStatusOrNull(rawStatus: String?): ScrobblingStatus? {
        if (rawStatus.isNullOrBlank()) {
            return null
        }
        return runCatching {
            ScrobblingStatus.valueOf(rawStatus)
        }.getOrNull()
    }

    private fun initialMetadataSourceSelection(): MetadataSourceSelection {
        val trackingOrigin = activeExternalOrigin as? org.skepsun.kototoro.details.ui.model.DetailsOrigin.TrackingItem
            ?: return MetadataSourceSelection.Base
        val service = ScrobblerService.entries.firstOrNull {
            it.id == trackingOrigin.serviceId.toIntOrNull()
        } ?: return MetadataSourceSelection.Base
        return MetadataSourceSelection.Tracking(
            service = service,
            remoteId = trackingOrigin.remoteId,
            url = trackingOrigin.url,
        )
    }

    private fun initialIsWorkDetails(): Boolean {
        val origin = activeExternalOrigin as? org.skepsun.kototoro.details.ui.model.DetailsOrigin.TrackingEntity
            ?: return true
        return origin.entityTypeName == EntityType.WORK.name
    }

    private fun isTrackingOriginSelectionPinned(): Boolean {
        val origin = activeExternalOrigin as? org.skepsun.kototoro.details.ui.model.DetailsOrigin.TrackingItem
            ?: return false
        val selection = selectedMetadataSource.value as? MetadataSourceSelection.Tracking
            ?: return false
        val service = ScrobblerService.entries.firstOrNull {
            it.id == origin.serviceId.toIntOrNull()
        } ?: return false
        return selection.service == service && selection.remoteId == origin.remoteId
    }

    private fun currentDetailsTitle(): String {
        return cleanSourceSearchQuery(currentDetailsContent()?.title.orEmpty())
    }

    private fun currentReadingSearchTitle(): String {
        return currentDetailsContent()?.readingSearchTitle().orEmpty()
    }

    private fun currentDetailsContent(): Content? {
        return mangaDetails.safeValueOrNull()?.toContent()
            ?: baseLoadedDetails?.toContent()
            ?: originContent
    }

    private fun Content.readingSearchTitle(): String {
        return title.trim()
    }

    private fun cleanSourceSearchQuery(value: String): String {
        return value.trim()
    }

    private fun knownSearchSourceNames(): Set<String> {
        val readingSearchSourceSnapshot = readingSearchSources.safeValueOrNull().orEmpty()
        val enabledSourceInfoSnapshot = allEnabledSourceInfos.safeValueOrNull().orEmpty()
        val metadataSourceOptionSnapshot = metadataSourceOptions.safeValueOrNull().orEmpty()
        val readingSourceOptionSnapshot = readingSourceOptions.safeValueOrNull().orEmpty()
        return buildSet {
            currentDetailsContent()?.source?.name?.let(::add)
            baseLoadedDetails?.toContent()?.source?.name?.let(::add)
            originContent?.source?.name?.let(::add)
            readingSearchSourceSnapshot.forEach { add(it.mangaSource.name) }
            enabledSourceInfoSnapshot.forEach { add(it.mangaSource.name) }
            metadataSourceOptionSnapshot.mapNotNull { it.source?.name }.forEach(::add)
            readingSourceOptionSnapshot.mapNotNull { it.source?.name }.forEach(::add)
        }
    }

    private fun currentBaseContentType(): ContentType? {
        return baseLoadedDetails?.toContent()?.source
            ?.resolveDetailsSource()
            ?.getContentType()
            ?: mangaDetails.safeValueOrNull()?.toContent()?.source
                ?.resolveDetailsSource()
                ?.getContentType()
            ?: originContent?.source
                ?.resolveDetailsSource()
                ?.getContentType()
    }

    private fun org.skepsun.kototoro.parsers.model.ContentSource.resolveDetailsSource(): org.skepsun.kototoro.parsers.model.ContentSource {
        return selectResolvedDetailsSource(
            original = this,
            enabledSources = allEnabledSourceInfos.value,
            pipelineResolved = contentSourceResolutionPipeline.resolve(ContentSource(name)),
        )
    }

    private fun currentMetadataContentType(): ContentType? {
        return when (selectedMetadataSource.safeValueOrNull() ?: MetadataSourceSelection.Base) {
            MetadataSourceSelection.Base -> currentBaseContentType()
            is MetadataSourceSelection.Tracking -> currentTrackingMetadataDetails()?.contentType ?: currentBaseContentType()
        }
    }

    private fun currentDetailsContentType(): ContentType? {
        return currentMetadataContentType()
    }

    private fun currentMetadataLanguageCode(): String? {
        return when (selectedMetadataSource.value) {
            MetadataSourceSelection.Base -> {
                baseLoadedDetails?.toContent()?.source
                    ?.resolveDetailsSource()
                    ?.locale
                    ?.takeIf { it.isNotBlank() }
                    ?: originContent?.source
                        ?.resolveDetailsSource()
                        ?.locale
                        ?.takeIf { it.isNotBlank() }
            }
            is MetadataSourceSelection.Tracking -> {
                currentTrackingMetadataDetails()?.let { details ->
                    resolveTrackingLanguage(details.infoboxProperties).takeIf { it.isNotBlank() }
                        ?: details.toContentLocale()
                }
            }
        }
    }

    private fun org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails.toContentLocale(): String? {
        return contentType?.let { _ ->
            resolveTrackingLanguage(infoboxProperties).takeIf { it.isNotBlank() }
        }
    }

    private fun currentReadingLanguageCode(): String? {
        return readingSourceOptions.safeValueOrNull()
            .orEmpty()
            .firstOrNull { it.isSelected }
            ?.source
            ?.locale
            ?.takeIf { it.isNotBlank() }
            ?: baseLoadedDetails?.local?.manga?.source
                ?.resolveDetailsSource()
                ?.locale
                ?.takeIf { it.isNotBlank() }
    }

    private fun String.normalizedLanguageCode(): String {
        return trim()
            .substringBefore('-')
            .substringBefore('_')
            .lowercase(Locale.ROOT)
    }

    private fun isTrackingSource(source: org.skepsun.kototoro.parsers.model.ContentSource): Boolean {
        return source.name.startsWith("TRACKING_")
    }

    private fun isReadingSearchSourceEligible(
        source: org.skepsun.kototoro.parsers.model.ContentSource,
    ): Boolean {
        return !isTrackingSource(source) && !source.isLocal
    }

    private fun TrackingSiteItem.toScrobblerContent(): org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent {
        return org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerContent(
            id = remoteId,
            name = title,
            altName = altTitle,
            cover = coverUrl,
            url = url.orEmpty(),
        )
    }

    private fun ContentType?.toScrobblerMediaType(): String? {
        return when (this) {
            ContentType.VIDEO,
            ContentType.HENTAI_VIDEO -> "anime"
            ContentType.MANGA,
            ContentType.HENTAI_MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.COMICS,
            ContentType.ONE_SHOT,
            ContentType.DOUJINSHI -> "manga"
            else -> null
        }
    }

    private fun refreshReadingSearchSources() {
        val currentSource = (baseLoadedDetails?.toContent() ?: mangaDetails.value?.toContent() ?: originContent)
            ?.source
            ?.takeIf { source -> isReadingSearchSourceEligible(source) }
            ?.let { source ->
                ContentSourceInfo(
                    mangaSource = source,
                    isEnabled = true,
                    isPinned = false,
                )
            }
        val filtered = buildList {
            currentSource?.let(::add)
            allEnabledSourceInfos.value
                .filter { info -> isReadingSearchSourceEligible(info.mangaSource) }
                .forEach { info ->
                    if (none { it.mangaSource.name == info.mangaSource.name }) {
                        add(info)
                    }
        }
        }
        readingSearchSources.value = filtered
        readingSearchScopeFilters.update { current ->
            if (current.sourceTypes == ALL_SOURCE_TYPES &&
                current.contentKinds == ALL_SEARCH_CONTENT_KINDS &&
                !current.pinnedOnly &&
                !current.hideEmpty
            ) {
                current.copy(contentKinds = defaultReadingSearchContentKinds())
            } else {
                current
            }
        }
        if (selectedReadingSearchSource.value !in filtered.map { it.mangaSource.name }.toSet()) {
            selectedReadingSearchSource.value = null
            readingSearchFilterState.value = ReadingSearchFilterState()
        }
    }

    private fun maybeAutoSearchReadingSourcesForTrackingWork() {
        if (activeExternalOrigin !is org.skepsun.kototoro.details.ui.model.DetailsOrigin.TrackingItem) {
            return
        }
        if (!allEnabledSourcesLoaded || !isWorkDetails.value || currentObservedLocalMangaIdSnapshot() != null) {
            return
        }
        val canRetryEmptySearch = readingSearchHasSearched.value &&
            readingSearchSections.value.isEmpty() &&
            readingSearchSources.value.isNotEmpty()
        if ((readingSearchHasSearched.value && !canRetryEmptySearch) || readingSearchLoading.value) {
            return
        }
        searchReadingBindings()
    }

    private fun List<ContentSourceInfo>.filterByPreset(
        preset: SourcePreset?,
    ): List<ContentSourceInfo> {
        if (preset == null) {
            return this
        }
        return filter { it.mangaSource.name in preset.sources }
    }

    private fun syntheticSource(
        name: String,
        contentType: ContentType,
        locale: String = "",
    ): org.skepsun.kototoro.parsers.model.ContentSource = object : org.skepsun.kototoro.parsers.model.ContentSource {
        override val name: String = name
        override val locale: String = locale
        override val contentType: ContentType = contentType
    }

    private fun trackingDetailsToSyntheticChapters(
        details: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails,
        source: org.skepsun.kototoro.parsers.model.ContentSource,
    ): List<ContentChapter> {
        val episodes = details.episodes.ifEmpty {
            val count = details.totalEpisodes?.takeIf { it > 0 } ?: return emptyList()
            val label = if (details.contentType == ContentType.VIDEO || details.contentType == ContentType.HENTAI_VIDEO) {
                "Episode"
            } else {
                "Chapter"
            }
            (1..count).map { number ->
                org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails.EpisodeInfo(
                    number = number.toString(),
                    title = "$label $number",
                    url = details.url.orEmpty(),
                )
            }
        }

        return episodes.mapIndexed { index, episode ->
            ContentChapter(
                id = syntheticChapterId(details.service, details.remoteId, episode.url, index),
                title = episode.title.ifBlank { episode.number },
                number = episode.number.toFloatOrNull() ?: (index + 1).toFloat(),
                volume = 0,
                url = episode.url,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }
    }

    private fun trackingDetailsToSyntheticContent(details: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails): Content {
        val contentType = details.contentType ?: ContentType.MANGA
        val language = resolveTrackingLanguage(details.infoboxProperties)
        val source = syntheticSource("TRACKING_${details.service.name}", contentType, language)
        val authors = resolveTrackingAuthors(details)
        val chapters = trackingDetailsToSyntheticChapters(details, source).ifEmpty { null }
        val normalizedCoverUrl = details.coverUrl.normalizedImageUrl()
        val description = details.description.toDisplayDescription()
        return Content(
            id = details.remoteId,
            title = details.title,
            altTitles = setOfNotNull(details.altTitle?.takeIf { it.isNotBlank() }),
            url = details.url.orEmpty(),
            publicUrl = details.url.orEmpty(),
            rating = (details.score ?: 0f) / 10f,
            contentRating = null,
            coverUrl = normalizedCoverUrl,
            largeCoverUrl = normalizedCoverUrl,
            tags = details.tags.mapTo(linkedSetOf()) { tag ->
                org.skepsun.kototoro.parsers.model.ContentTag(
                    title = tag,
                    key = tag.lowercase(),
                    source = source,
                )
            },
            state = resolveTrackingState(details.infoboxProperties),
            authors = authors,
            description = description,
            chapters = chapters,
            source = source,
        )
    }

    private fun String?.toDisplayDescription(): String? {
        if (isNullOrBlank()) {
            return null
        }
        return runCatching {
            parseAsHtml().sanitize().toString()
        }.getOrElse {
            sanitize().toString()
        }.takeIf { it.isNotBlank() }
    }

    private fun syntheticChapterId(
        service: ScrobblerService,
        remoteId: Long,
        url: String,
        index: Int,
    ): Long {
        return "${service.id}:$remoteId:$url:$index".hashCode().toLong() and Long.MAX_VALUE
    }

    private fun resolveTrackingAuthors(
        details: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails,
    ): Set<String> {
        if (details.authors.isNotEmpty()) {
            return details.authors.filter { it.isNotBlank() }.toSet()
        }
        return details.infoboxProperties.mapNotNull { (key, value) ->
            if (!key.isAuthorProperty()) {
                return@mapNotNull null
            }
            value.takeIf { it.isNotBlank() }
        }.toSet()
    }

    private fun resolveTrackingState(properties: List<Pair<String, String>>): ContentState? {
        val value = properties.firstMappedValue("status", "publishing", "airing", "state")
            ?.lowercase(Locale.ROOT)
            ?: return null
        return when {
            value.contains("ongoing") || value.contains("publishing") || value.contains("releasing") || value.contains("airing") -> ContentState.ONGOING
            value.contains("finished") || value.contains("completed") || value.contains("complete") || value.contains("ended") -> ContentState.FINISHED
            value.contains("hiatus") || value.contains("pause") || value.contains("on hold") -> ContentState.PAUSED
            value.contains("cancel") || value.contains("abandon") || value.contains("dropped") -> ContentState.ABANDONED
            value.contains("upcoming") || value.contains("announced") || value.contains("not yet") || value.contains("tba") -> ContentState.UPCOMING
            else -> null
        }
    }

    private fun resolveTrackingLanguage(properties: List<Pair<String, String>>): String {
        val value = properties.firstMappedValue("language", "lang", "original language")
            ?.lowercase(Locale.ROOT)
            ?: return ""
        return when {
            value.contains("japanese") || value.contains("日本語") || value.contains("ja") -> "ja"
            value.contains("chinese") || value.contains("中文") || value.contains("mandarin") || value.contains("zh") -> "zh"
            value.contains("english") || value.contains("en") -> "en"
            value.contains("korean") || value.contains("한국어") || value.contains("ko") -> "ko"
            value.contains("french") || value.contains("fr") -> "fr"
            value.contains("spanish") || value.contains("es") -> "es"
            else -> ""
        }
    }

    private fun List<Pair<String, String>>.firstMappedValue(vararg keys: String): String? {
        return firstOrNull { (key, value) ->
            value.isNotBlank() && keys.any { expected ->
                key.normalizedMetadataKey().contains(expected)
            }
        }?.second
    }

    private fun String.normalizedMetadataKey(): String {
        return lowercase(Locale.ROOT)
            .replace("：", ":")
            .replace("_", " ")
            .replace("-", " ")
            .replace(" ", "")
    }

    private fun String.isAuthorProperty(): Boolean {
        val key = normalizedMetadataKey()
        return key.contains("author") ||
            key.contains("creator") ||
            key.contains("writer") ||
            key.contains("artist") ||
            key.contains("illustrator") ||
            key.contains("director") ||
            key.contains("studio") ||
            key.contains("staff") ||
            key.contains("原作") ||
            key.contains("作者") ||
            key.contains("作画") ||
            key.contains("编剧") ||
            key.contains("脚本") ||
            key.contains("监督") ||
            key.contains("导演") ||
            key.contains("制作")
    }

    private fun String.isCharacterProperty(): Boolean {
        val key = normalizedMetadataKey()
        return key.contains("character") ||
            key.contains("cast") ||
            key.contains("角色") ||
            key.contains("人物") ||
            key.contains("登场")
    }

    private fun splitTrackingNames(raw: String): List<String> {
        return raw.split('/', '／', ',', '，', ';', '；', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun selectLegacyTrackingLinkAnchor(
        links: List<org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity>,
        preferredMangaIds: List<Long> = emptyList(),
    ): Long? {
        val distinctPreferredMangaIds = preferredMangaIds.distinct()
        if (distinctPreferredMangaIds.isNotEmpty()) {
            distinctPreferredMangaIds.firstOrNull { preferredMangaId ->
                links.any { link -> link.mangaId == preferredMangaId }
            }?.let { return it }
        }
        return links.asSequence()
            .filter { it.mangaId != 0L }
            .sortedWith(
                compareByDescending<org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity> { it.isManual }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.updatedAt },
            )
            .map { it.mangaId }
            .firstOrNull()
    }

    private fun normalizeContributorName(raw: String): String {
        return raw.substringBefore(" (").substringBefore("（").trim()
    }



    fun setSpaceContext(spaceId: SpaceId?) {
        if (detailsSpaceId == spaceId) {
            return
        }
        detailsSpaceId = spaceId
        Log.d("DetailsViewModel", "setSpaceContext: spaceId=$spaceId")
    }

    init {
        Log.i(
            DETAILS_TRACE_TAG,
            "vm.init origin=${activeExternalOrigin.detailsTraceSummary()} intentId=${intent.mangaId} " +
                "initialOverride=${initialProjectionIntentOverride?.mangaId} activeMangaId=${activeMangaIdFlow.value}",
        )
        // Apply instant first paint only from the explicit DetailsOrigin payload.
        // Raw intent seed should not predefine current details before real resolution.
        baseLoadedDetails = originContent?.let { ContentDetails(it) }
        syncDisplayedState()
        metadataSearchServices.value = ScrobblerService.entries.filter { service ->
            trackingSiteDiscoveryService.getCapabilities(service).supportsSearch
        }
        authorizedTrackingServices.value = scrobblers.filter { it.isEnabled }.mapTo(linkedSetOf()) { it.scrobblerService }
        selectedMetadataSearchService.value = (selectedMetadataSource.value as? MetadataSourceSelection.Tracking)?.service
            ?: metadataSearchServices.value.firstOrNull()
            ?: settings.preferredTrackingSite
        val initialTitle = currentDetailsTitle()
        if (metadataSearchQuery.value.isBlank() && initialTitle.isNotBlank()) {
            metadataSearchQuery.value = initialTitle
        }
        val initialReadingTitle = currentReadingSearchTitle()
        if (readingSearchQuery.value.isBlank() && initialReadingTitle.isNotBlank()) {
            readingSearchQuery.value = initialReadingTitle
        }
        activeMangaIdFlow.value
            ?.takeIf { activeExternalOrigin !is org.skepsun.kototoro.details.ui.model.DetailsOrigin.TrackingItem }
            ?.let { _ ->
                launchJob(Dispatchers.IO) {
                    val observedLocalMangaId = currentObservedLocalMangaIdSnapshot() ?: return@launchJob
                    restorePersistedMetadataSourceSelection(observedLocalMangaId)
                }
            }

        launchJob(Dispatchers.Default) {
            mangaDetails.filterNotNull().collect { details ->
                val content = details.toContent()
                val title = cleanSourceSearchQuery(content.title)
                val readingTitle = content.readingSearchTitle()
                if (title.isNotBlank()) {
                    if (metadataSearchQuery.value.isBlank()) {
                        metadataSearchQuery.value = title
                    }
                    if (readingSearchQuery.value.isBlank() && readingTitle.isNotBlank()) {
                        readingSearchQuery.value = readingTitle
                    }
                }
            }
        }

        launchJob(Dispatchers.Default) {
            combine(
                flowOrFallback(emptyList()) { contentSourcesRepository.observeEnabledSources() },
                activeSourcePreset,
            ) { sources, preset ->
                sources.filterByPreset(preset)
            }.collect { sources ->
                allEnabledSourceInfos.value = sources
                updateSourceOptions()
                refreshResolvedPresentationState()
                refreshReadingSearchSources()
                allEnabledSourcesLoaded = true
                maybeAutoSearchReadingSourcesForTrackingWork()
            }
        }

        launchJob(Dispatchers.Default) {
            merge(
                org.skepsun.kototoro.core.extensions.GlobalExtensionManager.updates,
                mihonExtensionManager.changes,
                aniyomiExtensionManager.changes,
                ireaderExtensionManager.changes,
            ).collect {
                updateSourceOptions()
                refreshResolvedPresentationState()
            }
        }

        launchJob(Dispatchers.IO) {
            currentObservedLocalMangaId.flatMapLatest { localId ->
                if (localId == null) {
                    flowOf(emptyList())
                } else {
                    flowOrFallback(emptyList()) { observeTrackingLinksByWork(localId) }
                }
            }.collect { links ->
                val linkedCandidates = links.mapNotNull { link ->
                    val service = ScrobblerService.entries.firstOrNull { it.id == link.service } ?: return@mapNotNull null
                    val cached = trackingSiteCacheRepository.readDetails(service, link.remoteId)
                    TrackingMetadataCandidate(
                        service = service,
                        remoteId = link.remoteId,
                        url = cached?.url,
                    )
                }
                trackingMetadataCandidates.value = mergeTrackingMetadataCandidates(linkedCandidates)
                syncDisplayedState()
            }
        }

        if (activeExternalOrigin is org.skepsun.kototoro.details.ui.model.DetailsOrigin.TrackingEntity) {
            launchJob(Dispatchers.IO) {
                val service = ScrobblerService.entries.firstOrNull {
                    it.id == activeExternalOrigin.serviceId.toIntOrNull()
                } ?: return@launchJob
                val entityType = runCatching {
                    EntityType.valueOf(activeExternalOrigin.entityTypeName)
                }.getOrNull() ?: return@launchJob
                isWorkDetails.value = entityType == EntityType.WORK
                val cached = trackingSiteCacheRepository.readEntityDetails(
                    service = service,
                    entityType = entityType,
                    remoteId = activeExternalOrigin.remoteId,
                )
                if (mangaDetails.value == null) {
                    baseLoadedDetails = ContentDetails(
                        cached?.let(::trackingDetailsToSyntheticContent)
                            ?: trackingEntityOriginToSyntheticContent(activeExternalOrigin, service),
                    )
                    syncDisplayedState()
                }
                val remoteDetails = runCatching {
                    trackingSiteDiscoveryService.getEntityDetails(
                        service = service,
                        entityType = entityType,
                        remoteId = activeExternalOrigin.remoteId,
                        urlHint = activeExternalOrigin.url,
                    )
                }.getOrNull()
                if (remoteDetails != null) {
                    cacheEntityTrackingDetails(entityType, remoteDetails)
                    baseLoadedDetails = ContentDetails(trackingDetailsToSyntheticContent(remoteDetails))
                    syncDisplayedState()
                }
            }
        } else if (activeExternalOrigin is org.skepsun.kototoro.details.ui.model.DetailsOrigin.TrackingItem) {
            launchJob(Dispatchers.IO) {
                val service = ScrobblerService.entries.firstOrNull { it.id == activeExternalOrigin.serviceId.toIntOrNull() } ?: return@launchJob
                val cached = trackingSiteCacheRepository.readDetails(service, activeExternalOrigin.remoteId)
                trackingMetadataCandidates.value = mergeTrackingMetadataCandidates(
                    listOf(
                        TrackingMetadataCandidate(
                            service = service,
                            remoteId = activeExternalOrigin.remoteId,
                            url = activeExternalOrigin.url ?: cached?.url,
                        ),
                    ),
                )

                if (mangaDetails.value == null && cached != null) {
                    cacheTrackingDetails(cached)
                    baseLoadedDetails = ContentDetails(trackingDetailsToSyntheticContent(cached))
                    syncDisplayedState()
                }

                val remoteDetails = try { trackingSiteDiscoveryService.getDetails(service, activeExternalOrigin.remoteId, activeExternalOrigin.url) } catch (e: Exception) { null }
                if (remoteDetails != null) {
                    cacheTrackingDetails(remoteDetails)
                    baseLoadedDetails = ContentDetails(trackingDetailsToSyntheticContent(remoteDetails))
                    syncDisplayedState()
                }

                if (remoteDetails != null) {
                    trackingSiteCacheRepository.saveDetails(remoteDetails)
                }

                flowOrFallback(emptyList()) {
                    db.getTrackingSiteDao().observeLinks(service.id, activeExternalOrigin.remoteId)
                }.collect { links ->
                    val trackingMangaId = links.firstOrNull { it.mangaId != 0L }?.mangaId
                    if (trackingMangaId != null && activeMangaIdFlow.value == null) {
                        currentLoadIntentOverride = ContentIntent.of(trackingMangaId)
                        activeMangaIdFlow.value = trackingMangaId
                        persistMetadataSourceSelectionForCurrentEntity(fallbackMangaId = trackingMangaId)
                        loadingJob = doLoad(force = false)
                    }
                }
            }
        }

    }

    private fun mergeTrackingMetadataCandidates(
        candidates: List<TrackingMetadataCandidate>,
    ): List<TrackingMetadataCandidate> {
        val originCandidate = (activeExternalOrigin as? org.skepsun.kototoro.details.ui.model.DetailsOrigin.TrackingItem)
            ?.let { origin ->
                val service = ScrobblerService.entries.firstOrNull { it.id == origin.serviceId.toIntOrNull() }
                    ?: return@let null
                TrackingMetadataCandidate(
                    service = service,
                    remoteId = origin.remoteId,
                    url = origin.url,
                )
            }
        // Keep the currently-selected tracking candidate visible even if the new
        // active local manga has no tracking link yet. Otherwise switching the
        // active local source would silently drop the user's tracking metadata
        // selection from the UI and appear as an auto-switch back to local.
        val selectedCandidate = (selectedMetadataSource.value as? MetadataSourceSelection.Tracking)
            ?.let { selection ->
                TrackingMetadataCandidate(
                    service = selection.service,
                    remoteId = selection.remoteId,
                    url = selection.url,
                )
            }
        return buildList {
            originCandidate?.let(::add)
            selectedCandidate?.let(::add)
            addAll(candidates)
        }.distinctBy { trackingMetadataKey(it.service, it.remoteId) }
    }

    private fun trackingMetadataKey(service: ScrobblerService, remoteId: Long): String {
        return "${service.id}:$remoteId"
    }

    private fun entityTrackingKey(
        entityType: EntityType,
        service: ScrobblerService,
        remoteId: Long,
    ): String {
        return "${entityType.name}:${service.id}:$remoteId"
    }

    private fun cacheTrackingDetails(details: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails) {
        cachedTrackingDetails[trackingMetadataKey(details.service, details.remoteId)] = details
    }

    private fun cacheEntityTrackingDetails(
        entityType: EntityType,
        details: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails,
    ) {
        cachedEntityTrackingDetails[entityTrackingKey(entityType, details.service, details.remoteId)] = details
    }

    private fun currentTrackingMetadataDetails(): org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails? {
        val selection = selectedMetadataSource.safeValueOrNull() as? MetadataSourceSelection.Tracking ?: return null
        return cachedTrackingDetails[trackingMetadataKey(selection.service, selection.remoteId)]
    }

    private fun currentSupplementalTrackingDetails(): org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails? {
        currentTrackingMetadataDetails()?.let { return it }
        val origin = activeExternalOrigin as? org.skepsun.kototoro.details.ui.model.DetailsOrigin.TrackingEntity
            ?: return null
        val service = ScrobblerService.entries.firstOrNull {
            it.id == origin.serviceId.toIntOrNull()
        } ?: return null
        val entityType = runCatching {
            EntityType.valueOf(origin.entityTypeName)
        }.getOrNull() ?: return null
        return cachedEntityTrackingDetails[entityTrackingKey(entityType, service, origin.remoteId)]
            ?: trackingSiteCacheRepository.readEntityDetails(service, entityType, origin.remoteId)?.also { cached ->
                cacheEntityTrackingDetails(entityType, cached)
            }
    }





    private fun supportsEntityTrackingDetails(
        service: ScrobblerService,
        entityType: EntityType,
    ): Boolean {
        return when (service) {
            ScrobblerService.ANILIST,
            ScrobblerService.BANGUMI,
            ScrobblerService.KITSU,
            ScrobblerService.MAL,
            ScrobblerService.SHIKIMORI,
            -> entityType == EntityType.PERSON || entityType == EntityType.CHARACTER

            ScrobblerService.MANGAUPDATES -> entityType == EntityType.PERSON
            ScrobblerService.SIMKL -> false
        }
    }

    private fun org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails.hasRichMetadata(): Boolean {
        return infoboxProperties.isNotEmpty() ||
            characters.isNotEmpty() ||
            commentThreads.isNotEmpty() ||
            relatedWorks.isNotEmpty() ||
            recommendations.isNotEmpty() ||
            extraSections.isNotEmpty() ||
            actions.isNotEmpty()
    }

    private fun MetadataSourceSelection.toPersistedSelection(): PersistedMetadataSourceSelection {
        return when (this) {
            MetadataSourceSelection.Base -> PersistedMetadataSourceSelection.Base
            is MetadataSourceSelection.Tracking -> PersistedMetadataSourceSelection.Tracking(
                serviceId = service.id,
                remoteId = remoteId,
            )
        }
    }

    private suspend fun persistMetadataSourceSelection(mangaId: Long) {
        dataRepository.setMetadataSourceSelection(
            mangaId = mangaId,
            selection = selectedMetadataSource.value.toPersistedSelection(),
        )
    }

    private suspend fun resolveCurrentMetadataPersistenceMangaId(): Long? {
        val projectionSnapshot = currentWorkProjectionSnapshot()
        return projectionSnapshot.activeLocalMangaId
            ?: projectionSnapshot.currentReadingProjectionMangaId
            ?: baseLoadedDetails?.local?.manga?.id
            ?: resolveCurrentLocalMangaId()
    }

    private suspend fun persistMetadataSourceSelectionForCurrentEntity(
        fallbackMangaId: Long? = null,
    ) {
        if (isTemporaryReadOnly) {
            return
        }
        val selection = selectedMetadataSource.value.toPersistedSelection()
        val resolvedFallbackMangaId = fallbackMangaId ?: resolveCurrentMetadataPersistenceMangaId()
        val targetIds = listOfNotNull(resolvedFallbackMangaId)
        android.util.Log.d(
            "DetailsViewModel",
            "persistMetadataSourceSelection: fallbackMangaId=$resolvedFallbackMangaId, " +
                "selection=$selection, fallbackTargetIds=$targetIds",
        )
        // Projection-first: the metadata authority belongs to the manga projection itself,
        // so it is persisted directly against every resolved projection id.
        targetIds.forEach { mangaId ->
            dataRepository.setMetadataSourceSelection(
                mangaId = mangaId,
                selection = selection,
            )
        }
    }

    private suspend fun restoreEntityMetadataSourceSelection(
        entityId: Long,
        fallbackMangaId: Long? = null,
    ) {
        val resolvedFallbackMangaId = fallbackMangaId ?: resolveCurrentMetadataPersistenceMangaId()
        resolvedFallbackMangaId?.let { restorePersistedMetadataSourceSelection(it) }
    }

    private suspend fun applyTrackingSource(service: ScrobblerService, remoteId: Long) {
        android.util.Log.d(
            "DetailsViewModel",
            "applyTrackingSource: service=${service.name}, remoteId=$remoteId",
        )
        val cached = trackingSiteCacheRepository.readDetails(service, remoteId)
        if (cached != null) {
            cacheTrackingDetails(cached)
        }
        trackingMetadataCandidates.value = mergeTrackingMetadataCandidates(
            trackingMetadataCandidates.value + TrackingMetadataCandidate(
                service = service,
                remoteId = remoteId,
                url = cached?.url,
            ),
        )
        selectedMetadataSource.value = MetadataSourceSelection.Tracking(
            service = service,
            remoteId = remoteId,
            url = cached?.url,
        )
        selectedMetadataSearchService.value = service
        syncDisplayedState()
        if (cached == null) {
            ensureTrackingDetailsLoaded(
                service = service,
                remoteId = remoteId,
                url = null,
            )
        }
    }

    private suspend fun restorePersistedMetadataSelection(
        persisted: PersistedMetadataSourceSelection.Tracking,
    ) {
        val service = ScrobblerService.entries.firstOrNull { it.id == persisted.serviceId } ?: return
        val cached = trackingSiteCacheRepository.readDetails(service, persisted.remoteId)
        android.util.Log.d(
            "DetailsViewModel",
            "restorePersistedMetadataSelection: service=${service.name}, remoteId=${persisted.remoteId}, cached=${cached != null}",
        )
        if (cached != null) {
            cacheTrackingDetails(cached)
        }
        trackingMetadataCandidates.value = mergeTrackingMetadataCandidates(
            trackingMetadataCandidates.value + TrackingMetadataCandidate(
                service = service,
                remoteId = persisted.remoteId,
                url = cached?.url,
            ),
        )
        selectedMetadataSource.value = MetadataSourceSelection.Tracking(
            service = service,
            remoteId = persisted.remoteId,
            url = cached?.url,
        )
        selectedMetadataSearchService.value = service
        syncDisplayedState()
        ensureTrackingDetailsLoaded(
            service = service,
            remoteId = persisted.remoteId,
            url = cached?.url,
        )
    }

    private suspend fun restorePersistedMetadataSourceSelection(mangaId: Long) {
        val persisted = dataRepository.getMetadataSourceSelection(mangaId)
        android.util.Log.d(
            "DetailsViewModel",
            "restorePersistedMetadataSourceSelection: mangaId=$mangaId, persisted=$persisted",
        )
        when (persisted) {
            null -> Unit
            PersistedMetadataSourceSelection.Base -> {
                if (selectedMetadataSource.value != MetadataSourceSelection.Base) {
                    selectedMetadataSource.value = MetadataSourceSelection.Base
                    syncDisplayedState()
                }
            }
            is PersistedMetadataSourceSelection.Tracking -> {
                restorePersistedMetadataSelection(persisted)
            }
        }
    }

    private fun mergeActualAndMetadataChapters(
        metadataChapters: List<ContentChapter>,
        actualChapters: List<ContentChapter>,
    ): List<ContentChapter> {
        if (actualChapters.isEmpty()) {
            return metadataChapters
        }
        if (metadataChapters.isEmpty()) {
            return actualChapters
        }
        val remainingActual = actualChapters.toMutableList()
        return buildList {
            metadataChapters.forEach { metadataChapter ->
                val match = remainingActual.firstOrNull { actual ->
                    actual.id == metadataChapter.id ||
                        (
                            actual.number > 0f &&
                                metadataChapter.number > 0f &&
                                actual.number == metadataChapter.number
                        ) ||
                        (
                            !actual.title.isNullOrBlank() &&
                                actual.title == metadataChapter.title
                        )
                }
                if (match != null) {
                    add(match)
                    remainingActual.remove(match)
                } else {
                    add(metadataChapter)
                }
            }
            addAll(remainingActual)
        }
    }

    private fun mergeTrackingMetadata(
        base: ContentDetails?,
        trackingDetails: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails,
    ): ContentDetails {
        val trackingContent = trackingDetailsToSyntheticContent(trackingDetails)
        val baseContent = base?.toContent()
        if (baseContent == null) {
            return ContentDetails(
                manga = trackingContent,
                localContent = null,
                override = null,
                description = trackingDetails.description.toDisplayDescription() ?: trackingContent.description,
                isLoaded = true,
            )
        }
        // IMPORTANT: keep baseContent's identity (id, url, publicUrl, source, chapters)
        // so the reader/downloader can still resolve pages from the real reading source.
        // Only override display-only fields from tracking metadata.
        val mergedManga = baseContent.copy(
            title = trackingContent.title.ifBlank { baseContent.title },
            altTitles = (baseContent.altTitles + trackingContent.altTitles).toSet(),
            coverUrl = trackingContent.coverUrl.normalizedImageUrl() ?: baseContent.coverUrl,
            largeCoverUrl = trackingContent.largeCoverUrl.normalizedImageUrl()
                ?: trackingContent.coverUrl.normalizedImageUrl()
                ?: baseContent.largeCoverUrl,
            rating = if (trackingContent.rating > 0f) trackingContent.rating else baseContent.rating,
            tags = if (trackingContent.tags.isNotEmpty()) trackingContent.tags else baseContent.tags,
            state = trackingContent.state ?: baseContent.state,
            authors = if (trackingContent.authors.isNotEmpty()) trackingContent.authors else baseContent.authors,
            description = trackingContent.description?.takeIf { it.isNotBlank() } ?: baseContent.description,
        )
        return ContentDetails(
            manga = mergedManga,
            localContent = base.local,
            override = null,
            description = trackingDetails.description.toDisplayDescription() ?: base.description ?: trackingContent.description,
            isLoaded = true,
        )
    }

    private fun observeTrackingLinksByWork(mangaId: Long) = flowOrFallback(emptyList()) {
        db.getTrackingSiteDao().observeLinksByManga(mangaId)
    }

    private suspend fun resolveWorkProjectionContext(mangaId: Long): WorkProjectionContext {
        return WorkProjectionContext(
            entityId = null,
            requestedMangaId = mangaId,
            preferredLocalMangaId = mangaId,
            persistedLocalMangaId = mangaId,
            candidateMangaIds = listOf(mangaId),
        )
    }

    private fun selectTrackingLinksForWork(
        context: WorkProjectionContext,
        links: List<org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity>,
    ): List<org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity> {
        return links.groupBy { it.service to it.remoteId }
            .values
            .mapNotNull { duplicates ->
                duplicates.sortedWith(
                    compareByDescending<org.skepsun.kototoro.core.db.entity.TrackingSiteLinkEntity> {
                        it.mangaId == context.requestedMangaId
                    }.thenByDescending {
                        it.mangaId == context.preferredLocalMangaId
                    }.thenByDescending {
                        it.mangaId == context.persistedLocalMangaId
                    }.thenByDescending {
                        it.isManual
                    }.thenByDescending {
                        it.confidence
                    }.thenByDescending {
                        it.updatedAt
                    },
                ).firstOrNull()
            }
            .sortedWith(compareBy({ it.service }, { it.remoteId }))
    }

    private fun syncDisplayedState() {
        val base = baseLoadedDetails
        val trackingDetails = currentTrackingMetadataDetails()
        val supplementalTrackingDetails = currentSupplementalTrackingDetails()
        mangaDetails.value = when {
            trackingDetails != null -> mergeTrackingMetadata(base, trackingDetails)
            base != null -> base
            else -> null
        }
        Log.i(
            DETAILS_TRACE_TAG,
            "state.sync base=${base?.toContent().detailsTraceSummary()} displayed=${mangaDetails.value?.toContent().detailsTraceSummary()} " +
                "tracking=${trackingDetails != null} activeMangaId=${activeMangaIdFlow.value}",
        )
        updateSourceOptions()
        refreshReadingSearchSources()
        maybeAutoSearchReadingSourcesForTrackingWork()
        updateSupplementalDetailsState(supplementalTrackingDetails)
        refreshResolvedPresentationState()
        refreshContextualEntityRelations()
    }

    private fun currentObservedLocalMangaIdSnapshot(): Long? {
        val activeMangaId = activeMangaIdFlow.safeValueOrNull()
        val currentDetails = mangaDetails.safeValueOrNull()
        return activeMangaId
            ?: currentDetails?.local?.manga?.id
            ?: currentDetails?.toContent()?.takeIf { it.isLocal }?.id
    }

    private fun currentWorkProjectionSnapshot(): CurrentWorkProjectionSnapshot {
        // Projection-first: the displayed manga row is both the active and the reading source.
        val localMangaId = currentObservedLocalMangaIdSnapshot()
        return CurrentWorkProjectionSnapshot(
            activeLocalMangaId = localMangaId,
            currentReadingProjectionMangaId = localMangaId,
        )
    }

    private fun refreshResolvedPresentationState() {
        val metadataLanguage = currentMetadataLanguageCode()?.takeIf { it.isNotBlank() }?.normalizedLanguageCode()
        val readingLanguage = currentReadingLanguageCode()?.takeIf { it.isNotBlank() }?.normalizedLanguageCode()
        resolvedMetadataContentType.value = currentMetadataContentType()
        resolvedMetadataLanguage.value = metadataLanguage
        resolvedReadingLanguage.value = readingLanguage
        Log.i(
            DETAILS_TRACE_TAG,
            "state.presentation metadataSource=${metadataSourceOptions.value.map { "${it.source?.name}:${it.source?.locale}:${it.isSelected}" }} " +
                "readingSource=${readingSourceOptions.value.map { "${it.source?.name}:${it.source?.locale}:${it.isSelected}" }} " +
                "metadataLanguage=$metadataLanguage readingLanguage=$readingLanguage contentType=${currentMetadataContentType()}",
        )
        refreshActiveLocalBrowserContent()
        refreshTranslateActionVisibility(metadataLanguage)
    }

    private fun refreshActiveLocalBrowserContent() {
        val activeLocalId = currentWorkProjectionSnapshot().activeLocalMangaId
        val baseContent = baseLoadedDetails?.toContent()
        if (activeLocalId == null || activeLocalId == baseLoadedDetails?.id) {
            activeLocalBrowserContent.value = baseContent?.takeIf { it.publicUrl.isNotBlank() }
            return
        }
        launchJob(Dispatchers.IO) {
            activeLocalBrowserContent.value = db.getMangaDao()
                .find(activeLocalId)
                ?.toContent()
                ?.takeIf { it.publicUrl.isNotBlank() }
                ?: baseContent?.takeIf { it.publicUrl.isNotBlank() }
        }
    }

    private fun refreshTranslateActionVisibility(metadataLanguage: String?) {
        if (!settings.isDetailsTranslateButtonVisible) {
            showTranslateAction.value = false
            return
        }
        val targetLanguage = currentTargetLang().takeIf { it.isNotBlank() }?.normalizedLanguageCode()
        if (targetLanguage.isNullOrBlank()) {
            showTranslateAction.value = false
            return
        }
        translateAvailabilityJob?.cancel()
        translateAvailabilityJob = viewModelScope.launch(Dispatchers.IO) {
            val details = mangaDetails.safeValueOrNull()
            val sampleText = buildString {
                append(details?.toContent()?.title.orEmpty())
                if (length < 24) {
                    append(' ')
                    append(details?.description?.toString().orEmpty())
                }
            }.trim()
            val detectedLanguage = if (sampleText.isNotBlank()) {
                detectLanguageViaMlKit(sampleText)?.normalizedLanguageCode()
            } else {
                null
            }
            val effectiveLanguage = metadataLanguage ?: detectedLanguage
            showTranslateAction.value = effectiveLanguage == null || effectiveLanguage != targetLanguage
        }
    }

    private fun updateSourceOptions() {
        val selection = selectedMetadataSource.value
        val baseContent = baseLoadedDetails?.toContent() ?: originContent
        val baseSource = baseContent?.source?.resolveDetailsSource()
        val baseLooksLikeTracking = baseSource?.name?.startsWith("TRACKING_") == true
        val metadata = buildList {
            if (baseSource != null && !baseLooksLikeTracking) {
                add(
                    DetailsSourceOption(
                        key = "base:${baseSource.name}",
                        source = baseSource,
                        title = baseContent?.title,
                        coverUrl = baseContent?.coverUrl.normalizedImageUrl(),
                        isSelected = selection == MetadataSourceSelection.Base,
                    ),
                )
            }
            addAll(
                trackingMetadataCandidates.value.map { candidate ->
                    val cached = cachedTrackingDetails[trackingMetadataKey(candidate.service, candidate.remoteId)]
                    DetailsSourceOption(
                        key = trackingMetadataKey(candidate.service, candidate.remoteId),
                        trackingService = candidate.service,
                        remoteId = candidate.remoteId,
                        url = candidate.url,
                        title = cached?.title ?: contentTitleFallback(candidate.service),
                        subtitle = contentTitleFallback(candidate.service),
                        coverUrl = cached?.coverUrl.normalizedImageUrl(),
                        isSelected = selection is MetadataSourceSelection.Tracking &&
                            selection.service == candidate.service &&
                            selection.remoteId == candidate.remoteId,
                    )
                },
            )
            if (isEmpty() && baseSource != null) {
                add(
                    DetailsSourceOption(
                        key = "base:${baseSource.name}",
                        source = baseSource,
                        title = baseContent?.title,
                        coverUrl = baseContent?.coverUrl.normalizedImageUrl(),
                        isSelected = true,
                    ),
                )
            }
        }.distinctBy(DetailsSourceOption::key)
        metadataSourceOptions.value = metadata

        // Projection-first: the reading source is the manga row itself. It is always listed —
        // the content-type compatibility check is for foreign candidates, and an uninstalled
        // extension would otherwise make the current manga's own source look incompatible.
        val currentDisplayedDetails = mangaDetails.safeValueOrNull()
        val readingSource = baseSource
            ?.takeUnless { it.name.startsWith("TRACKING_") }
            ?: currentDisplayedDetails
                ?.toContent()
                ?.source
                ?.resolveDetailsSource()
                ?.takeUnless { it.name.startsWith("TRACKING_") }
            ?: currentDisplayedDetails
                ?.takeIf { it.isLocal }
                ?.local
                ?.manga
                ?.source
        readingSourceOptions.value = readingSource?.let {
            listOf(
                DetailsSourceOption(
                    key = "reading:${it.name}",
                    source = it,
                    targetMangaId = currentObservedLocalMangaIdSnapshot(),
                    title = baseContent?.title,
                    coverUrl = baseContent?.coverUrl.normalizedImageUrl(),
                    isSelected = true,
                ),
            )
        }.orEmpty()
        updateChapterSourceTabs()
        Log.i(
            DETAILS_TRACE_TAG,
            "state.options base=${baseContent.detailsTraceSummary()} " +
                "metadata=${metadataSourceOptions.value.map { "${it.key}:${it.source?.name}:${it.isSelected}" }} " +
                "reading=${readingSourceOptions.value.map { "${it.key}:${it.source?.name}:${it.source?.locale}:${it.isSelected}" }}",
        )
    }

    private fun updateChapterSourceTabs() {
        val trackingSelection = selectedMetadataSource.safeValueOrNull() as? MetadataSourceSelection.Tracking
        metadataChapterTabs.value = if (trackingSelection != null) {
            trackingMetadataCandidates.safeValueOrNull().orEmpty().map { candidate ->
                val details = cachedTrackingDetails[trackingMetadataKey(candidate.service, candidate.remoteId)]
                val contentType = details?.contentType ?: currentMetadataContentType() ?: ContentType.MANGA
                val locale = details?.let { resolveTrackingLanguage(it.infoboxProperties) }.orEmpty()
                val source = syntheticSource(
                    name = "TRACKING_${candidate.service.name}",
                    contentType = contentType,
                    locale = locale,
                )
                DetailsChapterSourceTab(
                    key = trackingMetadataKey(candidate.service, candidate.remoteId),
                    source = source,
                    trackingService = candidate.service,
                    remoteId = candidate.remoteId,
                    url = candidate.url ?: details?.url,
                    chapters = details?.let { trackingDetailsToSyntheticChapters(it, source) }.orEmpty(),
                    isSelected = trackingSelection.service == candidate.service &&
                        trackingSelection.remoteId == candidate.remoteId,
                )
            }
        } else {
            emptyList()
        }

        readingChapterTabs.value = readingSourceOptions.safeValueOrNull().orEmpty().map { option ->
            DetailsChapterSourceTab(
                key = option.key,
                source = option.source,
                targetMangaId = option.targetMangaId,
                url = option.url,
                isSelected = option.isSelected,
            )
        }
        val metadataTabSummary = metadataChapterTabs.value.map { "${it.key}:${it.isSelected}:${it.chapters.size}" }
        val readingTabSummary = readingChapterTabs.value.map { "${it.key}:${it.isSelected}:${it.chapters.size}" }
        android.util.Log.d(
            "DetailsViewModel",
            "updateChapterSourceTabs: metadataTabs=$metadataTabSummary, readingTabs=$readingTabSummary",
        )
    }

    private fun updateSupplementalDetailsState(
        details: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails?,
    ) {
        supplementalMetadataProperties.value = details?.infoboxProperties.orEmpty()
        if (details == null) {
            supplementalSections.value = emptyList()
            supplementalActions.value = emptyList()
            supplementalCommentThreads.value = emptyList()
            supplementalCommentsUrl.value = null
            supplementalReviews.value = emptyList()
            supplementalReviewsUrl.value = null
            return
        }
        val commentsAction = details.actions.firstOrNull { action ->
            action.url.contains("/comments") ||
                action.url.contains("#comments") ||
                action.title.contains("吐槽", ignoreCase = true) ||
                action.title.contains("comment", ignoreCase = true)
        }
        val reviewsAction = details.actions.firstOrNull { action ->
            action.url.contains("/reviews") ||
                action.url.contains("/review/") ||
                action.title.contains("长评", ignoreCase = true) ||
                action.title.contains("评论", ignoreCase = true) ||
                action.title.contains("review", ignoreCase = true)
        }
        val fallbackCommentsUrl = buildFallbackTrackingCommentsUrl(details)
        val fallbackReviewsUrl = buildFallbackTrackingReviewsUrl(details)
        supplementalCommentsUrl.value = commentsAction?.url ?: fallbackCommentsUrl
        supplementalCommentThreads.value = details.commentThreads
        supplementalReviewsUrl.value = reviewsAction?.url ?: fallbackReviewsUrl
        supplementalReviews.value = details.reviews
        val baseActions = details.actions.filterNot { action ->
            action == commentsAction || action == reviewsAction
        }.map { action ->
            DetailsSupplementAction(
                title = action.title,
                url = action.url,
            )
        }
        supplementalActions.value = baseActions.distinctBy { it.title to it.url }
        supplementalSections.value = buildList {
            details.characters
                .takeIf { it.isNotEmpty() }
                ?.let { characters ->
                    add(
                        EntityRelationSection(
                            titleRes = R.string.entity_graph_section_characters,
                            items = characters.map { character ->
                                EntityRelationItem(
                                    stableKey = "tracking:${details.service.id}:character:${character.id}",
                                    name = character.name,
                                    coverUrl = character.coverUrl.normalizedImageUrl(),
                                    type = EntityType.CHARACTER,
                                    subtitle = character.role?.takeIf { it.isNotBlank() },
                                    supportingText = buildTrackingCharacterVoiceActorsText(character),
                                    detailLines = character.voiceActors
                                        .mapNotNull { it.name.takeIf(String::isNotBlank) }
                                        .distinct(),
                                    trackingService = details.service.takeIf { character.id > 0L },
                                    remoteId = character.id.takeIf { it > 0L },
                                    url = character.url,
                                )
                            }.distinctBy(EntityRelationItem::stableKey),
                        ),
                    )
                }
            details.relatedWorks
                .takeIf { it.isNotEmpty() }
                ?.let { works ->
                    add(
                        EntityRelationSection(
                            titleRes = R.string.details_related_works,
                            items = works.map { work ->
                                EntityRelationItem(
                                    stableKey = "tracking:${details.service.id}:${work.id}",
                                    name = work.title,
                                    coverUrl = work.coverUrl.normalizedImageUrl(),
                                    trackingService = details.service,
                                    remoteId = work.id,
                                    subtitle = work.relationship,
                                    url = work.url,
                                )
                            },
                        ),
                    )
                }
            details.recommendations
                .takeIf { it.isNotEmpty() }
                ?.let { works ->
                    add(
                        EntityRelationSection(
                            titleRes = R.string.details_recommendations,
                            items = works.map { work ->
                                EntityRelationItem(
                                    stableKey = "tracking:${details.service.id}:${work.id}",
                                    name = work.title,
                                    coverUrl = work.coverUrl.normalizedImageUrl(),
                                    trackingService = details.service,
                                    remoteId = work.id,
                                    subtitle = work.relationship,
                                    url = work.url,
                                )
                            },
                        ),
                    )
                }
            details.extraSections.forEach { section ->
                if (section.items.isEmpty()) {
                    return@forEach
                }
                add(
                    EntityRelationSection(
                        title = section.title,
                        items = section.items.map { work ->
                            EntityRelationItem(
                                stableKey = "tracking:${details.service.id}:${work.id}",
                                name = work.title,
                                coverUrl = work.coverUrl.normalizedImageUrl(),
                                trackingService = details.service,
                                remoteId = work.id,
                                subtitle = work.relationship,
                                url = work.url,
                            )
                        },
                    ),
                )
            }
        }.deduplicateRelationItems()
    }

    private fun buildTrackingCharacterVoiceActorsText(
        character: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails.CharacterInfo,
    ): String? {
        val voiceActors = character.voiceActors
            .mapNotNull { it.name.takeIf(String::isNotBlank) }
            .distinct()
        return voiceActors
            .take(2)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" / ")
            ?.let { names ->
                if (voiceActors.size > 2) {
                    context.getString(
                        R.string.details_character_voice_actors_more,
                        names,
                        voiceActors.size - 2,
                    )
                } else {
                    context.getString(R.string.details_character_voice_actors, names)
                }
            }
    }

    private fun buildFallbackTrackingCommentsUrl(
        details: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails,
    ): String? {
        val baseUrl = details.url?.substringBefore('?')?.trimEnd('/') ?: return null
        return when (details.service) {
            ScrobblerService.BANGUMI -> "$baseUrl/comments"
            ScrobblerService.MAL -> "$baseUrl/forum"
            ScrobblerService.SHIKIMORI -> "$baseUrl/forum"
            ScrobblerService.MANGAUPDATES -> "$baseUrl#comments"
            else -> baseUrl
        }
    }

    private fun buildFallbackTrackingReviewsUrl(
        details: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails,
    ): String? {
        val baseUrl = details.url?.substringBefore('?')?.trimEnd('/') ?: return null
        return when (details.service) {
            ScrobblerService.BANGUMI -> "$baseUrl/reviews"
            ScrobblerService.MAL -> "$baseUrl/reviews"
            ScrobblerService.SHIKIMORI -> "$baseUrl/reviews"
            else -> baseUrl
        }
    }

    private fun refreshContextualEntityRelations() {
        launchJob(Dispatchers.IO) {
            submitEntityRelationSections(buildEntityRelationSections())
        }
    }

    private suspend fun ensureTrackingDetailsLoaded(
        service: ScrobblerService,
        remoteId: Long,
        url: String?,
    ) {
        val cacheKey = trackingMetadataKey(service, remoteId)
        if (cachedTrackingDetails.containsKey(cacheKey)) {
            val currentSelection = selectedMetadataSource.value
            if (currentSelection is MetadataSourceSelection.Tracking &&
                currentSelection.service == service &&
                currentSelection.remoteId == remoteId
            ) {
                syncDisplayedState()
                refreshContextualEntityRelations()
            }
            return
        }
        trackingSiteCacheRepository.readDetails(service, remoteId)?.let { cached ->
            cacheTrackingDetails(cached)
            if (cached.hasRichMetadata()) {
                val currentSelection = selectedMetadataSource.value
                if (currentSelection is MetadataSourceSelection.Tracking &&
                    currentSelection.service == service &&
                    currentSelection.remoteId == remoteId
                ) {
                    syncDisplayedState()
                    refreshContextualEntityRelations()
                }
                return
            }
        }
        val details = runCatching {
            trackingSiteDiscoveryService.getDetails(service, remoteId, url)
        }.getOrNull() ?: return
        cacheTrackingDetails(details)
        trackingSiteCacheRepository.saveDetails(details)
        val currentSelection = selectedMetadataSource.value
        if (currentSelection is MetadataSourceSelection.Tracking &&
            currentSelection.service == service &&
            currentSelection.remoteId == remoteId
        ) {
            syncDisplayedState()
            refreshContextualEntityRelations()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val history = currentObservedLocalMangaId.flatMapLatest { mangaId ->
        if (mangaId == null) {
            flowOf(null)
        } else {
            flowOrFallback(null) { historyRepository.observeOne(mangaId) }
        }
    }
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    private val readingStateSync: StateFlow<ReaderState?> = combine(
        mangaDetails,
        history,
        selectedBranch,
    ) { details, h, branch ->
        val chapter = details?.allChapters?.findChapterByHistory(h)
            if (h != null && chapter != null) {
                val isCompleted = h.percent >= 0.99999f
                if (isCompleted) {
                    val branchChapters = details.allChapters
                        .filter { it.branch == branch }
                        .sortedBy { it.number }
                val index = branchChapters.indexOfFirst { it.id == chapter.id }
                if (index != -1 && index + 1 < branchChapters.size) {
                    val nextChapter = branchChapters[index + 1]
                    ReaderState(
                        chapterId = nextChapter.id,
                        page = 0,
                        scroll = 0,
                    )
                    } else {
                        ReaderState(
                            chapterId = FULLY_READ_CHAPTER_ID,
                            page = 0,
                            scroll = 0,
                    )
                }
            } else {
                ReaderState(h.copy(chapterId = chapter.id))
            }
        } else {
            null
        }
    }
        .onEach { state ->
            readingState.value = state
        }
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val favouriteCategories = currentObservedLocalMangaId.flatMapLatest { mangaId ->
        if (mangaId == null) {
            flowOf(emptySet())
        } else {
            flowOrFallback(emptySet()) { interactor.observeFavourite(mangaId) }
        }
    }
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptySet())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isStatsAvailable = currentObservedLocalMangaId.flatMapLatest { mangaId ->
        if (mangaId == null) {
            flowOf(false)
        } else {
            flowOrFallback(false) { statsRepository.observeHasStats(mangaId) }
        }
    }
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val readingRecordSnapshot: StateFlow<ReadingRecordSnapshot> = currentObservedLocalMangaId
        .flatMapLatest { mangaId ->
            if (mangaId == null) {
                flowOf(ReadingRecordSnapshot())
            } else {
                flowOrFallback(ReadingRecordSnapshot()) { readingRecordRepository.observeSnapshot(mangaId) }
            }
        }
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, ReadingRecordSnapshot())

    fun recordDetailsJump(toState: ReaderState, source: String) {
        val fromState = readingState.value ?: return
        if (fromState == toState) return
        val manga = getContentOrNull() ?: return
        launchJob(Dispatchers.Default) {
            readingRecordRepository.recordJumpPoint(
                manga = manga,
                fromState = fromState,
                fromPercent = estimateProgress(fromState),
                toState = toState,
                toPercent = estimateProgress(toState),
                source = source,
                force = true,
            )
        }
    }

    private fun estimateProgress(state: ReaderState): Float {
        val chapters = mangaDetails.value?.allChapters.orEmpty()
        if (chapters.isEmpty()) return 0f
        val chapterIndex = chapters.indexOfFirst { it.id == state.chapterId }
        if (chapterIndex < 0) return 0f
        val chapterProgress = (state.scroll / 10000f).coerceIn(0f, 1f)
        return ((chapterIndex + chapterProgress) / chapters.size).coerceIn(0f, 1f)
    }

    val isMarkedSafe = MutableStateFlow(false)

    val remoteContent = MutableStateFlow<Content?>(null)

    private val cachedTranslatedTitle = MutableStateFlow<String?>(null)
    private val cachedTranslatedDescription = MutableStateFlow<String?>(null)
    val isShowingTranslation = MutableStateFlow(false)
    val hasTranslationCache: StateFlow<Boolean> = combine(
        cachedTranslatedTitle,
        cachedTranslatedDescription,
    ) { title, description ->
        title != null || description != null
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)
    val translatedTitle: StateFlow<String?> = combine(
        cachedTranslatedTitle,
        isShowingTranslation,
    ) { title, isShowing ->
        title.takeIf { isShowing }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)
    val translatedDescription: StateFlow<String?> = combine(
        cachedTranslatedDescription,
        isShowingTranslation,
    ) { description, isShowing ->
        description.takeIf { isShowing }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)
    val isTranslating = MutableStateFlow(false)
    private val translationTextUiState = combine(
        translatedTitle,
        translatedDescription,
        isShowingTranslation,
        hasTranslationCache,
    ) { translatedTitle, translatedDescription, isShowingTranslation, hasTranslationCache ->
        TranslationTextUiState(
            translatedTitle = translatedTitle,
            translatedDescription = translatedDescription,
            isShowingTranslation = isShowingTranslation,
            hasTranslationCache = hasTranslationCache,
        )
    }
    val translationUiState: StateFlow<TranslationUiState> = combine(
        translationTextUiState,
        isTranslating,
        showTranslateAction,
    ) { textState, isTranslating, showTranslateAction ->
        TranslationUiState(
            translatedTitle = textState.translatedTitle,
            translatedDescription = textState.translatedDescription,
            isShowingTranslation = textState.isShowingTranslation,
            hasTranslationCache = textState.hasTranslationCache,
            isTranslating = isTranslating,
            showTranslateAction = showTranslateAction,
        )
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, TranslationUiState())

    val historyInfo: StateFlow<HistoryInfo> = combine(
        mangaDetails,
        selectedBranch,
        history,
        interactor.observeIncognitoMode(manga),
        isMergeRepeatedChapters,
    ) { m, b, h, im, mergeRepeated ->
        val estimatedTime = readingTimeUseCase.invoke(m, b, h)
        HistoryInfo(m, b, h, im == TriStateOption.ENABLED, estimatedTime, mergeRepeated)
    }.withErrorHandling()
        .stateIn(
            scope = viewModelScope + Dispatchers.Default,
            started = SharingStarted.Eagerly,
            initialValue = HistoryInfo(null, null, null, false, null),
        )

    val localSize = mangaDetails
        .map { it }  // 获取完整的ContentDetails
        .distinctUntilChanged()
        .combine(localStorageChanges.onStart { emit(null) }) { details, _ -> details }
        .map { details ->
            if (details == null) return@map 0L

            val local = details.local
            if (local != null) {
                // 普通本地漫画：计算文件夹大小
                runCatchingCancellable {
                    local.computeStoredSize(context)
                }.getOrDefault(0L)
            } else {
                // 检查是否有EPUB文件（对于非本地但有EPUB下载的manga）
                val manga = details.toContent()
                runCatchingCancellable {
                    val epubDir = epubStorageManager.getEpubDir(manga.id)
                    if (epubDir.exists()) {
                        epubDir.computeSize()
                    } else {
                        0L
                    }
                }.getOrDefault(0L)
            }
        }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), 0L)

    val isScrobblingAvailable: Boolean
        get() = scrobblers.any { it.isEnabled }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val observedScrobblingInfo = combine(
        currentObservedLocalMangaId,
        selectedMetadataSource,
    ) { activeMangaId, selection ->
        activeMangaId to selection
    }.flatMapLatest { (activeMangaId, selection) ->
        when {
            activeMangaId != null -> flowOrFallback(emptyList()) { interactor.observeScrobblingInfo(activeMangaId) }
            selection is MetadataSourceSelection.Tracking -> {
                flowOrFallback(emptyList()) {
                    db.getScrobblingDao().observeAllByTargetId(selection.service.id, selection.remoteId)
                }
                    .map { entities ->
                        entities.map { entity ->
                            val cached = trackingSiteCacheRepository.readDetails(selection.service, entity.targetId)
                            ScrobblingInfo(
                                scrobbler = selection.service,
                                preferredLocalMangaId = entity.mangaId.takeIf { it != 0L },
                                mangaId = entity.mangaId,
                                targetId = entity.targetId,
                                status = resolveScrobblingStatusOrNull(entity.status),
                                chapter = entity.chapter,
                                comment = entity.comment,
                                rating = entity.rating,
                                title = cached?.title ?: entity.remoteTitle ?: contentTitleFallback(selection.service),
                                coverUrl = cached?.coverUrl ?: entity.remoteCoverUrl.orEmpty(),
                                description = cached?.description,
                                externalUrl = cached?.url ?: entity.remoteUrl.orEmpty(),
                                mediaType = entity.mediaType.takeIf { it.isNotBlank() },
                            )
                        }
                    }
            }
            else -> flowOf(emptyList())
        }
    }
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val observedTrackingLinks = combine(
        currentObservedLocalMangaId,
        selectedMetadataSource,
    ) { activeMangaId, selection ->
        activeMangaId to selection
    }.flatMapLatest { (activeMangaId, selection) ->
        when {
            activeMangaId != null -> flowOrFallback(emptyList()) { observeTrackingLinksByWork(activeMangaId) }
            selection is MetadataSourceSelection.Tracking -> {
                flowOrFallback(emptyList()) {
                    db.getTrackingSiteDao().observeLinks(selection.service.id, selection.remoteId)
                }
            }
            else -> flowOf(emptyList())
        }
    }
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    val scrobblingInfo: StateFlow<List<ScrobblingInfo>> = observedScrobblingInfo

    val trackingMatchSuggestion = MutableStateFlow<TrackingSiteMatchResult?>(null)

    val linkedTrackingItems: StateFlow<List<LinkedTrackingItemUiModel>> = combine(
        observedTrackingLinks,
        scrobblingInfo,
    ) { links, scrobblingItems ->
        links.mapNotNull { link ->
            val service = org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService.entries
                .firstOrNull { it.id == link.service }
                ?: return@mapNotNull null
            val cached = trackingSiteCacheRepository.readDetails(service, link.remoteId)
            val scrobbling = scrobblingItems.firstOrNull {
                it.scrobbler == service && it.targetId == link.remoteId
            }
            LinkedTrackingItemUiModel(
                service = service,
                remoteId = link.remoteId,
                title = cached?.title ?: scrobbling?.title ?: contentTitleFallback(service),
                coverUrl = cached?.coverUrl.normalizedImageUrl() ?: scrobbling?.coverUrl.normalizedImageUrl(),
                summary = cached?.description ?: scrobbling?.description?.toString(),
                url = cached?.url ?: scrobbling?.externalUrl,
                status = scrobbling?.status,
                rating = scrobbling?.rating,
                hasScrobblingBinding = scrobbling != null,
                isPreferred = service == settings.preferredTrackingSite,
            )
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    val relatedContent: StateFlow<List<ContentListModel>> = combine(
        currentObservedLocalMangaId,
        mangaDetails,
    ) { localMangaId, details ->
        localMangaId to details
    }.mapLatest { (localMangaId, details) ->
        val seed = localMangaId
            ?.let { db.getMangaDao().find(it)?.toContent() }
            ?: details?.toContent()
        if (seed != null && settings.isRelatedContentEnabled) {
            val related = relatedContentUseCase(seed).orEmpty()
                .distinctBy { "${it.source.name}:${it.id}:${it.url}" }
            mangaListMapper.toListModelList(
                manga = related,
                mode = ListMode.GRID,
            )
        } else {
            emptyList()
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

    val tags = manga.mapLatest {
        mangaListMapper.mapTags(it?.tags.orEmpty())
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    val branches: StateFlow<List<ContentBranch>> = combine(
        mangaDetails,
        selectedBranch,
        history,
    ) { m, b, h ->
        val c = m?.chapters
        if (c.isNullOrEmpty()) {
            return@combine emptyList()
        }
        val currentBranch = m.allChapters.findChapterByHistory(h)?.branch
        c.map { x ->
            ContentBranch(
                name = x.key,
                count = x.value.size,
                isSelected = x.key == b,
                isCurrent = h != null && x.key == currentBranch,
            )
        }.sortedWith(BranchComparator())
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    private val readingStatus: StateFlow<ScrobblingStatus> = combine(
        currentObservedLocalMangaId.flatMapLatest { mangaId ->
            if (mangaId == null) {
                flowOf(null)
            } else {
                flowOrFallback(null) { dataRepository.observeReadingStatus(mangaId) }
            }
        },
        history,
        linkedTrackingItems,
    ) { localStatus, history, linkedTrackingItems ->
        localStatus
            ?: linkedTrackingItems.firstOrNull { it.isPreferred }?.status
            ?: linkedTrackingItems.firstOrNull()?.status
            ?: when {
                history == null -> ScrobblingStatus.PLANNED
                org.skepsun.kototoro.list.domain.ReadingProgress.isCompleted(history.percent) -> ScrobblingStatus.COMPLETED
                else -> ScrobblingStatus.READING
            }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, ScrobblingStatus.PLANNED)

    private val unifiedRating: StateFlow<Float> = linkedTrackingItems
        .map { items ->
            items.firstOrNull { it.hasScrobblingBinding && it.isPreferred && it.rating != null }?.rating
                ?: items.firstOrNull { it.hasScrobblingBinding && it.rating != null }?.rating
                ?: 0f
        }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0f)

    private val canEditUnifiedRating: StateFlow<Boolean> = linkedTrackingItems
        .map { items ->
            items.any { linked ->
                linked.hasScrobblingBinding &&
                scrobblers.any { scrobbler ->
                    scrobbler.scrobblerService == linked.service && scrobbler.isEnabled
                }
            }
        }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    private val detailsHeaderUiState = combine(
        mangaDetails,
        favouriteCategories,
        historyInfo,
        trackingMatchSuggestion,
        linkedTrackingItems,
    ) { mangaDetails, favouriteCategories, historyInfo, trackingSuggestion, linkedTrackingItems ->
        DetailsHeaderUiState(
            mangaDetails = mangaDetails,
            favouriteCategories = favouriteCategories,
            historyInfo = historyInfo,
            trackingSuggestion = trackingSuggestion,
            linkedTrackingItems = linkedTrackingItems,
        )
    }.combine(unifiedRating) { header, unifiedRating ->
        header.copy(unifiedRating = unifiedRating)
    }.combine(canEditUnifiedRating) { header, canEditUnifiedRating ->
        header.copy(canEditUnifiedRating = canEditUnifiedRating)
    }.combine(readingStatus) { header, readingStatus ->
        DetailsHeaderUiState(
            mangaDetails = header.mangaDetails,
            favouriteCategories = header.favouriteCategories,
            historyInfo = header.historyInfo,
            trackingSuggestion = header.trackingSuggestion,
            linkedTrackingItems = header.linkedTrackingItems,
            readingStatus = readingStatus,
            unifiedRating = header.unifiedRating,
            canEditUnifiedRating = header.canEditUnifiedRating,
        )
    }
    private val detailsPaneSummaryUiState = combine(
        remoteContent,
        branches,
        isStatsAvailable,
        isLoading,
        activeLocalBrowserContent,
    ) { remoteContent, branches, isStatsAvailable, isLoading, activeLocalBrowserContent ->
        DetailsPaneSummaryUiState(
            remoteContent = remoteContent,
            branches = branches,
            isStatsAvailable = isStatsAvailable,
            isLoading = isLoading,
            activeLocalBrowserContent = activeLocalBrowserContent,
        )
    }
    val detailsPrimaryUiState: StateFlow<DetailsPrimaryUiState> = combine(
        detailsHeaderUiState,
        detailsPaneSummaryUiState,
        entityRelationSections,
        relatedContent,
        isWorkDetails,
    ) { header, pane, entityRelationSections, relatedContent, isWorkDetails ->
        DetailsPrimaryUiState(
            mangaDetails = header.mangaDetails,
            remoteContent = pane.remoteContent,
            relatedContent = relatedContent,
            favouriteCategories = header.favouriteCategories,
            historyInfo = header.historyInfo,
            branches = pane.branches,
            isStatsAvailable = pane.isStatsAvailable,
            trackingSuggestion = header.trackingSuggestion,
            linkedTrackingItems = header.linkedTrackingItems,
            readingStatus = header.readingStatus,
            unifiedRating = header.unifiedRating,
            canEditUnifiedRating = header.canEditUnifiedRating,
            isLoading = pane.isLoading,
            entityRelationSections = entityRelationSections,
            activeLocalBrowserContent = pane.activeLocalBrowserContent,
            isWorkDetails = isWorkDetails,
        )
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, DetailsPrimaryUiState())

    val selectedBranchValue: String?
        get() = selectedBranch.value

    init {
        if (initialProjectionIntentOverride?.mangaId?.takeIf { it != 0L } != null || intent.mangaId != 0L || intent.manga != null) {
            loadingJob = doLoad(force = false)
        }
        scrobblingInfo
            .onEach {
                refreshTrackingMatchSuggestion()
            }
            .launchIn(viewModelScope + Dispatchers.Default)
        launchJob(Dispatchers.Default + SkipErrors) {
            val manga = mangaDetails.firstOrNull { !it?.chapters.isNullOrEmpty() } ?: return@launchJob
            val h = history.firstOrNull()
            if (h != null) {
                progressUpdateUseCase(manga.toContent())
            }
        }
        launchJob(Dispatchers.Default) {
            val manga = mangaDetails.firstOrNull { it != null && it.isLocal } ?: return@launchJob
            remoteContent.value = interactor.findRemote(manga.toContent())
        }
        launchJob(Dispatchers.Default) {
            val content = resolveCurrentLocalContent() ?: return@launchJob
            if (!content.isLocal) {
                trackingMatchSuggestion.value = null
                return@launchJob
            }
            refreshTrackingMatchSuggestion()
        }
        launchJob(Dispatchers.Default) {
            mangaDetails.filterNotNull().collect { details ->
                val override = dataRepository.getOverride(details.id)
                isMarkedSafe.value = override?.contentRating == org.skepsun.kototoro.parsers.model.ContentRating.SAFE
            }
        }
        launchJob(Dispatchers.Default) {
            mangaDetails.filterNotNull().collect { details ->
                restorePersistedTranslation(details)
            }
        }
    }

    private suspend fun buildEntityRelationSections(): List<EntityRelationSection> {
        val trackingDetails = currentTrackingMetadataDetails() ?: return emptyList()
        return buildCurrentTrackingWorkRelationSections(trackingDetails)
    }

    private fun buildCurrentTrackingWorkRelationSections(
        details: TrackingSiteItemDetails,
    ): List<EntityRelationSection> {
        return buildList {
            details.staff
                .takeIf { it.isNotEmpty() }
                ?.let { staff ->
                    add(
                        EntityRelationSection(
                            titleRes = R.string.entity_graph_section_creators,
                            items = staff.map { person ->
                                val remoteId = person.id
                                EntityRelationItem(
                                    stableKey = "tracking:${details.service.id}:staff:${person.name}:${remoteId.orEmptyKey()}",
                                    name = person.name,
                                    type = EntityType.PERSON,
                                    coverUrl = person.avatarUrl.normalizedImageUrl(),
                                    subtitle = person.role?.takeIf { it.isNotBlank() },
                                    trackingService = details.service.takeIf { remoteId != null },
                                    remoteId = remoteId,
                                    url = person.url,
                                )
                            }.distinctBy(EntityRelationItem::stableKey),
                        ),
                    )
                }
            details.characters
                .takeIf { it.isNotEmpty() }
                ?.let { characters ->
                    add(
                        EntityRelationSection(
                            titleRes = R.string.entity_graph_section_characters,
                            items = characters.map { character ->
                                val remoteId = character.id.takeIf { it > 0 }
                                EntityRelationItem(
                                    stableKey = "tracking:${details.service.id}:character:${character.name}:${remoteId.orEmptyKey()}",
                                    name = character.name,
                                    type = EntityType.CHARACTER,
                                    coverUrl = character.coverUrl.normalizedImageUrl(),
                                    subtitle = character.role?.takeIf { it.isNotBlank() },
                                    supportingText = buildTrackingCharacterVoiceActorsText(character),
                                    detailLines = character.voiceActors
                                        .mapNotNull { it.name.takeIf(String::isNotBlank) }
                                        .distinct(),
                                    trackingService = details.service.takeIf { remoteId != null },
                                    remoteId = remoteId,
                                    url = character.url.takeIf { it.isNotBlank() },
                                )
                            }.distinctBy(EntityRelationItem::stableKey),
                        ),
                    )
                }
            details.relatedWorks
                .takeIf { it.isNotEmpty() }
                ?.let { works ->
                    add(
                        EntityRelationSection(
                            titleRes = R.string.details_related_works,
                            items = works.map { work ->
                                EntityRelationItem(
                                    stableKey = "tracking:${details.service.id}:work:${work.id}",
                                    name = work.title,
                                    type = EntityType.WORK,
                                    coverUrl = work.coverUrl.normalizedImageUrl(),
                                    subtitle = work.relationship,
                                    trackingService = details.service,
                                    remoteId = work.id,
                                    url = work.url,
                                )
                            }.distinctBy(EntityRelationItem::stableKey),
                        ),
                    )
                }
            details.extraSections.forEach { section ->
                if (section.items.isNotEmpty()) {
                    add(
                        EntityRelationSection(
                            title = section.title,
                            items = section.items.mapIndexed { index, item ->
                                EntityRelationItem(
                                    stableKey = "tracking:${details.service.id}:extra:${item.id}:$index",
                                    name = item.title,
                                    coverUrl = item.coverUrl.normalizedImageUrl(),
                                    type = EntityType.WORK,
                                    trackingService = details.service.takeIf { item.id > 0L },
                                    remoteId = item.id.takeIf { item.id > 0L },
                                    subtitle = item.relationship,
                                    url = item.url,
                                )
                            },
                        ),
                    )
                }
            }
        }
    }

    private fun Long?.orEmptyKey(): String {
        return this?.toString() ?: "none"
    }

    private fun parseStoredContentType(value: String?): ContentType? {
        return value?.let { raw -> runCatching { ContentType.valueOf(raw) }.getOrNull() }
    }

    private fun submitEntityRelationSections(sections: List<EntityRelationSection>) {
        pendingEntityRelationSections.tryEmit(sections)
    }

    fun selectMetadataSource(option: DetailsSourceOption) {
        when {
            option.trackingService != null && option.remoteId != null -> {
                val nextSelection = MetadataSourceSelection.Tracking(
                    service = option.trackingService,
                    remoteId = option.remoteId,
                    url = option.url,
                )
                if (selectedMetadataSource.value == nextSelection) {
                    return
                }
                selectedMetadataSource.value = nextSelection
                selectedMetadataSearchService.value = option.trackingService
                syncDisplayedState()
                launchJob(Dispatchers.IO) {
                    persistMetadataSourceSelectionForCurrentEntity()
                    ensureTrackingDetailsLoaded(
                        service = option.trackingService,
                        remoteId = option.remoteId,
                        url = option.url,
                    )
                    val nextQuery = cachedTrackingDetails[trackingMetadataKey(option.trackingService, option.remoteId)]
                        ?.title
                        ?.takeIf { it.isNotBlank() }
                        ?: option.title?.takeIf { title ->
                            title.isNotBlank() && title != contentTitleFallback(option.trackingService)
                        }
                        ?: currentDetailsTitle()
                    val cleanedNextQuery = cleanSourceSearchQuery(nextQuery)
                    if (cleanedNextQuery.isNotBlank()) {
                        metadataSearchQuery.value = cleanedNextQuery
                    }
                    searchMetadataBindings()
                }
            }
            option.source != null -> {
                if (selectedMetadataSource.value == MetadataSourceSelection.Base) {
                    return
                }
                selectedMetadataSource.value = MetadataSourceSelection.Base
                syncDisplayedState()
                launchJob(Dispatchers.IO) {
                    persistMetadataSourceSelectionForCurrentEntity()
                    refreshContextualEntityRelations()
                }
            }
        }
    }

    fun removeMetadataSourceBinding(option: DetailsSourceOption) {
        val service = option.trackingService ?: return
        val remoteId = option.remoteId ?: return
        launchJob(Dispatchers.IO) {
            val activeLocalMangaId = resolveCurrentMetadataPersistenceMangaId()
            if (activeLocalMangaId != null) {
                trackingSiteMatcher.removeMatch(service, activeLocalMangaId)
            }
            trackingMetadataCandidates.value = trackingMetadataCandidates.value.filterNot { candidate ->
                candidate.service == service && candidate.remoteId == remoteId
            }
            val selection = selectedMetadataSource.value
            if (
                selection is MetadataSourceSelection.Tracking &&
                selection.service == service &&
                selection.remoteId == remoteId
            ) {
                selectedMetadataSource.value = MetadataSourceSelection.Base
                persistMetadataSourceSelectionForCurrentEntity()
                refreshContextualEntityRelations()
            }
            updateSourceOptions()
            syncDisplayedState()
        }
    }

    fun setMetadataSearchService(service: ScrobblerService) {
        if (selectedMetadataSearchService.value == service) {
            return
        }
        selectedMetadataSearchService.value = service
    }

    fun updateMetadataSearchQuery(query: String) {
        metadataSearchQuery.value = query
    }

    fun searchMetadataBindings() {
        val services = metadataSearchServices.value
        if (services.isEmpty()) {
            metadataSearchResults.value = emptyList()
            metadataSearchSections.value = emptyList()
            metadataSearchLoading.value = false
            metadataSearchHasSearched.value = true
            metadataSearchError.value = null
            return
        }
        val query = cleanSourceSearchQuery(metadataSearchQuery.value).ifBlank { currentDetailsTitle() }
        if (query != metadataSearchQuery.value) {
            metadataSearchQuery.value = query
        }
        launchJob(Dispatchers.IO) {
            metadataSearchLoading.value = true
            metadataSearchHasSearched.value = false
            metadataSearchError.value = null
            metadataSearchSections.value = services.map { service ->
                MetadataSearchSectionUiState(service = service, isLoading = true)
            }
            supervisorScope {
                services.map { service ->
                    async {
                        val section = runCatchingCancellable {
                            withTimeout(SOURCE_SEARCH_TIMEOUT_MS) {
                                trackingSiteDiscoveryService.search(
                                    TrackingSiteCatalog(
                                        service = service,
                                        query = query.ifBlank { null },
                                        contentType = currentDetailsContentType(),
                                    ),
                                )
                            }
                        }.fold(
                            onSuccess = { items ->
                                MetadataSearchSectionUiState(
                                    service = service,
                                    items = items,
                                    isLoading = false,
                                )
                            },
                            onFailure = { throwable ->
                                MetadataSearchSectionUiState(
                                    service = service,
                                    isLoading = false,
                                    errorMessage = throwable.localizedMessage ?: throwable.javaClass.simpleName,
                                )
                            },
                        )
                        metadataSearchSections.update { sections ->
                            sections.map { existing ->
                                if (existing.service == service) section else existing
                            }
                        }
                    }
                }.awaitAll()
            }
            metadataSearchLoading.value = false
            metadataSearchHasSearched.value = true
            val finalSections = metadataSearchSections.value
            metadataSearchResults.value = finalSections.flatMap { it.items }
            metadataSearchError.value = finalSections.firstOrNull { it.errorMessage != null }?.errorMessage
        }
    }

    fun bindMetadataSource(item: TrackingSiteItem) {
        launchJob(Dispatchers.IO) {
            trackingMetadataCandidates.value = mergeTrackingMetadataCandidates(
                trackingMetadataCandidates.value + TrackingMetadataCandidate(
                    service = item.service,
                    remoteId = item.remoteId,
                    url = item.url,
                ),
            )
            selectedMetadataSource.value = MetadataSourceSelection.Tracking(
                service = item.service,
                remoteId = item.remoteId,
                url = item.url,
            )
            val details = runCatchingCancellable {
                trackingSiteDiscoveryService.getDetails(item.service, item.remoteId, item.url)
            }.getOrNull()
            if (details != null) {
                cacheTrackingDetails(details)
                trackingSiteCacheRepository.saveDetails(details)
            }
            val localMangaId = resolveCurrentLocalMangaId()
            if (localMangaId != null) {
                trackingSiteMatcher.confirmMatch(item.service, localMangaId, item.remoteId)
                persistMetadataSourceSelectionForCurrentEntity()
                dataRepository.setIgnoredTrackingSuggestion(localMangaId, null)
                        autoLinkTrackingServiceIfAuthorized(
                            mangaId = localMangaId,
                            item = item,
                            contentType = details?.contentType ?: resolvedMetadataContentType.value,
                        )
                    }
            syncDisplayedState()
        }
    }

    fun setReadingSearchSource(sourceName: String?) {
        if (selectedReadingSearchSource.value == sourceName) {
            return
        }
        selectedReadingSearchSource.value = sourceName
        readingSearchSections.value = emptyList()
        readingSearchLoading.value = false
        readingSearchHasSearched.value = false
        readingSearchState.value = null
        if (sourceName == null) {
            readingSearchFilterState.value = ReadingSearchFilterState()
        } else {
            loadReadingSearchFilters(sourceName)
        }
    }

    fun updateReadingSearchQuery(query: String) {
        readingSearchQuery.value = query
    }

    fun toggleReadingSearchSourceType(type: SourceType) {
        readingSearchScopeFilters.update { current ->
            val updated = current.sourceTypes.toMutableSet().apply {
                if (!add(type)) {
                    remove(type)
                }
            }.ifEmpty { ALL_SOURCE_TYPES }
            current.copy(sourceTypes = updated)
        }
    }

    fun toggleReadingSearchContentKind(kind: SearchContentKind) {
        readingSearchScopeFilters.update { current ->
            val updated = current.contentKinds.toMutableSet().apply {
                if (!add(kind)) {
                    remove(kind)
                }
            }.ifEmpty { ALL_SEARCH_CONTENT_KINDS }
            current.copy(contentKinds = updated)
        }
    }

    fun setReadingSearchPinnedOnly(enabled: Boolean) {
        readingSearchScopeFilters.update { it.copy(pinnedOnly = enabled) }
    }

    fun setReadingSearchHideEmpty(enabled: Boolean) {
        readingSearchScopeFilters.update { it.copy(hideEmpty = enabled) }
    }

    fun setActiveLanguagePreset(presetId: Long) {
        if (settings.activeSourcePresetId != presetId) {
            settings.activeSourcePresetId = presetId
        }
    }

    fun setReadingSearchSortOrder(sortOrder: SortOrder) {
        readingSearchFilterState.update { state ->
            if (state.source == null) return@update state
            state.copy(selectedSortOrder = sortOrder)
        }
    }

    fun setReadingSearchAuthor(author: String?) {
        readingSearchFilterState.update { state ->
            if (state.source == null) return@update state
            state.copy(
                listFilter = state.listFilter.copy(author = author?.trim()?.takeIf { it.isNotEmpty() }),
            )
        }
    }

    fun setReadingSearchLocale(locale: Locale?) {
        readingSearchFilterState.update { state ->
            if (state.source == null) return@update state
            state.copy(listFilter = state.listFilter.copy(locale = locale))
        }
    }

    fun toggleReadingSearchState(value: ContentState, isSelected: Boolean) {
        readingSearchFilterState.update { state ->
            if (state.source == null) return@update state
            state.copy(
                listFilter = state.listFilter.copy(
                    states = if (isSelected) state.listFilter.states + value else state.listFilter.states - value,
                ),
            )
        }
    }

    fun toggleReadingSearchContentType(value: ContentType, isSelected: Boolean) {
        readingSearchFilterState.update { state ->
            if (state.source == null) return@update state
            state.copy(
                listFilter = state.listFilter.copy(
                    types = if (isSelected) state.listFilter.types + value else state.listFilter.types - value,
                ),
            )
        }
    }

    fun toggleReadingSearchTag(value: org.skepsun.kototoro.parsers.model.ContentTag, isSelected: Boolean, excludeMode: Boolean) {
        readingSearchFilterState.update { state ->
            if (state.source == null) return@update state
            val tagGroup = state.filterOptions.effectiveTagGroups.firstOrNull { value in it.tags }
            if (excludeMode) {
                val newTagsExclude = when {
                    tagGroup?.isExclusive == true -> {
                        val tagsWithoutGroup = state.listFilter.tagsExclude - tagGroup.tags
                        if (isSelected) tagsWithoutGroup + value else state.listFilter.tagsExclude - value
                    }
                    state.capabilities.isMultipleTagsSupported -> {
                        if (isSelected) state.listFilter.tagsExclude + value else state.listFilter.tagsExclude - value
                    }
                    else -> {
                        if (isSelected) setOf(value) else emptySet()
                    }
                }
                state.copy(
                    listFilter = state.listFilter.copy(
                        tags = state.listFilter.tags - newTagsExclude,
                        tagsExclude = newTagsExclude,
                    ),
                )
            } else {
                val newTags = when {
                    tagGroup?.isExclusive == true -> {
                        val tagsWithoutGroup = state.listFilter.tags - tagGroup.tags
                        if (isSelected) tagsWithoutGroup + value else state.listFilter.tags - value
                    }
                    state.capabilities.isMultipleTagsSupported -> {
                        if (isSelected) state.listFilter.tags + value else state.listFilter.tags - value
                    }
                    else -> {
                        if (isSelected) setOf(value) else emptySet()
                    }
                }
                state.copy(
                    listFilter = state.listFilter.copy(
                        tags = newTags,
                        tagsExclude = state.listFilter.tagsExclude - newTags,
                    ),
                )
            }
        }
    }

    fun resetReadingSearchFilters() {
        readingSearchFilterState.update { state ->
            if (state.source == null) return@update state
            state.copy(
                selectedSortOrder = state.defaultReadingSearchSortOrder(),
                listFilter = ContentListFilter.EMPTY,
                errorMessage = null,
            )
        }
    }

    fun isReadingSearchTextInputTag(tag: org.skepsun.kototoro.parsers.model.ContentTag): Boolean {
        return tag.key.startsWith("text:")
    }

    fun getReadingSearchTextInputLabel(tag: org.skepsun.kototoro.parsers.model.ContentTag): String {
        return tag.title.removePrefix("📝 ")
    }

    fun getReadingSearchTextInputValue(tag: org.skepsun.kototoro.parsers.model.ContentTag): String? {
        val baseKey = tag.key
        return readingSearchFilterState.value.listFilter.tags
            .find { it.key.startsWith(baseKey) && it.key.contains("=") }
            ?.key
            ?.substringAfter("=")
    }

    fun setReadingSearchTextInputValue(originalTag: org.skepsun.kototoro.parsers.model.ContentTag, value: String) {
        readingSearchFilterState.update { state ->
            if (state.source == null) return@update state
            val baseKey = originalTag.key
            val filteredTags = state.listFilter.tags.filter { !it.key.startsWith(baseKey) }.toSet()
            val newTags = if (value.isNotBlank()) {
                val tagWithValue = org.skepsun.kototoro.parsers.model.ContentTag(
                    title = "${originalTag.title.removePrefix("📝 ")}: $value",
                    key = "$baseKey=$value",
                    source = originalTag.source,
                )
                filteredTags + tagWithValue
            } else {
                filteredTags
            }
            state.copy(listFilter = state.listFilter.copy(tags = newTags))
        }
    }

    private fun List<Content>.withCurrentReadingSourceResult(
        currentContent: Content?,
        sourceInfo: ContentSourceInfo,
        query: String,
    ): List<Content> {
        if (currentContent == null || currentContent.source.name != sourceInfo.mangaSource.name) {
            return this
        }
        val normalizedQuery = query.trim()
        val matchesQuery = normalizedQuery.isBlank() ||
            currentContent.title.contains(normalizedQuery, ignoreCase = true) ||
            normalizedQuery.contains(currentContent.title, ignoreCase = true)
        if (!matchesQuery || any { it.id == currentContent.id || it.url == currentContent.url }) {
            return this
        }
        return listOf(currentContent) + this
    }

    private fun org.skepsun.kototoro.core.parser.ContentRepository.resolveReadingSearchSortOrder(): SortOrder {
        return when {
            SortOrder.RELEVANCE in sortOrders -> SortOrder.RELEVANCE
            SortOrder.POPULARITY in sortOrders -> SortOrder.POPULARITY
            SortOrder.ALPHABETICAL in sortOrders -> SortOrder.ALPHABETICAL
            else -> defaultSortOrder
        }
    }

    private fun replaceReadingSearchSection(
        sourceIndex: Int,
        section: ReadingSearchSectionUiState,
    ) {
        readingSearchSections.update { sections ->
            if (sourceIndex !in sections.indices) {
                return@update sections
            }
            sections.toMutableList().also { updated ->
                updated[sourceIndex] = section
            }
        }
    }

    fun searchReadingBindings() {
        readingSearchJob?.cancel()
        val generation = ++readingSearchGeneration
        val scopeFilter = readingSearchScopeFilters.value
        val availableSources = readingSearchSources.value
        // If allEnabledSourceInfos hasn't been populated yet (race condition during init),
        // defer the search until the full source list becomes available.
        if (!allEnabledSourcesLoaded) {
            Log.d(READING_SEARCH_LOG_TAG, "defer search: allEnabledSourceInfos not yet loaded, sources=${availableSources.size}")
            readingSearchLoading.value = true
            readingSearchHasSearched.value = false
            readingSearchState.value = LocalSearchState.Loading
            launchJob(Dispatchers.Default) {
                // Wait up to 3s for the source list to load, then proceed anyway
                kotlinx.coroutines.withTimeoutOrNull(3000L) {
                    while (!allEnabledSourcesLoaded && generation == readingSearchGeneration) {
                        kotlinx.coroutines.delay(100)
                    }
                }
                if (generation != readingSearchGeneration) return@launchJob
                searchReadingBindings()
            }
            return
        }
        val sources = availableSources.filter { sourceInfo ->
            val source = sourceInfo.mangaSource
            val sourceType = sourceTypeIdentifier.getSourceType(source.name)
            sourceType in scopeFilter.sourceTypes &&
                scopeFilter.contentKinds.any { kind -> kind.matches(source) } &&
                (!scopeFilter.pinnedOnly || sourceInfo.isPinned)
        }
        if (sources.isEmpty()) {
            readingSearchSections.value = emptyList()
            readingSearchLoading.value = false
            readingSearchHasSearched.value = true
            readingSearchState.value = LocalSearchState.Loaded(emptyList())
            Log.d(READING_SEARCH_LOG_TAG, "skip search: no sources after scope filter")
            return
        }
        val query = cleanSourceSearchQuery(readingSearchQuery.value).ifBlank { currentReadingSearchTitle() }
        if (query != readingSearchQuery.value) {
            readingSearchQuery.value = query
        }
        val currentContent = (baseLoadedDetails?.toContent() ?: mangaDetails.value?.toContent() ?: originContent)
            ?.takeUnless { it.source.name.startsWith("TRACKING_") }
        readingSearchJob = launchJob(Dispatchers.IO) {
            val searchStartedAt = SystemClock.elapsedRealtime()
            Log.d(
                READING_SEARCH_LOG_TAG,
                "start query=${query.take(80)} sources=${sources.size} parallelism=$READING_SEARCH_MAX_PARALLELISM " +
                    "timeoutMs=$SOURCE_SEARCH_TIMEOUT_MS hideEmpty=${scopeFilter.hideEmpty}",
            )
            readingSearchLoading.value = true
            readingSearchHasSearched.value = false
            readingSearchState.value = LocalSearchState.Loading
            readingSearchSections.value = sources.map { sourceInfo ->
                ReadingSearchSectionUiState(source = sourceInfo, isPending = true)
            }
            val semaphore = Semaphore(READING_SEARCH_MAX_PARALLELISM)
            supervisorScope {
                sources.mapIndexed { sourceIndex, sourceInfo ->
                    async {
                        semaphore.withPermit {
                            if (generation != readingSearchGeneration) {
                                return@withPermit
                            }
                            val section = try {
                                val items = withTimeout(SOURCE_SEARCH_TIMEOUT_MS) {
                                    val sourceStartedAt = SystemClock.elapsedRealtime()
                                    val repository = mangaRepositoryFactory.create(sourceInfo.mangaSource)
                                    replaceReadingSearchSection(
                                        sourceIndex,
                                        ReadingSearchSectionUiState(source = sourceInfo, isLoading = true),
                                    )
                                    Log.d(
                                        READING_SEARCH_LOG_TAG,
                                        "source start index=$sourceIndex source=${sourceInfo.mangaSource.name} " +
                                            "repo=${repository.javaClass.simpleName}",
                                    )
                                    if (!repository.filterCapabilities.isSearchSupported) {
                                        Log.d(
                                            READING_SEARCH_LOG_TAG,
                                            "source unsupported index=$sourceIndex source=${sourceInfo.mangaSource.name} " +
                                                "elapsedMs=${SystemClock.elapsedRealtime() - sourceStartedAt}",
                                        )
                                        return@withTimeout emptyList()
                                    }
                                    val listStartedAt = SystemClock.elapsedRealtime()
                                    val list = repository.getList(
                                        offset = 0,
                                        order = repository.resolveReadingSearchSortOrder(),
                                        filter = ContentListFilter(query = query),
                                    ).take(20)
                                    Log.d(
                                        READING_SEARCH_LOG_TAG,
                                        "source list index=$sourceIndex source=${sourceInfo.mangaSource.name} " +
                                            "count=${list.size} elapsedMs=${SystemClock.elapsedRealtime() - listStartedAt}",
                                    )
                                    val detailsStartedAt = SystemClock.elapsedRealtime()
                                    val detailed = list.map { content ->
                                        runCatchingCancellable {
                                            repository.getDetails(content)
                                        }.getOrDefault(content)
                                    }
                                    Log.d(
                                        READING_SEARCH_LOG_TAG,
                                        "source details index=$sourceIndex source=${sourceInfo.mangaSource.name} " +
                                            "count=${detailed.size} elapsedMs=${SystemClock.elapsedRealtime() - detailsStartedAt} " +
                                            "totalMs=${SystemClock.elapsedRealtime() - sourceStartedAt}",
                                    )
                                    detailed
                                }
                                Log.d(
                                    READING_SEARCH_LOG_TAG,
                                    "source success index=$sourceIndex source=${sourceInfo.mangaSource.name} " +
                                        "items=${items.size}",
                                )
                                ReadingSearchSectionUiState(
                                    source = sourceInfo,
                                    items = items.withCurrentReadingSourceResult(currentContent, sourceInfo, query),
                                    isLoading = false,
                                )
                            } catch (throwable: TimeoutCancellationException) {
                                Log.w(
                                    READING_SEARCH_LOG_TAG,
                                    "source failed index=$sourceIndex source=${sourceInfo.mangaSource.name} " +
                                        "timeout=true error=${throwable.javaClass.name}:${throwable.message}",
                                    throwable,
                                )
                                ReadingSearchSectionUiState(
                                    source = sourceInfo,
                                    isLoading = false,
                                    errorMessage = throwable.localizedMessage ?: throwable.javaClass.simpleName,
                                )
                            } catch (throwable: Throwable) {
                                if (throwable is CancellationException) {
                                    throw throwable
                                }
                                Log.w(
                                    READING_SEARCH_LOG_TAG,
                                    "source failed index=$sourceIndex source=${sourceInfo.mangaSource.name} " +
                                        "timeout=false error=${throwable.javaClass.name}:${throwable.message}",
                                    throwable,
                                )
                                ReadingSearchSectionUiState(
                                    source = sourceInfo,
                                    isLoading = false,
                                    errorMessage = throwable.localizedMessage ?: throwable.javaClass.simpleName,
                                )
                            }
                            if (generation != readingSearchGeneration) {
                                return@withPermit
                            }
                            replaceReadingSearchSection(sourceIndex, section)
                        }
                    }
                }.awaitAll()
            }
            if (generation != readingSearchGeneration) {
                return@launchJob
            }
            readingSearchLoading.value = false
            readingSearchHasSearched.value = true
            val finalSections = if (scopeFilter.hideEmpty) {
                readingSearchSections.value.filter { it.items.isNotEmpty() }
            } else {
                readingSearchSections.value
            }
            readingSearchSections.value = finalSections
            readingSearchState.value = LocalSearchState.Loaded(finalSections.flatMap { it.items })
            Log.d(
                READING_SEARCH_LOG_TAG,
                "finish sources=${sources.size} visibleSections=${finalSections.size} " +
                    "items=${finalSections.sumOf { it.items.size }} elapsedMs=${SystemClock.elapsedRealtime() - searchStartedAt}",
            )
        }
    }

    private fun defaultReadingSearchContentKinds(): Set<SearchContentKind> {
        return when (currentDetailsContentType()) {
            ContentType.VIDEO, ContentType.HENTAI_VIDEO -> setOf(SearchContentKind.VIDEO)
            ContentType.NOVEL, ContentType.HENTAI_NOVEL -> setOf(SearchContentKind.NOVEL)
            null -> ALL_SEARCH_CONTENT_KINDS
            else -> setOf(SearchContentKind.MANGA)
        }
    }

    private fun loadReadingSearchFilters(sourceName: String) {
        val sourceInfo = readingSearchSources.value.firstOrNull { it.mangaSource.name == sourceName }
        if (sourceInfo == null) {
            readingSearchFilterState.value = ReadingSearchFilterState()
            return
        }
        readingSearchFilterState.value = ReadingSearchFilterState(
            source = sourceInfo,
            isLoading = true,
        )
        launchJob(Dispatchers.IO) {
            val repository = mangaRepositoryFactory.create(sourceInfo.mangaSource)
            val sortOrders = repository.sortOrders.toList().sortedBy { it.ordinal }
            val defaultSortOrder = repository.resolveReadingSearchSortOrder()
            val optionsResult = runCatchingCancellable {
                repository.getFilterOptions()
            }
            if (selectedReadingSearchSource.value != sourceName) {
                return@launchJob
            }
            readingSearchFilterState.value = optionsResult.fold(
                onSuccess = { options ->
                    ReadingSearchFilterState(
                        source = sourceInfo,
                        capabilities = repository.filterCapabilities,
                        filterOptions = options,
                        sortOrders = sortOrders,
                        selectedSortOrder = defaultSortOrder,
                    )
                },
                onFailure = { error ->
                    ReadingSearchFilterState(
                        source = sourceInfo,
                        capabilities = repository.filterCapabilities,
                        sortOrders = sortOrders,
                        selectedSortOrder = defaultSortOrder,
                        errorMessage = error.localizedMessage ?: error.javaClass.simpleName,
                    )
                },
            )
        }
    }

    private fun ReadingSearchFilterState.defaultReadingSearchSortOrder(): SortOrder? {
        return sortOrders.firstOrNull { it == SortOrder.RELEVANCE } ?: selectedSortOrder ?: sortOrders.firstOrNull()
    }

    private fun ReadingSearchFilterState.toUiState(): ReadingSearchFilterUiState {
        val sortedLocales = filterOptions.availableLocales
            .sortedBy { it.getDisplayName(it).ifBlank { it.toLanguageTag() } }
        return ReadingSearchFilterUiState(
            hasSelectedSource = source != null,
            isLoading = isLoading,
            errorMessage = errorMessage,
            sortOrders = sortOrders,
            selectedSortOrder = selectedSortOrder,
            tagGroups = filterOptions.effectiveTagGroups.map { group ->
                UiTagGroup(
                    title = group.title,
                    tags = group.tags,
                    selected = group.tags.intersect(listFilter.tags),
                    isExclusive = group.isExclusive,
                )
            },
            excludedTagGroups = filterOptions.effectiveTagGroups.map { group ->
                UiTagGroup(
                    title = group.title,
                    tags = group.tags,
                    selected = group.tags.intersect(listFilter.tagsExclude),
                    isExclusive = group.isExclusive,
                )
            },
            contentTypes = filterOptions.availableContentTypes.toList().sortedBy { it.ordinal },
            selectedContentTypes = listFilter.types,
            states = filterOptions.availableStates.toList().sortedBy { it.ordinal },
            selectedStates = listFilter.states,
            locales = if (sortedLocales.isNotEmpty()) listOf(null) + sortedLocales else emptyList(),
            selectedLocale = listFilter.locale,
            author = listFilter.author,
            canSearchByAuthor = capabilities.isAuthorSearchSupported,
            supportsTagExclusion = capabilities.isTagsExclusionSupported,
            appliedFilterCount = listFilter.appliedFilterCount(),
        )
    }

    private fun ContentListFilter.appliedFilterCount(): Int {
        var count = 0
        count += tags.size
        count += tagsExclude.size
        count += states.size
        count += types.size
        if (locale != null) count++
        if (!author.isNullOrBlank()) count++
        return count
    }

    private fun org.skepsun.kototoro.core.parser.ContentRepository.buildReadingSearchFilter(
        query: String,
        baseFilter: ContentListFilter,
    ): ContentListFilter {
        var filter = baseFilter.copy(query = query.takeIf { it.isNotBlank() })
        if (!filter.author.isNullOrBlank() && !filterCapabilities.isAuthorSearchSupported) {
            filter = filter.copy(author = null)
        }
        if (!filter.query.isNullOrBlank() && filter.hasNonSearchOptions() && !filterCapabilities.isSearchWithFiltersSupported) {
            filter = filter.copy(query = null)
        }
        if (!filter.query.isNullOrBlank() && !filterCapabilities.isSearchSupported) {
            filter = filter.copy(query = null)
        }
        return filter
    }

    /**
     * Switches the reading source to [content] from another source.
     *
     * With a local manga on screen this is a migration: favourites, history (mapped to the
     * new source's chapters), preferences, tracking and scrobbling move to the new manga row,
     * as in the alternatives screen. A details page opened from a tracking item has no local
     * manga yet, so the candidate simply becomes its first reading source.
     */
    fun switchReadingSource(content: Content, onComplete: (() -> Unit)? = null) {
        val selection = selectedMetadataSource.value as? MetadataSourceSelection.Tracking
        launchLoadingJob(Dispatchers.IO) {
            val currentContent = resolveCurrentLocalContent()
            val targetContent = dataRepository.storeContentAndReturn(content, replaceExisting = false)
            if (currentContent != null && currentContent.id != targetContent.id) {
                migrateUseCase(currentContent, targetContent)
            }
            activeMangaIdFlow.value = targetContent.id
            currentLoadIntentOverride = ContentIntent.of(targetContent.id)
            loadingJob.cancel()
            loadingJob = doLoad(force = true)
            if (selection != null) {
                runCatchingCancellable {
                    trackingSiteMatcher.confirmMatch(selection.service, targetContent.id, selection.remoteId)
                }
                runCatchingCancellable {
                    persistMetadataSourceSelectionForCurrentEntity()
                }
            }
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    private suspend fun resolveCurrentLocalMangaId(): Long? {
        currentWorkProjectionSnapshot().activeLocalMangaId?.let { return it }
        val currentContent = manga.filterNotNull().firstOrNull()
        if (currentContent?.isLocal == true) {
            return currentContent.id
        }
        return null
    }

    private fun preferredFallbackTrackingMangaIds(): List<Long> {
        val projectionSnapshot = currentWorkProjectionSnapshot()
        return buildList {
            projectionSnapshot.currentReadingProjectionMangaId?.let(::add)
            projectionSnapshot.activeLocalMangaId?.let(::add)
            baseLoadedDetails?.local?.manga?.id?.let(::add)
        }.distinct()
    }

    private suspend fun resolveCurrentLocalContent(): Content? {
        val localMangaId = resolveCurrentLocalMangaId() ?: return null
        return dataRepository.findPreferredLocalContentById(localMangaId, withChapters = false)
            ?: dataRepository.findContentById(localMangaId, withChapters = false)
            ?: manga.filterNotNull().firstOrNull { it.id == localMangaId }
    }

    fun reload() {
        loadingJob.cancel()
        loadingJob = doLoad(force = true)
    }

    fun refreshSourceBindings() {
        sourceBindingsRefreshJob?.cancel()
        sourceBindingsRefreshJob = launchJob(Dispatchers.IO) {
            updateSourceOptions()
            refreshResolvedPresentationState()
        }
    }

    fun updateScrobbling(scrobblerServiceId: Int, rating: Float, status: ScrobblingStatus?) {
        val scrobbler = getScrobbler(scrobblerServiceId) ?: return
        launchJob(Dispatchers.Default) {
            val currentMangaId = resolveCurrentLocalMangaId() ?: return@launchJob
            scrobbler.updateScrobblingInfo(
                mangaId = currentMangaId,
                rating = rating,
                status = status,
                comment = null,
            )
        }
    }

    fun updateUnifiedReadingStatus(status: ScrobblingStatus) {
        launchJob(Dispatchers.Default) {
            val currentMangaId = ensureCurrentWorkProjection() ?: return@launchJob
            dataRepository.setReadingStatus(currentMangaId, status)
            linkedTrackingItems.value.forEach { linked ->
                if (!linked.hasScrobblingBinding) return@forEach
                val scrobbler = scrobblers.firstOrNull {
                    it.scrobblerService == linked.service && it.isEnabled
                } ?: return@forEach
                scrobbler.updateScrobblingInfo(
                    mangaId = currentMangaId,
                    rating = linked.rating ?: 0f,
                    status = status,
                    comment = null,
                )
            }
        }
    }

    fun updateUnifiedRating(rating: Float) {
        launchJob(Dispatchers.Default) {
            val currentMangaId = resolveCurrentLocalMangaId() ?: return@launchJob
            linkedTrackingItems.value.forEach { linked ->
                if (!linked.hasScrobblingBinding) return@forEach
                val scrobbler = scrobblers.firstOrNull {
                    it.scrobblerService == linked.service && it.isEnabled
                } ?: return@forEach
                scrobbler.updateScrobblingInfo(
                    mangaId = currentMangaId,
                    rating = rating.coerceIn(0f, 1f),
                    status = linked.status,
                    comment = null,
                )
            }
        }
    }

    fun unregisterScrobbling(scrobblerServiceId: Int) {
        val scrobbler = getScrobbler(scrobblerServiceId) ?: return
        launchJob(Dispatchers.Default) {
            val currentMangaId = resolveCurrentLocalMangaId() ?: return@launchJob
            scrobbler.unregisterScrobbling(
                mangaId = currentMangaId,
            )
        }
    }

    fun bindTrackingMatch(match: TrackingSiteMatchResult) {
        launchJob(Dispatchers.Default) {
            val content = resolveCurrentLocalContent() ?: return@launchJob
            trackingSiteMatcher.confirmMatch(match.service, content.id, match.remoteId)
            dataRepository.setIgnoredTrackingSuggestion(content.id, null)
                autoLinkTrackingServiceIfAuthorized(
                    mangaId = content.id,
                    item = org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItem(
                        service = match.service,
                        remoteId = match.remoteId,
                        title = match.title,
                        url = match.url,
                    ),
                    contentType = match.contentType ?: content.source.contentType,
                )
                refreshTrackingMatchSuggestion()
            }
        }

    fun ignoreTrackingSuggestion(match: TrackingSiteMatchResult) {
        launchJob(Dispatchers.Default) {
            val content = resolveCurrentLocalContent() ?: return@launchJob
            dataRepository.setIgnoredTrackingSuggestion(
                mangaId = content.id,
                suggestion = org.skepsun.kototoro.core.parser.ContentDataRepository.IgnoredTrackingSuggestion(
                    serviceId = match.service.id,
                    remoteId = match.remoteId,
                ),
            )
            trackingMatchSuggestion.value = null
        }
    }

    fun removeTrackingMatch(match: TrackingSiteMatchResult) {
        launchJob(Dispatchers.Default) {
            val content = resolveCurrentLocalContent() ?: return@launchJob
            trackingSiteMatcher.removeMatch(match.service, content.id)
            refreshTrackingMatchSuggestion()
        }
    }

    fun removeFromHistory() {
        launchJob(Dispatchers.Default) {
            val currentMangaId = resolveCurrentLocalMangaId() ?: return@launchJob
            val handle = historyRepository.delete(setOf(currentMangaId))
            onActionDone.call(ReversibleAction(R.string.removed_from_history, handle))
        }
    }

    // --- Favorite Category Management (for Compose dialog) ---

    val allCategories: StateFlow<List<FavouriteCategory>> = favouritesRepository.observeCategories()
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

    fun setFavouriteCategory(categoryId: Long, isChecked: Boolean) {
        launchJob(Dispatchers.Default) {
            val content = getContentOrNull() ?: return@launchJob
            if (isChecked) {
                favouritesRepository.addToCategory(categoryId, listOf(content))
            } else {
                favouritesRepository.removeFromCategory(categoryId, listOf(content.id))
            }
        }
    }

    private suspend fun ensureCurrentWorkProjection(): Long? {
        resolveCurrentLocalMangaId()?.let { return it }
        val content = getContentOrNull() ?: return null
        val storedContent = dataRepository.storeContentAndReturn(content, replaceExisting = false)
        activeMangaIdFlow.value = storedContent.id
        return storedContent.id
    }

    fun toggleMarkSafe() {
        launchJob(Dispatchers.Default) {
                val manga = baseLoadedDetails?.toContent()
                    ?: mangaDetails.value?.local?.manga
                    ?: mangaDetails.value?.toContent()?.takeUnless {
                        selectedMetadataSource.value is MetadataSourceSelection.Tracking && currentObservedLocalMangaIdSnapshot() == null
                    }
                    ?: return@launchJob
            val override = dataRepository.getOverride(manga.id) ?: org.skepsun.kototoro.core.ui.model.ContentOverride(null, null, null)

            val isCurrentlyNsfw = manga.isNsfw()
            val newRating = if (isCurrentlyNsfw) {
                org.skepsun.kototoro.parsers.model.ContentRating.SAFE
            } else {
                org.skepsun.kototoro.parsers.model.ContentRating.ADULT
            }

            dataRepository.setOverride(manga, override.copy(contentRating = newRating))
            doLoad(false)
        }
    }

    private fun doLoad(force: Boolean) = launchLoadingJob(Dispatchers.Default) {
        val resolvedIntent = currentLoadIntentOverride ?: intent
        val requestedMangaId = resolvedIntent.mangaId.takeIf { it != ContentIntent.ID_NONE }
        Log.i(
            DETAILS_TRACE_TAG,
            "load.start force=$force intentId=${resolvedIntent.mangaId} requestedMangaId=$requestedMangaId " +
                "activeMangaId=${activeMangaIdFlow.value} currentOverride=${currentLoadIntentOverride?.mangaId}",
        )
        if (resolvedIntent.mangaId == 0L && resolvedIntent.manga == null) {
            Log.w(DETAILS_TRACE_TAG, "load.skip reason=emptyIntent")
            return@launchLoadingJob
        }
        try {
            detailsLoadUseCase.invoke(resolvedIntent, force)
            .onEachWhile {
                if (it.allChapters.isNotEmpty()) {
                    val manga = it.toContent()
                    // find default branch
                    val hist = historyRepository.getOne(manga)
                    selectedBranch.value = it.allChapters.findChapterByHistory(hist)?.branch
                        ?: manga.getPreferredBranch(hist)
                    true
                } else {
                    false
                }
            }.collect { details ->
                if (requestedMangaId != null && activeMangaIdFlow.value != requestedMangaId) {
                    Log.w(
                        DETAILS_TRACE_TAG,
                        "load.drop reason=staleRequest requestedMangaId=$requestedMangaId activeMangaId=${activeMangaIdFlow.value} details=${details.toContent().detailsTraceSummary()}",
                    )
                    return@collect
                }
                    Log.i(
                        DETAILS_TRACE_TAG,
                        "load.emit details=${details.toContent().detailsTraceSummary()} isLoaded=${details.isLoaded} " +
                            "selectedBranchBefore=${selectedBranch.value}",
                )
                // For EPUB sources, DetailsLoadUseCase already handles chapter expansion
                // We just need to reset selectedBranch to null for EPUB chapters
                val finalDetails = if (localEpubSource.hasEpubFile(details.id)) {
                    android.util.Log.d("DetailsViewModel", "EPUB file detected for manga ${details.id}")
                    android.util.Log.d("DetailsViewModel", "Using chapters from DetailsLoadUseCase (${details.allChapters.size} chapters)")

                    // IMPORTANT: Reset selectedBranch to null for EPUB
                    // EPUB chapters all have branch=null, so we need to reset selectedBranch
                    // to avoid branch mismatch (e.g., selectedBranch="中日对照" but EPUB branch=null)
                    selectedBranch.value = null
                    android.util.Log.d("DetailsViewModel", "Reset selectedBranch to null for EPUB")

                    // Use the details as-is, which already contains expanded EPUB chapters
                    // from DetailsLoadUseCase (including both internal chapters and download links)
                    details
                } else {
                    details
                }
                Log.i(
                    DETAILS_TRACE_TAG,
                    "load.apply details=${finalDetails.toContent().detailsTraceSummary()} selectedBranchAfter=${selectedBranch.value}",
                )
                baseLoadedDetails = finalDetails
                activeProjectionStoredContentType = db.getMangaDao().find(finalDetails.id)?.manga?.contentType?.let(::parseStoredContentType)
                syncDisplayedState()
                trackingRepository.clearReadUpdates(finalDetails.id)
                if (!isTrackingOriginSelectionPinned()) {
                    restorePersistedMetadataSourceSelection(finalDetails.id)
                }
            }
        } catch (error: Throwable) {
            if (error !is CancellationException) {
                Log.e(DETAILS_TRACE_TAG, "load.failed force=$force requestedMangaId=$requestedMangaId", error)
            }
            throw error
        }
    }

    private fun contentTitleFallback(service: org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService): String {
        return service.name
    }

    private fun refreshTrackingMatchSuggestion() {
        launchJob(Dispatchers.Default) {
            val content = resolveCurrentLocalContent() ?: return@launchJob
            if (!content.isLocal) {
                trackingMatchSuggestion.value = null
                return@launchJob
            }
            if (selectedMetadataSource.value is MetadataSourceSelection.Tracking || linkedTrackingItems.value.isNotEmpty()) {
                trackingMatchSuggestion.value = null
                return@launchJob
            }
            val ignored = dataRepository.getIgnoredTrackingSuggestion(content.id)
            val suggestions = findTrackingSuggestions(content)
            val best = selectTrackingSuggestion(
                candidates = suggestions,
                preferredService = settings.preferredTrackingSite,
                ignored = ignored,
            )
            trackingMatchSuggestion.value = best
        }
    }

    private suspend fun findTrackingSuggestions(content: Content): List<TrackingSiteMatchResult> {
        val services = candidateTrackingSuggestionServices()
        if (services.isEmpty()) {
            return emptyList()
        }
        return services.flatMap { service ->
            runCatchingCancellable {
                withTimeout(8_000) {
                    trackingSiteMatcher.matchLocalContent(
                        service = service,
                        content = content,
                        limit = TRACKING_SUGGESTION_RESULT_LIMIT,
                        persistAutoMatch = false,
                    )
                }
            }.getOrElse { emptyList() }
        }.distinctBy { "${it.service.id}:${it.remoteId}" }
    }

    private fun candidateTrackingSuggestionServices(): List<ScrobblerService> {
        val searchable = metadataSearchServices.value.ifEmpty {
            ScrobblerService.entries.filter { service ->
                trackingSiteDiscoveryService.getCapabilities(service).supportsSearch
            }
        }
        val preferred = settings.preferredTrackingSite
        return buildList {
            searchable.firstOrNull { it == preferred }?.let(::add)
            addAll(searchable.filterNot { it == preferred })
        }
    }

    private fun selectTrackingSuggestion(
        candidates: List<TrackingSiteMatchResult>,
        preferredService: ScrobblerService,
        ignored: org.skepsun.kototoro.core.parser.ContentDataRepository.IgnoredTrackingSuggestion?,
    ): TrackingSiteMatchResult? {
        val filtered = candidates
            .asSequence()
            .filterNot(TrackingSiteMatchResult::isLinked)
            .filter { it.confidence >= TRACKING_SUGGESTION_THRESHOLD }
            .filterNot { candidate ->
                ignored?.serviceId == candidate.service.id && ignored.remoteId == candidate.remoteId
            }
            .sortedWith(
                compareByDescending<TrackingSiteMatchResult> { it.confidence }
                    .thenByDescending { it.service == preferredService }
                    .thenBy { it.title },
            )
            .toList()
        val best = filtered.firstOrNull() ?: return null
        val runnerUp = filtered.getOrNull(1) ?: return best
        return if (best.confidence - runnerUp.confidence >= TRACKING_SUGGESTION_GAP_THRESHOLD) {
            best
        } else {
            null
        }
    }

    private fun getScrobbler(scrobblerServiceId: Int): Scrobbler? {
        val scrobbler = scrobblers.find {
            it.scrobblerService.id == scrobblerServiceId && it.isEnabled
        }
        if (scrobbler == null) {
            errorEvent.call(IllegalStateException("Scrobbler [$scrobblerServiceId] is not available"))
        }
        return scrobbler
    }

    private suspend fun autoLinkTrackingServiceIfAuthorized(
        mangaId: Long,
        item: TrackingSiteItem,
        contentType: ContentType? = null,
    ) {
        val scrobbler = scrobblers.firstOrNull {
            it.scrobblerService == item.service && it.isEnabled
        } ?: return
        val manga = dataRepository.findPreferredLocalContentById(mangaId, withChapters = false)
            ?: dataRepository.findContentById(mangaId, withChapters = false)
            ?: manga.filterNotNull().firstOrNull { it.id == mangaId }
            ?: return
        val previous = scrobbler.getScrobblingInfoOrNull(mangaId)
        scrobbler.linkContent(
            mangaId,
            item.toScrobblerContent().copy(mediaType = contentType.toScrobblerMediaType()),
        )
        var linkedInfo = scrobbler.getScrobblingInfoOrNull(mangaId)
        if (linkedInfo == null) {
            scrobbler.syncLibrary()
            linkedInfo = scrobbler.getScrobblingInfoOrNull(mangaId)
        }
        checkNotNull(linkedInfo) {
            "Scrobbling info for manga $mangaId not found after linking target ${item.remoteId} " +
                "(${item.service.name}, mediaType=${contentType.toScrobblerMediaType()})"
        }
        val history = historyRepository.getOne(manga)
        scrobbler.updateScrobblingInfo(
            mangaId = mangaId,
            rating = previous?.rating ?: 0f,
            status = previous?.status ?: when {
                history == null -> ScrobblingStatus.PLANNED
                org.skepsun.kototoro.list.domain.ReadingProgress.isCompleted(history.percent) -> ScrobblingStatus.COMPLETED
                else -> ScrobblingStatus.READING
            },
            comment = previous?.comment,
        )
        if (history != null && manga.chapters?.any { it.id == history.chapterId } == true) {
            scrobbler.tryScrobble(manga, history.chapterId)
        }
    }

    /**
     * Expand EPUB chapters in the details page (NEW ARCHITECTURE - SIMPLIFIED)
     *
     * In the new architecture, EPUB chapters are already loaded by LocalEpubSource
     * in the doLoad() method, so this method simply returns the chapters as-is.
     *
     * This method is kept for backward compatibility with old EPUB data that
     * still uses the file://path#chapter/N format. Once all data is migrated,
     * this method can be removed entirely.
     */
    override suspend fun expandEpubChaptersIfNeeded(chapters: List<org.skepsun.kototoro.details.ui.model.ChapterListItem>): List<org.skepsun.kototoro.details.ui.model.ChapterListItem> {
        android.util.Log.d("DetailsViewModel", "expandEpubChaptersIfNeeded: NEW ARCHITECTURE - returning chapters as-is (${chapters.size} chapters)")
        val manga = mangaDetails.value?.toContent() ?: return chapters
        val contentType = manga.source.getContentType()
        if (contentType != ContentType.VIDEO && contentType != ContentType.HENTAI_VIDEO) {
            return chapters
        }
        val downloadedIds = videoDownloadIndex.getDownloadedChapterIds(manga.id)
        if (downloadedIds.isEmpty()) return chapters
        val downloadedOnly = isDownloadedOnly.value
        return chapters.mapNotNull { item ->
            val isDownloaded = item.chapter.id in downloadedIds || item.isDownloaded
            if (downloadedOnly && !isDownloaded) {
                return@mapNotNull null
            }
            if (isDownloaded && !item.isDownloaded) {
                item.copy(flags = item.flags or FLAG_DOWNLOADED)
            } else {
                item
            }
        }
    }

    override suspend fun onDownloadComplete(downloadedContent: LocalContent?) {
        super.onDownloadComplete(downloadedContent)
        downloadedContent ?: return
        baseLoadedDetails = interactor.updateLocal(baseLoadedDetails, downloadedContent)
    }

    fun translateTitleAndDescription(forceRefresh: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!forceRefresh && hasTranslationCache.value) {
                isShowingTranslation.value = true
                persistCurrentTranslationState()
                return@launch
            }
            val manga = getContentOrNull() ?: return@launch
            val title = manga.title
            val description = mangaDetails.value?.description?.toString() ?: ""
            if (title.isBlank()) return@launch

            isTranslating.value = true
            try {
                val targetLang = currentTargetLang()
                val sourceLang = currentSourceLang()

                // Use ML Kit for simple text translation (same as reader pipeline)
                val translatedTitleText = translateViaMlKit(title, sourceLang, targetLang)
                val nextTranslatedTitle = translatedTitleText.takeIf { it.isNotBlank() && it != title }

                var nextTranslatedDescription: String? = null
                if (description.isNotBlank()) {
                    val translatedDescText = translateViaMlKit(description, sourceLang, targetLang)
                    nextTranslatedDescription = translatedDescText.takeIf { it.isNotBlank() && it != description }
                }

                if (nextTranslatedTitle != null || nextTranslatedDescription != null) {
                    cachedTranslatedTitle.value = nextTranslatedTitle
                    cachedTranslatedDescription.value = nextTranslatedDescription
                    isShowingTranslation.value = true
                    translationCacheSourceLang = sourceLang
                    translationCacheTargetLang = targetLang
                    detailsTranslationCache.put(
                        content = manga,
                        sourceLang = sourceLang,
                        targetLang = targetLang,
                        entry = CachedTranslationEntry(
                            originalTitle = title,
                            translatedTitle = nextTranslatedTitle,
                            originalDescription = description,
                            translatedDescription = nextTranslatedDescription,
                            isShowingTranslation = true,
                        ),
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("DetailsViewModel", "Translation failed", e)
            } finally {
                isTranslating.value = false
            }
        }
    }

    fun toggleTranslationDisplay() {
        if (!hasTranslationCache.value) return
        isShowingTranslation.value = !isShowingTranslation.value
        persistCurrentTranslationState()
    }

    fun clearTranslationCache() {
        clearInMemoryTranslationState()
    }

    private suspend fun translateViaMlKit(text: String, sourceLang: String, targetLang: String): String {
        val resolvedSource = if (sourceLang.trim().lowercase() == "auto") {
            detectLanguageViaMlKit(text) ?: "en"
        } else {
            sourceLang
        }
        val mlSource = resolveMlKitLang(resolvedSource) ?: return ""
        val mlTarget = resolveMlKitLang(targetLang) ?: return ""

        val options = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
            .setSourceLanguage(mlSource)
            .setTargetLanguage(mlTarget)
            .build()
        val translator = com.google.mlkit.nl.translate.Translation.getClient(options)
        return try {
            withTimeout(60_000) {
                translator.downloadModelIfNeeded().awaitCancellable()
            }
            withTimeout(15_000) {
                translator.translate(text).awaitCancellable()
            }
        } finally {
            translator.close()
        }
    }

    private suspend fun detectLanguageViaMlKit(text: String): String? {
        return try {
            val identifier = com.google.mlkit.nl.languageid.LanguageIdentification.getClient()
            val lang = withTimeout(5_000) {
                identifier.identifyLanguage(text).awaitCancellable()
            }
            if (lang == "und") null else lang
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveMlKitLang(lang: String): String? {
        val normalized = lang.trim().lowercase().replace("-", "_")
        return com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(normalized)
    }

    private fun restorePersistedTranslation(details: ContentDetails) {
        val content = details.toContent()
        val sourceLang = currentSourceLang()
        val targetLang = currentTargetLang()
        val originalTitle = content.title
        val originalDescription = details.description?.toString().orEmpty()
        val restored = detailsTranslationCache.get(
            content = content,
            sourceLang = sourceLang,
            targetLang = targetLang,
            originalTitle = originalTitle,
            originalDescription = originalDescription,
        )
        if (restored == null) {
            clearInMemoryTranslationState()
            return
        }
        cachedTranslatedTitle.value = restored.translatedTitle
        cachedTranslatedDescription.value = restored.translatedDescription
        isShowingTranslation.value = restored.isShowingTranslation
        translationCacheSourceLang = sourceLang
        translationCacheTargetLang = targetLang
    }

    private fun persistCurrentTranslationState() {
        val sourceLang = translationCacheSourceLang ?: return
        val targetLang = translationCacheTargetLang ?: return
        val details = mangaDetails.value ?: return
        val content = details.toContent()
        if (!hasTranslationCache.value) return
        detailsTranslationCache.put(
            content = content,
            sourceLang = sourceLang,
            targetLang = targetLang,
            entry = CachedTranslationEntry(
                originalTitle = content.title,
                translatedTitle = cachedTranslatedTitle.value,
                originalDescription = details.description?.toString().orEmpty(),
                translatedDescription = cachedTranslatedDescription.value,
                isShowingTranslation = isShowingTranslation.value,
            ),
        )
    }

    private fun trackingEntityOriginToSyntheticContent(
        origin: org.skepsun.kototoro.details.ui.model.DetailsOrigin.TrackingEntity,
        service: ScrobblerService,
    ): Content {
        val source = syntheticSource("TRACKING_${service.name}_${origin.entityTypeName}", ContentType.MANGA)
        val normalizedCoverUrl = origin.coverUrl.normalizedImageUrl()
        return Content(
            id = origin.remoteId,
            title = origin.name,
            altTitles = setOfNotNull(origin.altName?.takeIf { it.isNotBlank() }),
            url = origin.url.orEmpty(),
            publicUrl = origin.url.orEmpty(),
            rating = 0f,
            contentRating = null,
            coverUrl = normalizedCoverUrl,
            largeCoverUrl = normalizedCoverUrl,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            description = null,
            chapters = null,
            source = source,
        )
    }

    private fun clearInMemoryTranslationState() {
        cachedTranslatedTitle.value = null
        cachedTranslatedDescription.value = null
        isShowingTranslation.value = false
        translationCacheSourceLang = null
        translationCacheTargetLang = null
    }

    private fun currentSourceLang(): String {
        return settings.readerTranslationSourceLanguage.ifBlank { "auto" }
    }

    private fun currentTargetLang(): String {
        return settings.readerTranslationTargetLanguage.ifBlank { "zh" }
    }
}
