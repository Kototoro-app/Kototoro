package org.skepsun.kototoro.local.ui

import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.toChipModel
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.ui.widgets.ChipsView
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.toFileOrNull
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.domain.ExploreRepository
import org.skepsun.kototoro.filter.ui.FilterCoordinator
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.list.ui.model.TipModel
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.local.domain.DeleteLocalContentUseCase
import org.skepsun.kototoro.local.domain.model.LocalContent
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
	@param:LocalStorageChanges private val localStorageChanges: SharedFlow<LocalContent?>,
	private val localStorageManager: LocalStorageManager,
	sourcesRepository: ContentSourcesRepository,
	sourceAvailabilityRepository: SourceAvailabilityRepository,
	mangaDataRepository: ContentDataRepository,
	private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
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
	localStorageChanges = localStorageChanges,
), SharedPreferences.OnSharedPreferenceChangeListener, QuickFilterListener {

	val onContentRemoved = MutableEventFlow<Unit>()

	override val currentGroupTab: StateFlow<BrowseGroupTab> = globalFavoritesState.selectedGroupTab

	init {
		launchJob(Dispatchers.Default) {
			localStorageChanges
				.collect {
					loadList(filterCoordinator.snapshot(), append = false).join()
				}
		}
		settings.subscribe(this)
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

	override fun clearFilter() = filterCoordinator.reset()

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
		if (key == AppSettings.KEY_LOCAL_MANGA_DIRS) {
			onRefresh()
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

	override fun createEmptyState(canResetFilter: Boolean): EmptyState = if (canResetFilter) {
		super.createEmptyState(true)
	} else {
		EmptyState(
			icon = R.drawable.ic_empty_local,
			textPrimary = R.string.text_local_holder_primary,
			textSecondary = R.string.text_local_holder_secondary,
			actionStringRes = R.string._import,
		)
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
		val result = ArrayList<ChipsView.ChipModel>(appliedTags.size + availableTags.size)
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
