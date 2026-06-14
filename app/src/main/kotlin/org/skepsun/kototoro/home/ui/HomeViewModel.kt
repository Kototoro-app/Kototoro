package org.skepsun.kototoro.home.ui

import android.content.Context
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.data.BackupRepository
import org.skepsun.kototoro.backups.domain.BackupWebDavRestoreCoordinator
import org.skepsun.kototoro.backups.domain.BackupWebDavUploadCoordinator
import org.skepsun.kototoro.backups.domain.ExternalBackupStorage
import org.skepsun.kototoro.backups.ui.periodical.RemoteNamespace
import org.skepsun.kototoro.backups.ui.periodical.WebDavBackupUploader
import org.skepsun.kototoro.core.jsonsource.OriginGroup
import org.skepsun.kototoro.core.jsonsource.SourceGroup
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
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.search.domain.ContentSearchRepository
import org.skepsun.kototoro.suggestions.domain.SuggestionRepository
import org.skepsun.kototoro.tracking.discovery.data.TrackingSiteCacheRepository
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItemDetails
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import javax.inject.Inject

@Immutable
data class HomeRecentItem(
    val content: Content,
    val groupKey: Long = content.id,
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
) {
    val title: String
        get() = content.title
}

@Immutable
data class HomeRecommendationItem(
    val content: Content,
    val groupKey: Long = content.id,
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
data class HomeSyncState(
    val isWebDavEnabled: Boolean = false,
    val isAutoSyncEnabled: Boolean = false,
    val lastUploadTime: Long = 0L,
    val lastUploadKind: String? = null,
    val isLegacyRestoreUploadBlocked: Boolean = false,
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
    val syncState: HomeSyncState = HomeSyncState(),
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
    @ApplicationContext private val appContext: Context,
) : BaseViewModel() {

    val onActionDone = MutableEventFlow<ReversibleAction>()

    private val selectedTabFlow = globalFavoritesState.selectedGroupTab.map { tabId ->
        when (tabId) {
            org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Content -> HomeContentTab.MANGA
            org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Novel -> HomeContentTab.NOVEL
            org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Video -> HomeContentTab.VIDEO
            else -> null
        }
    }
    private val selectedSourceTagsFlow = globalFavoritesState.selectedSourceTags
    private val activePresetFlow = settings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId }
        .flatMapLatest { id ->
            if (id == -1L) flowOf(null) else sourcePresetsRepository.observe(id)
        }
    private val recentHistoryFlow = historyRepository.observeAll()
    private val isHistoryNsfwDisabledFlow = settings.observeAsFlow(AppSettings.KEY_HISTORY_EXCLUDE_NSFW) { isHistoryExcludeNsfw }
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
                flow {
                    val entityId = entityGraphRepository.findEntityIdsByLocalMangaIds(setOf(content.id))[content.id]
                    val preferredLocalMangaId = if (entityId != null) {
                        contentDataRepository.getEntityPreferredLocalMangaId(entityId)
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
                    emit(Triple(representativeContent, entityId, preferredLocalMangaId))
                }.flatMapLatest { (representativeContent, entityId, preferredLocalMangaId) ->
                    historyRepository.observeOne(preferredLocalMangaId ?: representativeContent.id).map { history ->
                        HomeResumeState(
                            content = representativeContent,
                            progressPercent = history.toProgressPercent(),
                            entityId = entityId,
                            preferredLocalMangaId = preferredLocalMangaId ?: representativeContent.id,
                        )
                    }
                }
            }
        }
    private val favoritesCountFlow = favouritesRepository.observeContentCount()
    private val favoriteCategoriesCountFlow = favouritesRepository.observeCategories().map { it.size }
    private val unreadUpdatesCountFlow = trackingRepository.observeUnreadUpdatesCount()
    private val recentUpdatesFlow = trackingRepository.observeUpdatedContent(
        limit = Int.MAX_VALUE,
        filterOptions = emptySet(),
    )
    private val recommendationsFlow = suggestionRepository.observeAll()
    private val recentSearchesFlow = contentSearchRepository.observeRecentQueries(HOME_COVER_PREVIEW_LIMIT)
    private val displayChangesFlow = combine(
        contentDataRepository.observeDisplayPreferencesChanges(),
        trackingSiteCacheRepository.observeDetailsUpdates().onStart { emit(0L) },
    ) { _, _ -> Unit }.onStart { emit(Unit) }
    private val isTrackerNsfwDisabledFlow = settings.observeAsFlow(AppSettings.KEY_TRACKER_NO_NSFW) { isTrackerNsfwDisabled }
    private val isSuggestionNsfwDisabledFlow = settings.observeAsFlow(AppSettings.KEY_SUGGESTIONS_EXCLUDE_NSFW) { isSuggestionsExcludeNsfw }
    private val enabledSourcesCountFlow = contentSourcesRepository.observeEnabledSourcesCount()
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
    private val syncStateFlow = settings.observe(
        AppSettings.KEY_BACKUP_WEBDAV_ENABLED,
        AppSettings.KEY_BACKUP_WEBDAV_AUTO_SYNC,
        AppSettings.KEY_BACKUP_WEBDAV_LAST_UPLOAD_TIME,
        AppSettings.KEY_BACKUP_WEBDAV_LAST_UPLOAD_KIND,
        AppSettings.KEY_BACKUP_WEBDAV_BLOCK_AUTO_UPLOAD_AFTER_LEGACY_RESTORE,
    ).map {
        HomeSyncState(
            isWebDavEnabled = settings.isBackupWebDavUploadEnabled,
            isAutoSyncEnabled = settings.isBackupWebDavAutoSyncEnabled,
            lastUploadTime = settings.backupWebDavLastUploadTime,
            lastUploadKind = settings.backupWebDavLastUploadKind,
            isLegacyRestoreUploadBlocked = settings.isBackupWebDavAutoUploadBlockedByLegacyRestore,
        )
    }

    private val contentDataFlow = combine(
        resumeStateFlow,
        recentHistoryFlow,
        recentUpdatesFlow,
        recommendationsFlow,
        isHistoryNsfwDisabledFlow,
        isTrackerNsfwDisabledFlow,
    ) { values ->
        val resumeState = values[0] as HomeResumeState
        val recentHistory = values[1] as List<Content>
        val recentUpdates = values[2] as List<ContentTracking>
        val recommendations = values[3] as List<Content>
        val isHistoryNsfwDisabled = values[4] as Boolean
        val isTrackerNsfwDisabled = values[5] as Boolean
        val filteredHistory = if (isHistoryNsfwDisabled) recentHistory.filterNot { it.isNsfw() } else recentHistory
        val filteredUpdates = if (isTrackerNsfwDisabled) recentUpdates.filterNot { it.manga.isNsfw() } else recentUpdates
        ContentDataSnapshot(resumeState, filteredHistory, filteredUpdates, recommendations)
    }

    private val entityIdsFlow = contentDataFlow.map { snapshot ->
        buildSet {
            addAll(snapshot.history.map { it.id })
            addAll(snapshot.updates.map { it.manga.id })
            addAll(snapshot.recommendations.map { it.id })
        }
    }.distinctUntilChanged().map { ids ->
        entityGraphRepository.findEntityIdsByLocalMangaIds(ids)
    }

    private val metaFlow = combine(
        favoritesCountFlow,
        favoriteCategoriesCountFlow,
        enabledSourcesCountFlow,
        sourceBreakdownFlow,
        syncStateFlow,
    ) { favoritesCount, favoriteCategoriesCount, enabledSourcesCount, sourceBreakdown, syncState ->
        HomeMetaSnapshot(favoritesCount, favoriteCategoriesCount, enabledSourcesCount, sourceBreakdown, syncState)
    }

    val summaryState = combine(
        selectedTabFlow,
        combine(selectedSourceTagsFlow, activePresetFlow, ::Pair),
        contentDataFlow,
        entityIdsFlow,
        metaFlow,
        combine(
            unreadUpdatesCountFlow,
            recentSearchesFlow,
            isSuggestionNsfwDisabledFlow,
            displayChangesFlow,
        ) { _, recentSearches, isSuggestionNsfwDisabled, _ ->
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

        val allRecommendations = if (isSuggestionNsfwDisabled) contentData.recommendations.filterNot { it.isNsfw() } else contentData.recommendations
        val preferredLocalIdsByEntity = contentDataRepository.getEntityPreferredLocalMangaIds(entityIdsByMangaId.values)
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
            .aggregateHomeContentByEntity(entityIdsByMangaId, preferredLocalIdsByEntity)
            .selectHomeHistoryByTab(selectedTab, selectedSourceTags, sourceGroupManager, preset)
        val recentUpdates = contentData.updates
            .map { tracking ->
                val override = displayContentOverrides[tracking.manga.id]
                if (override == null) tracking else tracking.copy(manga = tracking.manga.withOverride(override))
            }
            .aggregateHomeUpdatesByEntity(entityIdsByMangaId, preferredLocalIdsByEntity)
            .selectHomeUpdatesByTab(selectedTab, selectedSourceTags, sourceGroupManager, preset)
        val recommendations = allRecommendations
            .map { content -> content.withOverride(displayContentOverrides[content.id]) }
            .aggregateHomeRecommendationsByEntity(entityIdsByMangaId, preferredLocalIdsByEntity)
            .selectHomeRecommendationsByTab(selectedTab, selectedSourceTags, sourceGroupManager, preset)

        HomeSummaryState(
            selectedTab = selectedTab,
            recentHistoryCount = recentHistory.size,
            recentHistoryItems = recentHistory,
            resumeState = resumeState,
            favoritesCount = meta.favoritesCount,
            favoriteCategoriesCount = meta.favoriteCategoriesCount,
            unreadUpdatesCount = recentUpdates.size,
            recentUpdates = recentUpdates,
            recommendationsCount = recommendations.size,
            recommendations = recommendations,
            recentSearches = recentSearches.map { HomeRecentSearchItem(it) },
            enabledSourcesCount = meta.enabledSourcesCount,
            sourceBreakdown = meta.sourceBreakdown,
            syncState = meta.syncState,
            selectedSourceTags = selectedSourceTags,
            isInitialized = true,
        )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeSummaryState(
            selectedTab = when (globalFavoritesState.selectedGroupTab.value) {
                org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Content -> HomeContentTab.MANGA
                org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Novel -> HomeContentTab.NOVEL
                org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Video -> HomeContentTab.VIDEO
                else -> null
            },
            selectedSourceTags = globalFavoritesState.selectedSourceTags.value,
        ),
    )

    fun setSelectedTab(tab: HomeContentTab?) {
        val groupTab = when (tab) {
            HomeContentTab.MANGA -> org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Content
            HomeContentTab.NOVEL -> org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Novel
            HomeContentTab.VIDEO -> org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.Video
            null -> org.skepsun.kototoro.explore.ui.model.BrowseGroupTab.All
        }
        globalFavoritesState.setSelectedGroupTab(groupTab)
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
        viewModelScope.launch(Dispatchers.Default) {
            isRandomLoading.value = true
            try {
                val manga = exploreRepository.findRandomContent(tagsLimit = 8)
                val entityId = entityGraphRepository.findEntityIdsByLocalMangaIds(setOf(manga.id))[manga.id]
                val preferredLocalMangaId = if (entityId != null) {
                    contentDataRepository.getEntityPreferredLocalMangaId(entityId)
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
            val entityId = entityGraphRepository.findEntityIdsByLocalMangaIds(setOf(content.id))[content.id]
            val preferredLocalMangaId = if (entityId != null) {
                contentDataRepository.getEntityPreferredLocalMangaId(entityId)
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
                val latest = webDavUploader.getLatestBackup(RemoteNamespace.V3)
                    ?: webDavUploader.getLatestBackup(RemoteNamespace.V2)
                    ?: webDavUploader.getLatestBackup(RemoteNamespace.V1)
                    ?: run {
                        errorEvent.call(IllegalStateException("No WebDAV backups found"))
                        return@launch
                    }
                val tempFile = java.io.File.createTempFile("webdav_backup_manual", ".bk.zip", appContext.cacheDir)
                try {
                    webDavUploader.downloadBackup(latest.name, tempFile, latest.namespace)
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
                        org.skepsun.kototoro.backups.domain.BackupSection.SOURCES,
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
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
            } catch (e: Exception) {
                e.printStackTraceDebug()
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
    val updates: List<ContentTracking>,
    val recommendations: List<Content>,
)

private data class HomeMetaSnapshot(
    val favoritesCount: Int,
    val favoriteCategoriesCount: Int,
    val enabledSourcesCount: Int,
    val sourceBreakdown: List<HomeSourceBreakdown>,
    val syncState: HomeSyncState,
)

private fun HomeSourceOrigin.toBreakdown(count: Int?): HomeSourceBreakdown? {
    if (count == null || count <= 0) return null
    return HomeSourceBreakdown(origin = this, count = count)
}

private fun Map<SourceGroup, Int>.countOf(key: OriginGroup): Int? {
    return this[SourceGroup.Origin(key)]
}

private fun Content.contentTab(): HomeContentTab? = source.getContentType().toHomeTab()

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
        result += HomeRecentItem(content = representative, groupKey = groupKey)
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
        result += HomeRecommendationItem(content = representative, groupKey = groupKey)
    }
    return result
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
