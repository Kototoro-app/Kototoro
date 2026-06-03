package org.skepsun.kototoro.favourites.ui.list

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.flattenLatest
import org.skepsun.kototoro.favourites.domain.FavoritesListQuickFilter
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.history.domain.MarkAsReadUseCase
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.ui.ContentListViewModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.toErrorState
import org.skepsun.kototoro.parsers.model.Content
import java.util.concurrent.atomic.AtomicBoolean
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.model.LocalContent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.list.ui.model.QuickFilter
import javax.inject.Inject

private const val ALL_FAVORITES_INITIAL_PAGE_SIZE = 96
private const val FAVORITES_INITIAL_PAGE_SIZE = 64
private const val FAVORITES_PAGE_SIZE = 64

private data class FavoritesListInputs(
	val mode: ListMode,
	val groupTab: BrowseGroupTab,
	val sourceTags: Set<SourceTag>,
	val categoryIds: Set<Long>,
	val preset: org.skepsun.kototoro.explore.data.SourcePreset?,
)

@HiltViewModel
class FavouritesListViewModel @Inject constructor(
	private val repository: FavouritesRepository,
	private val mangaListMapper: ContentListMapper,
	private val markAsReadUseCase: MarkAsReadUseCase,
	private val quickFilterFactory: FavoritesListQuickFilter.Factory,
	private val sourceGroupManager: SourceGroupManager,
	settings: AppSettings,
	mangaDataRepository: ContentDataRepository,
	private val sourcePresetsRepository: org.skepsun.kototoro.explore.data.SourcePresetsRepository,
	@LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
	private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
) : ContentListViewModel(settings, mangaDataRepository, localStorageChanges), QuickFilterListener {

	private val selectedFavoriteCategoryId = MutableStateFlow(NO_ID)
	val categoryId: Long
		get() = selectedFavoriteCategoryId.value
	private val activeQuickFilter = selectedFavoriteCategoryId
		.mapLatest(quickFilterFactory::create)
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, quickFilterFactory.create(NO_ID))
	private val refreshTrigger = MutableStateFlow(Any())
	private val pageSize = FAVORITES_PAGE_SIZE
	private val limit = MutableStateFlow(initialLimitFor(NO_ID))
	private val isPaginationReady = AtomicBoolean(false)
	@Volatile
	private var lastObservedFavoriteCount = 0
	private val _hasMoreItems = MutableStateFlow(true)
	override val hasMoreItems: StateFlow<Boolean> = _hasMoreItems

	override val isFilterBarVisible = MutableStateFlow(false)

	override val currentSourceTags = globalFavoritesState.selectedSourceTags

	override fun setSelectedSourceTags(tags: Set<org.skepsun.kototoro.explore.ui.model.SourceTag>) {
		globalFavoritesState.setSelectedSourceTags(tags)
	}

	override val currentGroupTab = globalFavoritesState.selectedGroupTab

	override fun setSelectedGroupTab(tab: org.skepsun.kototoro.explore.ui.model.BrowseGroupTab) {
		globalFavoritesState.setSelectedGroupTab(tab)
	}

	override val availableCategories = flowOf(emptyList<FavouriteCategory>())
		.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

	override val listMode = settings.observeAsFlow(AppSettings.KEY_LIST_MODE) { this.listMode }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.listMode)

	val topQuickFilter = combine(
		activeQuickFilter,
		globalFavoritesState.appliedFilter.combineWithSettings(),
	) { quickFilter, filters ->
		quickFilter to filters
	}.mapLatest { (quickFilter, filters) ->
		quickFilter.filterItem(filters)
	}
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null as QuickFilter?)

	val sortOrder: StateFlow<ListSortOrder?> = selectedFavoriteCategoryId
		.flatMapLatest { categoryId ->
			if (categoryId == NO_ID) {
				settings.observeAsFlow(AppSettings.KEY_FAVORITES_ORDER) {
					allFavoritesSortOrder
				}
			} else {
				repository.observeCategory(categoryId)
					.withErrorHandling()
					.map { it?.order }
			}
		}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	private val inputTriggers = merge(
		mangaListMapper.observeDisplayChanges().onStart { emit(Unit) },
		refreshTrigger.map { Unit },
	)

	private data class FavoritesFilterInputs(
		val groupTab: BrowseGroupTab,
		val sourceTags: Set<SourceTag>,
		val categoryIds: Set<Long>,
	)

	private val filterInputs = combine(
		currentGroupTab,
		currentSourceTags,
		selectedCategoryIds,
	) { groupTab, sourceTags, categoryIds ->
		FavoritesFilterInputs(
			groupTab = groupTab,
			sourceTags = sourceTags,
			categoryIds = categoryIds,
		)
	}

	private val displayInputs = combine(
		observeListModeWithTriggers(),
		filterInputs,
		settings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId }
			.flatMapLatest { id ->
				if (id == -1L) flowOf(null)
				else sourcePresetsRepository.observe(id)
			},
		inputTriggers,
	) { mode, filterInputs, preset, _ ->
		FavoritesListInputs(
			mode = mode,
			groupTab = filterInputs.groupTab,
			sourceTags = filterInputs.sourceTags,
			categoryIds = filterInputs.categoryIds,
			preset = preset,
		)
	}

	override val content = combine(
		observeFavorites(),
		activeQuickFilter,
		globalFavoritesState.appliedFilter,
		displayInputs,
	) { list, quickFilter, filters, inputs ->
		mapList(list, quickFilter, filters, inputs.mode, inputs.groupTab, inputs.sourceTags, inputs.categoryIds, inputs.preset)
	}.onEach { models ->
		_hasMoreItems.value = lastObservedFavoriteCount >= limit.value
		isPaginationReady.set(true)
	}.distinctUntilChanged().catch {
		emit(listOf(it.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	override fun onRefresh() {
		refreshTrigger.value = Any()
	}

	override fun onRetry() = Unit

	override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) =
		activeQuickFilter.value.setFilterOption(option, isApplied)

	override fun toggleFilterOption(option: ListFilterOption) = activeQuickFilter.value.toggleFilterOption(option)

	override fun clearFilter() = activeQuickFilter.value.clearFilter()

	fun setCategoryId(categoryId: Long) {
		if (selectedFavoriteCategoryId.value == categoryId) {
			return
		}
		selectedFavoriteCategoryId.value = categoryId
		limit.value = initialLimitFor(categoryId)
		isPaginationReady.set(false)
		_hasMoreItems.value = true
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
			val handle = if (categoryId == NO_ID) {
				repository.removeFromFavourites(ids)
			} else {
				repository.removeFromCategory(categoryId, ids)
			}
			onActionDone.call(ReversibleAction(R.string.removed_from_favourites, handle))
		}
	}

	suspend fun isPinned(ids: Set<Long>): Boolean {
		return repository.isPinned(ids)
	}

	fun setPinned(ids: Set<Long>, isPinned: Boolean) {
		launchJob(Dispatchers.Default) {
			repository.setPinned(ids, isPinned)
			onRefresh()
		}
	}

	fun togglePinned(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val currentlyPinned = repository.isPinned(ids)
			repository.setPinned(ids, !currentlyPinned)
			onRefresh()
		}
	}

	fun setSortOrder(order: ListSortOrder) {
		val selectedCategoryId = categoryId
		if (selectedCategoryId == NO_ID) {
			return
		}
		launchJob {
			repository.setCategoryOrder(selectedCategoryId, order)
		}
	}

	fun requestMoreItems() {
		if (!_hasMoreItems.value) {
			return
		}
		val didRequest = isPaginationReady.compareAndSet(true, false)
		if (didRequest) {
			limit.value += pageSize
		}
	}

	private suspend fun mapList(
		list: List<Content>,
		quickFilter: FavoritesListQuickFilter,
		filters: Set<ListFilterOption>,
		mode: ListMode,
		groupTab: BrowseGroupTab,
		sourceTags: Set<SourceTag>,
		categoryIds: Set<Long>,
		preset: org.skepsun.kototoro.explore.data.SourcePreset?,
	): List<ListModel> {
		lastObservedFavoriteCount = list.size
		val filteredList = list.filter { manga ->
			val source = manga.source
			if (preset != null && source.name !in preset.sources) {
				return@filter false
			}

			val contentGroup = sourceGroupManager.getContentGroup(source)
			val originGroup = sourceGroupManager.getOriginGroup(source)

			val groupMatches = groupTab.matchesContentGroup(contentGroup) && groupTab.matchesOriginGroup(originGroup)
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
			val models = mutableListOf<ListModel>()
			quickFilter.filterItem(filters)?.let(models::add)
			if (hideAdult && adultItems.isNotEmpty()) {
				models.add(
					org.skepsun.kototoro.list.ui.model.InfoModel(
						key = "hidden_nsfw_favourites",
						title = R.string.favourites_hidden_adult_title,
						text = R.string.favourites_hidden_adult_subtitle,
						icon = org.skepsun.kototoro.R.drawable.ic_eye_off,
					)
				)
			}
			models.add(
				if (filters.isEmpty() && groupTab == BrowseGroupTab.All && sourceTags.isEmpty() && categoryIds.isEmpty()) {
					getEmptyState(hasFilters = false)
				} else {
					getEmptyState(hasFilters = true)
				}
			)
			return models
		}

		val pinnedIds = repository.getPinnedIds(visibleItems.map { it.id })
		val result = ArrayList<ListModel>(visibleItems.size + 1)
		quickFilter.filterItem(filters)?.let(result::add)
		mangaListMapper.toListModelList(
			destination = result,
			manga = visibleItems,
			mode = mode,
			flags = ContentListMapper.NO_FAVORITE,
			pinnedIds = pinnedIds,
		)
		return result
	}

	private fun observeFavorites() = combine(
		selectedFavoriteCategoryId,
		sortOrder.filterNotNull(),
		globalFavoritesState.appliedFilter.combineWithSettings(),
		limit,
	) { categoryId, order, filters, limit ->
		isPaginationReady.set(false)
		if (categoryId == NO_ID) {
			repository.observeAll(order, filters, limit)
		} else {
			repository.observeAll(categoryId, order, filters, limit)
		}
	}.flattenLatest()

	private fun initialLimitFor(categoryId: Long): Int {
		return if (categoryId == NO_ID) ALL_FAVORITES_INITIAL_PAGE_SIZE else FAVORITES_INITIAL_PAGE_SIZE
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
