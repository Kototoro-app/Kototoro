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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
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
import org.skepsun.kototoro.core.util.ext.flattenLatest
import org.skepsun.kototoro.history.data.HistoryRepository
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
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository

private const val PAGE_SIZE = 16

@HiltViewModel
class HistoryListViewModel @Inject constructor(
	@ApplicationContext private val appContext: Context,
	private val repository: HistoryRepository,
	settings: AppSettings,
	private val mangaListMapper: ContentListMapper,
	private val markAsReadUseCase: MarkAsReadUseCase,
	private val quickFilter: HistoryListQuickFilter,
	private val sourceGroupManager: SourceGroupManager,
	private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
	private val networkState: NetworkState,
	private val dataRepository: ContentDataRepository,
	@LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
	private val sourcePresetsRepository: org.skepsun.kototoro.explore.data.SourcePresetsRepository,
	private val entityGraphRepository: EntityGraphRepository,
) : ContentListViewModel(settings, dataRepository, localStorageChanges), QuickFilterListener by quickFilter {

	@Volatile
	private var groupedHistoryIds: Map<Long, Set<Long>> = emptyMap()

	@Volatile
	private var groupedEntityIds: Map<Long, Long> = emptyMap()

	@Volatile
	private var groupedPreferredLocalIds: Map<Long, Long> = emptyMap()
	val onOpenReader = MutableEventFlow<Content>()

	override val isFilterBarVisible = MutableStateFlow(true)
		private val refreshTrigger = MutableStateFlow(Any())


	override val currentGroupTab = globalFavoritesState.selectedGroupTab
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
		settings.observe(
			AppSettings.KEY_MAIN_FAB,
			AppSettings.KEY_INCOGNITO_MODE,
		),
		repository.observeLast(),
		networkState,
	) { _, last, isOnline ->
		settings.isMainFabEnabled &&
			!settings.isIncognitoModeEnabled &&
			last != null &&
			(isOnline || last.isLocal)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5000), false)

	override val content = combine(
		quickFilter.appliedOptions,
		observeHistory(),
		isGroupingEnabled,
		observeListModeWithTriggers(),
		settings.observeAsFlow(AppSettings.KEY_INCOGNITO_MODE) { isIncognitoModeEnabled },
		this.currentGroupTab,
		this.currentSourceTags,
		mangaListMapper.observeDisplayChanges().onStart { emit(Unit) },
			refreshTrigger,
		settings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId }
			.flatMapLatest { id ->
				if (id == -1L) flowOf(null)
				else sourcePresetsRepository.observe(id)
			}
	) { values: Array<Any?> ->
		val filters = values[0] as Set<ListFilterOption>
		val list = values[1] as List<ContentWithHistory>
		val grouped = values[2] as Boolean
		val mode = values[3] as ListMode
		val incognito = values[4] as Boolean
		val groupTab = values[5] as BrowseGroupTab
		val sourceTags = values[6] as Set<SourceTag>
		val preset = values[8] as? org.skepsun.kototoro.explore.data.SourcePreset
		mapList(list, grouped, mode, filters, incognito, groupTab, sourceTags, preset)
	}.onEach {
		isPaginationReady.set(true)
	}.distinctUntilChanged().catch { e ->
		emit(listOf(e.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	override fun onRefresh() {
		refreshTrigger.value = Any()
	}

	override fun onRetry() = Unit

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
			val rawContent = repository.getLastOrNull() ?: throw EmptyHistoryException()
			val entityId = entityGraphRepository.findEntityIdsByLocalMangaIds(setOf(rawContent.id))[rawContent.id]
			val preferredLocalMangaId = entityId?.let { dataRepository.getEntityPreferredLocalMangaId(it) }
			val resolvedBase = preferredLocalMangaId
				?.takeIf { it != rawContent.id }
				?.let { dataRepository.findDisplayContentById(it, withChapters = false) }
				?: rawContent
			val manga = resolvedBase.let { content ->
				if (content.looksLikeLocalVideoContent()) {
					content.copy(
						source = LocalVideoSource,
						chapters = content.chapters?.map { chapter ->
							if (chapter.url.looksLikeVideoUrl()) chapter.copy(source = LocalVideoSource) else chapter
						},
					)
				} else {
					content
				}
			}
			onOpenReader.call(manga)
		}
	}

	private fun observeHistory() = combine(
		sortOrder,
		quickFilter.appliedOptions.combineWithSettings(),
		limit,
	) { order, filters, limit ->
		isPaginationReady.set(false)
		repository.observeAllWithHistory(order, filters, limit)
	}.flattenLatest()

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
		val visibleItems = if (hideAdult) filteredList.filterNot { it.manga.isNsfw() } else filteredList

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
		var prevHeader: ListHeader? = null
		var isEmpty = true
		for (item in foldedItems) {
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
			result += mangaListMapper.toListModel(
				manga = item.representative.manga,
				mode = mode,
				metadataSelectionOverride = item.metadataSourceSelection,
				useMetadataSelectionOverride = item.metadataSourceSelection != null,
			).toGroupedListModel(item)
		}
		if ((filters.isNotEmpty() || groupTab != BrowseGroupTab.All || sourceTags.isNotEmpty()) && isEmpty) {
			result += getEmptyState(hasFilters = true)
		}
		return result
	}

	private suspend fun List<ContentWithHistory>.foldAdjacentByEntity(): List<HistoryGroup> {
		if (isEmpty()) {
			return emptyList()
		}
		val resolvedEntityIds = mapNotNull(ContentWithHistory::entityId).distinct()
		val preferredLocalIdsByEntity = dataRepository.getEntityPreferredLocalMangaIds(resolvedEntityIds)
		val metadataSelectionsByEntity = dataRepository.getEntityMetadataSourceSelections(resolvedEntityIds)
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
				metadataSourceSelection = currentEntityId?.let(metadataSelectionsByEntity::get),
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
						metadataSourceSelection = null,
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
		preferredLocalMangaId: Long?,
		metadataSourceSelection: ContentDataRepository.MetadataSourceSelection?,
	): HistoryGroup {
		return HistoryGroup(
			uiId = uiId,
			representative = firstOrNull { it.manga.id == preferredLocalMangaId }
				?: firstOrNull { it.manga.id == first().preferredLocalMangaId }
				?: first(),
			mangaIds = mapTo(LinkedHashSet(size)) { it.manga.id },
			entityId = entityId,
			preferredLocalMangaId = preferredLocalMangaId ?: first().manga.id,
			metadataSourceSelection = metadataSourceSelection,
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
		val metadataSourceSelection: ContentDataRepository.MetadataSourceSelection?,
	)
}
