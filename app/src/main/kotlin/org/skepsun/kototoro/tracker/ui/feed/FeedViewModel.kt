package org.skepsun.kototoro.tracker.ui.feed

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.paging.PagingData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
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
import org.skepsun.kototoro.tracker.work.UpdateCheckRequest
import org.skepsun.kototoro.tracker.work.messageRes
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

internal fun observeFeedHeaderContent(
    isHeaderEnabled: Flow<Boolean>,
    filters: Flow<Set<ListFilterOption>>,
    observeUpdatedContent: (Set<ListFilterOption>) -> Flow<List<ContentTracking>>,
): Flow<List<ContentTracking>> = isHeaderEnabled.flatMapLatest { enabled ->
    if (enabled) {
        filters.flatMapLatest(observeUpdatedContent)
    } else {
        flowOf(emptyList())
    }
}

@HiltViewModel
class FeedViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
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
    private val feedSnapshotStore: org.skepsun.kototoro.tracker.domain.feed.FeedSnapshotStore,
    private val feedCardMapper: org.skepsun.kototoro.tracker.domain.feed.FeedCardMapper,
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

    private val updatedContent = observeFeedHeaderContent(isHeaderEnabled, feedFilters) { filters ->
        repository.observeUpdatedContent(UPDATED_CONTENT_LOOKAHEAD_SIZE, filters)
    }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())
    val onActionDone = MutableEventFlow<ReversibleAction>()
    val onMessage = MutableEventFlow<String>()

    private val excludedNsfw = combine(
        settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
        settings.observeAsFlow(AppSettings.KEY_FEED_EXCLUDE_NSFW) { isFeedExcludeNsfw },
    ) { skipNsfwGlobally, skipNsfwInFeed -> skipNsfwGlobally || skipNsfwInFeed }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    private val tagBlacklistFlow = settings.observeAsFlow(AppSettings.KEY_GLOBAL_TAG_BLACKLIST) {
        GlobalTagBlacklist(settings.globalTagBlacklist)
    }

    /**
     * The one and only feed list (history-updates-feed komikku-alignment Phase F4):
     * snapshot -> in-memory derivation -> card mapping -> date buckets. The paging
     * chain (two Pager sources + per-page display resolution + insertSeparators) is
     * replaced by this single re-derivation; changing the limit, showAll, scope or
     * filters never re-queries the database.
     */
    val content: StateFlow<List<ListModel>> = combine(
        feedSnapshotStore.observe(),
        showAllUpdates,
        feedLimitFlow,
        quickFilter.appliedOptions,
        feedScope,
        excludedNsfw,
        tagBlacklistFlow,
        mangaListMapper.observeDisplayChanges().onStart { emit(Unit) },
    ) { values: Array<Any?> ->
        buildFeedContent(
            snapshot = values[0] as org.skepsun.kototoro.tracker.domain.feed.FeedSnapshot,
            showAll = values[1] as Boolean,
            limit = values[2] as Int,
            filters = values[3] as Set<ListFilterOption>,
            scope = values[4] as FeedScopeParams,
            skipNsfw = values[5] as Boolean,
            tagBlacklist = values[6] as GlobalTagBlacklist,
        )
    }.stateIn(
        viewModelScope + Dispatchers.Default,
        SharingStarted.Eagerly,
        listOf(LoadingState),
    )

    /** The paging chain is gone; static [content] is the only feed list. */
    val pagingContent: Flow<PagingData<ListModel>>? = null

    val leadingContent = combine(
        quickFilter.appliedOptions,
        // Re-emit when the quick-filter visibility toggle changes so filterItem()
        // re-evaluates against the fresh setting (hides/shows the inline bar).
        settings.observeAsFlow(AppSettings.KEY_QUICK_FILTER) { isQuickFilterEnabled },
    ) { filters, _ -> filters }
        .combine(observeHeader()) { filters, header ->
            buildList<ListModel> {
                quickFilter.filterItem(filters)?.let(::add)
                header?.let(::add)
            }
        }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    private fun buildFeedContent(
        snapshot: org.skepsun.kototoro.tracker.domain.feed.FeedSnapshot,
        showAll: Boolean,
        limit: Int,
        filters: Set<ListFilterOption>,
        scope: FeedScopeParams,
        skipNsfw: Boolean,
        tagBlacklist: GlobalTagBlacklist,
    ): List<ListModel> {
        val derived = org.skepsun.kototoro.tracker.domain.feed.FeedDeriver.derive(
            org.skepsun.kototoro.tracker.domain.feed.FeedDeriver.Input(
                snapshot = snapshot,
                showAllUpdates = showAll,
                feedLimit = limit,
                filters = filters,
                excludedNsfw = skipNsfw,
                tagBlacklist = tagBlacklist,
                groupTab = scope.groupTab,
                sourceTags = scope.sourceTags,
                presetSourceNames = scope.preset?.sources,
                selectedCategoryId = scope.categoryId.takeIf { it != NO_ID },
                mangaCategoryIdsByFeedKey = scope.mangaCategoryIds,
            ),
        )
        val feedItems = feedCardMapper.map(
            derived.visibleRows,
            org.skepsun.kototoro.tracker.domain.feed.FeedCardMapper.Request(
                brokenTitle = appContext.getString(R.string.favourites_broken_projection_title),
            ),
        )
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
        launchJob(Dispatchers.Default) {
            when (val request = scheduler.requestCheckNow()) {
                UpdateCheckRequest.Started -> {
                    manualRefreshRequested.value = true
                    onMessage.call(appContext.getString(request.messageRes()))
                }
                UpdateCheckRequest.InFlight,
                UpdateCheckRequest.TooSoon,
                UpdateCheckRequest.TrackerDisabled -> {
                    onMessage.call(appContext.getString(request.messageRes()))
                }
            }
        }
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
        val nsfwCombined = combine(skipNsfwInFeed) { filters, skipNsfw ->
            if (skipNsfw) {
                filters + ListFilterOption.SFW
            } else {
                filters
            }
        }
        // Re-emit (unchanged filters) when the quick-filter visibility toggle changes.
        return combine(
            nsfwCombined,
            settings.observeAsFlow(AppSettings.KEY_QUICK_FILTER) { isQuickFilterEnabled },
        ) { filters, _ -> filters }
    }
}

private fun Content.feedLookupKey(): String {
    return "${source.name}|$url"
}
