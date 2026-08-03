package org.skepsun.kototoro.suggestions.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.util.ext.onFirst
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.ui.ContentListViewModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.toErrorState
import org.skepsun.kototoro.suggestions.domain.SuggestionRepository
import org.skepsun.kototoro.suggestions.domain.SuggestionsListQuickFilter
import javax.inject.Inject
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.model.LocalContent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.explore.data.SourcePresetsRepository
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.GlobalTagBlacklist
import org.skepsun.kototoro.work.domain.WorkResolver
import java.util.concurrent.atomic.AtomicBoolean
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.scopedToSpace

private const val PAGE_SIZE = 32

@HiltViewModel
class SuggestionsViewModel @Inject constructor(
	repository: SuggestionRepository,
	settings: AppSettings,
	private val mangaListMapper: ContentListMapper,
	private val quickFilter: SuggestionsListQuickFilter,
	private val suggestionsScheduler: SuggestionsWorker.Scheduler,
	private val sourceGroupManager: SourceGroupManager,
	private val sourcePresetsRepository: SourcePresetsRepository,
	private val workResolver: WorkResolver,
	private val dataRepository: ContentDataRepository,
	@LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
	private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
	spaceBrowseScope: SpaceBrowseScope,
) : ContentListViewModel(settings, dataRepository, localStorageChanges), QuickFilterListener by quickFilter,
	SpaceBindableViewModel {
	private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)

	override val isFilterBarVisible = MutableStateFlow(true)

	@Volatile
	private var groupedSuggestionIds: Map<Long, Set<Long>> = emptyMap()

	@Volatile
	private var groupedEntityIds: Map<Long, Long> = emptyMap()

	@Volatile
	private var groupedPreferredLocalIds: Map<Long, Long> = emptyMap()

	private val limit = MutableStateFlow(PAGE_SIZE)
	private val isPaginationReady = AtomicBoolean(false)

	override val hasMoreItems: StateFlow<Boolean> = limit
		.map { it < Int.MAX_VALUE }
		.stateIn(viewModelScope, SharingStarted.Eagerly, true)

	override val listMode = settings.observeAsFlow(AppSettings.KEY_LIST_MODE) { this.listMode }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.listMode)

	override val currentGroupTab = globalFavoritesState.selectedGroupTab.scopedToSpace(
		spaceGroupTab = spaceBinding.groupTab,
		coroutineScope = viewModelScope + Dispatchers.Default,
	)
	override fun bindSpace(spaceId: org.skepsun.kototoro.space.domain.SpaceId?) = spaceBinding.bindSpace(spaceId)
	override val currentSourceTags = globalFavoritesState.selectedSourceTags

	override fun setSelectedGroupTab(tab: BrowseGroupTab) {
		globalFavoritesState.setSelectedGroupTab(tab)
	}

	override fun setSelectedSourceTags(tags: Set<SourceTag>) {
		globalFavoritesState.setSelectedSourceTags(tags)
	}

	private val loadParams = quickFilter.appliedOptions.combineWithSettings()

	override val content = combine(
		loadParams.flatMapLatest { filterOptions ->
			repository.observeAll(200, filterOptions)
		},
		quickFilter.appliedOptions,
		observeListModeWithTriggers(),
		currentGroupTab,
		currentSourceTags,
		mangaListMapper.observeDisplayChanges().onStart { emit(Unit) },
		settings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId }
			.flatMapLatest { id ->
				if (id == -1L) kotlinx.coroutines.flow.flowOf(null)
				else sourcePresetsRepository.observe(id)
			},
	) { values: Array<Any?> ->
		val list = values[0] as List<Content>
		val filters = values[1] as Set<ListFilterOption>
		val mode = values[2] as ListMode
		val groupTab = values[3] as BrowseGroupTab
		val sourceTags = values[4] as Set<SourceTag>
		val preset = values[6] as? SourcePreset
		val filteredList = list.filter { manga ->
			val source = manga.source
			if (!preset.matches(source)) {
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

		val hideAdult = settings.isSuggestionsExcludeNsfw
		val adultFilteredList = if (hideAdult) filteredList.filterNot { it.isNsfw() } else filteredList
		val visibleList = GlobalTagBlacklist(settings.globalTagBlacklist).filter(adultFilteredList)

		val resultList = ArrayList<ListModel>()

		if (visibleList.isEmpty()) {
			groupedSuggestionIds = emptyMap()
			groupedEntityIds = emptyMap()
			groupedPreferredLocalIds = emptyMap()
			if (filters.isEmpty() && groupTab == BrowseGroupTab.All && sourceTags.isEmpty()) {
				resultList.add(
					EmptyState(
						icon = R.drawable.ic_empty_common,
						textPrimary = R.string.nothing_found,
						textSecondary = R.string.text_suggestion_holder,
						actionStringRes = 0,
					)
				)
			} else {
				quickFilter.filterItem(filters)?.let { resultList.add(it) }
				resultList.add(
					EmptyState(
						icon = R.drawable.ic_empty_common,
						textPrimary = R.string.nothing_found,
						textSecondary = R.string.text_empty_holder_secondary_filtered,
						actionStringRes = 0,
					)
				)
			}
		} else {
			val groupedList = visibleList.aggregateByEntity()
			groupedSuggestionIds = groupedList.associate { it.uiId to it.mangaIds }
			groupedEntityIds = groupedList.mapNotNull { group ->
				group.entityId?.let { group.uiId to it }
			}.toMap()
			groupedPreferredLocalIds = groupedList.mapNotNull { group ->
				group.preferredLocalMangaId?.let { group.uiId to it }
			}.toMap()
			quickFilter.filterItem(filters)?.let { resultList.add(it) }
			for (group in groupedList) {
				val model = mangaListMapper.toListModel(group.representative, mode)
				resultList += model.toGroupedListModel(group)
			}
		}
		resultList as List<ListModel>
	}.onStart {
		loadingCounter.increment()
	}.map {
		isPaginationReady.set(true)
		it
	}.onFirst {
		loadingCounter.decrement()
	}.catch {
		emit(listOf<ListModel>(it.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	override fun onRefresh() {
		updateSuggestions()
	}

	override fun onRetry() = Unit

	fun updateSuggestions() {
		launchJob(Dispatchers.Default) {
			suggestionsScheduler.startNow()
		}
	}

	fun requestMoreItems() {
		if (isPaginationReady.compareAndSet(true, false)) {
			limit.value += PAGE_SIZE
		}
	}

	override fun resolveEntityIdForUiItemId(id: Long): Long? {
		return groupedEntityIds[id]
	}

	override fun resolvePreferredLocalMangaIdForUiItemId(id: Long): Long? {
		return groupedPreferredLocalIds[id] ?: groupedSuggestionIds[id]?.firstOrNull()
	}

	private suspend fun List<Content>.aggregateByEntity(): List<SuggestionGroup> {
		if (isEmpty()) {
			return emptyList()
		}
		val identitiesByMangaId = workResolver.resolveManyByMangaIds(map { it.id })
		val resolvedEntityIdsByMangaId = identitiesByMangaId.mapValues { it.value.entityId }.filterValues { it != null }
			.mapValues { requireNotNull(it.value) }
		val preferredLocalIdsByEntity = identitiesByMangaId.values
			.mapNotNull { identity -> identity.entityId?.let { it to identity.preferredMangaId } }
			.toMap()
		val displayTypeOrdinalByEntity = this
			.groupBy { resolvedEntityIdsByMangaId[it.id] }
			.mapNotNull { (entityId, items) ->
				entityId?.let { it to items.resolveDisplayContentTypeOrdinal() }
			}
			.toMap()
		val grouped = LinkedHashMap<SuggestionGroupKey, MutableList<Content>>(size)
		for (item in this) {
			val entityId = resolvedEntityIdsByMangaId[item.id]
			val contentTypeOrdinal = entityId?.let(displayTypeOrdinalByEntity::get) ?: item.source.contentType.ordinal
			val key = SuggestionGroupKey(
				uiId = entityId?.toUiGroupId(contentTypeOrdinal) ?: item.id,
				contentTypeOrdinal = contentTypeOrdinal,
			)
			grouped.getOrPut(key) { ArrayList(1) }.add(item)
		}
		return grouped.map { (key, items) ->
			val entityId = resolvedEntityIdsByMangaId[items.first().id]
			val preferredLocalMangaId = entityId?.let(preferredLocalIdsByEntity::get)
			val representative = items.firstOrNull { it.id == preferredLocalMangaId } ?: items.first()
			SuggestionGroup(
				uiId = key.uiId,
				representative = representative,
				mangaIds = items.mapTo(LinkedHashSet(items.size)) { it.id },
				projectionCount = items.size,
				entityId = entityId,
				preferredLocalMangaId = preferredLocalMangaId ?: representative.id,
			)
		}
	}

	private fun org.skepsun.kototoro.list.ui.model.ContentListModel.toGroupedListModel(group: SuggestionGroup): ListModel {
		val groupSuffix = if (group.projectionCount > 1) {
			"${group.projectionCount} 个投影来源"
		} else {
			null
		}
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

	private fun SourcePreset?.matches(source: org.skepsun.kototoro.parsers.model.ContentSource): Boolean {
		this ?: return true
		if (sources.isNotEmpty() && source.name !in sources) {
			return false
		}
		if (languages.isNotEmpty()) {
			val localeLanguage = source.getLocale()?.language
			if (localeLanguage !in languages) {
				return false
			}
		}
		return true
	}

	private data class SuggestionGroup(
		val uiId: Long,
		val representative: Content,
		val mangaIds: Set<Long>,
		val entityId: Long?,
		val preferredLocalMangaId: Long?,
		val projectionCount: Int,
	)

	private data class SuggestionGroupKey(
		val uiId: Long,
		val contentTypeOrdinal: Int,
	)

	private fun Long.toUiGroupId(contentTypeOrdinal: Int): Long = -((this shl 8) or (contentTypeOrdinal + 1).toLong())

	private fun List<Content>.resolveDisplayContentTypeOrdinal(): Int {
		return firstOrNull { !it.source.name.startsWith("TRACKING_") }?.source?.contentType?.ordinal
			?: first().source.contentType.ordinal
	}
}
