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
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.groupByDateBucket
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
import org.skepsun.kototoro.core.prefs.TriStateOption
import org.skepsun.kototoro.download.ui.worker.DownloadTask
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.download.ui.worker.ExecutionChapterRef
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.work.domain.WorkResolver
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.scopedToSpace

private const val PAGE_SIZE = 20
private const val UPDATED_CONTENT_LOOKAHEAD_SIZE = 2000

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
	private val workResolver: WorkResolver,
	spaceBrowseScope: SpaceBrowseScope,
) : BaseViewModel(), QuickFilterListener by quickFilter, SpaceBindableViewModel {
	private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)

	private data class HeaderParams(
		val hasHeader: Boolean,
		val categoryId: Long,
		val groupTab: BrowseGroupTab,
		val sourceTags: Set<SourceTag>,
		val mangaCategoryIds: Map<String, Set<Long>>,
		val preset: org.skepsun.kototoro.explore.data.SourcePreset?,
	)

	private val feedLimitFlow = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_FEED_LIMIT,
		valueProducer = { feedLimit },
	)

	private val limit = MutableStateFlow(settings.feedLimit)
	private val isReady = AtomicBoolean(false)
	private val selectedCategoryId = MutableStateFlow(NO_ID)

	init {
		launchJob(Dispatchers.Default) {
			feedLimitFlow.collect { newLimit ->
				limit.value = newLimit
			}
		}
	}

	val categories = favouritesRepository.observeCategoriesForLibrary()
		.map { listOf(FavouriteCategory(id = NO_ID, title = "", sortKey = Int.MIN_VALUE, order = org.skepsun.kototoro.list.domain.ListSortOrder.NEWEST, createdAt = java.time.Instant.EPOCH, isTrackingEnabled = false, isVisibleInLibrary = true)) + it }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val currentCategoryId = selectedCategoryId
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, NO_ID)

	val currentGroupTab = globalFavoritesState.selectedGroupTab.scopedToSpace(
		spaceGroupTab = spaceBinding.groupTab,
		coroutineScope = viewModelScope + Dispatchers.Default,
	)
	override fun bindSpace(spaceId: org.skepsun.kototoro.space.domain.SpaceId?) = spaceBinding.bindSpace(spaceId)
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

	sealed class DownloadPrompt {
		data class MultipleUpdates(
			val manga: Content,
			val lastChapterId: Long,
			val allNewChaptersIds: LongArray,
		) : DownloadPrompt() {
			override fun equals(other: Any?): Boolean {
				if (this === other) return true
				if (other !is MultipleUpdates) return false
				if (manga != other.manga) return false
				if (lastChapterId != other.lastChapterId) return false
				return allNewChaptersIds contentEquals other.allNewChaptersIds
			}

			override fun hashCode(): Int {
				var result = manga.hashCode()
				result = 31 * result + lastChapterId.hashCode()
				result = 31 * result + allNewChaptersIds.contentHashCode()
				return result
			}
		}

		data class NoReadHistory(
			val manga: Content,
			val lastChapterId: Long,
			val allChaptersIds: LongArray,
		) : DownloadPrompt() {
			override fun equals(other: Any?): Boolean {
				if (this === other) return true
				if (other !is NoReadHistory) return false
				if (manga != other.manga) return false
				if (lastChapterId != other.lastChapterId) return false
				return allChaptersIds contentEquals other.allChaptersIds
			}

			override fun hashCode(): Int {
				var result = manga.hashCode()
				result = 31 * result + lastChapterId.hashCode()
				result = 31 * result + allChaptersIds.contentHashCode()
				return result
			}
		}
	}

	data class DeleteChapterPrompt(
		val manga: Content,
		val chapterId: Long,
		val chapterTitle: String,
	)

	val showAllUpdates = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_SHOW_ALL_UPDATES,
		valueProducer = { showAllUpdates },
	)

	private val logFlow = showAllUpdates.flatMapLatest { showAll ->
		val combinedSettings = quickFilter.appliedOptions.combineWithSettings()
		if (showAll) {
			combine(limit, combinedSettings, ::Pair)
				.flatMapLatest { repository.observeAllTrackingLogItems(it.first, it.second) }
		} else {
			combine(limit, combinedSettings, ::Pair)
				.flatMapLatest { repository.observeTrackingLog(it.first, it.second) }
		}
	}
	val onActionDone = MutableEventFlow<ReversibleAction>()

	@Suppress("USELESS_CAST")
	val content = combine(
		observeHeader(),
		quickFilter.appliedOptions,
		logFlow,
		quickFilter.appliedOptions.combineWithSettings()
			.flatMapLatest { repository.observeUpdatedContent(UPDATED_CONTENT_LOOKAHEAD_SIZE, it) },
		selectedCategoryId,
		currentGroupTab,
		currentSourceTags,
		favouritesRepository.observeFeedCategoryIds(),
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
		val updatedContent = values[3] as List<ContentTracking>
		val categoryId = values[4] as Long
		val groupTab = values[5] as BrowseGroupTab
		val sourceTags = values[6] as Set<SourceTag>
		val mangaCategoryIds = values[7] as Map<String, Set<Long>>
		val preset = values[9] as? org.skepsun.kototoro.explore.data.SourcePreset

		fun matchesFeedScope(item: Content): Boolean {
			val source = item.source
			if (preset != null && source.name !in preset.sources) {
				return false
			}
			val contentGroup = sourceGroupManager.getContentGroup(source)
			val originGroup = sourceGroupManager.getOriginGroup(source)
			val matchesCategory = categoryId == NO_ID || categoryId in mangaCategoryIds[item.feedLookupKey()].orEmpty()
			val matchesGroup = groupTab.matchesContentGroup(contentGroup)
			val matchesSourceTag = sourceTags.isEmpty() || sourceTags.any { it.matches(contentGroup, originGroup) }
			return matchesCategory && matchesGroup && matchesSourceTag
		}

		val filteredList = list.filter { item ->
			matchesFeedScope(item.manga)
		}
		val fallbackList = if (filteredList.isEmpty()) {
			updatedContent
				.filter { item -> matchesFeedScope(item.manga) }
				.map { item -> item.toFallbackTrackingLogItem() }
		} else {
			emptyList()
		}

		val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
		val displayList = filteredList.ifEmpty { fallbackList }
			.filterNot { it.manga in globalTagBlacklist }
		val result = ArrayList<ListModel>((displayList.size * 1.4).toInt().coerceAtLeast(3))
		quickFilter.filterItem(filters)?.let(result::add)
		if (header != null) {
			result += header
		}
		isReady.set(true)
		if (displayList.isEmpty()) {
			result += EmptyState(
				icon = R.drawable.ic_empty_feed,
				textPrimary = R.string.text_empty_holder_primary,
				textSecondary = R.string.text_feed_holder,
				actionStringRes = 0,
			)
		} else {
			displayList.mapListTo(result)
		}
		result as List<ListModel>
	}.catch { e ->
		emit(listOf(e.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	private fun ContentTracking.toFallbackTrackingLogItem(): TrackingLogItem {
		return TrackingLogItem(
			id = -anchorMangaId,
			anchorMangaId = anchorMangaId,
			entityId = entityId,
			preferredLocalMangaId = preferredLocalMangaId,
			manga = manga,
			chapters = List(newChapters.coerceAtLeast(1)) { "" },
			createdAt = lastChapterDate ?: lastCheck ?: java.time.Instant.EPOCH,
			isNew = newChapters > 0,
			count = newChapters,
		)
	}

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
			limit.value += 50
		}
	}

	fun markFeedAsOpened() {
		settings.feedLastOpenTime = System.currentTimeMillis()
	}

	fun update() {
		manualRefreshRequested.value = true
		scheduler.startNow()
	}

	fun setHeaderEnabled(value: Boolean) {
		settings.isFeedHeaderVisible = value
	}

	fun setShowAllUpdates(value: Boolean) {
		settings.showAllUpdates = value
	}

	fun onItemClick(item: FeedItem) {
		launchJob(Dispatchers.Default, CoroutineStart.ATOMIC) {
			if (item.id > 0L) {
				repository.markAsRead(item.id)
			}
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
		val bucketedItems = zip(feedItems).groupByDateBucket(instantOf = { it.first.createdAt })
		for ((date, items) in bucketedItems) {
			destination += if (date != null) {
				ListHeader(date)
			} else {
				ListHeader(R.string.unknown)
			}
			for ((_, feedItem) in items) {
				destination += feedItem
			}
		}
	}

	private fun observeHeader() = combine(
		isHeaderEnabled,
		selectedCategoryId,
		currentGroupTab,
		currentSourceTags,
		favouritesRepository.observeFeedCategoryIds(),
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
			mangaCategoryIds = values[4] as Map<String, Set<Long>>,
			preset = values[6] as? org.skepsun.kototoro.explore.data.SourcePreset,
		)
	}.flatMapLatest { args ->
		if (args.hasHeader) {
			quickFilter.appliedOptions.combineWithSettings().flatMapLatest {
				repository.observeUpdatedContent(UPDATED_CONTENT_LOOKAHEAD_SIZE, it)
			}.map { mangaList ->
				val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
				val filteredContentList = mangaList.filter { item ->
					if (item.manga in globalTagBlacklist) {
						return@filter false
					}
					val source = item.manga.source
					if (args.preset != null && source.name !in args.preset.sources) {
						return@filter false
					}
					val contentGroup = sourceGroupManager.getContentGroup(source)
					val originGroup = sourceGroupManager.getOriginGroup(source)
					val matchesCategory = args.categoryId == NO_ID || args.categoryId in args.mangaCategoryIds[item.manga.feedLookupKey()].orEmpty()
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
		val preferredLocalIdsByEntity = resolvePreferredLocalIdsByEntity(resolvedEntityIds)
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

	private suspend fun resolvePreferredLocalIdsByEntity(entityIds: Collection<Long>): Map<Long, Long?> {
		return entityIds.associateWith { entityId ->
			workResolver.resolveByEntityId(entityId)?.preferredMangaId
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

	private fun Flow<Set<ListFilterOption>>.combineWithSettings(): Flow<Set<ListFilterOption>> {
		val skipNsfwInFeed = combine(
			settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
			settings.observeAsFlow(AppSettings.KEY_FEED_EXCLUDE_NSFW) { isFeedExcludeNsfw },
		) { skipNsfwGlobally, skipNsfwInFeed ->
			skipNsfwGlobally || skipNsfwInFeed
		}
		return combine(skipNsfwInFeed) { filters, skipNsfw ->
			if (skipNsfw) {
				filters + ListFilterOption.SFW
			} else {
				filters
			}
		}
	}
}

private fun Content.feedLookupKey(): String {
	return "${source.name}|$url"
}
