package org.skepsun.kototoro.search.ui.multi

import android.net.Uri
import android.util.Log
import androidx.collection.ArraySet
import androidx.collection.LongSet
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.UnknownContentSource
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.append
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toLocale
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.explore.data.SourcePresetsRepository
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.favourites.domain.GlobalFavoritesState
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.ui.model.ButtonFooter
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingFooter
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.levenshteinDistance
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS
import org.skepsun.kototoro.search.domain.ALL_SOURCE_TYPES
import org.skepsun.kototoro.search.domain.AdvancedSearchParams
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.search.domain.SearchV2Helper
import org.skepsun.kototoro.search.domain.matches
import org.skepsun.kototoro.search.domain.searchContentKindsFromNames
import org.skepsun.kototoro.search.domain.sourceTypesFromNames
import org.skepsun.kototoro.search.domain.sourceTypesFromTags
import java.util.Locale
import javax.inject.Inject

private const val MAX_PARALLELISM = 4
private const val TAG = "SearchViewModel"

@HiltViewModel
class SearchViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val mangaListMapper: ContentListMapper,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val sourcesRepository: ContentSourcesRepository,
	private val sourceTypeIdentifier: SourceTypeIdentifier,
	private val appSettings: AppSettings,
	private val sourcePresetsRepository: SourcePresetsRepository,
	private val globalFavoritesState: GlobalFavoritesState,
	private val historyRepository: HistoryRepository,
	private val favouritesRepository: FavouritesRepository,
) : BaseViewModel() {

	val query = savedStateHandle.getStringArg(AppRouter.KEY_QUERY).orEmpty()
	val kind = savedStateHandle.getSearchKindArg(AppRouter.KEY_KIND) ?: SearchKind.SIMPLE
	
	val advancedQuery = if (kind == SearchKind.ADVANCED) {
		AdvancedSearchParams(
			query = query,
			title = savedStateHandle.get<String>(AppRouter.KEY_ADVANCED_TITLE).orEmpty(),
			tags = savedStateHandle.get<String>(AppRouter.KEY_ADVANCED_TAGS).orEmpty(),
			author = savedStateHandle.get<String>(AppRouter.KEY_ADVANCED_AUTHOR).orEmpty(),
    	)
	} else null

	private var includeDisabledSources = MutableStateFlow(false)
	private var pinnedOnly = MutableStateFlow(savedStateHandle.getBooleanArg(AppRouter.KEY_PINNED_ONLY) == true)
	private var hideEmpty = MutableStateFlow(savedStateHandle.getBooleanArg(AppRouter.KEY_HIDE_EMPTY) == true)
	private var sourceTypes = MutableStateFlow(
		sourceTypesFromNames(savedStateHandle.getStringList(AppRouter.KEY_SOURCE_TYPES))
			?: sourceTypesFromTags(globalFavoritesState.selectedSourceTags.value),
	)
	private var contentKinds = MutableStateFlow(
		searchContentKindsFromNames(savedStateHandle.getStringList(AppRouter.KEY_CONTENT_KINDS))
			?: ALL_SEARCH_CONTENT_KINDS,
	)
	val languagePresets: StateFlow<List<SourcePreset>> = sourcePresetsRepository.observeAll()
		.stateIn(viewModelScope + Dispatchers.IO, SharingStarted.Eagerly, emptyList())
	val activeLanguagePresetId: StateFlow<Long> = appSettings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.IO,
		key = AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID,
		valueProducer = { appSettings.activeSourcePresetId },
	)
	val globalTagBlacklist: StateFlow<Set<String>> = appSettings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.IO,
		key = AppSettings.KEY_GLOBAL_TAG_BLACKLIST,
		valueProducer = { appSettings.globalTagBlacklist },
	)
	private val results = MutableStateFlow<List<SearchResultsListModel>>(emptyList())

	private var searchJob: Job? = null

	val list: StateFlow<List<ListModel>> = combine(
		results,
		isLoading.dropWhile { !it },
		includeDisabledSources,
		hideEmpty,
	) { list, loading, includeDisabled, hideEmptyVal ->
		val filteredList = if (hideEmptyVal) {
			list.filter { it.list.isNotEmpty() }
		} else {
			list
		}
		when {
			filteredList.isEmpty() -> listOf(
				when {
					loading -> LoadingState
					else -> EmptyState(
						icon = R.drawable.ic_empty_common,
						textPrimary = R.string.nothing_found,
						textSecondary = R.string.text_search_holder_secondary,
						actionStringRes = 0,
					)
				},
			)

			loading -> filteredList + LoadingFooter()
			includeDisabled -> filteredList
			else -> filteredList + ButtonFooter(R.string.search_disabled_sources)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		doSearch()
	}

	fun getItems(ids: LongSet): Set<Content> {
		val snapshot = results.value
		val result = ArraySet<Content>(ids.size)
		snapshot.forEach { x ->
			for (item in x.list) {
				if (item.id in ids) {
					result.add(item.manga)
				}
			}
		}
		return result
	}

	fun getItems(ids: Set<Long>): Set<Content> {
		val snapshot = results.value
		val result = ArraySet<Content>(ids.size)
		snapshot.forEach { x ->
			for (item in x.list) {
				if (item.id in ids) {
					result.add(item.manga)
				}
			}
		}
		return result
	}

	fun retry() {
		searchJob?.cancel()
		results.value = emptyList()
		includeDisabledSources.value = false
		doSearch()
	}

	fun setPinnedOnly(value: Boolean) {
		if (pinnedOnly.value != value) {
			pinnedOnly.value = value
			retry()
		}
	}

	fun setHideEmpty(value: Boolean) {
		hideEmpty.value = value
	}

	val isPinnedOnlySelected: Boolean
		get() = pinnedOnly.value

	val isHideEmptySelected: Boolean
		get() = hideEmpty.value

	fun isSourceTypeEnabled(type: SourceType): Boolean {
		return type in sourceTypes.value
	}

	fun setSourceTypeEnabled(type: SourceType, enabled: Boolean) {
		val updated = sourceTypes.value.toMutableSet().apply {
			if (enabled) add(type) else remove(type)
		}
		setSourceTypes(updated)
	}

	fun setSourceTypes(types: Set<SourceType>) {
		val resolved = if (types.isEmpty()) ALL_SOURCE_TYPES else types
		if (resolved != sourceTypes.value) {
			sourceTypes.value = resolved
			retry()
		}
	}

	fun getSourceTypes(): Set<SourceType> {
		return sourceTypes.value
	}

	fun setContentKinds(kinds: Set<SearchContentKind>) {
		val resolved = if (kinds.isEmpty()) ALL_SEARCH_CONTENT_KINDS else kinds
		if (resolved != contentKinds.value) {
			contentKinds.value = resolved
			retry()
		}
	}

	fun getContentKinds(): Set<SearchContentKind> {
		return contentKinds.value
	}

	fun setActiveLanguagePreset(presetId: Long) {
		if (appSettings.activeSourcePresetId != presetId) {
			appSettings.activeSourcePresetId = presetId
			retry()
		}
	}

	fun continueSearch() {
		if (includeDisabledSources.value) {
			return
		}
		val prevJob = searchJob
		searchJob = launchLoadingJob(Dispatchers.Default) {
			includeDisabledSources.value = true
			prevJob?.join()
			val sources = if (pinnedOnly.value) {
				emptyList()
			} else {
				sourcesRepository.getDisabledSources()
					.sortedByDescending { it.priority() }
			}
			val filteredSources = filterSourcesByType(filterSourcesByActivePreset(sources))
			val semaphore = Semaphore(MAX_PARALLELISM)
			filteredSources.map { source ->
				launch {
					semaphore.withPermit {
						appendResult(searchSource(source))
					}
				}
			}.joinAll()
		}
	}

	private fun doSearch() {
		val prevJob = searchJob
		searchJob = launchLoadingJob(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			appendResult(searchHistory())
			appendResult(searchFavorites())
			appendResult(searchLocal())
			val sources = if (pinnedOnly.value) {
				sourcesRepository.getPinnedSources().toList()
			} else {
				sourcesRepository.getEnabledSources()
			}
			val filteredSources = filterSourcesByType(filterSourcesByActivePreset(sources))
			val semaphore = Semaphore(MAX_PARALLELISM)
			filteredSources.map { source ->
				launch {
					semaphore.withPermit {
						appendResult(searchSource(source))
					}
				}
			}.joinAll()
		}
	}

	// impl

	private suspend fun searchSource(source: ContentSource): SearchResultsListModel? = runCatchingCancellable {
		val searchHelper = searchHelperFactory.create(source)
		searchHelper(query, kind, advancedQuery)
	}.fold(
		onSuccess = { result ->
			if (result == null || result.manga.isEmpty()) {
				null
			} else {
				val list = mangaListMapper.toListModelList(
					manga = result.manga,
					mode = ListMode.GRID,
				)
				Log.d(
					TAG,
					"searchSource result source=${source.name} rawCount=${result.manga.size} mappedCount=${list.size} rawCovers=${
						result.manga.joinToString(limit = 5) { "${it.title}=>${it.coverUrl ?: "<null>"}" }
					} mappedCovers=${
						list.joinToString(limit = 5) { "${it.title}=>${it.coverUrl ?: "<null>"}" }
					}",
				)
				SearchResultsListModel(
					titleResId = 0,
					source = source,
					list = list,
					error = null,
					listFilter = result.listFilter,
					sortOrder = result.sortOrder,
				)
			}
		},
		onFailure = { error ->
			error.printStackTraceDebug()
			SearchResultsListModel(0, source, null, null, emptyList(), error)
		},
	)

	private suspend fun searchHistory(): SearchResultsListModel? = runCatchingCancellable {
		historyRepository.search(query, kind, Int.MAX_VALUE)
	}.fold(
		onSuccess = { result ->
			val filtered = filterContentBySourceType(result).applyAdvancedFilter()
			if (filtered.isNotEmpty()) {
				SearchResultsListModel(
					titleResId = R.string.history,
					source = UnknownContentSource,
					list = mangaListMapper.toListModelList(manga = filtered, mode = ListMode.GRID),
					error = null,
					listFilter = null,
					sortOrder = null,
				)
			} else {
				null
			}
		},
		onFailure = { error ->
			SearchResultsListModel(
				titleResId = R.string.history,
				source = UnknownContentSource,
				list = emptyList(),
				error = error,
				listFilter = null,
				sortOrder = null,
			)
		},
	)

	private suspend fun searchFavorites(): SearchResultsListModel? = runCatchingCancellable {
		favouritesRepository.search(query, kind, Int.MAX_VALUE)
	}.fold(
		onSuccess = { result ->
			val filtered = filterContentBySourceType(result).applyAdvancedFilter()
			if (filtered.isNotEmpty()) {
				SearchResultsListModel(
					titleResId = R.string.favourites,
					source = UnknownContentSource,
					list = mangaListMapper.toListModelList(
						manga = filtered,
						mode = ListMode.GRID,
						flags = ContentListMapper.NO_FAVORITE,
					),
					error = null,
					listFilter = null,
					sortOrder = null,
				)
			} else {
				null
			}
		},
		onFailure = { error ->
			SearchResultsListModel(
				titleResId = R.string.favourites,
				source = UnknownContentSource,
				list = emptyList(),
				error = error,
				listFilter = null,
				sortOrder = null,
			)
		},
	)

	private suspend fun searchLocal(): SearchResultsListModel? = runCatchingCancellable {
		if (isSourceTypeAllowed(LocalMangaSource)) {
			searchHelperFactory.create(LocalMangaSource).invoke(query, kind, advancedQuery)
		} else {
			null
		}
	}.fold(
		onSuccess = { result ->
			if (!result?.manga.isNullOrEmpty()) {
				SearchResultsListModel(
					titleResId = 0,
					source = LocalMangaSource,
					list = mangaListMapper.toListModelList(
						manga = result.manga,
						mode = ListMode.GRID,
						flags = ContentListMapper.NO_SAVED,
					),
					error = null,
					listFilter = result.listFilter,
					sortOrder = result.sortOrder,
				)
			} else {
				null
			}
		},
		onFailure = { error ->
			SearchResultsListModel(
				titleResId = 0,
				source = LocalMangaSource,
				list = emptyList(),
				error = error,
				listFilter = null,
				sortOrder = null,
			)
		},
	)

	private fun appendResult(item: SearchResultsListModel?) {
		if (item != null) {
			results.append(item)
		}
	}

	private fun filterSourcesByType(sources: Collection<ContentSource>): List<ContentSource> {
		val allowedSourceTypes = sourceTypes.value
		val allowedContentKinds = contentKinds.value
		return sources.filter { source ->
			sourceTypeIdentifier.getSourceType(source.name) in allowedSourceTypes &&
				allowedContentKinds.any { it.matches(source) }
		}
	}

	private fun filterSourcesByActivePreset(sources: Collection<ContentSource>): List<ContentSource> {
		val activePreset = languagePresets.value.firstOrNull { it.id == activeLanguagePresetId.value }
			?: return sources.toList()
		if (activePreset.sources.isEmpty()) {
			return emptyList()
		}
		return sources.filter { it.name in activePreset.sources }
	}

	private fun filterContentBySourceType(manga: List<Content>): List<Content> {
		val allowedSourceTypes = sourceTypes.value
		val allowedContentKinds = contentKinds.value
		return manga.filter { item ->
			sourceTypeIdentifier.getSourceType(item.source.name) in allowedSourceTypes &&
				allowedContentKinds.any { it.matches(item) }
		}
	}

	private fun isSourceTypeAllowed(source: ContentSource): Boolean {
		return sourceTypeIdentifier.getSourceType(source.name) in sourceTypes.value &&
			contentKinds.value.any { it.matches(source) }
	}

	
	private fun List<Content>.applyAdvancedFilter(): List<Content> {
		val advanced = advancedQuery ?: return this
		if (kind != SearchKind.ADVANCED) return this
		return filter { m ->
			var titleMatch: Boolean? = null
			var authorMatch: Boolean? = null
			var tagsMatch: Boolean? = null
			if (advanced.title.isNotEmpty()) {
				val threshold = 0.2f
				val titleDist = minOf(
					m.title.levenshteinDistance(advanced.title),
					m.altTitle?.levenshteinDistance(advanced.title) ?: Int.MAX_VALUE,
				)
				val titleLen = maxOf(
					maxOf(m.title.length, advanced.title.length),
					m.altTitle?.let { maxOf(it.length, advanced.title.length) } ?: 0,
				)
				titleMatch = titleLen > 0 && titleDist.toFloat() / titleLen <= threshold
			}
			if (advanced.author.isNotEmpty()) {
				authorMatch = m.authors.isEmpty() ||
					m.authors.any { it.contains(advanced.author, ignoreCase = true) }
			}
			if (advanced.tags.isNotEmpty()) {
				val parts = advanced.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
				val includeTags = parts.filter { it[0] != '-' }
				val excludeTags = parts.filter { it[0] == '-' }.map { it.substring(1) }
				val hasAllIncluded = includeTags.all { q ->
					m.tags.any { tag -> tag.title.equals(q, ignoreCase = true) }
				}
				val hasAnyExcluded = excludeTags.any { q ->
					m.tags.any { tag -> tag.title.equals(q, ignoreCase = true) }
				}
				tagsMatch = hasAllIncluded && !hasAnyExcluded
			}
			titleMatch != false && authorMatch != false && tagsMatch != false
		}
	}

	private fun ContentSource.priority(): Int {
		var res = 0
		if (this.getLocale() == Locale.getDefault()) res += 2
		return res
	}

}

private fun SavedStateHandle.getStringList(key: String): ArrayList<String>? {
	return when (val raw = get<Any?>(key)) {
		is ArrayList<*> -> ArrayList(raw.filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() })
			.takeIf { it.isNotEmpty() }
		is Collection<*> -> ArrayList(raw.filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() })
			.takeIf { it.isNotEmpty() }
		is String -> Uri.decode(raw)
			.takeIf { it.isNotBlank() }
			?.split(',')
			?.map { it.trim() }
			?.filter { it.isNotEmpty() }
			?.let(::ArrayList)
		else -> null
	}
}

private fun SavedStateHandle.getStringArg(key: String): String? = when (val raw = get<Any?>(key)) {
	is String -> Uri.decode(raw)
	is CharSequence -> raw.toString()
	is Enum<*> -> raw.name
	else -> null
}

private fun SavedStateHandle.getBooleanArg(key: String): Boolean? = when (val raw = get<Any?>(key)) {
	is Boolean -> raw
	is String -> Uri.decode(raw).toBooleanStrictOrNull()
	is Number -> raw.toInt() != 0
	else -> null
}

private fun SavedStateHandle.getSearchKindArg(key: String): SearchKind? = when (val raw = get<Any?>(key)) {
	is SearchKind -> raw
	is String -> runCatching { SearchKind.valueOf(Uri.decode(raw)) }.getOrNull()
	is Enum<*> -> runCatching { SearchKind.valueOf(raw.name) }.getOrNull()
	else -> null
}
