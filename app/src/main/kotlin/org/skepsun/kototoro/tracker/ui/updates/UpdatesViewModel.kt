package org.skepsun.kototoro.tracker.ui.updates

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.util.ext.groupByDateBucket
import org.skepsun.kototoro.core.util.ext.onFirst
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.ui.ContentListViewModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.toErrorState
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.tracker.domain.UpdatesListQuickFilter
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import javax.inject.Inject
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.model.LocalContent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.tracker.work.TrackWorker
import org.skepsun.kototoro.work.domain.WorkResolver
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.scopedToSpace

private const val PAGE_SIZE = 32

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

	override val isFilterBarVisible = MutableStateFlow(true)

	override val currentSourceTags = globalFavoritesState.selectedSourceTags

	override fun setSelectedSourceTags(tags: Set<org.skepsun.kototoro.explore.ui.model.SourceTag>) {
		globalFavoritesState.setSelectedSourceTags(tags)
	}

	override val currentGroupTab = globalFavoritesState.selectedGroupTab.scopedToSpace(
		spaceGroupTab = spaceBinding.groupTab,
		coroutineScope = viewModelScope + Dispatchers.Default,
	)
	override fun bindSpace(spaceId: org.skepsun.kototoro.space.domain.SpaceId?) = spaceBinding.bindSpace(spaceId)

	override fun setSelectedGroupTab(tab: org.skepsun.kototoro.explore.ui.model.BrowseGroupTab) {
		globalFavoritesState.setSelectedGroupTab(tab)
	}

	private val refreshTrigger = MutableStateFlow(Any())
	private val limit = MutableStateFlow(PAGE_SIZE)
	private val isPaginationReady = AtomicBoolean(false)

	override val hasMoreItems: StateFlow<Boolean> = limit
		.map { it < Int.MAX_VALUE }
		.stateIn(viewModelScope, SharingStarted.Eagerly, true)

	private val loadParams = combine(quickFilter.appliedOptions, refreshTrigger) { fo, _ -> fo }

	override val content = combine(
		loadParams.flatMapLatest { filterOptions ->
			repository.observeUpdatedContent(
				limit = 200,
				filterOptions = filterOptions,
			)
		},
		quickFilter.appliedOptions,
		settings.observeAsFlow(AppSettings.KEY_UPDATED_GROUPING) { isUpdatedGroupingEnabled },
		observeListModeWithTriggers(),
		currentGroupTab,
		currentSourceTags,
		mangaListMapper.observeDisplayChanges().onStart { emit(Unit) },
	) { values: Array<Any?> ->
		val mangaList = values[0] as List<ContentTracking>
		val filters = values[1] as Set<ListFilterOption>
		val grouping = values[2] as Boolean
		val mode = values[3] as ListMode
		val groupTab = values[4] as BrowseGroupTab
		val sourceTags = values[5] as Set<SourceTag>
		when {
			mangaList.isEmpty() -> if (filters.isEmpty() && groupTab == BrowseGroupTab.All && sourceTags.isEmpty()) {
				listOfNotNull(
					quickFilter.filterItem(filters),
					EmptyState(
						icon = R.drawable.ic_empty_feed,
						textPrimary = R.string.text_empty_holder_primary,
						textSecondary = R.string.text_feed_holder,
						actionStringRes = 0,
					),
				)
			} else {
				listOfNotNull(
					quickFilter.filterItem(filters),
					EmptyState(
						icon = R.drawable.ic_empty_history,
						textPrimary = R.string.nothing_found,
						textSecondary = R.string.text_empty_holder_secondary_filtered,
						actionStringRes = 0,
					),
				)
			}

			else -> mangaList.toUi(mode, filters, grouping, groupTab, sourceTags)
		}
	}.onStart {
		loadingCounter.increment()
	}.map {
		isPaginationReady.set(true)
		it
	}.onFirst {
		loadingCounter.decrement()
	}.catch {
		emit(listOf(it.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		launchJob(Dispatchers.Default) {
			repository.gc()
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
		if (isPaginationReady.compareAndSet(true, false)) {
			limit.value += PAGE_SIZE
		}
	}

	private suspend fun List<ContentTracking>.toUi(
		mode: ListMode,
		filters: Set<ListFilterOption>,
		grouped: Boolean,
		groupTab: BrowseGroupTab,
		sourceTags: Set<SourceTag>,
	): List<ListModel> {
		val filteredList = filter { item ->
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
		val adultFilteredList = if (hideAdult) filteredList.filterNot { it.manga.isNsfw() } else filteredList
		val globalTagBlacklist = GlobalTagBlacklist(settings.globalTagBlacklist)
		val visibleList = adultFilteredList.filterNot { it.manga in globalTagBlacklist }

		if (visibleList.isEmpty()) {
			groupedRemovalIds = emptyMap()
			groupedEntityIds = emptyMap()
			groupedPreferredLocalIds = emptyMap()
			return listOfNotNull(
				quickFilter.filterItem(filters),
				EmptyState(
					icon = R.drawable.ic_empty_history,
					textPrimary = R.string.nothing_found,
					textSecondary = R.string.text_empty_holder_secondary_filtered,
					actionStringRes = 0,
				),
			)
		}

		val groupedList = visibleList.aggregateByEntity()
		groupedRemovalIds = groupedList.associate { it.uiId to it.mangaIds }
		groupedEntityIds = groupedList.mapNotNull { group ->
			group.entityId?.let { group.uiId to it }
		}.toMap()
		groupedPreferredLocalIds = groupedList.mapNotNull { group ->
			group.preferredLocalMangaId?.let { group.uiId to it }
		}.toMap()

		val result = ArrayList<ListModel>(if (grouped) (groupedList.size * 1.4).toInt() else groupedList.size + 1)
		quickFilter.filterItem(filters)?.let(result::add)
		val groupedBuckets = if (grouped) {
			groupedList.groupByDateBucket(UpdateGroup::lastChapterDate)
		} else {
			listOf(null to groupedList)
		}
		for ((header, itemsInBucket) in groupedBuckets) {
			if (grouped && header != null) {
				result += ListHeader(header)
			}
			for (item in itemsInBucket) {
				result += mangaListMapper.toListModel(
					manga = item.representative.manga,
					mode = mode,
					metadataSelectionOverride = item.metadataSourceSelection,
					useMetadataSelectionOverride = item.metadataSourceSelection != null,
				).toGroupedListModel(item)
			}
		}
		return result
	}

	private suspend fun List<ContentTracking>.aggregateByEntity(): List<UpdateGroup> {
		if (isEmpty()) {
			return emptyList()
		}
		val resolvedEntityIds = mapNotNull(ContentTracking::entityId).distinct()
		val preferredLocalIdsByEntity = resolvePreferredLocalIdsByEntity(resolvedEntityIds)
		val metadataSelectionsByEntity = dataRepository.getEntityMetadataSourceSelections(resolvedEntityIds)
		val displayTypeOrdinalByEntity = this
			.groupBy(ContentTracking::entityId)
			.mapNotNull { (entityId, items) ->
				entityId?.let { it to items.resolveDisplayContentTypeOrdinal() }
			}
			.toMap()
		val grouped = LinkedHashMap<UpdateGroupKey, MutableList<ContentTracking>>(size)
		for (item in this) {
			val entityId = item.entityId
			val contentTypeOrdinal = entityId?.let(displayTypeOrdinalByEntity::get) ?: item.manga.source.contentType.ordinal
			val key = UpdateGroupKey(
				uiId = entityId?.toUiGroupId(contentTypeOrdinal) ?: item.manga.id,
				contentTypeOrdinal = contentTypeOrdinal,
			)
			grouped.getOrPut(key) { ArrayList(1) }.add(item)
		}
		return grouped.map { (key, items) ->
			items.toUpdateGroup(
				uiId = key.uiId,
				entityId = items.firstNotNullOfOrNull(ContentTracking::entityId),
				preferredLocalMangaId = items.firstNotNullOfOrNull { item ->
					item.entityId?.let(preferredLocalIdsByEntity::get) ?: item.preferredLocalMangaId
				},
				metadataSourceSelection = items.firstNotNullOfOrNull { item ->
					item.entityId?.let(metadataSelectionsByEntity::get)
				},
			)
		}
	}

	private suspend fun resolvePreferredLocalIdsByEntity(entityIds: Collection<Long>): Map<Long, Long?> {
		return entityIds.associateWith { entityId ->
			workResolver.resolveByEntityId(entityId)?.preferredMangaId
		}
	}

	private fun List<ContentTracking>.toUpdateGroup(
		uiId: Long,
		entityId: Long?,
		preferredLocalMangaId: Long?,
		metadataSourceSelection: ContentDataRepository.MetadataSourceSelection?,
	): UpdateGroup {
		val representative = firstOrNull { it.manga.id == preferredLocalMangaId } ?: maxWithOrNull(
			compareBy<ContentTracking>(
				{ it.lastChapterDate ?: Instant.EPOCH },
				{ it.lastCheck ?: Instant.EPOCH },
				{ it.newChapters },
			),
		) ?: first()
		return UpdateGroup(
			uiId = uiId,
			representative = representative,
			mangaIds = mapTo(LinkedHashSet(size)) { it.manga.id },
			lastChapterDate = mapNotNull { it.lastChapterDate }.maxOrNull(),
			totalNewChapters = sumOf { it.newChapters },
			entityId = entityId,
			preferredLocalMangaId = preferredLocalMangaId ?: representative.manga.id,
			metadataSourceSelection = metadataSourceSelection,
		)
	}

	private fun List<ContentTracking>.resolveDisplayContentTypeOrdinal(): Int {
		return firstOrNull { !it.manga.source.name.startsWith("TRACKING_") }?.manga?.source?.contentType?.ordinal
			?: first().manga.source.contentType.ordinal
	}

	override fun resolveEntityIdForUiItemId(id: Long): Long? {
		return groupedEntityIds[id]
	}

	override fun resolvePreferredLocalMangaIdForUiItemId(id: Long): Long? {
		return groupedPreferredLocalIds[id] ?: groupedRemovalIds[id]?.firstOrNull()
	}

	private fun org.skepsun.kototoro.list.ui.model.ContentListModel.toGroupedListModel(group: UpdateGroup): ListModel {
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

	private fun Long.toUiGroupId(contentTypeOrdinal: Int): Long = -((this shl 8) or (contentTypeOrdinal + 1).toLong())

	private data class UpdateGroupKey(
		val uiId: Long,
		val contentTypeOrdinal: Int,
	)

	private data class UpdateGroup(
		val uiId: Long,
		val representative: ContentTracking,
		val mangaIds: Set<Long>,
		val lastChapterDate: Instant?,
		val totalNewChapters: Int,
		val entityId: Long?,
		val preferredLocalMangaId: Long?,
		val metadataSourceSelection: ContentDataRepository.MetadataSourceSelection?,
	)
}
