package org.skepsun.kototoro.tracker.ui.feed

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.model.DateTimeAgo
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.calculateTimeAgo
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.domain.GlobalFavoritesState
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.toErrorState
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.tracker.domain.UpdatesListQuickFilter
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import org.skepsun.kototoro.tracker.domain.model.TrackingLogItem
import org.skepsun.kototoro.tracker.ui.feed.model.FeedItem
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeader
import org.skepsun.kototoro.tracker.ui.feed.model.UpdatedContentHeaderItem
import org.skepsun.kototoro.tracker.work.TrackWorker
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.core.parser.ContentDataRepository
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val PAGE_SIZE = 20

@HiltViewModel
class FeedViewModel @Inject constructor(
	private val settings: AppSettings,
	private val repository: TrackingRepository,
	private val scheduler: TrackWorker.Scheduler,
	private val mangaListMapper: ContentListMapper,
	private val quickFilter: UpdatesListQuickFilter,
	private val sourceGroupManager: SourceGroupManager,
	private val favouritesRepository: FavouritesRepository,
	private val globalFavoritesState: GlobalFavoritesState,
	private val sourcePresetsRepository: org.skepsun.kototoro.explore.data.SourcePresetsRepository,
	private val dataRepository: ContentDataRepository,
) : BaseViewModel(), QuickFilterListener by quickFilter {

	private data class HeaderParams(
		val hasHeader: Boolean,
		val categoryId: Long,
		val groupTab: BrowseGroupTab,
		val sourceTags: Set<SourceTag>,
		val favorites: List<org.skepsun.kototoro.favourites.data.FavouriteContent>,
		val preset: org.skepsun.kototoro.explore.data.SourcePreset?,
	)

	private val limit = MutableStateFlow(PAGE_SIZE)
	private val isReady = AtomicBoolean(false)
	private val selectedCategoryId = MutableStateFlow(NO_ID)

	val categories = favouritesRepository.observeCategoriesForLibrary()
		.map { listOf(FavouriteCategory(id = NO_ID, title = "", sortKey = Int.MIN_VALUE, order = org.skepsun.kototoro.list.domain.ListSortOrder.NEWEST, createdAt = java.time.Instant.EPOCH, isTrackingEnabled = false, isVisibleInLibrary = true)) + it }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val currentCategoryId = selectedCategoryId
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, NO_ID)

	val currentGroupTab = globalFavoritesState.selectedGroupTab
	val currentSourceTags = globalFavoritesState.selectedSourceTags

	private val workerRunning = scheduler.observeIsRunning()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

	private val manualRefreshRequested = MutableStateFlow(false)

	val isRefreshing = combine(workerRunning, manualRefreshRequested) { running, requested ->
		running && requested
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

	val isHeaderEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_FEED_HEADER,
		valueProducer = { isFeedHeaderVisible },
	)

	val onActionDone = MutableEventFlow<ReversibleAction>()

	@Suppress("USELESS_CAST")
	val content = combine(
		observeHeader(),
		quickFilter.appliedOptions,
		combine(limit, quickFilter.appliedOptions.combineWithSettings(), ::Pair)
			.flatMapLatest { repository.observeTrackingLog(it.first, it.second) },
		selectedCategoryId,
		currentGroupTab,
		currentSourceTags,
		favouritesRepository.observeAllRawFavorites(),
		mangaListMapper.observeDisplayChanges().onStart { emit(Unit) },
		settings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId }
			.flatMapLatest { id ->
				if (id == -1L) flowOf(null)
				else sourcePresetsRepository.observe(id)
			}
	) { values: Array<Any?> ->
		val header = values[0] as UpdatedContentHeader?
		val filters = values[1] as Set<ListFilterOption>
		val list = values[2] as List<TrackingLogItem>
		val categoryId = values[3] as Long
		val groupTab = values[4] as BrowseGroupTab
		val sourceTags = values[5] as Set<SourceTag>
		val favorites = values[6] as List<org.skepsun.kototoro.favourites.data.FavouriteContent>
		val preset = values[8] as? org.skepsun.kototoro.explore.data.SourcePreset
		val mangaCategoryIds = favorites.buildFeedCategoryIds()

		val filteredList = list.filter { item ->
			val source = item.manga.source
			if (preset != null && source.name !in preset.sources) {
				return@filter false
			}
			val contentGroup = sourceGroupManager.getContentGroup(source)
			val originGroup = sourceGroupManager.getOriginGroup(source)
			val matchesCategory = categoryId == NO_ID || categoryId in mangaCategoryIds[item.manga.feedLookupKey()].orEmpty()
			val matchesGroup = groupTab.matchesContentGroup(contentGroup)
			val matchesSourceTag = sourceTags.isEmpty() || sourceTags.any { it.matches(contentGroup, originGroup) }
			matchesCategory && matchesGroup && matchesSourceTag
		}

		val result = ArrayList<ListModel>((filteredList.size * 1.4).toInt().coerceAtLeast(3))
		quickFilter.filterItem(filters)?.let(result::add)
		if (header != null) {
			result += header
		}
		isReady.set(true)
		if (filteredList.isEmpty()) {
			result += EmptyState(
				icon = R.drawable.ic_empty_feed,
				textPrimary = R.string.text_empty_holder_primary,
				textSecondary = R.string.text_feed_holder,
				actionStringRes = 0,
			)
		} else {
			filteredList.mapListTo(result)
		}
		result as List<ListModel>
	}.catch { e ->
		emit(listOf(e.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		launchJob(Dispatchers.Default) {
			repository.gc()
		}
		launchJob(Dispatchers.Default) {
			workerRunning.collect { running ->
				if (!running) {
					manualRefreshRequested.value = false
				}
			}
		}
	}

	fun clearFeed(clearCounters: Boolean) {
		launchLoadingJob(Dispatchers.Default) {
			repository.clearLogs()
			if (clearCounters) {
				repository.clearCounters()
			}
			onActionDone.call(ReversibleAction(R.string.updates_feed_cleared, null))
		}
	}

	fun requestMoreItems() {
		if (isReady.compareAndSet(true, false)) {
			limit.value += PAGE_SIZE
		}
	}

	fun update() {
		manualRefreshRequested.value = true
		scheduler.startNow()
	}

	fun setHeaderEnabled(value: Boolean) {
		settings.isFeedHeaderVisible = value
	}

	fun onItemClick(item: FeedItem) {
		launchJob(Dispatchers.Default, CoroutineStart.ATOMIC) {
			repository.markAsRead(item.id)
		}
	}

	fun selectCategory(categoryId: Long) {
		selectedCategoryId.value = categoryId
	}

	fun setSelectedGroupTab(tab: BrowseGroupTab) {
		globalFavoritesState.setSelectedGroupTab(tab)
	}

	fun setSelectedSourceTags(tags: Set<SourceTag>) {
		globalFavoritesState.setSelectedSourceTags(tags)
	}

	fun toggleSourceTag(tag: SourceTag) {
		globalFavoritesState.toggleSourceTag(tag)
	}

	private suspend fun List<TrackingLogItem>.mapListTo(destination: MutableList<ListModel>) {
		val feedItems = mangaListMapper.toFeedItems(this)
		var prevDate: DateTimeAgo? = null
		for ((logItem, feedItem) in zip(feedItems)) {
			val date = calculateTimeAgo(logItem.createdAt)
			if (prevDate != date) {
				destination += if (date != null) {
					ListHeader(date)
				} else {
					ListHeader(R.string.unknown)
				}
			}
			prevDate = date
			destination += feedItem
		}
	}

	private fun observeHeader() = combine(
		isHeaderEnabled,
		selectedCategoryId,
		currentGroupTab,
		currentSourceTags,
		favouritesRepository.observeAllRawFavorites(),
		mangaListMapper.observeDisplayChanges().onStart { emit(Unit) },
		settings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId }
			.flatMapLatest { id ->
				if (id == -1L) flowOf(null)
				else sourcePresetsRepository.observe(id)
			}
	) { values: Array<Any?> ->
		HeaderParams(
			hasHeader = values[0] as Boolean,
			categoryId = values[1] as Long,
			groupTab = values[2] as BrowseGroupTab,
			sourceTags = values[3] as Set<SourceTag>,
			favorites = values[4] as List<org.skepsun.kototoro.favourites.data.FavouriteContent>,
			preset = values[6] as? org.skepsun.kototoro.explore.data.SourcePreset,
		)
	}.flatMapLatest { args ->
		if (args.hasHeader) {
			quickFilter.appliedOptions.combineWithSettings().flatMapLatest {
				repository.observeUpdatedContent(10, it)
			}.map { mangaList ->
				val mangaCategoryIds = args.favorites.buildFeedCategoryIds()
				val filteredContentList = mangaList.filter { item ->
					val source = item.manga.source
					if (args.preset != null && source.name !in args.preset.sources) {
						return@filter false
					}
					val contentGroup = sourceGroupManager.getContentGroup(source)
					val originGroup = sourceGroupManager.getOriginGroup(source)
					val matchesCategory = args.categoryId == NO_ID || args.categoryId in mangaCategoryIds[item.manga.feedLookupKey()].orEmpty()
					val matchesGroup = args.groupTab.matchesContentGroup(contentGroup)
					val matchesSourceTag = args.sourceTags.isEmpty() || args.sourceTags.any { it.matches(contentGroup, originGroup) }
					matchesCategory && matchesGroup && matchesSourceTag
				}
				if (filteredContentList.isEmpty()) {
					null
				} else {
					buildUpdatedContentHeader(filteredContentList)
				}
			}
		} else {
			flowOf(null)
		}
	}

	private suspend fun buildUpdatedContentHeader(items: List<ContentTracking>): UpdatedContentHeader {
		val groupedList = items.aggregateFeedUpdatesByEntity()
		return UpdatedContentHeader(
			list = groupedList.map { group ->
				UpdatedContentHeaderItem(
					model = mangaListMapper.toListModel(
						manga = group.representative.manga,
						mode = ListMode.GRID,
						metadataSelectionOverride = group.metadataSourceSelection,
						useMetadataSelectionOverride = group.metadataSourceSelection != null,
					),
					groupKey = group.groupKey,
					entityId = group.entityId,
					preferredLocalMangaId = group.preferredLocalMangaId,
					totalNewChapters = group.totalNewChapters,
				)
			},
		)
	}

	private suspend fun List<ContentTracking>.aggregateFeedUpdatesByEntity(): List<FeedUpdateGroup> {
		if (isEmpty()) {
			return emptyList()
		}
		val resolvedEntityIds = mapNotNull(ContentTracking::entityId).distinct()
		val preferredLocalIdsByEntity = dataRepository.getEntityPreferredLocalMangaIds(resolvedEntityIds)
		val metadataSelectionsByEntity = dataRepository.getEntityMetadataSourceSelections(resolvedEntityIds)
		val grouped = LinkedHashMap<Long, MutableList<ContentTracking>>()
		for (item in this) {
			val contentTypeOrdinal = item.manga.source.getContentType().ordinal
			val groupKey = item.entityId?.toFeedGroupKey(contentTypeOrdinal) ?: item.manga.id
			grouped.getOrPut(groupKey) { ArrayList(1) }.add(item)
		}
		return grouped.map { (groupKey, groupItems) ->
			val entityId = groupItems.firstNotNullOfOrNull(ContentTracking::entityId)
			val preferredLocalId = entityId?.let(preferredLocalIdsByEntity::get)
				?: groupItems.firstNotNullOfOrNull(ContentTracking::preferredLocalMangaId)
			val representative = groupItems.firstOrNull { it.manga.id == preferredLocalId }
				?: groupItems.maxWithOrNull(
					compareBy<ContentTracking>(
						{ it.lastChapterDate ?: java.time.Instant.EPOCH },
						{ it.lastCheck ?: java.time.Instant.EPOCH },
						{ it.newChapters },
					),
				)
				?: groupItems.first()
			FeedUpdateGroup(
				groupKey = groupKey,
				representative = representative,
				totalNewChapters = groupItems.sumOf { it.newChapters },
				entityId = entityId,
				preferredLocalMangaId = preferredLocalId ?: representative.manga.id,
				metadataSourceSelection = entityId?.let(metadataSelectionsByEntity::get),
			)
		}
	}

	private fun Long.toFeedGroupKey(contentTypeOrdinal: Int): Long = -((this shl 8) or (contentTypeOrdinal + 1).toLong())

	private data class FeedUpdateGroup(
		val groupKey: Long,
		val representative: ContentTracking,
		val totalNewChapters: Int,
		val entityId: Long?,
		val preferredLocalMangaId: Long?,
		val metadataSourceSelection: ContentDataRepository.MetadataSourceSelection?,
	)

	private fun Flow<Set<ListFilterOption>>.combineWithSettings(): Flow<Set<ListFilterOption>> = combine(
		settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
	) { filters, skipNsfw ->
		if (skipNsfw) {
			filters + ListFilterOption.SFW
		} else {
			filters
		}
	}
}

private fun org.skepsun.kototoro.favourites.data.FavouriteContent.feedLookupKey(): String {
	return "${manga.source}|${manga.url}"
}

private fun Content.feedLookupKey(): String {
	return "${source.name}|$url"
}

private fun List<org.skepsun.kototoro.favourites.data.FavouriteContent>.buildFeedCategoryIds(): Map<String, Set<Long>> {
	val categoryIdsByContent = LinkedHashMap<String, LinkedHashSet<Long>>(size)
	for (favorite in this) {
		val key = favorite.feedLookupKey()
		val categoryIds = categoryIdsByContent.getOrPut(key) { linkedSetOf() }
		favorite.categories.forEach { category ->
			categoryIds += category.categoryId.toLong()
		}
	}
	return categoryIdsByContent
}
