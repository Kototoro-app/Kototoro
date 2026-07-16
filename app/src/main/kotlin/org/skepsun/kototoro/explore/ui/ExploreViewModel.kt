package org.skepsun.kototoro.explore.ui

import android.util.Log
import androidx.collection.LongSet
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.core.model.ContentSourceAvailability
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.os.AppShortcutManager
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.combine
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.domain.ExploreRepository
import org.skepsun.kototoro.explore.data.SourceAvailabilityRepository
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.explore.ui.model.ExploreButtons
import org.skepsun.kototoro.explore.ui.model.SourceFilter
import org.skepsun.kototoro.explore.ui.model.ContentSourceItem
import org.skepsun.kototoro.list.ui.model.EmptyHint
import org.skepsun.kototoro.list.ui.model.ListHeader
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.scopedToSpace
import javax.inject.Inject

private const val ExploreViewModelTraceTag = "ExploreViewModelTrace"

private inline fun traceExploreViewModel(message: () -> String) {
	if (BuildConfig.DEBUG) {
		Log.d(ExploreViewModelTraceTag, message())
	}
}

private fun <T> Flow<T>.traceExploreInput(
	name: String,
	summary: (T) -> String,
): Flow<T> = onEach { value ->
	traceExploreViewModel { "input $name ${summary(value)}" }
}

@HiltViewModel
class ExploreViewModel @Inject constructor(
	private val settings: AppSettings,
	private val exploreRepository: ExploreRepository,
	private val sourcesRepository: ContentSourcesRepository,
	private val shortcutManager: AppShortcutManager,
	private val sourceGroupManager: SourceGroupManager,
	private val globalFavoritesState: org.skepsun.kototoro.favourites.domain.GlobalFavoritesState,
	private val sourcePresetsRepository: org.skepsun.kototoro.explore.data.SourcePresetsRepository,
	private val sourceAvailabilityRepository: SourceAvailabilityRepository,
	private val spaceBrowseScope: SpaceBrowseScope,
) : BaseViewModel(), SpaceBindableViewModel {
	private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)

	val isGrid = settings.observeAsStateFlow(
		key = AppSettings.KEY_SOURCES_GRID,
		scope = viewModelScope + Dispatchers.IO,
		valueProducer = { isSourcesGridMode },
	)

	val isAllSourcesEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.IO,
		key = AppSettings.KEY_SOURCES_ENABLED_ALL,
		valueProducer = { isAllSourcesEnabled },
	)

	val isEmptySourcesHidden = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.IO,
		key = AppSettings.KEY_EXPLORE_HIDE_EMPTY_SOURCES,
		valueProducer = { isEmptySourcesHiddenInExplore },
	)

	private val isSuggestionsEnabled = settings.observeAsFlow(
		key = AppSettings.KEY_SUGGESTIONS,
		valueProducer = { isSuggestionsEnabled },
	)

	val onOpenContent = MutableEventFlow<Content>()
	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onShowSuggestionsTip = MutableEventFlow<Unit>()

	/**
	 * Observable selected group tab for UI
	 */
	val currentGroupTab: StateFlow<BrowseGroupTab> = globalFavoritesState.selectedGroupTab.scopedToSpace(
		spaceGroupTab = spaceBinding.groupTab,
		coroutineScope = viewModelScope + Dispatchers.Default,
	)

	/**
	 * Observable selected source tags for UI
	 */
	val currentSourceTags: StateFlow<Set<SourceTag>> = globalFavoritesState.selectedSourceTags

	/**
	 * Available tabs based on NSFW setting
	 */
	val availableTabs: StateFlow<List<BrowseGroupTab>> = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.IO,
		key = AppSettings.KEY_DISABLE_NSFW,
		valueProducer = { BrowseGroupTab.getAvailableTabs(!isNsfwContentDisabled) },
	)

	/**
	 * Whether the secondary filter bar should be visible
	 */
	val isSourceFilterVisible: StateFlow<Boolean> = MutableStateFlow(true)

	val content: StateFlow<List<ListModel>> = isLoading
		.traceExploreInput("isLoading") { "value=$it" }
		.flatMapLatest { loading: Boolean ->
		if (loading) {
			flowOf<List<ListModel>>(getLoadingStateList())
		} else {
			createContentFlow()
		}
	}.onEach { models ->
		traceExploreViewModel {
			"content emitted models=${models.size} loadingOnly=${models.size == 1 && models.firstOrNull() is LoadingState}"
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, getLoadingStateList())
	
	/**
	 * Set the selected group tab and filter sources accordingly
	 */
	fun setSelectedGroupTab(tab: BrowseGroupTab) {
		globalFavoritesState.setSelectedGroupTab(tab)
	}

	override fun bindSpace(spaceId: org.skepsun.kototoro.space.domain.SpaceId?) {
		spaceBinding.bindSpace(spaceId)
	}

	/**
	 * Set selected source tags (multi-select)
	 */
	fun setSelectedSourceTags(tags: Set<SourceTag>) {
		globalFavoritesState.setSelectedSourceTags(tags)
	}
	
	/**
	 * Get the currently selected group tab
	 */
	fun getSelectedGroupTab(): BrowseGroupTab = currentGroupTab.value

	init {
		traceExploreViewModel {
			"created groupTab=${currentGroupTab.value} grid=${isGrid.value} allEnabled=${isAllSourcesEnabled.value} " +
				"activePreset=${settings.activeSourcePresetId}"
		}
		launchJob(Dispatchers.Default) {
			if (!settings.isSuggestionsEnabled && settings.isTipEnabled(TIP_SUGGESTIONS)) {
				onShowSuggestionsTip.call(Unit)
			}
		}
	}


	fun disableSources(sources: Collection<ContentSource>) {
		launchJob(Dispatchers.Default) {
			val rollback = sourcesRepository.setSourcesEnabled(sources, isEnabled = false)
			val message = if (sources.size == 1) R.string.source_disabled else R.string.sources_disabled
			onActionDone.call(ReversibleAction(message, rollback))
		}
	}

	fun requestPinShortcut(source: ContentSource) {
		launchLoadingJob(Dispatchers.Default) {
			shortcutManager.requestPinShortcut(source)
		}
	}

	fun setSourcesPinned(sources: Collection<ContentSource>, isPinned: Boolean) {
		launchJob(Dispatchers.Default) {
			sourcesRepository.setIsPinned(sources, isPinned)
			val message = if (sources.size == 1) {
				if (isPinned) R.string.source_pinned else R.string.source_unpinned
			} else {
				if (isPinned) R.string.sources_pinned else R.string.sources_unpinned
			}
			onActionDone.call(ReversibleAction(message, null))
		}
	}

	fun toggleEmptySourceAvailability(sources: Collection<ContentSourceInfo>) {
		if (sources.isEmpty()) {
			return
		}
		val target = if (sources.all { it.availability == ContentSourceAvailability.EMPTY }) {
			ContentSourceAvailability.AVAILABLE
		} else {
			ContentSourceAvailability.EMPTY
		}
		launchJob(Dispatchers.Default) {
			sources.forEach { source ->
				sourceAvailabilityRepository.setAvailability(source.mangaSource, target)
			}
		}
	}

	fun setEmptySourcesHidden(isHidden: Boolean) {
		settings.isEmptySourcesHiddenInExplore = isHidden
	}

	fun respondSuggestionTip(isAccepted: Boolean) {
		settings.isSuggestionsEnabled = isAccepted
		settings.closeTip(TIP_SUGGESTIONS)
	}

	fun sourcesSnapshot(ids: LongSet): List<ContentSourceInfo> {
		return content.value.mapNotNull {
			(it as? ContentSourceItem)?.takeIf { x -> x.id in ids }?.source
		}
	}

	private fun createContentFlow() = kotlinx.coroutines.flow.combine(
			sourcesRepository.observeEnabledBrowseSources()
				.traceExploreInput("sources") { "size=${it.size}" },
			isGrid.traceExploreInput("grid") { "value=$it" },
			isAllSourcesEnabled.traceExploreInput("allEnabled") { "value=$it" },
			sourcesRepository.observeHasNewSourcesForBadge()
				.traceExploreInput("badge") { "value=$it" },
			currentGroupTab.traceExploreInput("groupTab") { "value=$it" },
			currentSourceTags.traceExploreInput("sourceTags") { "size=${it.size} values=$it" },
			settings.observeAsStateFlow(
				key = AppSettings.KEY_SOURCES_GROUPED_BY_LANGUAGE,
				scope = viewModelScope + Dispatchers.IO,
				valueProducer = { isSourcesGroupedByLanguage },
			).traceExploreInput("grouped") { "value=$it" },
			settings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId }
				.traceExploreInput("presetId") { "value=$it" }
				.flatMapLatest { id ->
					if (id == -1L) flowOf(null)
					else sourcePresetsRepository.observe(id)
				}
				.traceExploreInput("preset") { "value=${it?.id ?: "none"}" },
			spaceBrowseScope.observeAllowedSourceNames(spaceBinding.spaceId),
		) { values: Array<Any?> ->
			@Suppress("UNCHECKED_CAST")
			buildList(
				values[0] as List<ContentSourceInfo>,
				values[1] as Boolean,
				values[2] as Boolean,
				values[3] as Boolean,
				values[4] as BrowseGroupTab,
				values[5] as Set<SourceTag>,
				values[6] as Boolean,
				values[7] as? org.skepsun.kototoro.explore.data.SourcePreset,
				values[8] as? Set<String>,
			)
		}.onEach { models ->
			traceExploreViewModel { "combined models=${models.size}" }
		}.withErrorHandling()

	private fun buildList(
		sources: List<ContentSourceInfo>,
		isGrid: Boolean,
		allSourcesEnabled: Boolean,
		hasNewSources: Boolean,
		groupTab: BrowseGroupTab,
		sourceTags: Set<SourceTag>,
		isGroupedByLanguage: Boolean,
		preset: org.skepsun.kototoro.explore.data.SourcePreset?,
		allowedSourceNames: Set<String>?,
	): List<ListModel> {
		// Apply group tab filtering
		val filteredSources = applyGroupTabFilter(sources, groupTab, sourceTags, preset, allowedSourceNames)
		
		val result = ArrayList<ListModel>(filteredSources.size + 3)
		if (filteredSources.isNotEmpty()) {
			if (isGroupedByLanguage) {
				val (pinned, unpinned) = filteredSources.partition { it.isPinned }
				if (pinned.isNotEmpty()) {
					result += ListHeader(
						textRes = R.string.source_pinned,
						buttonTextRes = if (allSourcesEnabled) R.string.manage else R.string.catalog,
						badge = if (!allSourcesEnabled && hasNewSources) "" else null,
					)
					pinned.mapTo(result) { ContentSourceItem(it, isGrid) }
				}
				
				val grouped = unpinned.groupBy { 
					it.mangaSource.getLocale()?.getDisplayName(java.util.Locale.getDefault())?.replaceFirstChar { c -> c.uppercase() } ?: "Other"
				}.toSortedMap()
				
				grouped.forEach { (language, sourcesInLang) ->
					result += ListHeader(
						text = language,
						buttonTextRes = if (allSourcesEnabled && result.none { it is ListHeader && it.buttonTextRes == R.string.manage }) R.string.manage else if (result.none { it is ListHeader && it.buttonTextRes == R.string.catalog }) R.string.catalog else 0,
						badge = if (!allSourcesEnabled && hasNewSources && result.none { it is ListHeader && it.badge != null }) "" else null,
					)
					sourcesInLang.mapTo(result) { ContentSourceItem(it, isGrid) }
				}
			} else {
				result += ListHeader(
					textRes = R.string.remote_sources,
					buttonTextRes = if (allSourcesEnabled) R.string.manage else R.string.catalog,
					badge = if (!allSourcesEnabled && hasNewSources) "" else null,
				)
				filteredSources.mapTo(result) { ContentSourceItem(it, isGrid) }
			}
		} else {
			result += EmptyHint(
				icon = R.drawable.ic_empty_common,
				textPrimary = emptySourceTitle(groupTab),
				textSecondary = emptySourceSubtitle(groupTab),
				actionStringRes = R.string.catalog,
			)
		}
		return result
	}
	
	/**
	 * Apply group tab filtering to sources
	 * 
	 * Filters sources based on the selected browse group tab:
	 * - All: Show all sources
	 * - Content/Novel/Video: Filter by content type
	 * 
	 * @param sources The complete list of sources to filter
	 * @param groupTab The selected browse group tab
	 * @return Filtered list of sources that match the tab criteria
	 */
	private fun applyGroupTabFilter(
		sources: List<ContentSourceInfo>,
		groupTab: BrowseGroupTab,
		sourceTags: Set<SourceTag>,
		preset: org.skepsun.kototoro.explore.data.SourcePreset?,
		allowedSourceNames: Set<String>?,
	): List<ContentSourceInfo> {
		val filtered = sources.filter { sourceInfo ->
			val source = sourceInfo.mangaSource
			if (allowedSourceNames != null && source.name !in allowedSourceNames) {
				return@filter false
			}
			if (preset != null && source.name !in preset.sources) {
				return@filter false
			}

			val contentGroup = sourceGroupManager.getContentGroup(source)
			val originGroup = sourceGroupManager.getOriginGroup(source)
			
			// Apply group tab and secondary tag filters.
			val groupMatches = groupTab.matchesContentGroup(contentGroup) && groupTab.matchesOriginGroup(originGroup)
			
			val originMatches = if (sourceTags.isEmpty()) {
				true
			} else if (sourceTags.contains(SourceTag.PINNED)) {
				sourceInfo.isPinned
			} else {
				sourceTags.any { it.matches(contentGroup, originGroup) }
			}

			groupMatches && originMatches
		}
		return filtered
	}

	private fun getLoadingStateList() = listOf(
		LoadingState,
	)

	private fun emptySourceTitle(groupTab: BrowseGroupTab): Int = when (groupTab) {
		BrowseGroupTab.Novel -> R.string.no_novel_sources
		BrowseGroupTab.Video -> R.string.no_video_sources
		else -> R.string.no_manga_sources
	}

	private fun emptySourceSubtitle(groupTab: BrowseGroupTab): Int = when (groupTab) {
		BrowseGroupTab.Novel -> R.string.no_novel_sources_text
		BrowseGroupTab.Video -> R.string.no_video_sources_text
		else -> R.string.no_manga_sources_text
	}

	companion object {

		private const val TIP_SUGGESTIONS = "suggestions"
	}

}
