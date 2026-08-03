package org.skepsun.kototoro.history.ui

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.EmptyHistoryException
import org.skepsun.kototoro.core.model.LocalVideoSource
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.looksLikeLocalVideoContent
import org.skepsun.kototoro.core.model.looksLikeVideoUrl
import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.calculateTimeAgo
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.domain.HistoryListQuickFilter
import org.skepsun.kototoro.history.domain.MarkAsReadUseCase
import org.skepsun.kototoro.history.domain.model.ContentWithHistory
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.list.ui.ContentListViewModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.InfoModel
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.toErrorState
import org.skepsun.kototoro.parsers.model.Content
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.model.LocalContent
import kotlinx.coroutines.flow.SharedFlow
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.list.ui.model.QuickFilter
import org.skepsun.kototoro.work.domain.WorkResolver
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.scopedToSpace

private const val PAGE_SIZE = 32

private data class HistoryUiParams(
	val order: ListSortOrder,
	val filters: Set<ListFilterOption>,
	val effectiveFilters: Set<ListFilterOption>,
	val grouped: Boolean,
	val mode: ListMode,
	val incognito: Boolean,
	val groupTab: BrowseGroupTab,
	val sourceTags: Set<SourceTag>,
	val preset: org.skepsun.kototoro.explore.data.SourcePreset?,
	val spaceId: SpaceId?,
)

@HiltViewModel
class HistoryListViewModel @Inject constructor(
	@ApplicationContext private val appContext: Context,
	private val repository: HistoryRepository,
	settings: AppSettings,
	private val mangaListMapper: ContentListMapper,
	private val favouritesRepository: FavouritesRepository,
	private val markAsReadUseCase: MarkAsReadUseCase,
	private val quickFilter: HistoryListQuickFilter,
	private val sourceGroupManager: SourceGroupManager,
	private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
	private val networkState: NetworkState,
	private val dataRepository: ContentDataRepository,
	@LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
	private val sourcePresetsRepository: org.skepsun.kototoro.explore.data.SourcePresetsRepository,
	private val entityGraphRepository: EntityGraphRepository,
	private val historyPreviewCache: HistoryPreviewCache,
	private val workResolver: WorkResolver,
	spaceBrowseScope: SpaceBrowseScope,
) : ContentListViewModel(settings, dataRepository, localStorageChanges), QuickFilterListener, SpaceBindableViewModel {
	private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)

	@Volatile
	private var groupedHistoryIds: Map<Long, Set<Long>> = emptyMap()

	@Volatile
	private var groupedEntityIds: Map<Long, Long> = emptyMap()

	@Volatile
	private var groupedPreferredLocalIds: Map<Long, Long> = emptyMap()
	val onOpenReader = MutableEventFlow<Content>()

	override val isFilterBarVisible = MutableStateFlow(true)
	private val refreshTrigger = MutableStateFlow(Any())
	private val activeSpaceScope = spaceBinding.spaceId


	override val currentGroupTab = globalFavoritesState.selectedGroupTab.scopedToSpace(
		spaceGroupTab = spaceBinding.groupTab,
		coroutineScope = viewModelScope + Dispatchers.Default,
	)
	override fun bindSpace(spaceId: SpaceId?) = spaceBinding.bindSpace(spaceId)
	override val currentSourceTags = globalFavoritesState.selectedSourceTags

	override fun setSelectedGroupTab(tab: BrowseGroupTab) {
		globalFavoritesState.setSelectedGroupTab(tab)
	}

	override fun setSelectedSourceTags(tags: Set<SourceTag>) {
		globalFavoritesState.setSelectedSourceTags(tags)
	}

	private val sortOrder: StateFlow<ListSortOrder> = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.IO,
		key = AppSettings.KEY_HISTORY_ORDER,
		valueProducer = { historySortOrder },
	)

	override val listMode = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_LIST_MODE,
		valueProducer = { settings.listMode },
	)

	private val isGroupingEnabled = settings.observeAsFlow(
		key = AppSettings.KEY_HISTORY_GROUPING,
		valueProducer = { isHistoryGroupingEnabled },
	).combine(sortOrder) { g, s ->
		g && s.isGroupingSupported()
	}

	private val limit = MutableStateFlow(PAGE_SIZE)
	private val isPaginationReady = AtomicBoolean(false)

	val isStatsEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_STATS_ENABLED,
		valueProducer = { isStatsEnabled },
	)

	val isResumeEnabled = combine(
		activeSpaceScope.flatMapLatest(repository::observeLast),
		networkState,
	) { last, isOnline ->
		last != null && (isOnline || last.isLocal)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), false)

	val headerQuickFilter: StateFlow<QuickFilter?> = quickFilter.appliedOptions
		.flatMapLatest { selectedOptions ->
			flow {
				if (!settings.isQuickFilterEnabled) {
					emit(null)
					return@flow
				}
				historyPreviewCache.observe().value?.quickFilter
					?.syncSelection(selectedOptions)
					?.let { emit(it) }
				emit(quickFilter.filterItem(selectedOptions))
			}
		}
		.distinctUntilChanged()
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			historyPreviewCache.observe().value?.quickFilter,
		)

	private val uiParams = combine(
		sortOrder,
		quickFilter.appliedOptions,
		isGroupingEnabled,
		observeListModeWithTriggers(),
		settings.observeAsFlow(AppSettings.KEY_INCOGNITO_MODE) { isIncognitoModeEnabled },
		this.currentGroupTab,
		this.currentSourceTags,
		refreshTrigger,
		settings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId }
			.flatMapLatest { id ->
				if (id == -1L) flowOf(null)
				else sourcePresetsRepository.observe(id)
			},
		settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
		activeSpaceScope,
	) { values: Array<Any?> ->
		val order = values[0] as ListSortOrder
		val filters = values[1] as Set<ListFilterOption>
		val grouped = values[2] as Boolean
		val mode = values[3] as ListMode
		val incognito = values[4] as Boolean
		val groupTab = values[5] as BrowseGroupTab
		val sourceTags = values[6] as Set<SourceTag>
		val preset = values[8] as? org.skepsun.kototoro.explore.data.SourcePreset
		val skipNsfw = values[9] as Boolean
		val spaceId = values[10] as? SpaceId
		HistoryUiParams(
			order = order,
			filters = filters,
			effectiveFilters = if (skipNsfw) filters + ListFilterOption.SFW else filters,
			grouped = grouped,
			mode = mode,
			incognito = incognito,
			groupTab = groupTab,
			sourceTags = sourceTags,
			preset = preset,
			spaceId = spaceId,
		)
	}.distinctUntilChanged()

	override val content = uiParams.flatMapLatest { params ->
		flow {
			isPaginationReady.set(false)
			buildPreviewStateOrNull(params)?.let { emit(it) } ?: emit(listOf(LoadingState))
			emitAll(
				combine(
					observeHistory(params.order, params.effectiveFilters, params.spaceId).onEach {
						isPaginationReady.set(true)
					},
					mangaListMapper.observeDisplayChanges().onStart { emit(Unit) },
				) { list, _ ->
					mapList(
						list = list,
						grouped = params.grouped,
						mode = params.mode,
						filters = params.filters,
						isIncognito = params.incognito,
						groupTab = params.groupTab,
						sourceTags = params.sourceTags,
						preset = params.preset,
					)
				},
			)
		}
	}.distinctUntilChanged().catch { e ->
		emit(listOf(e.toErrorState(canRetry = false)))
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		buildInitialPreviewStateOrNull(
			HistoryUiParams(
				order = sortOrder.value,
				filters = quickFilter.appliedOptions.value,
				effectiveFilters = if (settings.isNsfwContentDisabled) quickFilter.appliedOptions.value + ListFilterOption.SFW
				else quickFilter.appliedOptions.value,
				grouped = settings.isHistoryGroupingEnabled && sortOrder.value.isGroupingSupported(),
				mode = listMode.value,
				incognito = settings.isIncognitoModeEnabled,
				groupTab = currentGroupTab.value,
				sourceTags = currentSourceTags.value,
				preset = historyPreviewCache.observe().value?.preset,
				spaceId = activeSpaceScope.value,
			),
		) ?: listOf(LoadingState),
	)

	override fun onRefresh() {
		refreshTrigger.value = Any()
	}

	override fun onRetry() = Unit

	override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) {
		quickFilter.setFilterOption(option, isApplied)
	}

	override fun toggleFilterOption(option: ListFilterOption) {
		quickFilter.toggleFilterOption(option)
	}

	override fun clearFilter() {
		quickFilter.clearFilter()
	}

	fun clearHistory(minDate: Instant?) {
		launchJob(Dispatchers.Default) {
			val stringRes = if (minDate == null) {
				repository.clear()
				R.string.history_cleared
			} else {
				repository.deleteAfter(minDate.toEpochMilli())
				R.string.removed_from_history
			}
			onActionDone.call(ReversibleAction(stringRes, null))
		}
	}

	fun removeNotFavorite() {
		launchJob(Dispatchers.Default) {
			repository.deleteNotFavorite()
			onActionDone.call(ReversibleAction(R.string.removed_from_history, null))
		}
	}

	fun removeFromHistory(ids: Set<Long>) {
		if (ids.isEmpty()) {
			return
		}
		launchJob(Dispatchers.Default) {
			val handle = repository.delete(ids.expandGroupedIds())
			onActionDone.call(ReversibleAction(R.string.removed_from_history, handle))
		}
	}

	fun markAsRead(items: Set<Content>) {
		launchLoadingJob(Dispatchers.Default) {
			markAsReadUseCase(items)
		}
	}

	fun requestMoreItems() {
		if (isPaginationReady.compareAndSet(true, false)) {
			limit.value += PAGE_SIZE
		}
	}

	fun openLastReader() {
		launchLoadingJob(Dispatchers.Default) {
			val content = repository.getLastOrNull(activeSpaceScope.value) ?: throw EmptyHistoryException()
			val manga = content.let {
				if (it.looksLikeLocalVideoContent()) {
					it.copy(
						source = LocalVideoSource,
						chapters = it.chapters?.map { chapter ->
							if (chapter.url.looksLikeVideoUrl()) chapter.copy(source = LocalVideoSource) else chapter
						},
					)
				} else {
					it
				}
			}
			onOpenReader.call(manga)
		}
	}

	private fun observeHistory(
		order: ListSortOrder,
		effectiveFilters: Set<ListFilterOption>,
		spaceId: SpaceId?,
	) = limit.flatMapLatest { currentLimit ->
		repository.observeAllWithHistory(order, effectiveFilters, currentLimit, spaceId)
	}

	private suspend fun buildPreviewStateOrNull(
		params: HistoryUiParams,
	): List<ListModel>? {
		if (params.spaceId != null) {
			return null
		}
		val snapshot = historyPreviewCache.observe().value ?: return null
		val currentPresetId = settings.activeSourcePresetId.takeIf { it != -1L }
		if (
			snapshot.filters.isNotEmpty() ||
			params.order != snapshot.sortOrder ||
			params.grouped != snapshot.isGroupingEnabled ||
			params.groupTab != snapshot.groupTab ||
			params.sourceTags != snapshot.sourceTags ||
			params.mode != snapshot.listMode ||
			params.incognito != snapshot.isIncognito ||
			settings.isHistoryExcludeNsfw != snapshot.isHistoryExcludeNsfw ||
			currentPresetId != snapshot.preset?.id ||
			limit.value > PAGE_SIZE
		) {
			return null
		}
		if (ListFilterOption.Downloaded in params.effectiveFilters) {
			return null
		}
		val previewItems = repository.filterPreviewItems(snapshot.items, params.effectiveFilters)
		return mapPreviewList(
			list = previewItems,
			grouped = snapshot.isGroupingEnabled,
			mode = snapshot.listMode,
			isIncognito = snapshot.isIncognito,
			groupTab = snapshot.groupTab,
			sourceTags = snapshot.sourceTags,
			preset = snapshot.preset,
			quickFilter = snapshot.quickFilter?.syncSelection(params.filters),
			order = snapshot.sortOrder,
		)
	}

	private fun buildInitialPreviewStateOrNull(
		params: HistoryUiParams,
	): List<ListModel>? {
		if (params.spaceId != null || params.filters.isNotEmpty()) {
			return null
		}
		val snapshot = historyPreviewCache.observe().value ?: return null
		val currentPresetId = settings.activeSourcePresetId.takeIf { it != -1L }
		if (
			snapshot.filters.isNotEmpty() ||
			params.order != snapshot.sortOrder ||
			params.grouped != snapshot.isGroupingEnabled ||
			params.groupTab != snapshot.groupTab ||
			params.sourceTags != snapshot.sourceTags ||
			params.mode != snapshot.listMode ||
			params.incognito != snapshot.isIncognito ||
			settings.isHistoryExcludeNsfw != snapshot.isHistoryExcludeNsfw ||
			currentPresetId != snapshot.preset?.id ||
			limit.value > PAGE_SIZE
		) {
			return null
		}
		val previewItems = if (ListFilterOption.SFW in params.effectiveFilters) {
			snapshot.items.filterNot { it.manga.isNsfw() }
		} else {
			snapshot.items
		}
		return mapPreviewList(
			list = previewItems,
			grouped = snapshot.isGroupingEnabled,
			mode = snapshot.listMode,
			isIncognito = snapshot.isIncognito,
			groupTab = snapshot.groupTab,
			sourceTags = snapshot.sourceTags,
			preset = snapshot.preset,
			quickFilter = snapshot.quickFilter,
			order = snapshot.sortOrder,
		)
	}

	private suspend fun mapList(
		list: List<ContentWithHistory>,
		grouped: Boolean,
		mode: ListMode,
		filters: Set<ListFilterOption>,
		isIncognito: Boolean,
		groupTab: BrowseGroupTab,
		sourceTags: Set<SourceTag>,
		preset: org.skepsun.kototoro.explore.data.SourcePreset?,
	): List<ListModel> {
		val filteredList = list.filter { (manga, _) ->
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

			groupMatches && originMatches
		}

		val hideAdult = settings.isHistoryExcludeNsfw
		val adultFilteredItems = if (hideAdult) filteredList.filterNot { it.manga.isNsfw() } else filteredList
		val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
		val visibleItems = adultFilteredItems.filterNot { it.manga in globalTagBlacklist }

		if (visibleItems.isEmpty()) {
			groupedHistoryIds = emptyMap()
			groupedEntityIds = emptyMap()
			return if (filters.isEmpty() && groupTab == BrowseGroupTab.All && sourceTags.isEmpty()) {
				listOf(getEmptyState(hasFilters = false))
			} else {
				listOfNotNull(quickFilter.filterItem(filters), getEmptyState(hasFilters = true))
			}
		}
		val foldedItems = visibleItems.foldAdjacentByEntity()
		groupedHistoryIds = foldedItems.associate { it.uiId to it.mangaIds }
		groupedEntityIds = foldedItems.mapNotNull { group ->
			group.entityId?.let { group.uiId to it }
		}.toMap()
		groupedPreferredLocalIds = foldedItems.mapNotNull { group ->
			group.preferredLocalMangaId?.let { group.uiId to it }
		}.toMap()

		val result = ArrayList<ListModel>((if (grouped) (foldedItems.size * 1.4).toInt() else foldedItems.size) + 2)
		quickFilter.filterItem(filters)?.let(result::add)
		if (isIncognito) {
			result += InfoModel(
				key = AppSettings.KEY_INCOGNITO_MODE,
				title = R.string.incognito_mode,
				text = R.string.incognito_mode_hint,
				icon = R.drawable.ic_incognito,
			)
		}
		val order = sortOrder.value
		val representativeContents = foldedItems.map { it.representative.manga }
		val pinnedIds = favouritesRepository.getPinnedIds(representativeContents.map { it.id })
		val representativeModels = ArrayList<ContentListModel>(foldedItems.size)
		mangaListMapper.toListModelList(
			destination = representativeModels,
			manga = representativeContents,
			mode = mode,
			pinnedIds = pinnedIds,
		)
		var prevHeader: ListHeader? = null
		var isEmpty = true
		for (index in foldedItems.indices) {
			val item = foldedItems[index]
			val model = representativeModels[index]
			isEmpty = false
			if (grouped) {
				val header = item.representative.history.header(order)
				if (header != prevHeader) {
					if (header != null) {
						result += header
					}
					prevHeader = header
				}
			}
			result += model.toGroupedListModel(item)
		}
		if ((filters.isNotEmpty() || groupTab != BrowseGroupTab.All || sourceTags.isNotEmpty()) && isEmpty) {
			result += getEmptyState(hasFilters = true)
		}
		return result
	}

	private fun mapPreviewList(
		list: List<ContentWithHistory>,
		grouped: Boolean,
		mode: ListMode,
		isIncognito: Boolean,
		groupTab: BrowseGroupTab,
		sourceTags: Set<SourceTag>,
		preset: org.skepsun.kototoro.explore.data.SourcePreset?,
		quickFilter: org.skepsun.kototoro.list.ui.model.QuickFilter?,
		order: ListSortOrder,
	): List<ListModel> {
		val filteredList = list.filter { (manga, _) ->
			val source = manga.source
			if (preset != null && source.name !in preset.sources) {
				return@filter false
			}
			val contentGroup = sourceGroupManager.getContentGroup(source)
			val originGroup = sourceGroupManager.getOriginGroup(source)
			val groupMatches = groupTab.matchesContentGroup(contentGroup) && groupTab.matchesOriginGroup(originGroup)
			val originMatches = if (sourceTags.isEmpty()) true else sourceTags.any { it.matches(contentGroup, originGroup) }
			groupMatches && originMatches
		}
		val adultFilteredItems = if (settings.isHistoryExcludeNsfw) {
			filteredList.filterNot { it.manga.isNsfw() }
		} else {
			filteredList
		}
		val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
		val visibleItems = adultFilteredItems.filterNot { it.manga in globalTagBlacklist }
		if (visibleItems.isEmpty()) {
			return listOf(getEmptyState(hasFilters = false))
		}
		val foldedItems = visibleItems.foldAdjacentForPreviewByEntity()
		groupedHistoryIds = foldedItems.associate { it.uiId to it.mangaIds }
		groupedEntityIds = foldedItems.mapNotNull { group -> group.entityId?.let { group.uiId to it } }.toMap()
		groupedPreferredLocalIds = foldedItems.mapNotNull { group ->
			group.preferredLocalMangaId?.let { group.uiId to it }
		}.toMap()

		val result = ArrayList<ListModel>((if (grouped) (foldedItems.size * 1.4).toInt() else foldedItems.size) + 1)
		quickFilter?.let(result::add)
		if (isIncognito) {
			result += InfoModel(
				key = AppSettings.KEY_INCOGNITO_MODE,
				title = R.string.incognito_mode,
				text = R.string.incognito_mode_hint,
				icon = R.drawable.ic_incognito,
			)
		}
		var prevHeader: ListHeader? = null
		for (item in foldedItems) {
			if (grouped) {
				val header = item.representative.history.header(order)
				if (header != prevHeader) {
					header?.let(result::add)
					prevHeader = header
				}
			}
			result += item.toPreviewModel(mode)
		}
		return result
	}

	private fun QuickFilter.syncSelection(selectedOptions: Set<ListFilterOption>): QuickFilter {
		return copy(
			items = items.map { chip ->
				val option = chip.data as? ListFilterOption
				chip.copy(isChecked = option != null && option in selectedOptions)
			},
		)
	}

	private suspend fun List<ContentWithHistory>.foldAdjacentByEntity(): List<HistoryGroup> {
		if (isEmpty()) {
			return emptyList()
		}
		val resolvedEntityIds = mapNotNull(ContentWithHistory::entityId).distinct()
		val preferredLocalIdsByEntity = resolvedEntityIds.associateWith { entityId ->
			workResolver.resolveByEntityId(entityId)?.preferredMangaId
		}
		val result = ArrayList<HistoryGroup>(size)
		var current: MutableList<ContentWithHistory>? = null
		var currentUiId: Long? = null
		var currentEntityId: Long? = null
		var currentContentTypeOrdinal: Int? = null

		fun flushCurrent() {
			val items = current ?: return
			val uiId = currentUiId ?: return
			result += items.toHistoryGroup(
				uiId = uiId,
				entityId = currentEntityId,
				preferredLocalMangaId = currentEntityId?.let(preferredLocalIdsByEntity::get)
					?: items.firstNotNullOfOrNull(ContentWithHistory::preferredLocalMangaId),
			)
			current = null
			currentUiId = null
			currentEntityId = null
			currentContentTypeOrdinal = null
		}

		for (item in this) {
			val entityId = item.entityId
			val contentTypeOrdinal = item.manga.source.contentType.ordinal
			when {
				entityId == null -> {
					flushCurrent()
					result += listOf(item).toHistoryGroup(
						uiId = item.manga.id,
						entityId = null,
						preferredLocalMangaId = null,
					)
				}

				currentEntityId == entityId && currentContentTypeOrdinal == contentTypeOrdinal -> {
					current?.add(item)
				}

				else -> {
					flushCurrent()
					currentEntityId = entityId
					currentContentTypeOrdinal = contentTypeOrdinal
					currentUiId = entityId.toUiGroupId(contentTypeOrdinal)
					current = arrayListOf(item)
				}
			}
		}
		flushCurrent()
		return result
	}

	private fun List<ContentWithHistory>.foldAdjacentForPreviewByEntity(): List<HistoryGroup> {
		if (isEmpty()) {
			return emptyList()
		}
		val result = ArrayList<HistoryGroup>(size)
		var current: MutableList<ContentWithHistory>? = null
		var currentUiId: Long? = null
		var currentEntityId: Long? = null
		var currentContentTypeOrdinal: Int? = null

		fun flushCurrent() {
			val items = current ?: return
			val uiId = currentUiId ?: return
			result += items.toHistoryGroup(
				uiId = uiId,
				entityId = currentEntityId,
				preferredLocalMangaId = items.firstNotNullOfOrNull(ContentWithHistory::preferredLocalMangaId),
			)
			current = null
			currentUiId = null
			currentEntityId = null
			currentContentTypeOrdinal = null
		}

		for (item in this) {
			val entityId = item.entityId
			val contentTypeOrdinal = item.manga.source.contentType.ordinal
			when {
				entityId == null -> {
					flushCurrent()
					result += listOf(item).toHistoryGroup(
						uiId = item.manga.id,
						entityId = null,
						preferredLocalMangaId = item.preferredLocalMangaId,
					)
				}

				currentEntityId == entityId && currentContentTypeOrdinal == contentTypeOrdinal -> {
					current?.add(item)
				}

				else -> {
					flushCurrent()
					currentEntityId = entityId
					currentContentTypeOrdinal = contentTypeOrdinal
					currentUiId = entityId.toUiGroupId(contentTypeOrdinal)
					current = arrayListOf(item)
				}
			}
		}
		flushCurrent()
		return result
	}

	private fun List<ContentWithHistory>.toHistoryGroup(
		uiId: Long,
		entityId: Long?,
		preferredLocalMangaId: Long?
	): HistoryGroup {
		return HistoryGroup(
			uiId = uiId,
			representative = firstOrNull { it.manga.id == preferredLocalMangaId }
				?: firstOrNull { it.manga.id == first().preferredLocalMangaId }
				?: first(),
			mangaIds = mapTo(LinkedHashSet(size)) { it.manga.id },
			entityId = entityId,
			preferredLocalMangaId = preferredLocalMangaId ?: first().manga.id,
		)
	}

	override fun resolveEntityIdForUiItemId(id: Long): Long? {
		return groupedEntityIds[id]
	}

	override fun resolvePreferredLocalMangaIdForUiItemId(id: Long): Long? {
		return groupedPreferredLocalIds[id] ?: groupedHistoryIds[id]?.firstOrNull()
	}

	private fun Set<Long>.expandGroupedIds(): Set<Long> {
		return flatMapTo(LinkedHashSet()) { id ->
			groupedHistoryIds[id].orEmpty().ifEmpty { setOf(id) }
		}
	}

	private fun org.skepsun.kototoro.list.ui.model.ContentListModel.toGroupedListModel(group: HistoryGroup): ListModel {
		val groupSuffix = group.groupSuffix()
		return when (this) {
			is ContentCompactListModel -> copy(
				id = group.uiId,
				subtitle = listOfNotNull(subtitle?.takeIf { it.isNotBlank() }, groupSuffix).joinToString(" · "),
			)
			is ContentDetailedListModel -> copy(
				id = group.uiId,
				subtitle = listOfNotNull(subtitle.takeIf { !it.isNullOrBlank() }, groupSuffix).joinToString(" · "),
			)
			is ContentGridModel -> copy(
				id = group.uiId,
			)
		}
	}

	private fun HistoryGroup.groupSuffix(): String? {
		val projectionLabel = representative.manga.source.getTitle(appContext)
		val currentProjectionLabel = if (mangaIds.size > 1) {
			appContext.getString(
				R.string.favourites_entity_current_projection_with_count,
				projectionLabel,
				mangaIds.size,
			)
		} else {
			appContext.getString(R.string.favourites_entity_current_projection, projectionLabel)
		}
		if (mangaIds.size <= 1) {
			return currentProjectionLabel
		}
		val recordsLabel = appContext.resources.getQuantityString(
			R.plurals.history_grouped_records,
			mangaIds.size,
			mangaIds.size,
		)
		return listOf(currentProjectionLabel, recordsLabel).joinToString(" · ")
	}

	private fun Long.toUiGroupId(contentTypeOrdinal: Int): Long = -((this shl 8) or (contentTypeOrdinal + 1).toLong())

	private fun HistoryGroup.toPreviewModel(mode: ListMode): ListModel {
		val manga = representative.manga
		val subtitle = listOfNotNull(
			manga.altTitles.firstOrNull()?.takeIf { it.isNotBlank() },
			groupSuffix(),
		).joinToString(" · ").ifBlank { null }
		val progress = ReadingProgress(
			percent = representative.history.percent,
			totalChapters = representative.history.chaptersCount,
			mode = settings.progressIndicatorMode,
		).takeIf { it.isValid() }
		return when (mode) {
			ListMode.LIST -> ContentCompactListModel(
				manga = manga,
				override = null,
				subtitle = subtitle,
				counter = 0,
				id = uiId,
				progress = progress,
			)
			ListMode.DETAILED_LIST -> ContentDetailedListModel(
				manga = manga,
				override = null,
				subtitle = subtitle,
				counter = 0,
				id = uiId,
				progress = progress,
				isFavorite = false,
				isSaved = manga.isLocal,
				tags = emptyList(),
			)
			ListMode.GRID,
			ListMode.COMPACT_GRID -> ContentGridModel(
				manga = manga,
				override = null,
				subtitle = manga.altTitles.firstOrNull(),
				counter = 0,
				id = uiId,
				progress = progress,
				isFavorite = false,
				isSaved = manga.isLocal,
			)
		}
	}

	private fun ContentHistory.header(order: ListSortOrder): ListHeader? = when (order) {
		ListSortOrder.LAST_READ,
		ListSortOrder.LONG_AGO_READ -> calculateTimeAgo(updatedAt)?.let {
			ListHeader(it)
		} ?: ListHeader(R.string.unknown)

		ListSortOrder.OLDEST,
		ListSortOrder.NEWEST -> calculateTimeAgo(createdAt)?.let {
			ListHeader(it)
		} ?: ListHeader(R.string.unknown)

		ListSortOrder.UNREAD,
		ListSortOrder.PROGRESS -> ListHeader(
			when {
				ReadingProgress.isCompleted(percent) -> R.string.status_completed
				percent in 0f..0.01f -> R.string.status_planned
				percent in 0f..1f -> R.string.status_reading
				else -> R.string.unknown
			},
		)

		ListSortOrder.ALPHABETIC,
		ListSortOrder.ALPHABETIC_REVERSE,
		ListSortOrder.RELEVANCE,
		ListSortOrder.NEW_CHAPTERS,
		ListSortOrder.UPDATED,
		ListSortOrder.RATING -> null
	}

	private fun getEmptyState(hasFilters: Boolean) = if (hasFilters) {
		EmptyState(
			icon = R.drawable.ic_empty_history,
			textPrimary = R.string.nothing_found,
			textSecondary = R.string.text_empty_holder_secondary_filtered,
			actionStringRes = R.string.reset_filter,
		)
	} else {
		EmptyState(
			icon = R.drawable.ic_empty_history,
			textPrimary = R.string.text_history_holder_primary,
			textSecondary = R.string.text_history_holder_secondary,
			actionStringRes = 0,
		)
	}

	private data class HistoryGroup(
		val uiId: Long,
		val representative: ContentWithHistory,
		val mangaIds: Set<Long>,
		val entityId: Long?,
		val preferredLocalMangaId: Long?,
	)
}
