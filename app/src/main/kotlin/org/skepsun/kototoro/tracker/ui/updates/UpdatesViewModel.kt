package org.skepsun.kototoro.tracker.ui.updates

import android.content.Context
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.ui.model.DateTimeAgo
import org.skepsun.kototoro.core.util.ext.calculateDateGroup
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
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
import org.skepsun.kototoro.tracker.work.TrackWorker
import org.skepsun.kototoro.tracker.work.UpdateCheckRequest
import org.skepsun.kototoro.tracker.work.messageRes
import javax.inject.Inject

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: TrackingRepository,
    private val scheduler: TrackWorker.Scheduler,
    settings: AppSettings,
    private val quickFilter: UpdatesListQuickFilter,
    dataRepository: ContentDataRepository,
    private val updatesSnapshotStore: org.skepsun.kototoro.tracker.domain.updates.UpdatesSnapshotStore,
    private val updatesCardMapper: org.skepsun.kototoro.tracker.domain.updates.UpdatesCardMapper,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
    private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
    spaceBrowseScope: SpaceBrowseScope,
) : ContentListViewModel(settings, dataRepository, localStorageChanges), QuickFilterListener by quickFilter,
    SpaceBindableViewModel {
    private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)

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

    override val hasMoreItems = MutableStateFlow(false)

    /** One cold-start read shared by list derivation and quick-filter metadata. */
    private val updatesSnapshot = updatesSnapshotStore.observe()
        .onEach(quickFilter::acceptSnapshot)
        .shareIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, replay = 1)

    val headerQuickFilter: StateFlow<QuickFilter?> = combine(
        quickFilter.appliedOptions,
        // Re-emit when the quick-filter visibility toggle changes so filterItem()
        // re-evaluates against the fresh setting (hides/shows the inline bar).
        settings.observeAsFlow(AppSettings.KEY_QUICK_FILTER) { isQuickFilterEnabled },
    ) { filters, _ -> filters }
        .mapLatest { filters -> quickFilter.filterItem(filters) }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    /**
     * The visible entity groups (history-updates-feed komikku-alignment Phase U4):
     * snapshot -> in-memory derivation. Derived once here and re-used by the
     * removal/entity-navigation index, so the whole list is re-derived (never
     * re-queried) whenever a filter, the group tab or a tag changes.
     */
    private val derivedGroups: StateFlow<List<org.skepsun.kototoro.tracker.domain.updates.UpdateGroupRow>> = combine(
        updatesSnapshot,
        quickFilter.appliedOptions,
        currentGroupTab,
        currentSourceTags,
        settings.observeAsFlow(AppSettings.KEY_TRACKER_NO_NSFW) { isTrackerNsfwDisabled },
        settings.observeAsFlow(AppSettings.KEY_GLOBAL_TAG_BLACKLIST) {
            GlobalTagBlacklist(settings.globalTagBlacklist)
        },
    ) { values: Array<Any?> ->
        org.skepsun.kototoro.tracker.domain.updates.UpdatesDeriver.derive(
            org.skepsun.kototoro.tracker.domain.updates.UpdatesDeriver.Input(
                snapshot = values[0] as org.skepsun.kototoro.tracker.domain.updates.UpdatesSnapshot,
                filters = values[1] as Set<ListFilterOption>,
                groupTab = values[2] as BrowseGroupTab,
                sourceTags = values[3] as Set<SourceTag>,
                excludedNsfw = values[4] as Boolean,
                tagBlacklist = values[5] as GlobalTagBlacklist,
            ),
        )
    }.mapLatest { derived ->
        derived.visibleGroups
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    private val groupsById: Map<Long, org.skepsun.kototoro.tracker.domain.updates.UpdateGroupRow>
        get() = derivedGroups.value.associateBy(org.skepsun.kototoro.tracker.domain.updates.UpdateGroupRow::uiId)

    /**
     * The one and only updates list: derived groups mapped to cards and, when the
     * grouping setting is on, a [ListHeader] before each date bucket change. The
     * paging chain (Pager + per-page aggregation + insertSeparators) is gone.
     */
    override val content: StateFlow<List<ListModel>> = combine(
        derivedGroups,
        settings.observeAsFlow(AppSettings.KEY_UPDATED_GROUPING) { isUpdatedGroupingEnabled },
        observeListModeWithTriggers(),
    ) { groups, grouped, mode ->
        buildStaticContent(groups, grouped, mode)
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

    init {
        launchJob(Dispatchers.Default) {
            repository.gcIfNeeded()
        }
    }

    override fun onRefresh() {
        launchJob(Dispatchers.Default) {
            val request = scheduler.requestCheckNow()
            onContentMessage.call(appContext.getString(request.messageRes()))
        }
    }

    override fun onRetry() = Unit

    fun remove(ids: Set<Long>) {
        launchJob(Dispatchers.Default) {
            repository.clearUpdates(
                ids.flatMapTo(LinkedHashSet()) { groupId ->
                    groupsById[groupId]?.mangaIds.orEmpty().ifEmpty { setOf(groupId) }
                },
            )
        }
    }

    fun requestMoreItems() {
        // Paging prefetches from LazyPagingItems access.
    }

    override fun resolveEntityIdForUiItemId(id: Long): Long? {
        return groupsById[id]?.entityId
    }

    override fun resolvePreferredLocalMangaIdForUiItemId(id: Long): Long? {
        val group = groupsById[id] ?: return null
        return group.displayMangaId ?: group.mangaIds.firstOrNull()
    }

    private fun buildStaticContent(
        groups: List<org.skepsun.kototoro.tracker.domain.updates.UpdateGroupRow>,
        grouped: Boolean,
        mode: ListMode,
    ): List<ListModel> {
        if (groups.isEmpty()) {
            return emptyList()
        }
        val cards = updatesCardMapper.map(
            groups,
            org.skepsun.kototoro.tracker.domain.updates.UpdatesCardMapper.Slice(mode = mode),
        )
        if (!grouped) {
            return cards
        }
        val result = ArrayList<ListModel>(cards.size + 8)
        var currentHeader: DateTimeAgo? = null
        val headers = HashMap<Long, DateTimeAgo?>(cards.size)
        for (group in groups) {
            headers[group.uiId] = group.lastChapterDate
                ?.let { java.time.Instant.ofEpochMilli(it) }
                ?.let { calculateDateGroup(it) }
        }
        for (card in cards) {
            val header = headers[card.id]
            if (header != null && header != currentHeader) {
                result += ListHeader(header)
                currentHeader = header
            }
            result += card
        }
        return result
    }
}
