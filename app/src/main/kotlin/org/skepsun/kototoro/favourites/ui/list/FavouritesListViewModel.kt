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
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.flattenLatest
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.domain.FavoritesListQuickFilter
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.domain.MarkAsReadUseCase
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.QuickFilterListener
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
    private val entityGraphRepository: EntityGraphRepository,
    settings: AppSettings,
    private val dataRepository: ContentDataRepository,
    private val sourcePresetsRepository: org.skepsun.kototoro.explore.data.SourcePresetsRepository,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
    private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
    @ApplicationContext private val appContext: Context,
) : ContentListViewModel(settings, dataRepository, localStorageChanges), QuickFilterListener {

    @AssistedFactory
    interface Factory {
        fun create(categoryId: Long): FavouritesListViewModel
    }

    private val quickFilter = quickFilterFactory.create(categoryId)
    private val refreshTrigger = MutableStateFlow(Any())
    private val limit = MutableStateFlow(0)
    private val isPaginationReady = AtomicBoolean(false)

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

    override val currentGroupTab = globalFavoritesState.selectedGroupTab

    override fun setSelectedGroupTab(tab: BrowseGroupTab) {
        globalFavoritesState.setSelectedGroupTab(tab)
    }

    override val availableCategories = flowOf(emptyList<FavouriteCategory>())
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    override val listMode = settings.observeAsFlow(AppSettings.KEY_LIST_MODE) { this.listMode }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.listMode)

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

    override val content = combine(
        observeFavorites(),
        quickFilter.appliedOptions,
        observeListModeWithTriggers(),
        refreshTrigger,
        currentGroupTab,
        currentSourceTags,
        selectedCategoryIds,
        mangaListMapper.observeDisplayChanges().onStart { emit(Unit) },
        settings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId }
            .flatMapLatest { id ->
                if (id == -1L) {
                    flowOf(null)
                } else {
                    sourcePresetsRepository.observe(id)
                }
            },
    ) { values: Array<Any?> ->
        val list = values[0] as List<Content>
        val filters = values[1] as Set<ListFilterOption>
        val mode = values[2] as ListMode
        val groupTab = values[4] as BrowseGroupTab
        val sourceTags = values[5] as Set<SourceTag>
        val categoryIds = values[6] as Set<Long>
        val preset = values[8] as? org.skepsun.kototoro.explore.data.SourcePreset
        mapList(list, filters, mode, groupTab, sourceTags, categoryIds, preset)
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

    private suspend fun mapList(
        list: List<Content>,
        filters: Set<ListFilterOption>,
        mode: ListMode,
        groupTab: BrowseGroupTab,
        sourceTags: Set<SourceTag>,
        categoryIds: Set<Long>,
        preset: org.skepsun.kototoro.explore.data.SourcePreset?,
    ): List<ListModel> {
        val filteredList = list.filter { manga ->
            val source = manga.source
            if (preset != null && source.name !in preset.sources) {
                return@filter false
            }

            val contentGroup = sourceGroupManager.getContentGroup(source)
            val originGroup = sourceGroupManager.getOriginGroup(source)
            val groupMatches = groupTab.matchesContentGroup(contentGroup) &&
                groupTab.matchesOriginGroup(originGroup)
            val originMatches = if (sourceTags.isEmpty()) {
                true
            } else {
                sourceTags.any { it.matches(contentGroup, originGroup) }
            }
            val categoryMatches = if (categoryIds.isEmpty()) {
                true
            } else {
                val mangaCategories = repository.getCategoriesIds(manga.id).toSet()
                categoryIds.any { it in mangaCategories }
            }

            groupMatches && originMatches && categoryMatches
        }

        val hideAdult = settings.isFavouritesExcludeNsfw
        val adultItems = filteredList.filter { it.isNsfw() }
        val visibleItems = if (hideAdult) filteredList.filterNot { it.isNsfw() } else filteredList

        if (visibleItems.isEmpty()) {
            groupedFavoriteIds = emptyMap()
            groupedEntityIds = emptyMap()
            val models = mutableListOf<ListModel>()
            quickFilter.filterItem(filters)?.let(models::add)
            if (hideAdult && adultItems.isNotEmpty()) {
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
                categoryIds.isEmpty()
            ) {
                getEmptyState(hasFilters = false)
            } else {
                getEmptyState(hasFilters = true)
            }
            return models
        }

        val groupedItems = visibleItems.aggregateByEntity()
        groupedFavoriteIds = groupedItems.associate { it.uiId to it.mangaIds }
        groupedEntityIds = groupedItems.mapNotNull { group ->
            group.entityId?.let { group.uiId to it }
        }.toMap()
        groupedPreferredLocalIds = groupedItems.mapNotNull { group ->
            group.preferredLocalMangaId?.let { group.uiId to it }
        }.toMap()

        val result = ArrayList<ListModel>(groupedItems.size + 1)
        quickFilter.filterItem(filters)?.let(result::add)
        for (group in groupedItems) {
            val model = mangaListMapper.toListModel(
                manga = group.representative,
                mode = mode,
                flags = ContentListMapper.NO_FAVORITE,
                metadataSelectionOverride = group.metadataSourceSelection,
                useMetadataSelectionOverride = group.metadataSourceSelection != null,
            )
            result += model.toGroupedListModel(
                group = group,
                isPinned = repository.isPinned(group.mangaIds),
            )
        }
        return result
    }

    private suspend fun List<Content>.aggregateByEntity(): List<FavouriteGroup> {
        if (isEmpty()) {
            return emptyList()
        }
        val resolvedEntityIdsByMangaId = entityGraphRepository.findEntityIdsByAnyMangaIds(map { it.id })
        val resolvedEntityIds = resolvedEntityIdsByMangaId.values.distinct()
        val preferredLocalIdsByEntity = dataRepository.getEntityPreferredLocalMangaIds(resolvedEntityIds)
        val metadataSelectionsByEntity = dataRepository.getEntityMetadataSourceSelections(resolvedEntityIds)
        val displayTypeOrdinalByEntity = this
            .groupBy { resolvedEntityIdsByMangaId[it.id] }
            .mapNotNull { (entityId, items) ->
                entityId?.let {
                    it to items.resolveDisplayContentTypeOrdinal()
                }
            }
            .toMap()
        val grouped = LinkedHashMap<FavouriteGroupKey, MutableList<Content>>(size)
        for (item in this) {
            val entityId = resolvedEntityIdsByMangaId[item.id]
            val contentTypeOrdinal = entityId?.let(displayTypeOrdinalByEntity::get) ?: item.source.contentType.ordinal
            val key = FavouriteGroupKey(
                uiId = entityId?.toUiGroupId(contentTypeOrdinal) ?: item.id,
                contentTypeOrdinal = contentTypeOrdinal,
            )
            grouped.getOrPut(key) { ArrayList(1) }.add(item)
        }
        return grouped.map { (key, items) ->
            val entityId = resolvedEntityIdsByMangaId[items.first().id]
            val preferredLocalId = entityId?.let(preferredLocalIdsByEntity::get)
            // Grouped favourites are entity-first: once a work/entity exists, the preferred
            // local projection becomes the representative row anchor and metadata source owner.
            // Only no-entity groups fall back to a plain local manga representative.
            val representative = items.firstOrNull { it.id == preferredLocalId } ?: items.first()
            FavouriteGroup(
                uiId = key.uiId,
                representative = representative,
                mangaIds = items.mapTo(LinkedHashSet(items.size)) { it.id },
                projectionCount = items.size,
                entityId = entityId,
                preferredLocalMangaId = preferredLocalId ?: representative.id,
                metadataSourceSelection = entityId?.let(metadataSelectionsByEntity::get),
            )
        }
    }

    private fun Set<Long>.expandGroupedIds(): Set<Long> {
        return flatMapTo(LinkedHashSet()) { id ->
            groupedFavoriteIds[id].orEmpty().ifEmpty { setOf(id) }
        }
    }

    private suspend fun org.skepsun.kototoro.list.ui.model.ContentListModel.toGroupedListModel(
        group: FavouriteGroup,
        isPinned: Boolean,
    ): ListModel {
        val groupSuffix = group.groupSuffix()
        return when (this) {
            is ContentCompactListModel -> copy(
                id = group.uiId,
                subtitle = listOfNotNull(subtitle?.takeIf { it.isNotBlank() }, groupSuffix).joinToString(" · "),
                isPinned = isPinned,
            )

            is ContentDetailedListModel -> copy(
                id = group.uiId,
                subtitle = listOfNotNull(subtitle.takeIf { !it.isNullOrBlank() }, groupSuffix).joinToString(" · "),
                isPinned = isPinned,
            )

            is ContentGridModel -> copy(
                id = group.uiId,
                isPinned = isPinned,
            )
        }
    }

    private fun FavouriteGroup.groupSuffix(): String? {
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

    private data class FavouriteGroup(
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

    private fun List<Content>.resolveDisplayContentTypeOrdinal(): Int {
        return firstOrNull { !it.source.name.startsWith("TRACKING_") }?.source?.contentType?.ordinal
            ?: first().source.contentType.ordinal
    }

    private fun observeFavorites() = if (categoryId == NO_ID) {
        combine(
            sortOrder.filterNotNull(),
            quickFilter.appliedOptions.combineWithSettings(),
            limit,
        ) { order, filters, limit ->
            isPaginationReady.set(false)
            repository.observeAll(order, filters, limit)
        }.flattenLatest()
    } else {
        combine(
            quickFilter.appliedOptions.combineWithSettings(),
            limit,
        ) { filters, limit ->
            isPaginationReady.set(false)
            repository.observeAll(categoryId, filters, limit)
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
