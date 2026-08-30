package org.skepsun.kototoro.favourites.ui.list

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.cachedIn
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.paging.BatchMappingPagingSource
import org.skepsun.kototoro.core.paging.FavouriteLibraryPagingConfig
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.domain.FavoritesListQuickFilter
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.domain.MarkAsReadUseCase
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.ContentListViewModel
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.scopedToSpace
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.tracker.work.TrackWorker
import org.skepsun.kototoro.tracker.work.UpdateCheckRequest
import org.skepsun.kototoro.tracker.work.messageRes
import org.skepsun.kototoro.work.domain.WorkAggregate
import org.skepsun.kototoro.work.domain.WorkAggregateRepository

@HiltViewModel(assistedFactory = FavouritesListViewModel.Factory::class)
class FavouritesListViewModel @AssistedInject constructor(
    @Assisted val categoryId: Long,
    private val repository: FavouritesRepository,
    private val mangaListMapper: ContentListMapper,
    private val markAsReadUseCase: MarkAsReadUseCase,
    quickFilterFactory: FavoritesListQuickFilter.Factory,
    private val sourceGroupManager: SourceGroupManager,
    private val workAggregateRepository: WorkAggregateRepository,
    private val appSettings: AppSettings,
    private val dataRepository: ContentDataRepository,
    private val sourcePresetsRepository: org.skepsun.kototoro.explore.data.SourcePresetsRepository,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
    private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
    @ApplicationContext private val appContext: Context,
    spaceBrowseScope: SpaceBrowseScope,
    private val trackingRepository: TrackingRepository,
    private val trackWorkerScheduler: TrackWorker.Scheduler,
) : ContentListViewModel(appSettings, dataRepository, localStorageChanges), QuickFilterListener,
    SpaceBindableViewModel {

    @AssistedFactory
    interface Factory {
        fun create(categoryId: Long): FavouritesListViewModel
    }

    private val quickFilter = quickFilterFactory.create(categoryId)
    private val refreshTrigger = MutableStateFlow(Any())
    private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)
    private val activeSpaceScope = spaceBinding.spaceId

    private data class FavouriteListParams(
        val order: ListSortOrder,
        val filters: Set<ListFilterOption>,
        val mode: ListMode,
        val groupTab: BrowseGroupTab,
        val sourceTags: Set<SourceTag>,
        val preset: org.skepsun.kototoro.explore.data.SourcePreset?,
        val categoryIds: Set<Long>,
        val spaceId: SpaceId?,
        val refreshToken: Any,
    )

    private data class FavouriteListResult(
        val items: List<ListModel>,
        val lookup: FavouriteItemLookup,
    )

    private data class FavouriteItemLookup(
        val mangaIds: Map<Long, Set<Long>> = emptyMap(),
        val entityIds: Map<Long, Long> = emptyMap(),
        val preferredLocalIds: Map<Long, Long> = emptyMap(),
    )

    @Volatile
    private var itemLookup = FavouriteItemLookup()

    override val isFilterBarVisible = MutableStateFlow(false)

    override val currentSourceTags = globalFavoritesState.selectedSourceTags

    override fun setSelectedSourceTags(tags: Set<SourceTag>) {
        globalFavoritesState.setSelectedSourceTags(tags)
    }

    override val currentGroupTab = globalFavoritesState.selectedGroupTab.scopedToSpace(
        spaceGroupTab = spaceBinding.groupTab,
        coroutineScope = viewModelScope + Dispatchers.Default,
    )
    override fun bindSpace(spaceId: SpaceId?) = spaceBinding.bindSpace(spaceId)

    override fun setSelectedGroupTab(tab: BrowseGroupTab) {
        globalFavoritesState.setSelectedGroupTab(tab)
    }

    override val availableCategories = flowOf(emptyList<FavouriteCategory>())
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    override val listMode = appSettings.observeAsFlow(AppSettings.KEY_LIST_MODE) { this.listMode }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, appSettings.listMode)

    val topQuickFilter = quickFilter.appliedOptions
        .combineWithSettings()
        .mapLatest { filters -> quickFilter.filterItem(filters) }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null as QuickFilter?)

    /**
     * Like [topQuickFilter] but ignores the "show quick filters" appearance setting, so the
     * top-bar filter panel can keep offering the same options even when the inline tab bar
     * (QuickFilterSection) is hidden by the user.
     */
    val popupQuickFilter = quickFilter.appliedOptions
        .combineWithSettings()
        .mapLatest { filters -> quickFilter.filterItem(filters, ignoreVisibilitySetting = true) }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null as QuickFilter?)

    val sortOrder: StateFlow<ListSortOrder?> = if (categoryId == NO_ID) {
        settings.observeAsFlow(AppSettings.KEY_FAVORITES_ORDER) { allFavoritesSortOrder }
    } else {
        repository.observeCategory(categoryId)
            .withErrorHandling()
            .map { it?.order }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    private val activeSourcePreset = appSettings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId }
        .flatMapLatest { id ->
            if (id == -1L) {
                flowOf(null)
            } else {
                sourcePresetsRepository.observe(id)
            }
        }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    override val content = flowOf(listOf<ListModel>(LoadingState))
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(LoadingState))

    override val pagingContent = combine(
        sortOrder.filterNotNull(),
        quickFilter.appliedOptions.combineWithSettings(),
        observeListModeWithTriggers(),
        currentGroupTab,
        currentSourceTags,
        activeSourcePreset,
        selectedCategoryIds,
        activeSpaceScope,
        refreshTrigger,
    ) { values: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        FavouriteListParams(
            order = values[0] as ListSortOrder,
            filters = values[1] as Set<ListFilterOption>,
            mode = values[2] as ListMode,
            groupTab = values[3] as BrowseGroupTab,
            sourceTags = values[4] as Set<SourceTag>,
            preset = values[5] as? org.skepsun.kototoro.explore.data.SourcePreset,
            categoryIds = values[6] as Set<Long>,
            spaceId = values[7] as? SpaceId,
            refreshToken = requireNotNull(values[8]),
        )
    }.distinctUntilChanged().flatMapLatest { params ->
        itemLookup = FavouriteItemLookup()
        Pager(
            config = FavouriteLibraryPagingConfig,
            pagingSourceFactory = {
                val aggregateSource = workAggregateRepository.createFavouritePagingSource(
                    categoryId = categoryId,
                    order = params.order,
                    filterOptions = params.filters,
                    spaceId = params.spaceId,
                    groupTab = params.groupTab,
                    includeTags = params.mode == ListMode.LIST ||
                        params.mode == ListMode.DETAILED_LIST ||
                        settings.globalTagBlacklist.isNotEmpty() ||
                        params.filters.any { it is ListFilterOption.Tag },
                )
                BatchMappingPagingSource(aggregateSource, diagnosticLabel = "favourites-ui") { aggregates ->
                    mapFavouritePage(aggregates, params).let { result ->
                        itemLookup = itemLookup.merge(result.lookup)
                        result.items
                    }
                }
            },
        ).flow
    }.cachedIn(viewModelScope)

    override val hasMoreItems = MutableStateFlow(false)

    override fun onRefresh() {
        refreshTrigger.value = Any()
    }

    /**
     * Pull-to-refresh entry point: re-queries the local list and requests a new-chapter
     * update check through the shared gate ([TrackWorker.Scheduler.requestCheckNow], the
     * same one used by the Updates and Feed pages), finishing with a summary toast. If the
     * gate refuses the request (check already running / checked too recently / tracker
     * disabled) the corresponding prompt toast is shown instead.
     */
    fun checkForUpdates() {
        refreshTrigger.value = Any()
        launchLoadingJob(Dispatchers.Default) {
            when (val request = trackWorkerScheduler.requestCheckNow()) {
                UpdateCheckRequest.Started -> {
                    onContentMessage.call(appContext.getString(R.string.checking_for_updates))
                    val finished = trackWorkerScheduler.awaitOneShot(UPDATE_CHECK_AWAIT_MS)
                    val message = if (finished) {
                        val summary = trackingRepository.getFavouriteUpdatesSummary()
                        if (summary.worksWithUpdates > 0) {
                            appContext.getString(
                                R.string.favourites_updates_found,
                                summary.worksWithUpdates,
                                summary.newChapters,
                            )
                        } else {
                            appContext.getString(R.string.favourites_no_updates)
                        }
                    } else {
                        appContext.getString(R.string.updates_check_still_running)
                    }
                    onContentMessage.call(message)
                    refreshTrigger.value = Any()
                }
                UpdateCheckRequest.InFlight,
                UpdateCheckRequest.TooSoon,
                UpdateCheckRequest.TrackerDisabled -> {
                    onContentMessage.call(appContext.getString(request.messageRes()))
                }
            }
        }
    }

    override fun onRetry() = Unit

    override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) =
        quickFilter.setFilterOption(option, isApplied)

    override fun toggleFilterOption(option: ListFilterOption) = quickFilter.toggleFilterOption(option)

    override fun clearFilter() {
        globalFavoritesState.resetFilters(clearGroupTab = activeSpaceScope.value == null)
        selectedCategoryIds.value = emptySet()
    }

    fun markAsRead(items: Set<Content>) {
        launchLoadingJob(Dispatchers.Default) {
            markAsReadUseCase(items)
            onRefresh()
        }
    }

    fun removeFromFavourites(ids: Set<Long>) {
        if (ids.isEmpty()) {
            return
        }
        launchJob(Dispatchers.Default) {
            val mangaIds = ids.expandGroupedIds()
            val handle = if (categoryId == NO_ID) {
                repository.removeFromFavourites(mangaIds)
            } else {
                repository.removeFromCategory(categoryId, mangaIds)
            }
            onActionDone.call(ReversibleAction(R.string.removed_from_favourites, handle))
        }
    }

    fun resolveSelectionToMangaIds(ids: Set<Long>): Set<Long> {
        return ids.expandGroupedIds()
    }

    override fun resolveEntityIdForUiItemId(id: Long): Long? {
        return itemLookup.entityIds[id]
    }

    override fun resolvePreferredLocalMangaIdForUiItemId(id: Long): Long? {
        return itemLookup.preferredLocalIds[id] ?: itemLookup.mangaIds[id]?.firstOrNull()
    }

    suspend fun isPinned(ids: Set<Long>): Boolean {
        return repository.isPinned(ids.expandGroupedIds())
    }

    fun setPinned(ids: Set<Long>, isPinned: Boolean) {
        launchJob(Dispatchers.Default) {
            repository.setPinned(ids.expandGroupedIds(), isPinned)
            onRefresh()
        }
    }

    fun togglePinned(ids: Set<Long>) {
        launchJob(Dispatchers.Default) {
            val currentlyPinned = repository.isPinned(ids.expandGroupedIds())
            repository.setPinned(ids.expandGroupedIds(), !currentlyPinned)
            onRefresh()
        }
    }

    fun setSortOrder(order: ListSortOrder) {
        if (categoryId == NO_ID) {
            return
        }
        launchJob {
            repository.setCategoryOrder(categoryId, order)
        }
    }

    private suspend fun mapFavouritePage(
        aggregates: List<WorkAggregate>,
        params: FavouriteListParams,
    ): FavouriteListResult = coroutineScope {
        val hideAdult = settings.isFavouritesExcludeNsfw
        val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
        val entries = aggregates.mapNotNull { aggregate ->
            val representative = aggregate.displayProjection ?: return@mapNotNull null
            if (params.preset != null && representative.source.name !in params.preset.sources) return@mapNotNull null
            if (params.categoryIds.isNotEmpty() && aggregate.categories.none { it.id in params.categoryIds }) {
                return@mapNotNull null
            }
            if (params.groupTab != BrowseGroupTab.All || params.sourceTags.isNotEmpty()) {
                val contentGroup = sourceGroupManager.getContentGroup(representative.source)
                val originGroup = sourceGroupManager.getOriginGroup(representative.source)
                // The persisted content type is authoritative; the source-group heuristic
                // can mislabel local/anonymous projections, so accept the aggregate type too.
                val typeMatches = aggregate.contentType?.let(params.groupTab::matchesContentType) == true
                val sourceGroupMatches = params.groupTab.matchesContentGroup(contentGroup) &&
                    params.groupTab.matchesOriginGroup(originGroup)
                if (!sourceGroupMatches && !typeMatches) {
                    return@mapNotNull null
                }
                val matchesSourceTags = params.sourceTags.isEmpty() || params.sourceTags.any { tag ->
                    tag.matches(contentGroup, originGroup)
                }
                if (!matchesSourceTags) {
                    return@mapNotNull null
                }
            }
            if (hideAdult && representative.isNsfw()) {
                return@mapNotNull null
            }
            if (representative in globalTagBlacklist) return@mapNotNull null
            val entityId = aggregate.identity.entityId ?: return@mapNotNull null
            aggregate to VisibleFavouriteGroup(
                entityId = entityId,
                preferredLocalMangaId = aggregate.identity.preferredMangaId ?: representative.id,
                representative = representative,
                mangaIds = aggregate.identity.localMangaIds.toCollection(LinkedHashSet())
                    .ifEmpty { setOf(representative.id) },
                projectionCount = aggregate.identity.localMangaIds.size.coerceAtLeast(1),
            )
        }
        if (entries.isEmpty()) {
            return@coroutineScope FavouriteListResult(emptyList(), FavouriteItemLookup())
        }

        val groups = entries.map { (_, group) -> group }
        val metadataByEntityDeferred = async {
            dataRepository.getEntityMetadataSourceSelections(
                groups.map(VisibleFavouriteGroup::entityId),
            )
        }
        val overridesByMangaIdDeferred = async {
            dataRepository.getOverridesForWorkItems(
                groups.associate { group -> group.representative.id to group.entityId },
            )
        }
        val metadataByEntity = metadataByEntityDeferred.await()
        val overridesByMangaId = overridesByMangaIdDeferred.await()
        val requests = groups.map { group ->
            ContentListMapper.ListModelRequest(
                manga = group.representative,
                metadataSelectionOverride = metadataByEntity[group.entityId],
                useMetadataSelectionOverride = true,
                manualOverride = overridesByMangaId[group.representative.id],
                useManualOverride = true,
            )
        }
        val models = mangaListMapper.toRequestedListModelList(
            requests = requests,
            mode = params.mode,
            flags = ContentListMapper.NO_FAVORITE or
                ContentListMapper.NO_PROGRESS or
                ContentListMapper.NO_COUNTER,
            pinnedIds = entries.asSequence()
                .filter { (aggregate, _) -> aggregate.favourite?.isPinned == true }
                .mapTo(LinkedHashSet()) { (_, group) -> group.preferredLocalMangaId },
        )

        val lookup = FavouriteItemLookup(
            mangaIds = groups.associate { it.entityId to it.mangaIds },
            entityIds = groups.associate { it.entityId to it.entityId },
            preferredLocalIds = groups.associate { it.entityId to it.preferredLocalMangaId },
        )
        val items = entries.mapIndexed { index, (aggregate, group) ->
            val progress = aggregate.toReadingProgress() ?: models[index].progressOrNull()
            models[index].toGroupedListModel(
                group = group,
                isPinned = aggregate.favourite?.isPinned == true,
                progress = progress,
                counter = if (progress?.isCompleted() == true) {
                    0
                } else {
                    aggregate.tracking?.newChapters ?: models[index].counter
                },
            )
        }
        FavouriteListResult(items, lookup)
    }

    private fun FavouriteItemLookup.merge(other: FavouriteItemLookup): FavouriteItemLookup {
        return FavouriteItemLookup(
            mangaIds = mangaIds + other.mangaIds,
            entityIds = entityIds + other.entityIds,
            preferredLocalIds = preferredLocalIds + other.preferredLocalIds,
        )
    }

    private fun Set<Long>.expandGroupedIds(): Set<Long> {
        return flatMapTo(LinkedHashSet()) { id ->
            itemLookup.mangaIds[id].orEmpty().ifEmpty { setOf(id) }
        }
    }

    private fun org.skepsun.kototoro.list.ui.model.ContentListModel.toGroupedListModel(
        group: VisibleFavouriteGroup,
        isPinned: Boolean,
        progress: ReadingProgress?,
        counter: Int,
    ): ListModel {
        val groupSuffix = group.groupSuffix()
        return when (this) {
            is ContentCompactListModel -> copy(
                id = group.listId,
                subtitle = listOfNotNull(subtitle?.takeIf { it.isNotBlank() }, groupSuffix).joinToString(" · "),
                counter = counter,
                progress = progress,
                projectionCount = group.projectionCount,
                isPinned = isPinned,
            )

            is ContentDetailedListModel -> copy(
                id = group.listId,
                subtitle = listOfNotNull(subtitle.takeIf { !it.isNullOrBlank() }, groupSuffix).joinToString(" · "),
                counter = counter,
                progress = progress,
                projectionCount = group.projectionCount,
                isPinned = isPinned,
            )

            is ContentGridModel -> copy(
                id = group.listId,
                counter = counter,
                progress = progress,
                projectionCount = group.projectionCount,
                isPinned = isPinned,
            )
        }
    }

    private fun WorkAggregate.toReadingProgress(): ReadingProgress? {
        val history = history ?: return null
        val fixedPercent = if (ReadingProgress.isCompleted(history.percent)) 1f else history.percent
        return ReadingProgress(
            percent = fixedPercent,
            totalChapters = history.chaptersCount,
            mode = appSettings.progressIndicatorMode,
        ).takeIf { it.isValid() }
    }

    private fun org.skepsun.kototoro.list.ui.model.ContentListModel.progressOrNull(): ReadingProgress? = when (this) {
        is ContentDetailedListModel -> progress
        is ContentGridModel -> progress
        is ContentCompactListModel -> null
    }

    private fun VisibleFavouriteGroup.groupSuffix(): String? {
        val projectionLabel = representative.source.getTitle(appContext)
        return if (projectionCount > 1) {
            appContext.getString(
                R.string.favourites_entity_current_projection_with_count,
                projectionLabel,
                projectionCount,
            )
        } else {
            appContext.getString(R.string.favourites_entity_current_projection, projectionLabel)
        }
    }

    // Every favourite aggregate is entity-backed, so this remains stable when its
    // representative projection, source, cover, or display type changes.
    private val VisibleFavouriteGroup.listId: Long
        get() = entityId

    private data class VisibleFavouriteGroup(
        val representative: Content,
        val mangaIds: Set<Long>,
        val projectionCount: Int,
        val entityId: Long,
        val preferredLocalMangaId: Long,
    )

    private fun getEmptyState(hasFilters: Boolean) = if (hasFilters) {
        EmptyState(
            icon = R.drawable.ic_empty_favourites,
            textPrimary = R.string.nothing_found,
            textSecondary = R.string.text_empty_holder_secondary_filtered,
            actionStringRes = R.string.reset_filter,
        )
    } else {
        EmptyState(
            icon = R.drawable.ic_empty_favourites,
            textPrimary = R.string.text_empty_holder_primary,
            textSecondary = if (categoryId == NO_ID) {
                R.string.you_have_not_favourites_yet
            } else {
                R.string.favourites_category_empty
            },
            actionStringRes = 0,
        )
    }

    private companion object {

        /**
         * How long the pull-to-refresh spinner waits for the one-shot update check before
         * giving up on a result toast. The worker keeps running in the background (and
         * reports via its own notification) if the check is simply slow.
         */
        const val UPDATE_CHECK_AWAIT_MS = 60_000L
    }
}
