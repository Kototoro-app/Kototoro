package org.skepsun.kototoro.tracker.ui.feed

import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.paging.BatchMappingPagingSource
import org.skepsun.kototoro.core.paging.LargeLibraryPagingConfig
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.groupByDateBucket
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.domain.GlobalFavoritesState
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.RetainedPagingSnapshot
import org.skepsun.kototoro.list.ui.RetainedPagingSnapshotHost
import org.skepsun.kototoro.list.ui.RetainedPagingSnapshotStore
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.tracker.domain.UpdatesListQuickFilter
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import org.skepsun.kototoro.tracker.domain.model.TrackingLogItem
import org.skepsun.kototoro.tracker.ui.feed.model.FeedItem
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeader
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeaderItem
import org.skepsun.kototoro.tracker.work.TrackWorker
import org.skepsun.kototoro.core.prefs.TriStateOption
import org.skepsun.kototoro.download.ui.worker.DownloadTask
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.download.ui.worker.ExecutionChapterRef
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.scopedToSpace

private const val UPDATED_CONTENT_LOOKAHEAD_SIZE = 200

internal fun observeFeedCategoryIdsForSelection(
    selectedCategoryId: Flow<Long>,
    observeCategoryIds: () -> Flow<Map<String, Set<Long>>>,
): Flow<Map<String, Set<Long>>> = selectedCategoryId.flatMapLatest { categoryId ->
    if (categoryId == NO_ID) {
        flowOf(emptyMap())
    } else {
        observeCategoryIds()
    }
}

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val settings: AppSettings,
    private val repository: TrackingRepository,
    private val scheduler: TrackWorker.Scheduler,
    private val mangaListMapper: ContentListMapper,
    private val quickFilter: UpdatesListQuickFilter,
    private val sourceGroupManager: SourceGroupManager,
    private val favouritesRepository: FavouritesRepository,
    private val globalFavoritesState: GlobalFavoritesState,
    private val sourcePresetsRepository: org.skepsun.kototoro.explore.data.SourcePresetsRepository,
    private val dataRepository: ContentDataRepository,
    private val workResolver: WorkResolver,
    spaceBrowseScope: SpaceBrowseScope,
) : BaseViewModel(), QuickFilterListener by quickFilter, SpaceBindableViewModel,
    RetainedPagingSnapshotHost {
    private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)

    private val retainedPagingSnapshotStore = RetainedPagingSnapshotStore()

    override fun retainPagingSnapshot(snapshot: RetainedPagingSnapshot) =
        retainedPagingSnapshotStore.retainPagingSnapshot(snapshot)

    override fun peekRetainedPagingSnapshot(): RetainedPagingSnapshot? =
        retainedPagingSnapshotStore.peekRetainedPagingSnapshot()

    override fun clearRetainedPagingSnapshot(generation: Long) =
        retainedPagingSnapshotStore.clearRetainedPagingSnapshot(generation)

    private data class FeedScopeParams(
        val categoryId: Long,
        val groupTab: BrowseGroupTab,
        val sourceTags: Set<SourceTag>,
        val mangaCategoryIds: Map<String, Set<Long>>,
        val preset: org.skepsun.kototoro.explore.data.SourcePreset?,
    )

    private data class FeedPagingParams(
        val showAll: Boolean,
        val limit: Int,
        val filters: Set<ListFilterOption>,
        val scope: FeedScopeParams,
    )

    private val feedLimitFlow = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_FEED_LIMIT,
        valueProducer = { feedLimit },
    )

    private val selectedCategoryId = MutableStateFlow(NO_ID)

    val categories = favouritesRepository.observeCategoriesForLibrary()
        .map { listOf(FavouriteCategory(id = NO_ID, title = "", sortKey = Int.MIN_VALUE, order = org.skepsun.kototoro.list.domain.ListSortOrder.NEWEST, createdAt = java.time.Instant.EPOCH, isTrackingEnabled = false, isVisibleInLibrary = true)) + it }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    val currentCategoryId = selectedCategoryId
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, NO_ID)

    val currentGroupTab = globalFavoritesState.selectedGroupTab.scopedToSpace(
        spaceGroupTab = spaceBinding.groupTab,
        coroutineScope = viewModelScope + Dispatchers.Default,
    )
    override fun bindSpace(spaceId: org.skepsun.kototoro.space.domain.SpaceId?) = spaceBinding.bindSpace(spaceId)
    val currentSourceTags = globalFavoritesState.selectedSourceTags

    private val feedCategoryIds = observeFeedCategoryIdsForSelection(selectedCategoryId) {
        favouritesRepository.observeFeedCategoryIds()
    }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyMap())

    private val activeSourcePreset = settings.observeAsFlow(
        AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID,
    ) { activeSourcePresetId }
        .flatMapLatest { id ->
            if (id == -1L) flowOf(null) else sourcePresetsRepository.observe(id)
        }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    private val feedScope = combine(
        selectedCategoryId,
        currentGroupTab,
        currentSourceTags,
        feedCategoryIds,
        activeSourcePreset,
    ) { categoryId, groupTab, sourceTags, mangaCategoryIds, preset ->
        FeedScopeParams(
            categoryId = categoryId,
            groupTab = groupTab,
            sourceTags = sourceTags,
            mangaCategoryIds = mangaCategoryIds,
            preset = preset,
        )
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.Eagerly,
        FeedScopeParams(NO_ID, BrowseGroupTab.All, emptySet(), emptyMap(), null),
    )

    private val workerRunning = scheduler.observeIsRunning()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

    private val manualRefreshRequested = MutableStateFlow(false)

    val isRefreshing = combine(workerRunning, manualRefreshRequested) { running, requested ->
        running && requested
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

    val isHeaderEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_FEED_HEADER,
        valueProducer = { isFeedHeaderVisible },
    )

    sealed class DownloadPrompt {
        data class MultipleUpdates(
            val manga: Content,
            val lastChapterId: Long,
            val allNewChaptersIds: LongArray,
        ) : DownloadPrompt() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is MultipleUpdates) return false
                if (manga != other.manga) return false
                if (lastChapterId != other.lastChapterId) return false
                return allNewChaptersIds contentEquals other.allNewChaptersIds
            }

            override fun hashCode(): Int {
                var result = manga.hashCode()
                result = 31 * result + lastChapterId.hashCode()
                result = 31 * result + allNewChaptersIds.contentHashCode()
                return result
            }
        }

        data class NoReadHistory(
            val manga: Content,
            val lastChapterId: Long,
            val allChaptersIds: LongArray,
        ) : DownloadPrompt() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is NoReadHistory) return false
                if (manga != other.manga) return false
                if (lastChapterId != other.lastChapterId) return false
                return allChaptersIds contentEquals other.allChaptersIds
            }

            override fun hashCode(): Int {
                var result = manga.hashCode()
                result = 31 * result + lastChapterId.hashCode()
                result = 31 * result + allChaptersIds.contentHashCode()
                return result
            }
        }
    }

    data class DeleteChapterPrompt(
        val manga: Content,
        val chapterId: Long,
        val chapterTitle: String,
    )

    val showAllUpdates = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_SHOW_ALL_UPDATES,
        valueProducer = { showAllUpdates },
    )

    private val feedFilters = quickFilter.appliedOptions.combineWithSettings()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptySet())

    private val updatedContent = feedFilters
        .flatMapLatest { repository.observeUpdatedContent(UPDATED_CONTENT_LOOKAHEAD_SIZE, it) }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())
    val onActionDone = MutableEventFlow<ReversibleAction>()

    val pagingContent: Flow<PagingData<ListModel>> = combine(
        showAllUpdates,
        feedLimitFlow,
        feedFilters,
        feedScope,
        mangaListMapper.observeDisplayChanges().onStart { emit(Unit) },
    ) { showAll, limit, filters, scope, _ ->
        FeedPagingParams(showAll, limit, filters, scope)
    }.flatMapLatest { params ->
        Pager(
            config = LargeLibraryPagingConfig,
            pagingSourceFactory = {
                val source = if (params.showAll) {
                    repository.createAllTrackingLogItemsPagingSource(params.limit, params.filters)
                } else {
                    repository.createTrackingLogPagingSource(params.limit, params.filters)
                }
                BatchMappingPagingSource(
                    delegate = source,
                    diagnosticLabel = "feed-ui",
                ) { items -> mapFeedPage(items, params.scope) }
            },
        ).flow.map { pagingData -> pagingData.applyFeedPagingPresentation() }
    }.cachedIn(viewModelScope)

    val leadingContent = combine(observeHeader(), quickFilter.appliedOptions) { header, filters ->
        buildList<ListModel> {
            quickFilter.filterItem(filters)?.let(::add)
            header?.let(::add)
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    val fallbackContent = combine(
        updatedContent,
        feedScope,
        mangaListMapper.observeDisplayChanges().onStart { emit(Unit) },
    ) { items, scope, _ -> items to scope }
        .mapLatest { (items, scope) ->
            val logs = items.asSequence()
                .filter { item -> item.manga.matchesFeedScope(scope) }
                .map { item -> item.toFallbackTrackingLogItem() }
                .toList()
            buildStaticFeedContent(logs, scope)
        }
        .stateIn(
            viewModelScope + Dispatchers.Default,
            SharingStarted.Eagerly,
            listOf(LoadingState),
        )

    private fun ContentTracking.toFallbackTrackingLogItem(): TrackingLogItem {
        return TrackingLogItem(
            id = -anchorMangaId,
            anchorMangaId = anchorMangaId,
            entityId = entityId,
            preferredLocalMangaId = preferredLocalMangaId,
            manga = manga,
            chapters = List(newChapters.coerceAtLeast(1)) { "" },
            createdAt = lastChapterDate ?: lastCheck ?: java.time.Instant.EPOCH,
            isNew = newChapters > 0,
            count = newChapters,
        )
    }

    init {
        launchJob(Dispatchers.Default) {
            repository.gcIfNeeded()
        }
        launchJob(Dispatchers.Default) {
            workerRunning.collect { running ->
                if (!running) {
                    manualRefreshRequested.value = false
                }
            }
        }
    }

    fun clearFeed(clearCounters: Boolean) {
        launchLoadingJob(Dispatchers.Default) {
            repository.clearLogs()
            if (clearCounters) {
                repository.clearCounters()
            }
            onActionDone.call(ReversibleAction(R.string.updates_feed_cleared, null))
        }
    }

    fun update() {
        manualRefreshRequested.value = true
        scheduler.startNow()
    }

    fun setHeaderEnabled(value: Boolean) {
        settings.isFeedHeaderVisible = value
    }

    fun setShowAllUpdates(value: Boolean) {
        settings.showAllUpdates = value
    }

    fun onItemClick(item: FeedItem) {
        launchJob(Dispatchers.Default, CoroutineStart.ATOMIC) {
            if (item.id > 0L) {
                repository.markAsRead(item.id)
            }
        }
    }

    fun markAsRead(ids: Collection<Long>) {
        if (ids.isEmpty()) {
            return
        }
        launchJob(Dispatchers.Default) {
            for (id in ids) {
                if (id > 0L) {
                    repository.markAsRead(id)
                }
            }
        }
    }

    fun selectCategory(categoryId: Long) {
        selectedCategoryId.value = categoryId
    }

    fun setSelectedGroupTab(tab: BrowseGroupTab) {
        globalFavoritesState.setSelectedGroupTab(tab)
    }

    fun setSelectedSourceTags(tags: Set<SourceTag>) {
        globalFavoritesState.setSelectedSourceTags(tags)
    }

    fun toggleSourceTag(tag: SourceTag) {
        globalFavoritesState.toggleSourceTag(tag)
    }

    private suspend fun mapFeedPage(
        items: List<TrackingLogItem>,
        scope: FeedScopeParams,
    ): List<FeedItem> {
        val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
        val visibleItems = items.filter { item ->
            item.manga.matchesFeedScope(scope) && item.manga !in globalTagBlacklist
        }
        return mangaListMapper.toFeedItems(visibleItems)
    }

    private suspend fun buildStaticFeedContent(
        items: List<TrackingLogItem>,
        scope: FeedScopeParams,
    ): List<ListModel> {
        val feedItems = mapFeedPage(items, scope)
        if (feedItems.isEmpty()) {
            return listOf(
                EmptyState(
                    icon = R.drawable.ic_empty_feed,
                    textPrimary = R.string.text_empty_holder_primary,
                    textSecondary = R.string.text_feed_holder,
                    actionStringRes = 0,
                ),
            )
        }
        val result = ArrayList<ListModel>((feedItems.size * 1.4).toInt().coerceAtLeast(1))
        val bucketedItems = feedItems.groupByDateBucket(FeedItem::createdAt)
        for ((date, items) in bucketedItems) {
            result += if (date != null) {
                ListHeader(date)
            } else {
                ListHeader(R.string.unknown)
            }
            for (feedItem in items) {
                result += feedItem
            }
        }
        return result
    }

    private fun observeHeader() = combine(
        isHeaderEnabled,
        feedScope,
        mangaListMapper.observeDisplayChanges().onStart { emit(Unit) },
    ) { enabled, scope, _ -> enabled to scope }
        .flatMapLatest { (enabled, scope) ->
            if (enabled) {
                updatedContent.map { mangaList ->
                    val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
                    val filteredContentList = mangaList.filter { item ->
                        item.manga !in globalTagBlacklist && item.manga.matchesFeedScope(scope)
                    }
                    if (filteredContentList.isEmpty()) {
                        null
                    } else {
                        buildUpdatedContentHeader(filteredContentList)
                    }
                }
            } else {
                flowOf(null)
            }
        }

    private fun Content.matchesFeedScope(scope: FeedScopeParams): Boolean {
        val contentSource = source
        if (scope.preset != null && contentSource.name !in scope.preset.sources) {
            return false
        }
        val contentGroup = sourceGroupManager.getContentGroup(contentSource)
        val originGroup = sourceGroupManager.getOriginGroup(contentSource)
        val matchesCategory = scope.categoryId == NO_ID ||
            scope.categoryId in scope.mangaCategoryIds[feedLookupKey()].orEmpty()
        val matchesGroup = scope.groupTab.matchesContentGroup(contentGroup)
        val matchesSourceTag = scope.sourceTags.isEmpty() ||
            scope.sourceTags.any { it.matches(contentGroup, originGroup) }
        return matchesCategory && matchesGroup && matchesSourceTag
    }

    private suspend fun buildUpdatedContentHeader(items: List<ContentTracking>): UpdatedContentHeader {
        val groupedList = items.aggregateFeedUpdatesByEntity()
        return UpdatedContentHeader(
            list = groupedList.map { group ->
                UpdatedContentHeaderItem(
                    model = mangaListMapper.toListModel(
                        manga = group.representative.manga,
                        mode = ListMode.GRID,
                        metadataSelectionOverride = group.metadataSourceSelection,
                        useMetadataSelectionOverride = group.metadataSourceSelection != null,
                    ),
                    groupKey = group.groupKey,
                    entityId = group.entityId,
                    preferredLocalMangaId = group.preferredLocalMangaId,
                    totalNewChapters = group.totalNewChapters,
                )
            },
        )
    }

    private suspend fun List<ContentTracking>.aggregateFeedUpdatesByEntity(): List<FeedUpdateGroup> {
        if (isEmpty()) {
            return emptyList()
        }
        val resolvedEntityIds = mapNotNull(ContentTracking::entityId).distinct()
        val preferredLocalIdsByEntity = resolvePreferredLocalIdsByEntity(resolvedEntityIds)
        val metadataSelectionsByEntity = dataRepository.getEntityMetadataSourceSelections(resolvedEntityIds)
        val grouped = LinkedHashMap<Long, MutableList<ContentTracking>>()
        for (item in this) {
            val contentTypeOrdinal = item.manga.source.getContentType().ordinal
            val groupKey = item.entityId?.toFeedGroupKey(contentTypeOrdinal) ?: item.manga.id
            grouped.getOrPut(groupKey) { ArrayList(1) }.add(item)
        }
        return grouped.map { (groupKey, groupItems) ->
            val entityId = groupItems.firstNotNullOfOrNull(ContentTracking::entityId)
            val preferredLocalId = entityId?.let(preferredLocalIdsByEntity::get)
                ?: groupItems.firstNotNullOfOrNull(ContentTracking::preferredLocalMangaId)
            val representative = groupItems.firstOrNull { it.manga.id == preferredLocalId }
                ?: groupItems.maxWithOrNull(
                    compareBy<ContentTracking>(
                        { it.lastChapterDate ?: java.time.Instant.EPOCH },
                        { it.lastCheck ?: java.time.Instant.EPOCH },
                        { it.newChapters },
                    ),
                )
                ?: groupItems.first()
            FeedUpdateGroup(
                groupKey = groupKey,
                representative = representative,
                totalNewChapters = groupItems.sumOf { it.newChapters },
                entityId = entityId,
                preferredLocalMangaId = preferredLocalId ?: representative.manga.id,
                metadataSourceSelection = entityId?.let(metadataSelectionsByEntity::get),
            )
        }
    }

    private suspend fun resolvePreferredLocalIdsByEntity(entityIds: Collection<Long>): Map<Long, Long?> {
        return workResolver.resolveManyByEntityIds(entityIds)
            .mapValues { (_, identity) -> identity.preferredMangaId }
    }

    private fun Long.toFeedGroupKey(contentTypeOrdinal: Int): Long = -((this shl 8) or (contentTypeOrdinal + 1).toLong())

    private data class FeedUpdateGroup(
        val groupKey: Long,
        val representative: ContentTracking,
        val totalNewChapters: Int,
        val entityId: Long?,
        val preferredLocalMangaId: Long?,
        val metadataSourceSelection: ContentDataRepository.MetadataSourceSelection?,
    )

    private fun Flow<Set<ListFilterOption>>.combineWithSettings(): Flow<Set<ListFilterOption>> {
        val skipNsfwInFeed = combine(
            settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
            settings.observeAsFlow(AppSettings.KEY_FEED_EXCLUDE_NSFW) { isFeedExcludeNsfw },
        ) { skipNsfwGlobally, skipNsfwInFeed ->
            skipNsfwGlobally || skipNsfwInFeed
        }
        return combine(skipNsfwInFeed) { filters, skipNsfw ->
            if (skipNsfw) {
                filters + ListFilterOption.SFW
            } else {
                filters
            }
        }
    }
}

private fun Content.feedLookupKey(): String {
    return "${source.name}|$url"
}
