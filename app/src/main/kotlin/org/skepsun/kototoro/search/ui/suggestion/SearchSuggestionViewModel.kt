package org.skepsun.kototoro.search.ui.suggestion

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import org.skepsun.kototoro.core.model.ContentSourceInfo
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.SearchSuggestionType
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.widgets.ChipModel
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.jsonsource.SourceType
import org.skepsun.kototoro.core.jsonsource.SourceTypeIdentifier
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.explore.data.SourcePresetsRepository
import org.skepsun.kototoro.favourites.domain.GlobalFavoritesState
import org.skepsun.kototoro.tracking.discovery.domain.EntityType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.search.domain.ContentSearchRepository
import org.skepsun.kototoro.search.domain.LibrarySearchScope
import org.skepsun.kototoro.search.domain.matchLibrarySuggestions
import org.skepsun.kototoro.local.data.LocalMangaRepository
import org.skepsun.kototoro.suggestions.domain.SuggestionRepository
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.search.domain.ALL_SOURCE_TYPES
import org.skepsun.kototoro.search.domain.ALL_SEARCH_CONTENT_KINDS
import org.skepsun.kototoro.search.domain.SearchContentKind
import org.skepsun.kototoro.search.domain.matches
import org.skepsun.kototoro.search.domain.sourceTypesFromTags
import org.skepsun.kototoro.search.ui.suggestion.model.SearchSuggestionItem
import org.skepsun.kototoro.search.ui.suggestion.model.TrackingEntity
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.PreferredTrackingSiteProvider
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCatalog
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService
import javax.inject.Inject

private const val DEBOUNCE_TIMEOUT = 300L
private const val REMOTE_SUGGESTIONS_TIMEOUT = 10_000L
private const val MAX_MANGA_ITEMS = 12
private const val MAX_QUERY_ITEMS = 16
private const val MAX_HINTS_ITEMS = 3
private const val MAX_AUTHORS_ITEMS = 2
private const val MAX_TAGS_ITEMS = 8
private const val MAX_SOURCES_ITEMS = 6
private const val MAX_SOURCES_TIPS_ITEMS = 2
private const val MAX_TRACKING_WORK_ITEMS = 3
private const val MAX_TRACKING_ENTITY_ITEMS = 6
private const val MAX_LIBRARY_ITEMS = 24

/** How long a loaded page stays valid for suggestions; the page itself always filters live data. */
private const val LIBRARY_SNAPSHOT_TTL_MILLIS = 60_000L

@HiltViewModel
class SearchSuggestionViewModel @Inject constructor(
    private val repository: ContentSearchRepository,
    private val settings: AppSettings,
    private val sourcesRepository: ContentSourcesRepository,
    private val sourcePresetsRepository: SourcePresetsRepository,
    private val sourceTypeIdentifier: SourceTypeIdentifier,
    private val globalFavoritesState: GlobalFavoritesState,
    private val trackingSiteDiscoveryService: TrackingSiteDiscoveryService,
    private val preferredTrackingSiteProvider: PreferredTrackingSiteProvider,
    private val historyRepository: HistoryRepository,
    private val favouritesRepository: FavouritesRepository,
    private val localMangaRepository: LocalMangaRepository,
    private val suggestionRepository: SuggestionRepository,
) : BaseViewModel() {

    private val query = MutableStateFlow("")

    /** Non-null while the overlay's page tab narrows suggestions to one library page. */
    private val libraryScope = MutableStateFlow<LibrarySearchScope?>(null)
    private val invalidationTrigger = MutableStateFlow(0)
    private val sourceTypes = MutableStateFlow(
        sourceTypesFromTags(globalFavoritesState.selectedSourceTags.value),
    )
    private val contentKinds = MutableStateFlow(ALL_SEARCH_CONTENT_KINDS)

    private val activeSourcePreset: Flow<SourcePreset?> = settings.observeAsFlow(
        AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID,
    ) {
        activeSourcePresetId
    }.mapLatest { presetId ->
        if (presetId > 0L) {
            sourcePresetsRepository.getById(presetId)
        } else {
            null
        }
    }

    private val enabledSourcesSnapshot: Flow<EnabledSourcesSnapshot> = combine(
        sourcesRepository.observeEnabledSources(),
        activeSourcePreset,
    ) { infos, preset ->
        infos.toEnabledSourcesSnapshot().filterByPreset(preset)
    }
        .distinctUntilChanged()

    val isIncognitoModeEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_INCOGNITO_MODE,
        valueProducer = { isIncognitoModeEnabled },
    )

    private val suggestionParams = combine(
        combine(
            query,
            enabledSourcesSnapshot,
            settings.observeAsFlow(AppSettings.KEY_SEARCH_SUGGESTION_TYPES) { searchSuggestionTypes },
            preferredTrackingSiteProvider.preferredSite,
        ) { searchQuery, enabledSources, types, preferredTrackingSite ->
            SuggestionParamsPartial(
                searchQuery = searchQuery,
                enabledSources = enabledSources,
                types = types,
                preferredTrackingSite = preferredTrackingSite,
            )
        },
        sourceTypes,
        contentKinds,
    ) { partial, activeSourceTypes, activeContentKinds ->
        SuggestionParams(
            searchQuery = partial.searchQuery,
            enabledSources = partial.enabledSources,
            types = partial.types,
            preferredTrackingSite = partial.preferredTrackingSite,
            activeSourceTypes = activeSourceTypes,
            activeContentKinds = activeContentKinds,
        )
    }

    val suggestionState: Flow<SearchSuggestionState> = combine(
        suggestionParams,
        invalidationTrigger,
        libraryScope,
    ) { params, _, scope ->
        params to scope
    }.flatMapLatest { (params, scope) ->
        if (scope != null) {
            return@flatMapLatest librarySuggestions(params.searchQuery, scope, params.types)
        }
        val filteredSources = params.enabledSources.filterByTypes(
            sourceTypes = params.activeSourceTypes,
            contentKinds = params.activeContentKinds,
            identifier = sourceTypeIdentifier,
        )
        val sourceScope = SourceScope(
            presetNames = params.enabledSources.presetNames,
            sourceTypes = params.activeSourceTypes,
            contentKinds = params.activeContentKinds,
            identifier = sourceTypeIdentifier,
        )
        flow {
            emit(SearchSuggestionState(query = params.searchQuery, isLoading = true))
            if (params.searchQuery.isNotEmpty()) delay(DEBOUNCE_TIMEOUT)
            emitAll(
                progressiveSearchSuggestions(
                    local = {
                        buildLocalSuggestions(
                            searchQuery = params.searchQuery,
                            enabledSources = filteredSources,
                            sourceScope = sourceScope,
                            types = params.types,
                        )
                    },
                    remote = if (params.searchQuery.isEmpty()) null else {
                        { getTrackingEntities(params.searchQuery, params.preferredTrackingSite) }
                    },
                ).map { it.copy(query = params.searchQuery) },
            )
        }
    }.distinctUntilChanged()
        .withErrorHandling()
        .flowOn(Dispatchers.Default)

    val suggestion: Flow<List<SearchSuggestionItem>> = suggestionState
        .filter { !it.isLoading }
        .map { it.items }
        .distinctUntilChanged()

    fun onQueryChanged(newQuery: String) {
        query.value = newQuery.trim()
    }

    fun retrySuggestions() {
        synchronized(this) { librarySnapshot = null }
        invalidationTrigger.value++
    }

    fun setSourceTypes(types: Set<SourceType>) {
        val resolved = if (types.isEmpty()) ALL_SOURCE_TYPES else types
        if (sourceTypes.value != resolved) {
            sourceTypes.value = resolved
        }
    }

    fun getSourceTypes(): Set<SourceType> = sourceTypes.value

    fun setContentKinds(kinds: Set<SearchContentKind>) {
        val resolved = if (kinds.isEmpty()) ALL_SEARCH_CONTENT_KINDS else kinds
        if (contentKinds.value != resolved) {
            contentKinds.value = resolved
        }
    }

    fun getContentKinds(): Set<SearchContentKind> = contentKinds.value

    fun setLibraryScope(scope: LibrarySearchScope?) {
        libraryScope.value = scope
        // Start reading the page now: by the time the first word is typed it is usually ready.
        if (scope != null && scope.matchesInMemory) libraryContents(scope)
    }

    /**
     * One loaded page, so each keystroke filters it in memory instead of reading the whole history
     * again. Reading thousands of history rows with their tags took seconds on a large library.
     */
    private class LibrarySnapshot(
        val scope: LibrarySearchScope,
        val loadedAtMillis: Long,
        val contents: Deferred<List<Content>>,
    )

    private var librarySnapshot: LibrarySnapshot? = null

    private fun libraryContents(scope: LibrarySearchScope): Deferred<List<Content>> = synchronized(this) {
        val now = System.currentTimeMillis()
        val current = librarySnapshot
        if (current != null && current.scope == scope && !current.contents.isCancelled &&
            now - current.loadedAtMillis < LIBRARY_SNAPSHOT_TTL_MILLIS
        ) {
            return@synchronized current.contents
        }
        // Failed loads count as cancelled, so the next keystroke tries again.
        val contents = viewModelScope.async(Dispatchers.Default) { loadLibrary(scope) }
        librarySnapshot = LibrarySnapshot(scope, now, contents)
        contents
    }

    private suspend fun loadLibrary(scope: LibrarySearchScope): List<Content> = when (scope) {
        LibrarySearchScope.HISTORY -> historyRepository.getAllForLibrarySearch()
        LibrarySearchScope.FAVOURITES -> favouritesRepository.getAllContent()
        LibrarySearchScope.SUGGESTIONS -> suggestionRepository.observeAll().first()
        LibrarySearchScope.LOCAL,
        LibrarySearchScope.UPDATES,
        LibrarySearchScope.FEED,
        LibrarySearchScope.BOOKMARKS -> emptyList()
    }

    /** Pages whose suggestions are matched against a loaded list (the others query their own source). */
    private val LibrarySearchScope.matchesInMemory: Boolean
        get() = this == LibrarySearchScope.HISTORY ||
            this == LibrarySearchScope.FAVOURITES ||
            this == LibrarySearchScope.SUGGESTIONS

    fun saveQuery(query: String) {
        if (!settings.isIncognitoModeEnabled) {
            repository.saveSearchQuery(query)
            invalidationTrigger.value++
        }
    }

    fun clearSearchHistory() {
        launchJob(Dispatchers.Default) {
            repository.clearSearchHistory()
            invalidationTrigger.value++
        }
    }

    fun onSourceToggle(source: ContentSource, isEnabled: Boolean) {
        launchJob(Dispatchers.Default) {
            sourcesRepository.setSourcesEnabled(setOf(source), isEnabled)
        }
    }

    fun deleteQuery(query: String) {
        launchJob(Dispatchers.Default) {
            repository.deleteSearchQuery(query)
            invalidationTrigger.value++
        }
    }

    private suspend fun buildLocalSuggestions(
        searchQuery: String,
        enabledSources: EnabledSourcesSnapshot,
        sourceScope: SourceScope,
        types: Set<SearchSuggestionType>,
    ): List<SearchSuggestionItem> = coroutineScope {
        listOfNotNull(
            if (SearchSuggestionType.GENRES in types) {
                async { getTags(searchQuery) }
            } else {
                null
            },
            if (SearchSuggestionType.MANGA in types || searchQuery.isBlank()) {
                async { getContent(searchQuery) }
            } else {
                null
            },
            if (SearchSuggestionType.SOURCES in types) {
                async { getSources(searchQuery, enabledSources) }
            } else {
                null
            },
            if (SearchSuggestionType.QUERIES_RECENT in types) {
                async { getRecentQueries(searchQuery) }
            } else {
                null
            },
            if (SearchSuggestionType.QUERIES_SUGGEST in types) {
                async { getQueryHints(searchQuery) }
            } else {
                null
            },
            if (SearchSuggestionType.RECENT_SOURCES in types) {
                async { getRecentSources(searchQuery, enabledSources, sourceScope) }
            } else {
                null
            },
            if (SearchSuggestionType.AUTHORS in types) {
                async {
                    getAuthors(searchQuery)
                }
            } else {
                null
            },
        ).flatMap { it.await() }
    }

    private suspend fun getTrackingEntities(
        searchQuery: String,
        service: ScrobblerService,
    ): List<SearchSuggestionItem> {
        val trimmedQuery = searchQuery.trim()
        if (trimmedQuery.isEmpty()) {
            return emptyList()
        }
        return runCatchingCancellable {
            coroutineScope {
                val worksDeferred = async {
                    trackingRequest {
                        trackingSiteDiscoveryService.search(
                            TrackingSiteCatalog(
                                service = service,
                                query = trimmedQuery,
                            ),
                        ).asSequence()
                            .take(MAX_TRACKING_WORK_ITEMS)
                            .map { item ->
                                TrackingEntity(
                                    service = item.service,
                                    entityType = EntityType.WORK,
                                    remoteId = item.remoteId,
                                    name = item.title,
                                    altName = item.altTitle ?: item.subtitle,
                                    coverUrl = item.coverUrl,
                                    url = item.url,
                                )
                            }
                            .toList()
                    }
                }
                val personsDeferred = async {
                    trackingRequest {
                        trackingSiteDiscoveryService.searchEntities(
                            service = service,
                            entityType = EntityType.PERSON,
                            query = trimmedQuery,
                        ).take(MAX_TRACKING_ENTITY_ITEMS / 2)
                    }
                }
                val charactersDeferred = async {
                    trackingRequest {
                        trackingSiteDiscoveryService.searchEntities(
                            service = service,
                            entityType = EntityType.CHARACTER,
                            query = trimmedQuery,
                        ).take(MAX_TRACKING_ENTITY_ITEMS / 2)
                    }
                }
                val works = worksDeferred.await()
                val persons = personsDeferred.await()
                val characters = charactersDeferred.await()
                val entities = (persons.getOrDefault(emptyList()) + characters.getOrDefault(emptyList()))
                    .asSequence()
                    .distinctBy { "${it.entityType.name}:${it.remoteId}" }
                    .take(MAX_TRACKING_ENTITY_ITEMS)
                    .map { item ->
                        TrackingEntity(
                            service = item.service,
                            entityType = item.entityType,
                            remoteId = item.remoteId,
                            name = item.name,
                            altName = item.altName,
                            coverUrl = item.coverUrl,
                            url = item.url,
                        )
                    }
                    .toList()
                val items = works.getOrDefault(emptyList()) + entities
                if (items.isEmpty()) {
                    listOfNotNull(works.exceptionOrNull(), persons.exceptionOrNull(), characters.exceptionOrNull())
                        .firstOrNull()?.let { throw it }
                    emptyList()
                } else {
                    listOf(SearchSuggestionItem.TrackingEntityList(service, items))
                }
            }
        }.getOrElse { e ->
            e.printStackTraceDebug()
            throw e
        }
    }

    private suspend fun <T> trackingRequest(block: suspend () -> List<T>): Result<List<T>> =
        runCatchingCancellable {
            withTimeoutOrNull(REMOTE_SUGGESTIONS_TIMEOUT) { block() } ?: throw SocketTimeoutException()
        }

    private fun librarySuggestions(
        searchQuery: String,
        scope: LibrarySearchScope,
        types: Set<SearchSuggestionType>,
    ): Flow<SearchSuggestionState> = flow {
        emit(SearchSuggestionState(query = searchQuery, isLoading = true, libraryScope = scope))
        if (searchQuery.isNotEmpty()) delay(DEBOUNCE_TIMEOUT)
        val items = coroutineScope {
            val matches = async { getLibraryMatches(searchQuery, scope) }
            val recent = async {
                if (SearchSuggestionType.QUERIES_RECENT in types) getRecentQueries(searchQuery) else emptyList()
            }
            matches.await() + recent.await()
        }
        emit(SearchSuggestionState(query = searchQuery, items = items, libraryScope = scope))
    }

    private suspend fun getLibraryMatches(
        searchQuery: String,
        scope: LibrarySearchScope,
    ): List<SearchSuggestionItem> = runCatchingCancellable {
        when (scope) {
            LibrarySearchScope.HISTORY,
            LibrarySearchScope.FAVOURITES,
            LibrarySearchScope.SUGGESTIONS -> if (searchQuery.isEmpty()) emptyList() else {
                libraryContents(scope).await().matchLibrarySuggestions(searchQuery, MAX_LIBRARY_ITEMS)
            }
            // The local page filters through the repository's own query, so preview the same way.
            LibrarySearchScope.LOCAL -> if (searchQuery.isEmpty()) emptyList() else {
                localMangaRepository.getAll(filter = ContentListFilter(query = searchQuery)).take(MAX_LIBRARY_ITEMS)
            }
            LibrarySearchScope.UPDATES,
            LibrarySearchScope.FEED,
            LibrarySearchScope.BOOKMARKS -> emptyList()
        }.map { SearchSuggestionItem.LibraryMatch(scope, it) }
    }.getOrElse { e ->
        e.printStackTraceDebug()
        listOf(SearchSuggestionItem.Text(0, e))
    }

    private suspend fun getAuthors(searchQuery: String): List<SearchSuggestionItem> = runCatchingCancellable {
        repository.getAuthorsSuggestion(searchQuery, MAX_AUTHORS_ITEMS)
            .map { SearchSuggestionItem.Author(it) }
    }.getOrElse { e ->
        e.printStackTraceDebug()
        listOf(SearchSuggestionItem.Text(0, e))
    }

    private suspend fun getQueryHints(searchQuery: String): List<SearchSuggestionItem> = runCatchingCancellable {
        repository.getQueryHintSuggestion(searchQuery, MAX_HINTS_ITEMS)
            .map { SearchSuggestionItem.Hint(it) }
    }.getOrElse { e ->
        e.printStackTraceDebug()
        listOf(SearchSuggestionItem.Text(0, e))
    }

    private suspend fun getRecentQueries(searchQuery: String): List<SearchSuggestionItem> = runCatchingCancellable {
        repository.getQuerySuggestion(searchQuery, MAX_QUERY_ITEMS)
            .map { SearchSuggestionItem.RecentQuery(it) }
    }.getOrElse { e ->
        e.printStackTraceDebug()
        listOf(SearchSuggestionItem.Text(0, e))
    }

    private suspend fun getTags(searchQuery: String): List<SearchSuggestionItem> = runCatchingCancellable {
        val tags = repository.getTagsSuggestion(searchQuery, MAX_TAGS_ITEMS, null)
        if (tags.isEmpty()) {
            emptyList()
        } else {
            listOf(SearchSuggestionItem.Tags(mapTags(tags)))
        }
    }.getOrElse { e ->
        e.printStackTraceDebug()
        listOf(SearchSuggestionItem.Text(0, e))
    }

    private suspend fun getContent(searchQuery: String): List<SearchSuggestionItem> = runCatchingCancellable {
        val entities = repository.getContentSuggestion(searchQuery, MAX_MANGA_ITEMS, null)
            .filter { item -> contentKinds.value.any { kind -> kind.matches(item.representative) } }
        if (entities.isEmpty()) {
            emptyList()
        } else {
            listOf(SearchSuggestionItem.LocalEntityList(entities))
        }
    }.getOrElse { e ->
        e.printStackTraceDebug()
        listOf(SearchSuggestionItem.Text(0, e))
    }

    private fun getSources(searchQuery: String, enabledSources: EnabledSourcesSnapshot): List<SearchSuggestionItem> =
        runCatchingCancellable {
            repository.getSourcesSuggestion(searchQuery, MAX_SOURCES_ITEMS, enabledSources.sources)
                .map { SearchSuggestionItem.Source(it, it.name in enabledSources.names) }
        }.getOrElse { e ->
            e.printStackTraceDebug()
            listOf(SearchSuggestionItem.Text(0, e))
        }

    private suspend fun getRecentSources(
        searchQuery: String,
        enabledSources: EnabledSourcesSnapshot,
        sourceScope: SourceScope,
    ): List<SearchSuggestionItem> = if (searchQuery.isEmpty()) {
        runCatchingCancellable {
            // Disabled sources stay listed so the quick toggle can turn them back on.
            repository.getSourcesSuggestion(MAX_SOURCES_TIPS_ITEMS)
                .filter { sourceScope.contains(it) }
                .map { SearchSuggestionItem.SourceTip(it, it.name in enabledSources.names) }
        }.getOrElse { e ->
            e.printStackTraceDebug()
            listOf(SearchSuggestionItem.Text(0, e))
        }
    } else {
        emptyList()
    }

    private fun mapTags(tags: List<ContentTag>): List<ChipModel> = tags.map { tag ->
        ChipModel(
            title = tag.title,
            data = tag,
        )
    }
}

private data class EnabledSourcesSnapshot(
    val sources: List<ContentSource>,
    val names: Set<String>,
    val presetNames: Set<String>? = null,
)

private class SourceScope(
    private val presetNames: Set<String>?,
    private val sourceTypes: Set<SourceType>,
    private val contentKinds: Set<SearchContentKind>,
    private val identifier: SourceTypeIdentifier,
) {

    fun contains(source: ContentSource): Boolean =
        (presetNames == null || source.name in presetNames) &&
            identifier.getSourceType(source.name) in sourceTypes &&
            contentKinds.any { kind -> kind.matches(source) }
}

private data class SuggestionParams(
    val searchQuery: String,
    val enabledSources: EnabledSourcesSnapshot,
    val types: Set<SearchSuggestionType>,
    val preferredTrackingSite: ScrobblerService,
    val activeSourceTypes: Set<SourceType>,
    val activeContentKinds: Set<SearchContentKind>,
)

private data class SuggestionParamsPartial(
    val searchQuery: String,
    val enabledSources: EnabledSourcesSnapshot,
    val types: Set<SearchSuggestionType>,
    val preferredTrackingSite: ScrobblerService,
)

private fun EnabledSourcesSnapshot.filterByTypes(
    sourceTypes: Set<SourceType>,
    contentKinds: Set<SearchContentKind>,
    identifier: SourceTypeIdentifier,
): EnabledSourcesSnapshot {
    val filtered = sources.filter { source ->
        identifier.getSourceType(source.name) in sourceTypes &&
            contentKinds.any { kind -> kind.matches(source) }
    }
    return EnabledSourcesSnapshot(
        sources = filtered,
        names = filtered.mapToSet { it.name },
        presetNames = presetNames,
    )
}

private fun EnabledSourcesSnapshot.filterByPreset(
    preset: SourcePreset?,
): EnabledSourcesSnapshot {
    if (preset == null) {
        return this
    }
    val filtered = sources.filter { source -> source.name in preset.sources }
    return EnabledSourcesSnapshot(
        sources = filtered,
        names = filtered.mapToSet { it.name },
        presetNames = preset.sources,
    )
}

private fun List<ContentSourceInfo>.toEnabledSourcesSnapshot(): EnabledSourcesSnapshot {
    val sources = this.map { it.mangaSource }
    return EnabledSourcesSnapshot(
        sources = sources,
        names = sources.mapToSet { it.name },
    )
}
