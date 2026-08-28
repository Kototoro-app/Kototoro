package org.skepsun.kototoro.tracker.ui.updates

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.paging.BatchMappingPagingSource
import org.skepsun.kototoro.core.paging.LargeLibraryPagingConfig
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.util.ext.calculateDateGroup
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.ui.ContentListViewModel
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.scopedToSpace
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.tracker.domain.UpdatesListQuickFilter
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import org.skepsun.kototoro.tracker.work.TrackWorker
import org.skepsun.kototoro.work.domain.WorkResolver
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: TrackingRepository,
    private val scheduler: TrackWorker.Scheduler,
    settings: AppSettings,
    private val mangaListMapper: ContentListMapper,
    private val quickFilter: UpdatesListQuickFilter,
    private val sourceGroupManager: SourceGroupManager,
    private val dataRepository: ContentDataRepository,
    private val workResolver: WorkResolver,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
    private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
    spaceBrowseScope: SpaceBrowseScope,
) : ContentListViewModel(settings, dataRepository, localStorageChanges), QuickFilterListener by quickFilter,
    SpaceBindableViewModel {
    private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)

    @Volatile
    private var groupedRemovalIds: Map<Long, Set<Long>> = emptyMap()

    @Volatile
    private var groupedEntityIds: Map<Long, Long> = emptyMap()

    @Volatile
    private var groupedPreferredLocalIds: Map<Long, Long> = emptyMap()

    private val pagingHeaders = ConcurrentHashMap<Long, ListHeader>()

    override val isFilterBarVisible = MutableStateFlow(true)

    override val currentSourceTags = globalFavoritesState.selectedSourceTags

    override fun setSelectedSourceTags(tags: Set<SourceTag>) {
        globalFavoritesState.setSelectedSourceTags(tags)
    }

    override val currentGroupTab = globalFavoritesState.selectedGroupTab.scopedToSpace(
        spaceGroupTab = spaceBinding.groupTab,
        coroutineScope = viewModelScope + Dispatchers.Default,
    )
    override fun bindSpace(spaceId: org.skepsun.kototoro.space.domain.SpaceId?) = spaceBinding.bindSpace(spaceId)

    override fun setSelectedGroupTab(tab: BrowseGroupTab) {
        globalFavoritesState.setSelectedGroupTab(tab)
    }

    private val refreshTrigger = MutableStateFlow(Any())

    override val hasMoreItems = MutableStateFlow(false)

    val headerQuickFilter: StateFlow<QuickFilter?> = quickFilter.appliedOptions
        .mapLatest { filters -> quickFilter.filterItem(filters) }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    override val pagingContent: Flow<PagingData<ListModel>> = combine(
        refreshTrigger,
        quickFilter.appliedOptions,
        settings.observeAsFlow(AppSettings.KEY_UPDATED_GROUPING) { isUpdatedGroupingEnabled },
        observeListModeWithTriggers(),
        currentGroupTab,
        currentSourceTags,
    ) { values: Array<Any?> ->
        UpdatesPagingParams(
            filters = values[1] as Set<ListFilterOption>,
            grouped = values[2] as Boolean,
            mode = values[3] as ListMode,
            groupTab = values[4] as BrowseGroupTab,
            sourceTags = values[5] as Set<SourceTag>,
        )
    }.flatMapLatest { params ->
        pagingHeaders.clear()
        groupedRemovalIds = emptyMap()
        groupedEntityIds = emptyMap()
        groupedPreferredLocalIds = emptyMap()
        Pager(
            config = LargeLibraryPagingConfig,
            pagingSourceFactory = {
                BatchMappingPagingSource(
                    delegate = repository.createUpdatedPagingSource(params.filters),
                    diagnosticLabel = "updates-ui",
                ) { tracks -> mapUpdatesPage(tracks, params) }
            },
        ).flow.map { pagingData ->
            pagingData.applyUpdatesPagingPresentation(grouped = params.grouped) { model ->
                (model as? ContentListModel)?.let { pagingHeaders[it.id] }
            }
        }
    }.cachedIn(viewModelScope)

    override val content = flowOf(listOf<ListModel>(LoadingState))
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(LoadingState))

    init {
        launchJob(Dispatchers.Default) {
            repository.gcIfNeeded()
        }
    }

    override fun onRefresh() {
        scheduler.startNow()
        refreshTrigger.value = Any()
    }

    override fun onRetry() = Unit

    fun remove(ids: Set<Long>) {
        launchJob(Dispatchers.Default) {
            repository.clearUpdates(
                ids.flatMapTo(LinkedHashSet()) { groupId ->
                    groupedRemovalIds[groupId].orEmpty().ifEmpty { setOf(groupId) }
                },
            )
        }
    }

    fun requestMoreItems() {
        // Paging prefetches from LazyPagingItems access.
    }

    override fun resolveEntityIdForUiItemId(id: Long): Long? {
        return groupedEntityIds[id]
    }

    override fun resolvePreferredLocalMangaIdForUiItemId(id: Long): Long? {
        return groupedPreferredLocalIds[id] ?: groupedRemovalIds[id]?.firstOrNull()
    }

    private suspend fun mapUpdatesPage(
        tracks: List<ContentTracking>,
        params: UpdatesPagingParams,
    ): List<ListModel> {
        val visible = tracks.filterVisible(
            groupTab = params.groupTab,
            sourceTags = params.sourceTags,
        )
        if (visible.isEmpty()) {
            return emptyList()
        }
        val groups = visible.aggregateByEntity()
        if (groups.isEmpty()) {
            return emptyList()
        }
        groupedRemovalIds = groupedRemovalIds + groups.associate { it.uiId to it.mangaIds }
        groupedEntityIds = groupedEntityIds + groups.mapNotNull { group ->
            group.entityId?.let { group.uiId to it }
        }.toMap()
        groupedPreferredLocalIds = groupedPreferredLocalIds + groups.mapNotNull { group ->
            group.preferredLocalMangaId?.let { group.uiId to it }
        }.toMap()
        if (params.grouped) {
            for (group in groups) {
                group.lastChapterDate?.let { date ->
                    calculateDateGroup(date)?.let { header -> pagingHeaders[group.uiId] = ListHeader(header) }
                }
            }
        }
        return groups.map { group ->
            mangaListMapper.toListModel(
                manga = group.representative.manga,
                mode = params.mode,
                metadataSelectionOverride = group.metadataSourceSelection,
                useMetadataSelectionOverride = group.metadataSourceSelection != null,
            ).toGroupedListModel(group)
        }
    }

    private fun List<ContentTracking>.filterVisible(
        groupTab: BrowseGroupTab,
        sourceTags: Set<SourceTag>,
    ): List<ContentTracking> {
        val filtered = filter { item ->
            val source = item.manga.source
            val contentGroup = sourceGroupManager.getContentGroup(source)
            val originGroup = sourceGroupManager.getOriginGroup(source)

            val groupMatches = groupTab.matchesContentGroup(contentGroup) && groupTab.matchesOriginGroup(originGroup)
            val originMatches = if (sourceTags.isEmpty()) {
                true
            } else {
                sourceTags.any { it.matches(contentGroup, originGroup) }
            }

            groupMatches && originMatches
        }

        val hideAdult = settings.isTrackerNsfwDisabled
        val adultFilteredList = if (hideAdult) filtered.filterNot { it.manga.isNsfw() } else filtered
        val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
        return adultFilteredList.filterNot { it.manga in globalTagBlacklist }
    }

    private suspend fun List<ContentTracking>.aggregateByEntity(): List<UpdateGroup> {
        if (isEmpty()) {
            return emptyList()
        }
        val resolvedEntityIds = mapNotNull(ContentTracking::entityId).distinct()
        val preferredLocalIdsByEntity = resolvePreferredLocalIdsByEntity(resolvedEntityIds)
        val metadataSelectionsByEntity = dataRepository.getEntityMetadataSourceSelections(resolvedEntityIds)
        return groupTrackingByEntity(preferredLocalIdsByEntity, metadataSelectionsByEntity)
    }

    private suspend fun resolvePreferredLocalIdsByEntity(entityIds: Collection<Long>): Map<Long, Long?> {
        return workResolver.resolveManyByEntityIds(entityIds)
            .mapValues { (_, identity) -> identity.preferredMangaId }
    }

    private fun ContentListModel.toGroupedListModel(group: UpdateGroup): ListModel {
        val groupSuffix = group.groupSuffix()
        return when (this) {
            is ContentCompactListModel -> copy(
                counter = group.totalNewChapters,
                id = group.uiId,
                subtitle = listOfNotNull(subtitle?.takeIf { it.isNotBlank() }, groupSuffix).joinToString(" · "),
            )
            is ContentDetailedListModel -> copy(
                counter = group.totalNewChapters,
                id = group.uiId,
                subtitle = listOfNotNull(subtitle.takeIf { !it.isNullOrBlank() }, groupSuffix).joinToString(" · "),
            )
            is ContentGridModel -> copy(
                counter = group.totalNewChapters,
                id = group.uiId,
            )
        }
    }

    private fun UpdateGroup.groupSuffix(): String? {
        val projectionLabel = representative.manga.source.getTitle(appContext)
        return if (mangaIds.size > 1) {
            appContext.getString(
                R.string.favourites_entity_current_projection_with_count,
                projectionLabel,
                mangaIds.size,
            )
        } else {
            appContext.getString(R.string.favourites_entity_current_projection, projectionLabel)
        }
    }
}
