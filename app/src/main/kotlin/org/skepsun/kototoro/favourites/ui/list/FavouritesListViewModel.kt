package org.skepsun.kototoro.favourites.ui.list

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
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
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.flattenLatest
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
import org.skepsun.kototoro.list.ui.model.InfoModel
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.list.ui.model.toErrorState
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.scopedToSpace
import org.skepsun.kototoro.work.domain.WorkAggregate
import org.skepsun.kototoro.work.domain.WorkAggregateRepository
import org.skepsun.kototoro.work.domain.WorkResolver
import java.util.concurrent.atomic.AtomicBoolean

private const val PAGE_SIZE = 32

@HiltViewModel(assistedFactory = FavouritesListViewModel.Factory::class)
class FavouritesListViewModel @AssistedInject constructor(
    @Assisted val categoryId: Long,
    private val repository: FavouritesRepository,
    private val mangaListMapper: ContentListMapper,
    private val markAsReadUseCase: MarkAsReadUseCase,
    quickFilterFactory: FavoritesListQuickFilter.Factory,
    private val sourceGroupManager: SourceGroupManager,
    private val workResolver: WorkResolver,
    private val workAggregateRepository: WorkAggregateRepository,
    private val appSettings: AppSettings,
    private val dataRepository: ContentDataRepository,
    private val sourcePresetsRepository: org.skepsun.kototoro.explore.data.SourcePresetsRepository,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
    private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
    @ApplicationContext private val appContext: Context,
    spaceBrowseScope: SpaceBrowseScope,
) : ContentListViewModel(appSettings, dataRepository, localStorageChanges), QuickFilterListener,
    SpaceBindableViewModel {

    @AssistedFactory
    interface Factory {
        fun create(categoryId: Long): FavouritesListViewModel
    }

    private val quickFilter = quickFilterFactory.create(categoryId)
    private val refreshTrigger = MutableStateFlow(Any())
    private val limit = MutableStateFlow(if (categoryId == NO_ID) 1000 else 200)
    private val isPaginationReady = AtomicBoolean(false)
    private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)
    private val activeSpaceScope = spaceBinding.spaceId

    @Volatile
    private var groupedFavoriteIds: Map<Long, Set<Long>> = emptyMap()

    @Volatile
    private var groupedEntityIds: Map<Long, Long> = emptyMap()

    @Volatile
    private var groupedPreferredLocalIds: Map<Long, Long> = emptyMap()

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

    private val preparedGroups = combine(
        observeFavorites(),
        selectedCategoryIds,
        activeSourcePreset,
    ) { list, categoryIds, preset ->
        Triple(list, categoryIds, preset)
    }.mapLatest { (list, categoryIds, preset) ->
        preparedGroupsReady(prepareGroups(list, categoryIds, preset))
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, PreparedGroupsState.Loading)

    private val listContext = combine(
        preparedGroups,
        quickFilter.appliedOptions,
        observeListModeWithTriggers(),
    ) { groups, filters, mode ->
        Triple(groups, filters, mode)
    }

    private val activeSelection = combine(
        refreshTrigger,
        currentGroupTab,
        currentSourceTags,
    ) { _, groupTab, sourceTags ->
        Pair(groupTab, sourceTags)
    }

    private val listParams = combine(
        listContext,
        activeSelection,
    ) { (groups, filters, mode), (groupTab, sourceTags) ->
        ListParams(
            groups = groups,
            filters = filters,
            mode = mode,
            groupTab = groupTab,
            sourceTags = sourceTags,
        )
    }

    override val content = combine(
        listParams,
        mangaListMapper.observeDisplayChanges().onStart { emit(Unit) },
    ) { params, _ ->
        when (val groups = params.groups) {
            PreparedGroupsState.Loading -> listOf(LoadingState)
            is PreparedGroupsState.Ready -> mapList(
                groups = groups.groups,
                filters = params.filters,
                mode = params.mode,
                groupTab = params.groupTab,
                sourceTags = params.sourceTags,
            )
        }
    }.onEach {
        isPaginationReady.set(true)
    }.distinctUntilChanged().catch {
        emit(listOf(it.toErrorState(canRetry = false)))
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

    override val hasMoreItems: StateFlow<Boolean> = limit
        .map { categoryId != NO_ID }
        .stateIn(viewModelScope, SharingStarted.Eagerly, categoryId != NO_ID)

    override fun onRefresh() {
        refreshTrigger.value = Any()
    }

    override fun onRetry() = Unit

    override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) =
        quickFilter.setFilterOption(option, isApplied)

    override fun toggleFilterOption(option: ListFilterOption) = quickFilter.toggleFilterOption(option)

    override fun clearFilter() = quickFilter.clearFilter()

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
        return groupedEntityIds[id]
    }

    override fun resolvePreferredLocalMangaIdForUiItemId(id: Long): Long? {
        return groupedPreferredLocalIds[id] ?: groupedFavoriteIds[id]?.firstOrNull()
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

    fun requestMoreItems() {
        if (isPaginationReady.compareAndSet(true, false)) {
            limit.value += PAGE_SIZE
        }
    }

    private suspend fun prepareGroups(
        list: List<Content>,
        categoryIds: Set<Long>,
        preset: org.skepsun.kototoro.explore.data.SourcePreset?,
    ): List<PreparedFavouriteGroup> {
        val presetFiltered = if (preset == null) {
            list
        } else {
            list.filter { it.source.name in preset.sources }
        }
        if (presetFiltered.isEmpty()) {
            return emptyList()
        }
        val categoriesByMangaId = if (categoryIds.isEmpty()) {
            emptyMap<Long, Set<Long>>()
        } else {
            repository.getCategoriesIds(presetFiltered.map(Content::id))
        }
        val categoryFiltered = if (categoryIds.isEmpty()) {
            presetFiltered
        } else {
            presetFiltered.filter { manga ->
                val mangaCategories = categoriesByMangaId[manga.id].orEmpty()
                categoryIds.any { it in mangaCategories }
            }
        }
        return categoryFiltered.map { manga ->
            val source = manga.source
            PreparedFavouriteItem(
                content = manga,
                contentGroup = sourceGroupManager.getContentGroup(source),
                originGroup = sourceGroupManager.getOriginGroup(source),
                isNsfw = manga.isNsfw(),
            )
        }.aggregateByEntity()
    }

    private suspend fun mapList(
        groups: List<PreparedFavouriteGroup>,
        filters: Set<ListFilterOption>,
        mode: ListMode,
        groupTab: BrowseGroupTab,
        sourceTags: Set<SourceTag>,
    ): List<ListModel> {
        val hideAdult = settings.isFavouritesExcludeNsfw
        var hasHiddenAdultItems = false
        val visibleGroups = groups.mapNotNull { group ->
            val matchingItems = group.items.filter { item ->
                val groupMatches = groupTab.matchesContentGroup(item.contentGroup) &&
                    groupTab.matchesOriginGroup(item.originGroup)
                val originMatches = sourceTags.isEmpty() ||
                    sourceTags.any { it.matches(item.contentGroup, item.originGroup) }
                groupMatches && originMatches
            }
            if (matchingItems.isEmpty()) {
                return@mapNotNull null
            }
            val adultFilteredItems = if (hideAdult) {
                matchingItems.filterNot(PreparedFavouriteItem::isNsfw)
            } else {
                matchingItems
            }
            if (hideAdult && adultFilteredItems.size != matchingItems.size) {
                hasHiddenAdultItems = true
            }
            val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
            val visibleItems = adultFilteredItems.filterNot { it.content in globalTagBlacklist }
            if (visibleItems.isEmpty()) {
                return@mapNotNull null
            }
            group.toVisibleGroup(visibleItems)
        }

        if (visibleGroups.isEmpty()) {
            groupedFavoriteIds = emptyMap()
            groupedEntityIds = emptyMap()
            groupedPreferredLocalIds = emptyMap()
            val models = mutableListOf<ListModel>()
            quickFilter.filterItem(filters)?.let(models::add)
            if (hasHiddenAdultItems) {
                models += InfoModel(
                    key = "hidden_nsfw_favourites",
                    title = R.string.favourites_hidden_adult_title,
                    text = R.string.favourites_hidden_adult_subtitle,
                    icon = R.drawable.ic_eye_off,
                )
            }
            models += if (filters.isEmpty() &&
                groupTab == BrowseGroupTab.All &&
                sourceTags.isEmpty() &&
                currentCategoryIds.value.isEmpty()
            ) {
                getEmptyState(hasFilters = false)
            } else {
                getEmptyState(hasFilters = true)
            }
            return models
        }

        val filteredGroups = visibleGroups.filter { group ->
            if (ListFilterOption.Macro.MULTI_PROJECTION !in filters) {
                true
            } else {
                group.projectionCount > 1
            }
        }

        if (filteredGroups.isEmpty()) {
            groupedFavoriteIds = emptyMap()
            groupedEntityIds = emptyMap()
            groupedPreferredLocalIds = emptyMap()
            val models = mutableListOf<ListModel>()
            quickFilter.filterItem(filters)?.let(models::add)
            if (hasHiddenAdultItems) {
                models += InfoModel(
                    key = "hidden_nsfw_favourites",
                    title = R.string.favourites_hidden_adult_title,
                    text = R.string.favourites_hidden_adult_subtitle,
                    icon = R.drawable.ic_eye_off,
                )
            }
            models += getEmptyState(hasFilters = true)
            return models
        }

        groupedFavoriteIds = filteredGroups.associate { it.uiId to it.mangaIds }
        groupedEntityIds = filteredGroups.mapNotNull { group ->
            group.entityId?.let { group.uiId to it }
        }.toMap()
        groupedPreferredLocalIds = filteredGroups.mapNotNull { group ->
            group.preferredLocalMangaId?.let { group.uiId to it }
        }.toMap()

        val result = ArrayList<ListModel>(filteredGroups.size + 1)
        quickFilter.filterItem(filters)?.let(result::add)
        val pinnedIds = repository.getPinnedIds(filteredGroups.map { it.preferredLocalMangaId ?: it.representative.id })
        val aggregatesByEntityId = workAggregateRepository.findAggregatesByEntityIds(
            filteredGroups.mapNotNull(VisibleFavouriteGroup::entityId),
        )
        val models = mangaListMapper.toRequestedListModelList(
            requests = filteredGroups.map { group ->
                ContentListMapper.ListModelRequest(
                    manga = group.representative,
                    metadataSelectionOverride = group.metadataSourceSelection,
                    useMetadataSelectionOverride = group.metadataSourceSelection != null,
                )
            },
            mode = mode,
            flags = ContentListMapper.NO_FAVORITE,
            pinnedIds = pinnedIds,
        )
        for (index in filteredGroups.indices) {
            val group = filteredGroups[index]
            val model = models[index]
            val aggregate = group.entityId?.let(aggregatesByEntityId::get)
            val progress = aggregate?.toReadingProgress() ?: model.progressOrNull()
            val counter = if (progress?.isCompleted() == true) {
                0
            } else {
                aggregate?.tracking?.newChapters ?: model.counter
            }
            result += model.toGroupedListModel(
                group = group,
                isPinned = (group.preferredLocalMangaId ?: group.representative.id) in pinnedIds,
                progress = progress,
                counter = counter,
            )
        }
        return result
    }

    private suspend fun List<PreparedFavouriteItem>.aggregateByEntity(): List<PreparedFavouriteGroup> {
        if (isEmpty()) {
            return emptyList()
        }
        val identitiesByMangaId = workResolver.resolveManyByMangaIds(map { it.content.id })
        val resolvedEntityIdsByMangaId = identitiesByMangaId.mapValues { it.value.entityId }.filterValues { it != null }
            .mapValues { requireNotNull(it.value) }
        val resolvedEntityIds = resolvedEntityIdsByMangaId.values.distinct()
        val preferredLocalIdsByEntity = identitiesByMangaId.values
            .mapNotNull { identity -> identity.entityId?.let { it to identity.preferredMangaId } }
            .toMap()
        val metadataSelectionsByEntity = dataRepository.getEntityMetadataSourceSelections(resolvedEntityIds)
        val displayTypeOrdinalByEntity = this
            .groupBy { resolvedEntityIdsByMangaId[it.content.id] }
            .mapNotNull { (entityId, items) ->
                entityId?.let {
                    it to items.resolveDisplayContentTypeOrdinal()
                }
            }
            .toMap()
        val grouped = LinkedHashMap<FavouriteGroupKey, MutableList<PreparedFavouriteItem>>(size)
        for (item in this) {
            val entityId = resolvedEntityIdsByMangaId[item.content.id]
            val contentTypeOrdinal = entityId?.let(displayTypeOrdinalByEntity::get) ?: item.content.source.contentType.ordinal
            val key = FavouriteGroupKey(
                uiId = entityId?.toUiGroupId(contentTypeOrdinal) ?: item.id,
                contentTypeOrdinal = contentTypeOrdinal,
            )
            grouped.getOrPut(key) { ArrayList(1) }.add(item)
        }
        return grouped.map { (key, items) ->
            val entityId = resolvedEntityIdsByMangaId[items.first().content.id]
            val preferredLocalId = entityId?.let(preferredLocalIdsByEntity::get)
            PreparedFavouriteGroup(
                uiId = key.uiId,
                entityId = entityId,
                preferredLocalMangaId = preferredLocalId,
                metadataSourceSelection = entityId?.let(metadataSelectionsByEntity::get),
                items = items,
            )
        }
    }

    private fun Set<Long>.expandGroupedIds(): Set<Long> {
        return flatMapTo(LinkedHashSet()) { id ->
            groupedFavoriteIds[id].orEmpty().ifEmpty { setOf(id) }
        }
    }

    private suspend fun org.skepsun.kototoro.list.ui.model.ContentListModel.toGroupedListModel(
        group: VisibleFavouriteGroup,
        isPinned: Boolean,
        progress: ReadingProgress?,
        counter: Int,
    ): ListModel {
        val groupSuffix = group.groupSuffix()
        return when (this) {
            is ContentCompactListModel -> copy(
                id = group.uiId,
                subtitle = listOfNotNull(subtitle?.takeIf { it.isNotBlank() }, groupSuffix).joinToString(" · "),
                counter = counter,
                progress = progress,
                projectionCount = group.projectionCount,
                isPinned = isPinned,
            )

            is ContentDetailedListModel -> copy(
                id = group.uiId,
                subtitle = listOfNotNull(subtitle.takeIf { !it.isNullOrBlank() }, groupSuffix).joinToString(" · "),
                counter = counter,
                progress = progress,
                projectionCount = group.projectionCount,
                isPinned = isPinned,
            )

            is ContentGridModel -> copy(
                id = group.uiId,
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

    private fun Long.toUiGroupId(contentTypeOrdinal: Int): Long = -((this shl 8) or (contentTypeOrdinal + 1).toLong())

    private data class PreparedFavouriteItem(
        val content: Content,
        val contentGroup: org.skepsun.kototoro.core.jsonsource.ContentGroup,
        val originGroup: org.skepsun.kototoro.core.jsonsource.OriginGroup,
        val isNsfw: Boolean,
    )

    private data class PreparedFavouriteGroup(
        val uiId: Long,
        val entityId: Long?,
        val preferredLocalMangaId: Long?,
        val metadataSourceSelection: ContentDataRepository.MetadataSourceSelection?,
        val items: List<PreparedFavouriteItem>,
    )

    private data class VisibleFavouriteGroup(
        val uiId: Long,
        val representative: Content,
        val mangaIds: Set<Long>,
        val projectionCount: Int,
        val entityId: Long?,
        val preferredLocalMangaId: Long?,
        val metadataSourceSelection: ContentDataRepository.MetadataSourceSelection?,
    )

    private data class FavouriteGroupKey(
        val uiId: Long,
        val contentTypeOrdinal: Int,
    )

    private data class ListParams(
        val groups: PreparedGroupsState,
        val filters: Set<ListFilterOption>,
        val mode: ListMode,
        val groupTab: BrowseGroupTab,
        val sourceTags: Set<SourceTag>,
    )

    private sealed class PreparedGroupsState {
        object Loading : PreparedGroupsState()
        data class Ready(val groups: List<PreparedFavouriteGroup>) : PreparedGroupsState()
    }

    private fun preparedGroupsReady(groups: List<PreparedFavouriteGroup>): PreparedGroupsState {
        return PreparedGroupsState.Ready(groups)
    }

    private fun PreparedFavouriteGroup.toVisibleGroup(items: List<PreparedFavouriteItem>): VisibleFavouriteGroup? {
        if (items.isEmpty()) {
            return null
        }
        val representative = items.firstOrNull { it.content.id == preferredLocalMangaId }?.content ?: items.first().content
        return VisibleFavouriteGroup(
            uiId = uiId,
            representative = representative,
            mangaIds = items.mapTo(LinkedHashSet(items.size)) { it.content.id },
            projectionCount = items.size,
            entityId = entityId,
            preferredLocalMangaId = preferredLocalMangaId?.takeIf { preferredId ->
                items.any { it.content.id == preferredId }
            } ?: representative.id,
            metadataSourceSelection = metadataSourceSelection,
        )
    }

    private val PreparedFavouriteItem.id: Long
        get() = content.id

    private fun List<PreparedFavouriteItem>.resolveDisplayContentTypeOrdinal(): Int {
        return firstOrNull { !it.content.source.name.startsWith("TRACKING_") }?.content?.source?.contentType?.ordinal
            ?: first().content.source.contentType.ordinal
    }

    private fun observeFavorites() = if (categoryId == NO_ID) {
        combine(
            sortOrder.filterNotNull(),
            quickFilter.appliedOptions.combineWithSettings(),
            limit,
            activeSpaceScope,
        ) { order, filters, limit, spaceId ->
            isPaginationReady.set(false)
            repository.observeAllProjectionContents(order, filters, limit, spaceId)
        }.flattenLatest()
    } else {
        combine(
            quickFilter.appliedOptions.combineWithSettings(),
            limit,
            activeSpaceScope,
        ) { filters, limit, spaceId ->
            isPaginationReady.set(false)
            repository.observeAllProjectionContents(categoryId, filters, limit, spaceId)
        }.flattenLatest()
    }

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
}
