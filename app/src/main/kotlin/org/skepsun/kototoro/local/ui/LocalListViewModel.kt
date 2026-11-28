package org.skepsun.kototoro.local.ui

import org.skepsun.kototoro.search.domain.LibrarySearchScope
import org.skepsun.kototoro.search.domain.LibrarySearchQueries
import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.toChipModel
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.toFileOrNull
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.details.domain.DetailsLoadUseCase
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.domain.ExploreRepository
import org.skepsun.kototoro.filter.ui.FilterCoordinator
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.list.ui.model.TipModel
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.local.domain.DeleteLocalContentUseCase
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.local.domain.model.LocalReadStatusFilter
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.explore.data.SourceAvailabilityRepository
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.remotelist.ui.RemoteListViewModel
import javax.inject.Inject

@HiltViewModel
class LocalListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    mangaRepositoryFactory: ContentRepository.Factory,
    filterCoordinator: FilterCoordinator,
    settings: AppSettings,
    mangaListMapper: ContentListMapper,
    private val deleteLocalContentUseCase: DeleteLocalContentUseCase,
    exploreRepository: ExploreRepository,
    @param:LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
    private val localStorageManager: LocalStorageManager,
    sourcesRepository: ContentSourcesRepository,
    sourceAvailabilityRepository: SourceAvailabilityRepository,
    mangaDataRepository: ContentDataRepository,
    captchaAutoResolveCoordinator: org.skepsun.kototoro.core.exceptions.resolve.CaptchaAutoResolveCoordinator,
    detailsLoadUseCase: DetailsLoadUseCase,
    private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
    private val localContentIndex: LocalContentIndex,
    private val librarySearchQueries: LibrarySearchQueries,
) : RemoteListViewModel(
    savedStateHandle = savedStateHandle,
    mangaRepositoryFactory = mangaRepositoryFactory,
    filterCoordinator = filterCoordinator,
    settings = settings,
    mangaListMapper = mangaListMapper,
    exploreRepository = exploreRepository,
    sourcesRepository = sourcesRepository,
    sourceAvailabilityRepository = sourceAvailabilityRepository,
    mangaDataRepository = mangaDataRepository,
    captchaAutoResolveCoordinator = captchaAutoResolveCoordinator,
    detailsLoadUseCase = detailsLoadUseCase,
    localStorageChanges = localStorageChanges,
), SharedPreferences.OnSharedPreferenceChangeListener, QuickFilterListener {

    override fun displaySection() = org.skepsun.kototoro.list.ui.ListDisplaySection.LOCAL

    val onContentRemoved = MutableEventFlow<Unit>()

    override val currentGroupTab: StateFlow<BrowseGroupTab> = globalFavoritesState.selectedGroupTab

    /** Text typed in the overlay's "Local" tab; applied as the local repository's own query. */
    val libraryQuery: StateFlow<String> = librarySearchQueries.query(LibrarySearchScope.LOCAL)

    /** 当前排序，换算成共享排序控件使用的 [ListSortOrder]。 */
    val sortOrder: StateFlow<ListSortOrder> = filterCoordinator.sortOrder
        .map { property -> property.selectedItems.firstOrNull() ?: settings.localListOrder }
        .map { order -> order.toLocalListSortOrder() }
        .stateIn(
            scope = viewModelScope + Dispatchers.Default,
            started = SharingStarted.Eagerly,
            initialValue = settings.localListOrder.toLocalListSortOrder(),
        )

    /** 阅读状态筛选（全部/未读/在读/已读完）。 */
    val readStatusFilter: StateFlow<LocalReadStatusFilter> = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_LOCAL_READ_STATUS_FILTER,
        valueProducer = { localReadStatusFilter },
    )

    /**
     * 切换排序。[FilterCoordinator] 会把值写回 `repository.defaultSortOrder`
     * （即 `settings.localListOrder`），并触发列表重新加载，因此这里不需要额外刷新。
     */
    fun setSortOrder(order: ListSortOrder) {
        filterCoordinator.setSortOrder(order.toLocalRepositorySortOrder())
    }

    /**
     * 切换阅读状态筛选。该条件在仓库层生效，不是 [FilterCoordinator] 的一部分，
     * 所以需要手动触发一次重载。
     */
    fun setReadStatusFilter(filter: LocalReadStatusFilter) {
        if (settings.localReadStatusFilter == filter) {
            return
        }
        settings.localReadStatusFilter = filter
        launchJob(Dispatchers.Default) {
            reloadListAfterCurrentLoad()
        }
    }

    fun clearLibraryQuery() = librarySearchQueries.clear(LibrarySearchScope.LOCAL)

    init {
        launchJob(Dispatchers.Default) {
            // Only touch the filter query once the page tab has set one, so a query that came
            // from elsewhere is not wiped by the initial empty value.
            var applied: String? = null
            libraryQuery.collect { query ->
                if (query.isNotEmpty() || applied != null) {
                    filterCoordinator.setQuery(query.ifEmpty { null })
                    applied = query.ifEmpty { null }
                }
            }
        }
        settings.subscribe(this)
        // 兜底：索引里一条记录都没有（首次安装 / 换设备恢复备份）时扫描一次，
        // 否则「打开不扫描」会让用户看到空列表。已有内容时绝不扫描。
        launchJob(Dispatchers.IO) {
            if (!localContentIndex.hasEntries()) {
                runCatching { localContentIndex.update() }
                reloadListAfterCurrentLoad()
            }
        }
    }

    override suspend fun onBuildList(list: MutableList<ListModel>) {
        super.onBuildList(list)
        createFilterHeader()?.let {
            list.add(0, it)
        }
        if (!localStorageManager.hasExternalStoragePermission(isReadOnly = true)) {
            for (item in list) {
                if (item !is ContentListModel) {
                    continue
                }
                val file = item.manga.url.toUriOrNull()?.toFileOrNull() ?: continue
                if (localStorageManager.isOnExternalStorage(file)) {
                    val tip = TipModel(
                        key = "permission",
                        title = R.string.external_storage,
                        text = R.string.missing_storage_permission,
                        icon = R.drawable.ic_storage,
                        primaryButtonText = R.string.fix,
                        secondaryButtonText = R.string.settings,
                    )
                    list.add(0, tip)
                    return
                }
            }
        }
    }

    override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) {
        if (option is ListFilterOption.Tag) {
            filterCoordinator.toggleTag(option.tag, isApplied)
        }
    }

    override fun toggleFilterOption(option: ListFilterOption) {
        if (option is ListFilterOption.Tag) {
            val isSelected = option.tag in filterCoordinator.snapshot().listFilter.tags
            filterCoordinator.toggleTag(option.tag, !isSelected)
        }
    }

    override fun clearFilter() {
        clearLibraryQuery()
        filterCoordinator.reset()
        setReadStatusFilter(LocalReadStatusFilter.ALL)
    }

    /**
     * 将 BrowseGroupTab（内容类型胶囊）映射到 filterCoordinator 的 ContentType 过滤。
     * 本地内容支持漫画/小说/视频三种类型，通过 LocalMangaRepository.getList() 的 filter.types 过滤。
     */
    override fun setSelectedGroupTab(tab: BrowseGroupTab) {
        globalFavoritesState.setSelectedGroupTab(tab)
        val types = when (tab) {
            BrowseGroupTab.Content -> setOf(ContentType.MANGA, ContentType.HENTAI_MANGA)
            BrowseGroupTab.Novel -> setOf(ContentType.NOVEL, ContentType.HENTAI_NOVEL)
            BrowseGroupTab.Video -> setOf(ContentType.VIDEO, ContentType.HENTAI_VIDEO)
            BrowseGroupTab.All -> emptySet()
        }
        // 清除旧的内容类型过滤，再设置新的
        val currentFilter = filterCoordinator.snapshot().listFilter
        val nonTypeFilter = currentFilter.copy(types = emptySet())
        filterCoordinator.set(nonTypeFilter)
        types.forEach { type -> filterCoordinator.toggleContentType(type, isSelected = true) }
    }

    /**
     * 本地内容全部来自 BUILTIN 来源，来源标签过滤对本地页无实际意义，忽略即可。
     */
    override fun setSelectedSourceTags(tags: Set<SourceTag>) {
        super.setSelectedSourceTags(tags)
        // 本地内容不按来源标签过滤，不需要桥接到 filterCoordinator
    }

    override fun onCleared() {
        settings.unsubscribe(this)
        super.onCleared()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == null || key in setOf(
                AppSettings.KEY_LOCAL_MANGA_DIRS,
                AppSettings.KEY_LOCAL_MANGA_DIR_URIS,
                AppSettings.KEY_LOCAL_NOVEL_DIR_URIS,
                AppSettings.KEY_LOCAL_VIDEO_DIR_URIS,
                AppSettings.KEY_LOCAL_NOVEL_STORAGE,
                AppSettings.KEY_LOCAL_NOVEL_STORAGE_URI,
                AppSettings.KEY_LOCAL_VIDEO_STORAGE,
                AppSettings.KEY_LOCAL_VIDEO_STORAGE_URI,
            )
        ) {
            launchJob(Dispatchers.IO) {
                // The committed index change reloads the list after newly selected roots are scanned.
                localContentIndex.updateIfRequired()
            }
        }
    }

    /**
     * 下拉刷新：唯一会重新扫描本地目录、重新读取清单的入口。
     * 打开 App 或切回本地页时都不再自动刷新，直接展示库里已有的内容。
     */
    override fun onRefresh() {
        userRefreshing.value = true
        launchJob(Dispatchers.IO) {
            try {
                // 显式下拉时才做全量扫描，用户能看到刷新指示器，等待是可预期的。
                runCatching { localContentIndex.update() }
                reloadListAfterCurrentLoad()
            } finally {
                userRefreshing.value = false
            }
        }
    }

    fun delete(ids: Set<Long>) {
        launchLoadingJob(Dispatchers.Default) {
            deleteLocalContentUseCase(ids)
            onContentRemoved.call(Unit)
        }
    }

    override suspend fun mapContentList(
        destination: MutableCollection<in ListModel>,
        manga: Collection<Content>,
        mode: ListMode
    ) = mangaListMapper.toListModelList(destination, manga, mode, ContentListMapper.NO_SAVED)

    override fun createEmptyState(canResetFilter: Boolean): EmptyState {
        // 阅读状态筛选不是 FilterCoordinator 的一部分，空态是否给"重置"要把它也算进来。
        val hasLibraryFilter = canResetFilter || settings.localReadStatusFilter != LocalReadStatusFilter.ALL
        return if (hasLibraryFilter) {
            super.createEmptyState(true)
        } else {
            EmptyState(
                icon = R.drawable.ic_empty_local,
                textPrimary = R.string.text_local_holder_primary,
                textSecondary = R.string.text_local_holder_secondary,
                actionStringRes = R.string._import,
            )
        }
    }

    override fun resolveInitialSource(savedStateHandle: SavedStateHandle): ContentSource {
        return LocalMangaSource
    }

    private suspend fun createFilterHeader(): QuickFilter? {
        val appliedTags = filterCoordinator.snapshot().listFilter.tags
            .sortedBy(ContentTag::title)
        val availableTags = repository.getFilterOptions().availableTags
            .sortedBy(ContentTag::title)
        if (appliedTags.isEmpty() && availableTags.isEmpty()) {
            return null
        }
        val result = ArrayList<ChipModel>(appliedTags.size + availableTags.size)
        appliedTags.mapTo(result) { tag ->
            ListFilterOption.Tag(tag).toChipModel(isChecked = true)
        }
        for (tag in availableTags) {
            if (tag in appliedTags) {
                continue
            }
            result.add(ListFilterOption.Tag(tag).toChipModel(isChecked = false))
        }
        return QuickFilter(result)
    }
}
