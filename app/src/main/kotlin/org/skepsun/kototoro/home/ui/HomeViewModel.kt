package org.skepsun.kototoro.home.ui

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.collection.LongObjectMap
import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.backups.domain.BackupPayloadGuard
import org.skepsun.kototoro.backups.domain.BackupWebDavRestoreCoordinator
import org.skepsun.kototoro.backups.domain.BackupWebDavUploadCoordinator
import org.skepsun.kototoro.backups.domain.ExternalBackupStorage
import org.skepsun.kototoro.backups.ui.periodical.RemoteNamespace
import org.skepsun.kototoro.backups.ui.periodical.WebDavBackupUploader
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.core.jsonsource.SourceGroup
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.withOverride
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.ui.model.ContentOverride
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.history.domain.HistoryListQuickFilter
import org.skepsun.kototoro.history.domain.model.ContentWithHistory
import org.skepsun.kototoro.history.ui.HistoryPreviewCache
import org.skepsun.kototoro.history.ui.HistoryPreviewSnapshot
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.search.domain.ContentSearchRepository
import org.skepsun.kototoro.suggestions.domain.SuggestionRepository
import org.skepsun.kototoro.tracking.discovery.data.TrackingSiteCacheRepository
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import org.skepsun.kototoro.work.domain.WorkAggregate
import org.skepsun.kototoro.work.domain.WorkAggregateRepository
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.scopedToSpace
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject

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
    val selectedSourceTags: Set<org.skepsun.kototoro.explore.ui.model.SourceTag> = emptySet(),
    val isInitialized: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    historyRepository: HistoryRepository,
    favouritesRepository: FavouritesRepository,
    trackingRepository: TrackingRepository,
    suggestionRepository: SuggestionRepository,
    contentSourcesRepository: ContentSourcesRepository,
    private val exploreRepository: org.skepsun.kototoro.explore.domain.ExploreRepository,
    private val settings: AppSettings,
    private val webDavUploader: WebDavBackupUploader,
    private val backupWebDavUploadCoordinator: BackupWebDavUploadCoordinator,
    private val backupWebDavRestoreCoordinator: BackupWebDavRestoreCoordinator,
    private val backupStorage: ExternalBackupStorage,
    private val repository: BackupRepository,
    private val entityGraphRepository: EntityGraphRepository,
    private val sourceGroupManager: org.skepsun.kototoro.core.jsonsource.SourceGroupManager,
    private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
    private val sourcePresetsRepository: org.skepsun.kototoro.explore.data.SourcePresetsRepository,
    private val contentSearchRepository: ContentSearchRepository,
    private val contentDataRepository: ContentDataRepository,
    private val trackingSiteCacheRepository: TrackingSiteCacheRepository,
    private val workResolver: WorkResolver,
    private val workAggregateRepository: WorkAggregateRepository,
    @ApplicationContext private val appContext: Context,
    private val historyPreviewCache: HistoryPreviewCache,
    private val historyQuickFilter: HistoryListQuickFilter,
    spaceBrowseScope: SpaceBrowseScope,
) : BaseViewModel(), SpaceBindableViewModel {

    private companion object {
        private const val TAG = "HomeViewModel"
        private const val HOME_UPDATES_LIMIT = 64
        private const val HOME_RECOMMENDATIONS_LIMIT = 64
    }

    private fun logHomeDiag(stage: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[diag] $stage $message")
        }
    }

    val onActionDone = MutableEventFlow<ReversibleAction>()

    private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)
    private val selectedBrowseGroupTab = globalFavoritesState.selectedGroupTab.scopedToSpace(
        spaceGroupTab = spaceBinding.groupTab,
        coroutineScope = viewModelScope + Dispatchers.Default,
    )
    private val selectedTabFlow = selectedBrowseGroupTab.map(BrowseGroupTab::toHomeContentTab)
        .distinctUntilChanged()
        .onEach { tab ->
            logHomeDiag("selectedTabFlow", "tab=$tab")
        }
    private val selectedSourceTagsFlow = globalFavoritesState.selectedSourceTags
        .onStart { emit(globalFavoritesState.selectedSourceTags.value) }
        .distinctUntilChanged()
        .onEach { tags ->
            logHomeDiag("selectedSourceTagsFlow", "tags=${tags.homeTagSignature()}")
        }
    private val activePresetFlow = settings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId }
        .flatMapLatest { id ->
            if (id == -1L) flowOf(null) else sourcePresetsRepository.observe(id)
        }
        .onStart {
            val presetId = settings.activeSourcePresetId
            emit(if (presetId == -1L) null else sourcePresetsRepository.getById(presetId))
        }
        .distinctUntilChanged()
        .onEach { preset ->
            Log.d(TAG, "activePresetFlow presetId=${preset?.id ?: -1L}")
            logHomeDiag(
                "activePresetFlow",
                "presetId=${preset?.id ?: -1L} sourceCount=${preset?.sources?.size ?: 0}",
            )
        }
    private val recentHistoryWithMetadataFlow = historyRepository.observeAllWithHistory(
        order = ListSortOrder.LAST_READ,
        filterOptions = emptySet(),
        limit = 64,
    )
        .onStart { emit(emptyList()) }
        .distinctUntilChanged()
    private val recentHistoryFlow = recentHistoryWithMetadataFlow
        .map { items -> items.map(ContentWithHistory::manga) }
        .distinctUntilChanged()
        .onEach { items ->
            logHomeDiag("recentHistoryFlow", "items=${items.homeContentSignature()}")
        }
    private val isHistoryNsfwDisabledFlow = settings.observeAsFlow(AppSettings.KEY_HISTORY_EXCLUDE_NSFW) { isHistoryExcludeNsfw }
        .onStart { emit(settings.isHistoryExcludeNsfw) }
        .distinctUntilChanged()
    private val resumeCandidateFlow = combine(
        recentHistoryFlow,
        selectedTabFlow,
        selectedSourceTagsFlow,
        activePresetFlow,
        isHistoryNsfwDisabledFlow,
    ) { history, selectedTab, selectedSourceTags, preset, isHistoryNsfwDisabled ->
        history.firstOrNull { item ->
            item.matchesHomeFilters(
                tab = selectedTab,
                sourceTags = selectedSourceTags,
                sourceGroupManager = sourceGroupManager,
                preset = preset,
                excludeNsfw = isHistoryNsfwDisabled,
            )
        }
    }
    private val resumeStateFlow = resumeCandidateFlow
        .flatMapLatest { content ->
            if (content == null) {
                flowOf(HomeResumeState())
            } else {
                historyRepository.observeOne(content.id).mapLatest { history ->
                    val baseState = HomeResumeState(
                        content = content,
                        progressPercent = history.toProgressPercent(),
                    )
                    runCatching {
                        val entityId = workResolver.resolveByMangaId(content.id).entityId
                        val preferredLocalMangaId = if (entityId != null) {
                            workResolver.selectPreferredProjection(entityId)
                        } else {
                            null
                        }
                        val representativeContent = contentDataRepository.findDisplayContentById(
                            preferredLocalMangaId ?: content.id,
                            withChapters = false,
                        ) ?: contentDataRepository.findPreferredLocalContentById(
                            content.id,
                            withChapters = false,
                        ) ?: content
                        baseState.copy(
                            content = representativeContent,
                            entityId = entityId,
                            preferredLocalMangaId = preferredLocalMangaId ?: representativeContent.id,
                        )
                    }.getOrElse {
                        it.printStackTraceDebug()
                        baseState
                    }
                }
            }
        }
        .onEach { resumeState ->
            Log.d(
                TAG,
                "resumeStateFlow contentId=${resumeState.content?.id} entityId=${resumeState.entityId} preferredLocalMangaId=${resumeState.preferredLocalMangaId} progress=${resumeState.progressPercent}",
            )
            logHomeDiag("resumeStateFlow", resumeState.homeDiagSignature())
        }
        .distinctUntilChanged()
    private val favoritesCountFlow = favouritesRepository.observeContentCount()
        .onStart { emit(0) }
        .distinctUntilChanged()
    private val favoriteCategoriesCountFlow = favouritesRepository.observeCategories()
        .map { it.size }
        .onStart { emit(0) }
        .distinctUntilChanged()
    private val recentHistoryCountFlow = historyRepository.observeCount()
        .onStart { emit(0) }
        .distinctUntilChanged()
    private val unreadUpdatesCountFlow = trackingRepository.observeUpdatedContentCount()
        .onStart { emit(0) }
        .distinctUntilChanged()
    private val recommendationsCountFlow = suggestionRepository.observeCount()
        .onStart { emit(0) }
        .distinctUntilChanged()
    private val recentUpdatesFlow = trackingRepository.observeUpdatedContent(
        limit = HOME_UPDATES_LIMIT,
        filterOptions = emptySet(),
    )
        .distinctUntilChanged { old, new -> old.isSameForHomeUpdates(new) }
        .onEach { items ->
            logHomeDiag("recentUpdatesFlow", "items=${items.homeUpdateSignature()}")
        }
        .onStart { emit(emptyList()) }
    private val recommendationsFlow = suggestionRepository.observeAll(HOME_RECOMMENDATIONS_LIMIT, emptySet())
        .distinctUntilChanged()
        .onEach { items ->
            logHomeDiag("recommendationsFlow", "items=${items.homeContentSignature()}")
        }
        .onStart { emit(emptyList()) }
    private val recentSearchesFlow = contentSearchRepository.observeRecentQueries(HOME_COVER_PREVIEW_LIMIT)
        .distinctUntilChanged()
        .onEach { items ->
            logHomeDiag("recentSearchesFlow", "queries=${items.joinToString(limit = 6)}")
        }
        .onStart { emit(emptyList()) }
    private val displayChangesFlow = combine(
        contentDataRepository.observeDisplayPreferencesChanges(),
        trackingSiteCacheRepository.observeDetailsUpdates().onStart { emit(0L) },
    ) { displayPrefsHash, detailsUpdateTick ->
        displayPrefsHash to detailsUpdateTick
    }
        .distinctUntilChanged()
        .onEach { (displayPrefsHash, detailsUpdateTick) ->
            logHomeDiag(
                "displayChangesFlow",
                "prefsHash=$displayPrefsHash detailsTick=$detailsUpdateTick",
            )
        }
        .map { Unit }
        .onStart { emit(Unit) }
    private val isTrackerNsfwDisabledFlow = settings.observeAsFlow(AppSettings.KEY_TRACKER_NO_NSFW) { isTrackerNsfwDisabled }
        .onStart { emit(settings.isTrackerNsfwDisabled) }
        .distinctUntilChanged()
    private val isSuggestionNsfwDisabledFlow = settings.observeAsFlow(AppSettings.KEY_SUGGESTIONS_EXCLUDE_NSFW) { isSuggestionsExcludeNsfw }
        .onStart { emit(settings.isSuggestionsExcludeNsfw) }
        .distinctUntilChanged()
    private val globalTagBlacklistFlow = settings.observeAsFlow(AppSettings.KEY_GLOBAL_TAG_BLACKLIST) { globalTagBlacklist }
        .onStart { emit(settings.globalTagBlacklist) }
        .distinctUntilChanged()
    private val enabledSourcesCountFlow = contentSourcesRepository.observeEnabledSourcesCount()
        .onStart { emit(0) }
        .distinctUntilChanged()
    private val sourceBreakdownFlow = contentSourcesRepository.observeGroupCounts()
        .map { counts ->
            listOfNotNull(
                HomeSourceOrigin.BUILT_IN.toBreakdown(counts.countOf(OriginGroup.NATIVE)),
                HomeSourceOrigin.MIHON.toBreakdown(counts.countOf(OriginGroup.MIHON)),
                HomeSourceOrigin.ANIYOMI.toBreakdown(counts.countOf(OriginGroup.ANIYOMI)),
                HomeSourceOrigin.LEGADO.toBreakdown(counts.countOf(OriginGroup.LEGADO_JSON)),
                HomeSourceOrigin.TVBOX.toBreakdown(counts.countOf(OriginGroup.TVBOX_JSON)),
                HomeSourceOrigin.EXTERNAL.toBreakdown(counts.countOf(OriginGroup.EXTERNAL)),
                HomeSourceOrigin.IREADER.toBreakdown(counts.countOf(OriginGroup.IREADER)),
            ).sortedByDescending { it.count }.take(3)
        }
        .onStart { emit(emptyList()) }
        .distinctUntilChanged()
    private val contentDataFlow = combine(
        resumeStateFlow,
        recentHistoryFlow,
        recentHistoryWithMetadataFlow,
        recentUpdatesFlow,
        recommendationsFlow,
        isHistoryNsfwDisabledFlow,
        isTrackerNsfwDisabledFlow,
        globalTagBlacklistFlow,
    ) { values ->
        val resumeState = values[0] as HomeResumeState
        val recentHistory = values[1] as List<Content>
        val recentHistoryWithMetadata = values[2] as List<ContentWithHistory>
        val recentUpdates = values[3] as List<ContentTracking>
        val recommendations = values[4] as List<Content>
        val isHistoryNsfwDisabled = values[5] as Boolean
        val isTrackerNsfwDisabled = values[6] as Boolean
        @Suppress("UNCHECKED_CAST")
        val globalTagBlacklist = GlobalTagBlacklist(values[7] as Set<String>)
        val filteredHistory = if (isHistoryNsfwDisabled) recentHistory.filterNot { it.isNsfw() } else recentHistory
        val filteredHistoryWithMetadata = if (isHistoryNsfwDisabled) {
            recentHistoryWithMetadata.filterNot { it.manga.isNsfw() }
        } else {
            recentHistoryWithMetadata
        }
        val filteredUpdates = if (isTrackerNsfwDisabled) recentUpdates.filterNot { it.manga.isNsfw() } else recentUpdates
        ContentDataSnapshot(
            resumeState = resumeState,
            history = globalTagBlacklist.filter(filteredHistory),
            historyWithMetadata = filteredHistoryWithMetadata.filterNot { it.manga in globalTagBlacklist },
            updates = filteredUpdates.filterNot { it.manga in globalTagBlacklist },
            recommendations = globalTagBlacklist.filter(recommendations),
        )
    }
        .distinctUntilChanged()
        .onEach { snapshot ->
            Log.d(
                TAG,
                "contentDataFlow history=${snapshot.history.size} updates=${snapshot.updates.size} recommendations=${snapshot.recommendations.size} resumeContentId=${snapshot.resumeState.content?.id}",
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ContentDataSnapshot(
                resumeState = HomeResumeState(),
                history = emptyList(),
                historyWithMetadata = emptyList(),
                updates = emptyList(),
                recommendations = emptyList(),
            ),
        )

    private val entityIdsFlow = contentDataFlow.map { snapshot ->
        buildSet {
            addAll(snapshot.history.map { it.id })
            addAll(snapshot.updates.map { it.manga.id })
            addAll(snapshot.recommendations.map { it.id })
        }
    }.distinctUntilChanged().map { ids ->
        resolveEntityIdsByMangaIds(ids)
    }
        .distinctUntilChanged()
        .onEach { entityIdsByMangaId ->
            Log.d(
                TAG,
                "entityIdsFlow localIds=${entityIdsByMangaId.size} entityIds=${entityIdsByMangaId.values.toSet().size}",
            )
        }

    private val primaryCountsFlow = combine(
        favoritesCountFlow,
        favoriteCategoriesCountFlow,
        recentHistoryCountFlow,
        unreadUpdatesCountFlow,
        recommendationsCountFlow,
    ) { favoritesCount, favoriteCategoriesCount, recentHistoryCount, unreadUpdatesCount, recommendationsCount ->
        HomePrimaryCountsSnapshot(
            favoritesCount = favoritesCount,
            favoriteCategoriesCount = favoriteCategoriesCount,
            recentHistoryCount = recentHistoryCount,
            unreadUpdatesCount = unreadUpdatesCount,
            recommendationsCount = recommendationsCount,
        )
    }.distinctUntilChanged()

    private val metaFlow = combine(
        primaryCountsFlow,
        enabledSourcesCountFlow,
        sourceBreakdownFlow,
    ) { primaryCounts, enabledSourcesCount, sourceBreakdown ->
        HomeMetaSnapshot(
            favoritesCount = primaryCounts.favoritesCount,
            favoriteCategoriesCount = primaryCounts.favoriteCategoriesCount,
            recentHistoryCount = primaryCounts.recentHistoryCount,
            unreadUpdatesCount = primaryCounts.unreadUpdatesCount,
            recommendationsCount = primaryCounts.recommendationsCount,
            enabledSourcesCount = enabledSourcesCount,
            sourceBreakdown = sourceBreakdown,
        )
    }
        .distinctUntilChanged()
        .onEach { meta ->
            val sourceBreakdownSummary = meta.sourceBreakdown.joinToString { "${it.origin}:${it.count}" }
            Log.d(
                TAG,
                "metaFlow favorites=${meta.favoritesCount} categories=${meta.favoriteCategoriesCount} history=${meta.recentHistoryCount} updates=${meta.unreadUpdatesCount} recommendations=${meta.recommendationsCount} enabledSources=${meta.enabledSourcesCount} sourceBreakdown=$sourceBreakdownSummary",
            )
        }

    val summaryState = combine(
        selectedTabFlow,
        combine(selectedSourceTagsFlow, activePresetFlow, ::Pair),
        contentDataFlow,
        entityIdsFlow,
        metaFlow,
        combine(
            recentSearchesFlow,
            isSuggestionNsfwDisabledFlow,
            displayChangesFlow,
        ) { recentSearches, isSuggestionNsfwDisabled, _ ->
            Pair(recentSearches, isSuggestionNsfwDisabled)
        },
    ) { values ->
        val selectedTab = values[0] as HomeContentTab?
        @Suppress("UNCHECKED_CAST")
        val tagsAndPreset = values[1] as Pair<Set<org.skepsun.kototoro.explore.ui.model.SourceTag>, org.skepsun.kototoro.explore.data.SourcePreset?>
        val contentData = values[2] as ContentDataSnapshot
        @Suppress("UNCHECKED_CAST")
        val entityIdsByMangaId = values[3] as Map<Long, Long>
        val meta = values[4] as HomeMetaSnapshot
        @Suppress("UNCHECKED_CAST")
        val extras = values[5] as Pair<List<String>, Boolean>
        val selectedSourceTags = tagsAndPreset.first
        val preset = tagsAndPreset.second
        val isSuggestionNsfwDisabled = extras.second
        val recentSearches = extras.first
        val startNs = System.nanoTime()

        runCatching {
            Log.d(
                TAG,
                "summaryState start tab=$selectedTab tags=${selectedSourceTags.size} presetId=${preset?.id ?: -1L} history=${contentData.history.size} updates=${contentData.updates.size} recommendations=${contentData.recommendations.size} entities=${entityIdsByMangaId.size}",
            )

            val allRecommendations = if (isSuggestionNsfwDisabled) {
                contentData.recommendations.filterNot { it.isNsfw() }
            } else {
                contentData.recommendations
            }
            val progressIndicatorMode = settings.progressIndicatorMode
            val preferredLocalIdsByEntity = resolvePreferredMangaIdsByEntityIds(entityIdsByMangaId.values)
            val workAggregatesByEntity = workAggregateRepository.findAggregatesByEntityIds(entityIdsByMangaId.values)
            val displayContentOverrides = buildDisplayContentOverrides(
                resumeContent = contentData.resumeState.content,
                history = contentData.history,
                updates = contentData.updates,
                recommendations = allRecommendations,
                contentDataRepository = contentDataRepository,
                trackingSiteCacheRepository = trackingSiteCacheRepository,
            )

            val resumeState = contentData.resumeState
                .withOverrides(displayContentOverrides)
                .filtered(
                    tab = selectedTab,
                    sourceTags = selectedSourceTags,
                    sourceGroupManager = sourceGroupManager,
                    preset = preset,
                )
                .filteredNsfw(false)
                .withGroupKey(entityIdsByMangaId)
            val recentHistory = contentData.history
                .map { content -> content.withOverride(displayContentOverrides[content.id]) }
                .aggregateHomeContentByEntity(entityIdsByMangaId, preferredLocalIdsByEntity, workAggregatesByEntity, progressIndicatorMode)
                .selectHomeHistoryByTab(selectedTab, selectedSourceTags, sourceGroupManager, preset)
            val recentUpdates = contentData.updates
                .map { tracking ->
                    val override = displayContentOverrides[tracking.manga.id]
                    if (override == null) tracking else tracking.copy(manga = tracking.manga.withOverride(override))
                }
                .aggregateHomeUpdatesByEntity(entityIdsByMangaId, preferredLocalIdsByEntity)
                .withWorkBadges(entityIdsByMangaId, workAggregatesByEntity, progressIndicatorMode)
                .selectHomeUpdatesByTab(selectedTab, selectedSourceTags, sourceGroupManager, preset)
            val recommendations = allRecommendations
                .map { content -> content.withOverride(displayContentOverrides[content.id]) }
                .aggregateHomeRecommendationsByEntity(entityIdsByMangaId, preferredLocalIdsByEntity, workAggregatesByEntity, progressIndicatorMode)
                .selectHomeRecommendationsByTab(selectedTab, selectedSourceTags, sourceGroupManager, preset)

            Pair(
                HomeSummaryState(
                    selectedTab = selectedTab,
                    recentHistoryCount = meta.recentHistoryCount,
                    recentHistoryItems = recentHistory,
                    resumeState = resumeState,
                    favoritesCount = meta.favoritesCount,
                    favoriteCategoriesCount = meta.favoriteCategoriesCount,
                    unreadUpdatesCount = meta.unreadUpdatesCount,
                    recentUpdates = recentUpdates,
                    recommendationsCount = meta.recommendationsCount,
                    recommendations = recommendations,
                    recentSearches = recentSearches.map { HomeRecentSearchItem(it) },
                    enabledSourcesCount = meta.enabledSourcesCount,
                    sourceBreakdown = meta.sourceBreakdown,
                    selectedSourceTags = selectedSourceTags,
                    isInitialized = true,
                ),
                HistoryPreviewSnapshot(
                    items = contentData.historyWithMetadata.map { item ->
                        item.copy(manga = item.manga.withOverride(displayContentOverrides[item.manga.id]))
                    },
                    listMode = settings.listMode,
                    sortOrder = ListSortOrder.LAST_READ,
                    isGroupingEnabled = settings.isHistoryGroupingEnabled && ListSortOrder.LAST_READ.isGroupingSupported(),
                    isIncognito = settings.isIncognitoModeEnabled,
                    groupTab = selectedTab.toBrowseGroupTab(),
                    sourceTags = selectedSourceTags,
                    preset = preset,
                    filters = emptySet(),
                    quickFilter = historyQuickFilter.previewFilterItem(emptySet()),
                    isHistoryExcludeNsfw = settings.isHistoryExcludeNsfw,
                ),
            )
        }.onSuccess { (summary, preview) ->
            historyPreviewCache.update(preview)
            Log.d(
                TAG,
                "summaryState success history=${summary.recentHistoryCount} updates=${summary.unreadUpdatesCount} recommendations=${summary.recommendationsCount} initialized=${summary.isInitialized} tookMs=${(System.nanoTime() - startNs) / 1_000_000}",
            )
            logHomeDiag("summaryState.emit", summary.homeDiagSignature())
        }.map { it.first }.getOrElse { error ->
            Log.e(
                TAG,
                "summaryState failed tab=$selectedTab tags=${selectedSourceTags.size} presetId=${preset?.id ?: -1L} history=${contentData.history.size} updates=${contentData.updates.size} recommendations=${contentData.recommendations.size}",
                error,
            )
            HomeSummaryState(
                selectedTab = selectedTab,
                resumeState = contentData.resumeState,
                favoritesCount = meta.favoritesCount,
                favoriteCategoriesCount = meta.favoriteCategoriesCount,
                recentSearches = recentSearches.map { HomeRecentSearchItem(it) },
                enabledSourcesCount = meta.enabledSourcesCount,
                sourceBreakdown = meta.sourceBreakdown,
                selectedSourceTags = selectedSourceTags,
                isInitialized = true,
            )
        }
    }

        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = HomeSummaryState(
                selectedTab = selectedBrowseGroupTab.value.toHomeContentTab(),
                selectedSourceTags = globalFavoritesState.selectedSourceTags.value,
            ),
        )

    private suspend fun resolveEntityIdsByMangaIds(mangaIds: Collection<Long>): Map<Long, Long> {
        return workResolver.resolveManyByMangaIds(mangaIds)
            .mapValues { (_, identity) -> identity.entityId }
            .filterValues { it != null }
            .mapValues { (_, entityId) -> requireNotNull(entityId) }
    }

    private suspend fun resolvePreferredMangaIdsByEntityIds(entityIds: Collection<Long>): Map<Long, Long> {
        return entityIds.distinct()
            .mapNotNull { entityId ->
                val preferredMangaId = workResolver.selectPreferredProjection(entityId)
                if (preferredMangaId == null) null else entityId to preferredMangaId
            }
            .toMap()
    }

    fun setSelectedTab(tab: HomeContentTab?) {
        val groupTab = when (tab) {
            HomeContentTab.MANGA -> org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Content
            HomeContentTab.NOVEL -> org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Novel
            HomeContentTab.VIDEO -> org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Video
            null -> org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.All
        }
        globalFavoritesState.setSelectedGroupTab(groupTab)
    }

    override fun bindSpace(spaceId: org.skepsun.kototoro.space.domain.SpaceId?) {
        spaceBinding.bindSpace(spaceId)
    }

    fun setSelectedSourceTags(tags: Set<org.skepsun.kototoro.explore.ui.model.SourceTag>) {
        globalFavoritesState.setSelectedSourceTags(tags)
    }

    val isRandomLoading = MutableStateFlow(false)
    data class HomeOpenContentEvent(
        val content: Content,
        val entityId: Long? = null,
        val preferredLocalMangaId: Long? = null,
    )

    val onOpenContent = org.skepsun.kototoro.core.util.ext.MutableEventFlow<HomeOpenContentEvent>()

    fun openRandom() {
        if (isRandomLoading.value) return
        launchJob(Dispatchers.Default) {
            isRandomLoading.value = true
            try {
                val manga = exploreRepository.findRandomContent(tagsLimit = 8)
                val entityId = workResolver.resolveByMangaId(manga.id).entityId
                val preferredLocalMangaId = if (entityId != null) {
                    workResolver.selectPreferredProjection(entityId)
                } else {
                    null
                }
                onOpenContent.call(
                    HomeOpenContentEvent(
                        content = manga,
                        entityId = entityId,
                        preferredLocalMangaId = preferredLocalMangaId,
                    ),
                )
            } finally {
                isRandomLoading.value = false
            }
        }
    }

    fun openContent(content: Content) {
        viewModelScope.launch(Dispatchers.Default) {
            val entityId = workResolver.resolveByMangaId(content.id).entityId
            val preferredLocalMangaId = if (entityId != null) {
                workResolver.selectPreferredProjection(entityId)
            } else {
                null
            }
            onOpenContent.call(
                HomeOpenContentEvent(
                    content = content,
                    entityId = entityId,
                    preferredLocalMangaId = preferredLocalMangaId,
                ),
            )
        }
    }

    fun uploadWebDavNow() {
        viewModelScope.launch(Dispatchers.Default) {
            val output = org.skepsun.kototoro.backups.domain.BackupUtils.createTempFile(appContext)
            try {
                val zip = java.util.zip.ZipOutputStream(output.outputStream())
                try {
                    repository.createBackup(zip, null)
                } finally {
                    zip.close()
                }

                if (settings.isBackupWebDavKeepLocalCopyEnabled) {
                    runCatching {
                        backupStorage.put(output)
                        backupStorage.trim(settings.periodicalBackupMaxCount)
                    }.onFailure { it.printStackTraceDebug() }
                }
                backupWebDavUploadCoordinator.uploadAndCommit(
                    file = output,
                    uploadKind = "manual",
                )
            } catch (e: Exception) {
                e.printStackTraceDebug()
            } finally {
                output.delete()
            }
        }
    }

    fun restoreWebDavNow() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                Log.d(TAG, "restoreWebDavNow: listing backups once...")
                val latest = webDavUploader.getLatestBackup() ?: run {
                    Log.w(TAG, "restoreWebDavNow: no backups found in any namespace")
                    errorEvent.call(IllegalStateException("No WebDAV backups found"))
                    return@launch
                }
                Log.d(TAG, "restoreWebDavNow: found ${latest.name} (ns=${latest.namespace}, ${latest.size}b)")
                val tempFile = java.io.File.createTempFile("webdav_backup_manual", ".bk.zip", appContext.cacheDir)
                try {
                    Log.d(TAG, "restoreWebDavNow: downloading ${latest.name}...")
                    webDavUploader.downloadBackup(latest.name, tempFile, latest.namespace)
                    val inspection = BackupPayloadGuard.requireRestorableWorkSnapshot(
                        file = tempFile,
                        operation = "manual WebDAV restore",
                    )
                    Log.d(
                        TAG,
                        "restoreWebDavNow: downloaded name=${latest.name} size=${tempFile.length()}b " +
                            "entries=${inspection.describe()}, starting restore from zip...",
                    )
                    val allSections = setOf(
                        org.skepsun.kototoro.backups.domain.BackupSection.INDEX,
                        org.skepsun.kototoro.backups.domain.BackupSection.HISTORY,
                        org.skepsun.kototoro.backups.domain.BackupSection.CATEGORIES,
                        org.skepsun.kototoro.backups.domain.BackupSection.FAVOURITES,
                        org.skepsun.kototoro.backups.domain.BackupSection.BOOKMARKS,
                        org.skepsun.kototoro.backups.domain.BackupSection.STATS,
                        org.skepsun.kototoro.backups.domain.BackupSection.WORK_HISTORY,
                        org.skepsun.kototoro.backups.domain.BackupSection.WORK_FAVOURITES,
                        org.skepsun.kototoro.backups.domain.BackupSection.WORK_STATS,
                        org.skepsun.kototoro.backups.domain.BackupSection.EXTENSION_REPOS,
                        org.skepsun.kototoro.backups.domain.BackupSection.SETTINGS,
                        org.skepsun.kototoro.backups.domain.BackupSection.SETTINGS_READER_GRID,
                        org.skepsun.kototoro.backups.domain.BackupSection.ENTITY_GRAPH_ENTITIES,
                        org.skepsun.kototoro.backups.domain.BackupSection.ENTITY_GRAPH_BINDINGS,
                        org.skepsun.kototoro.backups.domain.BackupSection.ENTITY_GRAPH_RELATIONS,
                        org.skepsun.kototoro.backups.domain.BackupSection.ENTITY_GRAPH_PREFS,
                    )
                    val restoreResult = java.util.zip.ZipInputStream(java.io.FileInputStream(tempFile)).use { zis ->
                        repository.restoreBackup(zis, allSections, null)
                    }
                    Log.d(TAG, "restoreWebDavNow: restore complete, committing...")
                    val restoreContext = repository.resolveRestoreSemanticContext(restoreResult.backupIndex)
                    backupWebDavRestoreCoordinator.commitManualRestore(
                        state = BackupWebDavRestoreCoordinator.RestoreSemanticState(
                            semanticSchemaVersion = restoreContext.semanticSchemaVersion,
                            transportGeneration = restoreContext.transportGeneration,
                        ),
                    )
                    onActionDone.call(
                        ReversibleAction(
                            if (restoreContext.isLegacySemanticSchema && restoreResult.legacyJarReposImported) {
                                R.string.webdav_restore_success_legacy_requires_normalization_with_jar_hint
                            } else if (restoreContext.isLegacySemanticSchema) {
                                R.string.webdav_restore_success_legacy_requires_normalization
                            } else if (restoreResult.legacyJarReposImported) {
                                R.string.webdav_restore_success_legacy_jar_hint
                            } else {
                                R.string.webdav_restore_success
                            },
                            null,
                        ),
                    )
                    Log.d(TAG, "restoreWebDavNow: committed, done")
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
            } catch (e: Exception) {
                e.printStackTraceDebug()
                Log.e(TAG, "restoreWebDavNow: failed", e)
                errorEvent.call(e)
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

private data class ContentDataSnapshot(
    val resumeState: HomeResumeState,
    val history: List<Content>,
    val historyWithMetadata: List<ContentWithHistory>,
    val updates: List<ContentTracking>,
    val recommendations: List<Content>,
)

private data class HomePrimaryCountsSnapshot(
    val favoritesCount: Int,
    val favoriteCategoriesCount: Int,
    val recentHistoryCount: Int,
    val unreadUpdatesCount: Int,
    val recommendationsCount: Int,
)

private data class HomeMetaSnapshot(
    val favoritesCount: Int,
    val favoriteCategoriesCount: Int,
    val recentHistoryCount: Int,
    val unreadUpdatesCount: Int,
    val recommendationsCount: Int,
    val enabledSourcesCount: Int,
    val sourceBreakdown: List<HomeSourceBreakdown>,
)

private fun HomeSourceOrigin.toBreakdown(count: Int?): HomeSourceBreakdown? {
    if (count == null || count <= 0) return null
    return HomeSourceBreakdown(origin = this, count = count)
}

private fun Map<SourceGroup, Int>.countOf(key: OriginGroup): Int? {
    return this[SourceGroup.Origin(key)]
}

private fun Content.contentTab(): HomeContentTab? = source.getContentType().toHomeTab()

private fun HomeContentTab?.toBrowseGroupTab(): org.skepsun.kototoro.explore.ui.model.BrowseGroupTab = when (this) {
    HomeContentTab.MANGA -> org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Content
    HomeContentTab.NOVEL -> org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Novel
    HomeContentTab.VIDEO -> org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Video
    null -> org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.All
}

private fun BrowseGroupTab.toHomeContentTab(): HomeContentTab? = when (this) {
    BrowseGroupTab.Content -> HomeContentTab.MANGA
    BrowseGroupTab.Novel -> HomeContentTab.NOVEL
    BrowseGroupTab.Video -> HomeContentTab.VIDEO
    BrowseGroupTab.All -> null
}

private fun List<HomeRecentItem>.selectHomeHistoryByTab(
    tab: HomeContentTab?,
    sourceTags: Set<org.skepsun.kototoro.explore.ui.model.SourceTag>,
    sourceGroupManager: org.skepsun.kototoro.core.jsonsource.SourceGroupManager,
    preset: org.skepsun.kototoro.explore.data.SourcePreset?,
): List<HomeRecentItem> {
    return filter { item ->
        item.content.matchesHomeFilters(
            tab = tab,
            sourceTags = sourceTags,
            sourceGroupManager = sourceGroupManager,
            preset = preset,
        )
    }
}

private fun List<HomeUpdateItem>.selectHomeUpdatesByTab(
    tab: HomeContentTab?,
    sourceTags: Set<org.skepsun.kototoro.explore.ui.model.SourceTag>,
    sourceGroupManager: org.skepsun.kototoro.core.jsonsource.SourceGroupManager,
    preset: org.skepsun.kototoro.explore.data.SourcePreset?,
): List<HomeUpdateItem> {
    return filter { item ->
        item.content.matchesHomeFilters(
            tab = tab,
            sourceTags = sourceTags,
            sourceGroupManager = sourceGroupManager,
            preset = preset,
        )
    }
}

private fun List<HomeRecommendationItem>.selectHomeRecommendationsByTab(
    tab: HomeContentTab?,
    sourceTags: Set<org.skepsun.kototoro.explore.ui.model.SourceTag>,
    sourceGroupManager: org.skepsun.kototoro.core.jsonsource.SourceGroupManager,
    preset: org.skepsun.kototoro.explore.data.SourcePreset?,
): List<HomeRecommendationItem> {
    return filter { item ->
        item.content.matchesHomeFilters(
            tab = tab,
            sourceTags = sourceTags,
            sourceGroupManager = sourceGroupManager,
            preset = preset,
        )
    }
}

private fun HomeResumeState.filtered(
    tab: HomeContentTab?,
    sourceTags: Set<org.skepsun.kototoro.explore.ui.model.SourceTag>,
    sourceGroupManager: org.skepsun.kototoro.core.jsonsource.SourceGroupManager,
    preset: org.skepsun.kototoro.explore.data.SourcePreset?,
): HomeResumeState {
    val current = content ?: return this
    return if (
        current.matchesHomeFilters(
            tab = tab,
            sourceTags = sourceTags,
            sourceGroupManager = sourceGroupManager,
            preset = preset,
        )
    ) {
        this
    } else {
        HomeResumeState()
    }
}

private fun HomeResumeState.filteredNsfw(isNsfwDisabled: Boolean): HomeResumeState {
    return if (isNsfwDisabled && content?.isNsfw() == true) HomeResumeState() else this
}

private fun HomeResumeState.withOverrides(overrides: Map<Long, ContentOverride>): HomeResumeState {
    val current = content ?: return this
    return copy(content = current.withOverride(overrides[current.id]))
}

private fun HomeResumeState.withGroupKey(entityIdsByMangaId: Map<Long, Long>): HomeResumeState {
    val current = content ?: return this
    val resolvedEntityId = entityId ?: entityIdsByMangaId[current.id]
    return copy(groupKey = resolvedEntityId?.toHomeGroupKey(current.source.getContentType().ordinal) ?: current.id)
}

private fun Content.matchesHomeFilters(
    tab: HomeContentTab?,
    sourceTags: Set<org.skepsun.kototoro.explore.ui.model.SourceTag>,
    sourceGroupManager: org.skepsun.kototoro.core.jsonsource.SourceGroupManager,
    preset: org.skepsun.kototoro.explore.data.SourcePreset?,
    excludeNsfw: Boolean = false,
): Boolean {
    if (excludeNsfw && isNsfw()) {
        return false
    }
    if (tab != null && contentTab() != tab) {
        return false
    }
    if (preset != null && source.name !in preset.sources) {
        return false
    }
    if (sourceTags.isEmpty()) {
        return true
    }
    val contentGroup = sourceGroupManager.getContentGroup(source)
    val originGroup = sourceGroupManager.getOriginGroup(source)
    return sourceTags.any { it.matches(contentGroup, originGroup) }
}

private fun List<Content>.aggregateHomeContentByEntity(
    entityIdsByMangaId: Map<Long, Long>,
    preferredLocalIdsByEntity: Map<Long, Long?>,
    workAggregatesByEntity: Map<Long, WorkAggregate>,
    progressIndicatorMode: org.skepsun.kototoro.core.prefs.ProgressIndicatorMode,
): List<HomeRecentItem> {
    if (isEmpty()) {
        return emptyList()
    }
    val grouped = LinkedHashMap<Long, MutableList<Content>>()
    for (item in this) {
        val groupKey = entityIdsByMangaId[item.id]?.toHomeGroupKey(item.source.getContentType().ordinal) ?: item.id
        grouped.getOrPut(groupKey) { ArrayList(1) }.add(item)
    }
    val result = ArrayList<HomeRecentItem>(grouped.size)
    grouped.forEach { (groupKey, items) ->
        val entityId = entityIdsByMangaId[items.first().id]
        val preferredLocalId = entityId?.let(preferredLocalIdsByEntity::get)
        val representative = items.firstOrNull { it.id == preferredLocalId } ?: items.first()
        val aggregate = entityId?.let(workAggregatesByEntity::get)
        result += HomeRecentItem(
            content = representative,
            groupKey = groupKey,
            counter = aggregate?.homeCounter() ?: 0,
            progress = aggregate?.toHomeReadingProgress(progressIndicatorMode),
        )
    }
    return result
}

private fun List<ContentTracking>.aggregateHomeUpdatesByEntity(
    entityIdsByMangaId: Map<Long, Long>,
    preferredLocalIdsByEntity: Map<Long, Long?>,
): List<HomeUpdateItem> {
    if (isEmpty()) {
        return emptyList()
    }
    val grouped = LinkedHashMap<Long, MutableList<ContentTracking>>()
    for (item in this) {
        val groupKey = entityIdsByMangaId[item.manga.id]?.toHomeGroupKey(item.manga.source.getContentType().ordinal) ?: item.manga.id
        grouped.getOrPut(groupKey) { ArrayList(1) }.add(item)
    }
    return grouped.map { (groupKey, items) ->
        val entityId = entityIdsByMangaId[items.first().manga.id]
        val preferredLocalId = entityId?.let(preferredLocalIdsByEntity::get)
        val representative = items.firstOrNull { it.manga.id == preferredLocalId }
            ?: items.maxWithOrNull(
                compareBy<ContentTracking>(
                    { it.lastChapterDate ?: java.time.Instant.EPOCH },
                    { it.lastCheck ?: java.time.Instant.EPOCH },
                    { it.newChapters },
                ),
            )
            ?: items.first()
        HomeUpdateItem(
            content = representative.manga,
            newChapters = items.sumOf { it.newChapters },
            groupKey = groupKey,
        )
    }
}

private fun List<Content>.aggregateHomeRecommendationsByEntity(
    entityIdsByMangaId: Map<Long, Long>,
    preferredLocalIdsByEntity: Map<Long, Long?>,
    workAggregatesByEntity: Map<Long, WorkAggregate>,
    progressIndicatorMode: org.skepsun.kototoro.core.prefs.ProgressIndicatorMode,
): List<HomeRecommendationItem> {
    if (isEmpty()) {
        return emptyList()
    }
    val grouped = LinkedHashMap<Long, MutableList<Content>>()
    for (item in this) {
        val groupKey = entityIdsByMangaId[item.id]?.toHomeGroupKey(item.source.getContentType().ordinal) ?: item.id
        grouped.getOrPut(groupKey) { ArrayList(1) }.add(item)
    }
    val result = ArrayList<HomeRecommendationItem>(grouped.size)
    grouped.forEach { (groupKey, items) ->
        val entityId = entityIdsByMangaId[items.first().id]
        val preferredLocalId = entityId?.let(preferredLocalIdsByEntity::get)
        val representative = items.firstOrNull { it.id == preferredLocalId } ?: items.first()
        val aggregate = entityId?.let(workAggregatesByEntity::get)
        result += HomeRecommendationItem(
            content = representative,
            groupKey = groupKey,
            counter = aggregate?.homeCounter() ?: 0,
            progress = aggregate?.toHomeReadingProgress(progressIndicatorMode),
        )
    }
    return result
}

private fun List<HomeUpdateItem>.withWorkBadges(
    entityIdsByMangaId: Map<Long, Long>,
    workAggregatesByEntity: Map<Long, WorkAggregate>,
    progressIndicatorMode: org.skepsun.kototoro.core.prefs.ProgressIndicatorMode,
): List<HomeUpdateItem> {
    return map { item ->
        val aggregate = entityIdsByMangaId[item.content.id]?.let(workAggregatesByEntity::get)
        item.copy(
            counter = aggregate?.homeCounter() ?: item.newChapters,
            progress = aggregate?.toHomeReadingProgress(progressIndicatorMode),
        )
    }
}

private fun WorkAggregate.homeCounter(): Int {
    return if (history?.percent?.let(ReadingProgress::isCompleted) == true) {
        0
    } else {
        tracking?.newChapters ?: 0
    }
}

private fun WorkAggregate.toHomeReadingProgress(
    progressIndicatorMode: org.skepsun.kototoro.core.prefs.ProgressIndicatorMode,
): ReadingProgress? {
    val history = history ?: return null
    val fixedPercent = if (ReadingProgress.isCompleted(history.percent)) 1f else history.percent
    return ReadingProgress(
        percent = fixedPercent,
        totalChapters = history.chaptersCount,
        mode = progressIndicatorMode,
    ).takeIf { it.isValid() }
}

private fun List<ContentTracking>.isSameForHomeUpdates(other: List<ContentTracking>): Boolean {
    if (size != other.size) {
        return false
    }
    return indices.all { index ->
        this[index].toHomeUpdateSignature() == other[index].toHomeUpdateSignature()
    }
}

private fun ContentTracking.toHomeUpdateSignature(): HomeUpdateSignature {
    return HomeUpdateSignature(
        anchorMangaId = anchorMangaId,
        entityId = entityId,
        preferredLocalMangaId = preferredLocalMangaId,
        manga = manga,
        newChapters = newChapters,
    )
}

private data class HomeUpdateSignature(
    val anchorMangaId: Long,
    val entityId: Long?,
    val preferredLocalMangaId: Long?,
    val manga: Content,
    val newChapters: Int,
)

private fun HomeResumeState.homeDiagSignature(): String {
    return "contentId=${content?.id} groupKey=$groupKey progress=$progressPercent entityId=$entityId preferredLocal=$preferredLocalMangaId cover=${content?.coverUrl}"
}

private fun HomeSummaryState.homeDiagSignature(): String {
    return buildString {
        append("tab=").append(selectedTab)
        append(" initialized=").append(isInitialized)
        append(" resume=").append(resumeState.content?.id).append('@').append(resumeState.groupKey)
        append(" history=").append(recentHistoryItems.joinToString(limit = 6) { "${it.groupKey}:${it.content.id}:${it.counter}:${it.progress?.percent}" })
        append(" updates=").append(recentUpdates.joinToString(limit = 6) { "${it.groupKey}:${it.content.id}:${it.newChapters}:${it.counter}:${it.progress?.percent}" })
        append(" recommendations=").append(recommendations.joinToString(limit = 6) { "${it.groupKey}:${it.content.id}:${it.counter}:${it.progress?.percent}" })
        append(" searches=").append(recentSearches.joinToString(limit = 4) { it.query })
        append(" tags=").append(selectedSourceTags.homeTagSignature())
    }
}

private fun List<Content>.homeContentSignature(): String {
    return joinToString(limit = 6) { "${it.id}:${it.coverUrl}" }
}

private fun List<ContentTracking>.homeUpdateSignature(): String {
    return joinToString(limit = 6) {
        "${it.anchorMangaId}:${it.entityId}:${it.preferredLocalMangaId}:${it.manga.id}:${it.newChapters}:${it.manga.coverUrl}"
    }
}

private fun Set<org.skepsun.kototoro.explore.ui.model.SourceTag>.homeTagSignature(): String {
    return map { it.id }
        .sorted()
        .joinToString()
}

private suspend fun buildDisplayContentOverrides(
    resumeContent: Content?,
    history: List<Content>,
    updates: List<ContentTracking>,
    recommendations: List<Content>,
    contentDataRepository: ContentDataRepository,
    trackingSiteCacheRepository: TrackingSiteCacheRepository,
): Map<Long, ContentOverride> {
    val contents = buildList {
        resumeContent?.let(::add)
        addAll(history)
        addAll(updates.map { it.manga })
        addAll(recommendations)
    }.distinctBy { it.id }
    if (contents.isEmpty()) return emptyMap()

    val manualOverrides = contentDataRepository.getOverrides()
    val metadataSelections = contentDataRepository.getMetadataSourceSelections(contents.map(Content::id))
    val trackingDetailsCache = HashMap<Pair<Int, Long>, TrackingSiteItemDetails?>(contents.size)
    return buildMap(contents.size) {
        contents.forEach { content ->
            val override = resolveDisplayOverride(
                content = content,
                manualOverride = manualOverrides[content.id],
                metadataSelection = metadataSelections[content.id],
                trackingDetailsCache = trackingDetailsCache,
                trackingSiteCacheRepository = trackingSiteCacheRepository,
            ) ?: return@forEach
            put(content.id, override)
        }
    }
}

private suspend fun resolveDisplayOverride(
    content: Content,
    manualOverride: ContentOverride?,
    metadataSelection: ContentDataRepository.MetadataSourceSelection?,
    trackingDetailsCache: MutableMap<Pair<Int, Long>, TrackingSiteItemDetails?>,
    trackingSiteCacheRepository: TrackingSiteCacheRepository,
): ContentOverride? {
    val trackingOverride = (metadataSelection as? ContentDataRepository.MetadataSourceSelection.Tracking)
        ?.let { trackingSelection ->
            val service = ScrobblerService.entries.firstOrNull { it.id == trackingSelection.serviceId }
                ?: return@let null
            val cacheKey = trackingSelection.serviceId to trackingSelection.remoteId
            val details = trackingDetailsCache.getOrPut(cacheKey) {
                trackingSiteCacheRepository.readDetails(service, trackingSelection.remoteId)
            }
            ContentOverride(
                coverUrl = details?.coverUrl?.takeIf { it.isNotBlank() },
                title = details?.title?.takeIf { it.isNotBlank() },
                contentRating = null,
            )
        }
    val merged = ContentOverride(
        coverUrl = manualOverride?.coverUrl ?: trackingOverride?.coverUrl,
        title = manualOverride?.title ?: trackingOverride?.title,
        contentRating = manualOverride?.contentRating,
    )
    return if (
        merged.coverUrl == null &&
        merged.title == null &&
        merged.contentRating == null
    ) {
        null
    } else {
        merged
    }
}

private fun Long.toHomeGroupKey(contentTypeOrdinal: Int): Long = -((this shl 8) or (contentTypeOrdinal + 1).toLong())

private fun ContentType.toHomeTab(): HomeContentTab? = when (this) {
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

private fun org.skepsun.kototoro.core.model.ContentHistory?.toProgressPercent(): Int? {
    val percent = this?.percent ?: return null
    return (percent * 100f).toInt().coerceIn(0, 100)
}
