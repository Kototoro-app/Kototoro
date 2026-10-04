package org.skepsun.kototoro.favourites.ui.container

import android.content.Context
import kotlinx.coroutines.flow.first
import androidx.room.withTransaction
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.plus
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.favourites.domain.library.FavouritesCardMapper
import org.skepsun.kototoro.favourites.ui.list.FavouritesListHost
import org.skepsun.kototoro.list.ui.ContentActionHostRequest
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.tracker.work.UpdateCheckRequest
import org.skepsun.kototoro.tracker.work.messageRes
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.ui.util.ReversibleHandle
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.ParserContentRepository
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.parsers.exception.AuthRequiredException
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.core.model.unwrap
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.parser.external.ExternalContentSource
import org.skepsun.kototoro.parsers.ContentFavoriteFolder
import org.skepsun.kototoro.parsers.CategorizedFavoritesProvider
import org.skepsun.kototoro.core.os.NetworkState
import org.skepsun.kototoro.favourites.domain.GlobalFavoritesState
import org.skepsun.kototoro.favourites.domain.FavoritesListQuickFilter
import org.skepsun.kototoro.list.domain.QuickFilterListener
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.explore.ui.model.SourceTag
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.scopedToSpace
import org.skepsun.kototoro.parsers.util.levenshteinDistance

@HiltViewModel
class FavouritesContainerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: AppSettings,
    private val favouritesRepository: FavouritesRepository,
    private val sourcesRepository: ContentSourcesRepository,
    private val mangaRepositoryFactory: ContentRepository.Factory,
    private val mangaDataRepository: ContentDataRepository,
    @LocalStorageChanges private val localStorageChanges: SharedFlow<LocalContent?>,
    networkState: NetworkState,
    internal val globalFavoritesState: GlobalFavoritesState,
    private val sourceGroupManager: SourceGroupManager,
    spaceBrowseScope: SpaceBrowseScope,
    private val db: org.skepsun.kototoro.core.db.MangaDatabase,
    private val favouriteLibrarySnapshotStore: org.skepsun.kototoro.favourites.domain.library.FavouriteLibrarySnapshotStore,
    private val spaceContentPolicy: org.skepsun.kototoro.space.domain.SpaceContentPolicy,
    private val sourcePresetsRepository: org.skepsun.kototoro.explore.data.SourcePresetsRepository,

    private val cardMapper: FavouritesCardMapper,
    private val contentResolver: org.skepsun.kototoro.favourites.domain.library.FavouriteContentResolver,
    private val quickFilterFactory: FavoritesListQuickFilter.Factory,
    private val markAsReadUseCase: org.skepsun.kototoro.history.domain.MarkAsReadUseCase,
    private val trackingRepository: org.skepsun.kototoro.tracker.domain.TrackingRepository,
    private val trackWorkerScheduler: org.skepsun.kototoro.tracker.work.TrackWorker.Scheduler,
) : BaseViewModel(), SpaceBindableViewModel {
    private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)
    init {
        launchJob(Dispatchers.IO) {
            sourcesRepository.getAllAvailableSourcesUnfiltered()
        }
        // Offline means "only what is on the device": the Downloaded quick filter follows
        // the connectivity state once per favourites page (it used to be applied by every
        // per-category child view model, i.e. once per tab).
        globalFavoritesState.setFilterOption(ListFilterOption.Downloaded, !networkState.value)
    }

    data class FavoritesHostUiState(
        val isLoading: Boolean = true,
        val categories: List<FavouriteTabModel> = emptyList(),
        val isEmpty: Boolean = false,
    )

    val listMode = settings.observeAsFlow(AppSettings.KEY_LIST_MODE) { this.listMode }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.listMode)

    val isQuickFilterEnabled = settings.observeAsFlow(AppSettings.KEY_QUICK_FILTER) { isQuickFilterEnabled }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.isQuickFilterEnabled)

    fun setQuickFilterEnabled(enabled: Boolean) {
        settings.isQuickFilterEnabled = enabled
    }

    /** Whether a category page shows the "continue reading" shelf above its grid. */
    val isShelfEnabled = settings.observeAsFlow(AppSettings.KEY_FAVOURITES_SHELF) { isFavouritesShelfEnabled }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.isFavouritesShelfEnabled)

    fun setShelfEnabled(enabled: Boolean) {
        settings.isFavouritesShelfEnabled = enabled
    }

    val allFavoritesSortOrder = settings.observeAsFlow(AppSettings.KEY_FAVORITES_ORDER) {
        allFavoritesSortOrder
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.allFavoritesSortOrder)

    val currentGroupTab = globalFavoritesState.selectedGroupTab.scopedToSpace(
        spaceGroupTab = spaceBinding.groupTab,
        coroutineScope = viewModelScope + Dispatchers.Default,
    )
    override fun bindSpace(spaceId: org.skepsun.kototoro.space.domain.SpaceId?) = spaceBinding.bindSpace(spaceId)
    val selectedSourceTags = globalFavoritesState.selectedSourceTags
    val availableSourceTags = flowOf(SourceTag.quickFilterEntries.toSet())
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, SourceTag.quickFilterEntries.toSet())

    fun setSelectedGroupTab(tab: BrowseGroupTab) {
        if (currentGroupTab.value == tab) {
            globalFavoritesState.clearSelectedGroupTab()
        } else {
            globalFavoritesState.setSelectedGroupTab(tab)
        }
    }

    // ---------------------------------------------------------------------
    // Library snapshot state (favourites-komikku-alignment Phase 4): the container
    // is the single screen-level state holder. Quick filters / category / sort / list
    // mode / space / preset changes re-derive in memory — they never re-query the
    // favourites tables.
    // ---------------------------------------------------------------------

    private val activeSpaceId = spaceBinding.spaceId

    private val activeSourcePreset = settings.observeAsFlow(AppSettings.KEY_ACTIVE_SOURCE_PRESET_ID) { activeSourcePresetId }
        .flatMapLatest { id ->
            if (id == -1L) {
                flowOf(null)
            } else {
                sourcePresetsRepository.observe(id)
            }
        }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    private fun combineLibraryDerivationParams(): Flow<FavouriteLibraryParams> = combine(
        currentGroupTab,
        selectedSourceTags,
        activeSourcePreset,
        activeSpaceId,
        globalFavoritesState.appliedFilter,
        settings.observeAsFlow(AppSettings.KEY_FAVOURITES_EXCLUDE_NSFW) { isFavouritesExcludeNsfw },
        settings.observeAsFlow(AppSettings.KEY_GLOBAL_TAG_BLACKLIST) { globalTagBlacklist },
        allFavoritesSortOrder,
        favouritesRepository.observeCategories(),
    ) { values: Array<*> ->
        @Suppress("UNCHECKED_CAST")
        val categories = (values[8] as List<org.skepsun.kototoro.core.model.FavouriteCategory>)
        FavouriteLibraryParams(
            groupTab = values[0] as BrowseGroupTab,
            sourceTags = values[1] as Set<SourceTag>,
            preset = values[2] as? org.skepsun.kototoro.explore.data.SourcePreset,
            spaceId = values[3] as? org.skepsun.kototoro.space.domain.SpaceId,
            filters = values[4] as Set<ListFilterOption>,
            excludeNsfw = values[5] as Boolean,
            blacklist = values[6] as Collection<String>,
            defaultOrder = values[7] as ListSortOrder,
            ordersByCategory = categories.associate { it.id to it.order },
        )
    }.distinctUntilChanged()

    private val searchMatchingIds = MutableStateFlow<Set<Long>?>(null)

    fun setSearchMatchingIds(ids: Set<Long>?) {
        searchMatchingIds.value = ids
    }

    val libraryState: StateFlow<FavouriteLibraryUiState> = combine(
        favouriteLibrarySnapshotStore.observe(),
        combineLibraryDerivationParams(),
        searchMatchingIds,
    ) { snapshot, params, matchingIds ->
        val state = buildFavouriteLibraryUiState(snapshot, params, spaceContentPolicy)
        if (matchingIds == null) state else {
            val visible = state.visibleIdsByCategory.mapValues { (_, ids) -> ids.filter { it in matchingIds } }
            state.copy(
                visibleIdsByCategory = visible,
                categoryCounts = visible.mapValues { it.value.size },
                totalCount = visible[NO_ID].orEmpty().size,
            )
        }
    }.withErrorHandling()
        .stateIn(
            viewModelScope + Dispatchers.Default,
            SharingStarted.WhileSubscribed(5_000),
            FavouriteLibraryUiState(),
        )


    // ---------------------------------------------------------------------
    // Per-category list hosts (favourites-komikku-alignment Phase 6): the favourites
    // page has a single state holder — this view model. What a category page needs is a
    // thin [FavouritesListHost] adapter that slices [libraryState] into cards and
    // forwards selection / quick-filter callbacks here.
    // ---------------------------------------------------------------------

    /** Scope every favourites derivation and list action runs on. */
    internal val listScope = viewModelScope + Dispatchers.Default

    internal val gridScale = settings.observeAsStateFlow(
        scope = listScope,
        key = AppSettings.KEY_GRID_SIZE,
        valueProducer = { gridSize / 100f },
    )

    /** Toasts and host requests raised by list actions, collected by the shared route. */
    internal val onContentMessage = MutableEventFlow<String>()
    internal val onContentActionHostRequest = MutableEventFlow<ContentActionHostRequest>()

    private val listHosts = HashMap<Long, FavouritesListHost>()

    /**
     * The list host of one category page, cached: the same instance — and the cards it
     * mapped — is reused across recompositions, tab switches and configuration changes,
     * and goes away with the screen. There is no per-category child ViewModel anymore.
     */
    @Synchronized
    internal fun listHost(categoryId: Long): FavouritesListHost = listHosts.getOrPut(categoryId) {
        FavouritesListHost(
            categoryId = categoryId,
            container = this,
            cardMapper = cardMapper,
            quickFilter = quickFilterFactory.create(categoryId, libraryState),
        )
    }

    /**
     * Re-map trigger for the card lists: the display mode plus everything that changes
     * what a card shows (badges, progress indicators, tracker state, overrides,
     * favourites, local storage). Library data itself arrives through the snapshot.
     */
    internal fun observeListModeWithTriggers(): Flow<ListMode> = combine(
        listMode,
        merge(
            mangaDataRepository.observeOverridesTrigger(emitInitialState = true).map { Unit },
            mangaDataRepository.observeFavoritesTrigger(emitInitialState = true).map { Unit },
            localStorageChanges.onStart { emit(null) }.map { Unit },
        ),
        settings.observeChanges().filter { key ->
            key == AppSettings.KEY_PROGRESS_INDICATORS
                || key == AppSettings.KEY_TRACKER_ENABLED
                || key == AppSettings.KEY_QUICK_FILTER
                || key == AppSettings.KEY_MANGA_LIST_BADGES
        }.onStart { emit("") },
    ) { mode, _, _ ->
        mode
    }

    // ------------------------------------------------------------ list actions

    /** Remove the selection (entity row ids) from favourites or from one category. */
    internal fun removeFromFavourites(categoryId: Long, ids: Set<Long>) {
        if (ids.isEmpty()) {
            return
        }
        launchJob(Dispatchers.Default) {
            val mangaIds = expandToMangaIds(ids)
            val handle = if (categoryId == NO_ID) {
                favouritesRepository.removeFromFavourites(mangaIds)
            } else {
                favouritesRepository.removeFromCategory(categoryId, mangaIds)
            }
            onActionDone.call(ReversibleAction(R.string.removed_from_favourites, handle))
        }
    }

    internal suspend fun isPinned(ids: Set<Long>): Boolean =
        favouritesRepository.isPinned(expandToMangaIds(ids))

    internal fun setPinned(ids: Set<Long>, isPinned: Boolean) {
        launchJob(Dispatchers.Default) {
            favouritesRepository.setPinned(expandToMangaIds(ids), isPinned)
        }
    }

    internal fun togglePinned(ids: Set<Long>) {
        launchJob(Dispatchers.Default) {
            val currentlyPinned = favouritesRepository.isPinned(expandToMangaIds(ids))
            favouritesRepository.setPinned(expandToMangaIds(ids), !currentlyPinned)
        }
    }

    /** Mark the selected entities as read through their stored manga. */
    internal fun markAsRead(entityIds: Collection<Long>) {
        if (entityIds.isEmpty()) return
        launchLoadingJob(Dispatchers.Default) {
            val contents = resolveSelectedContents(entityIds)
            if (contents.isNotEmpty()) {
                markAsReadUseCase(contents)
            }
        }
    }

    /** Stored contents of the selection, for the actions that cannot use the card stub. */
    internal suspend fun resolveSelectedContents(ids: Collection<Long>): List<Content> =
        contentResolver.resolveByDisplayMangaIds(
            ids.mapNotNullTo(ArrayList(ids.size)) { libraryState.value.rowsByEntityId[it]?.displayMangaId },
        )

    internal fun resolveSelectionToMangaIds(ids: Set<Long>): Set<Long> = expandToMangaIds(ids)

    /**
     * Pull-to-refresh entry point: requests a new-chapter update check through the shared
     * gate ([TrackWorker.Scheduler.requestCheckNow], the same one used by the Updates and
     * Feed pages), finishing with a summary toast. If the gate refuses the request (check
     * already running / checked too recently / tracker disabled) the corresponding prompt
     * toast is shown instead.
     */
    internal fun checkForUpdates() {
        // The worker owns progress reporting through its notification. This background check
        // must not drive the list route's loading indicator while it waits for completion.
        launchJob(Dispatchers.Default) {
            when (val request = trackWorkerScheduler.requestCheckNow()) {
                UpdateCheckRequest.Started -> {
                    onContentMessage.call(appContext.getString(R.string.checking_for_updates))
                    val finished = trackWorkerScheduler.awaitOneShot(UPDATE_CHECK_AWAIT_MS)
                    val message = if (finished) {
                        val summary = trackingRepository.getFavouriteUpdatesSummary()
                        if (summary.worksWithUpdates > 0) {
                            appContext.getString(
                                R.string.favourites_updates_found,
                                summary.worksWithUpdates,
                                summary.newChapters,
                            )
                        } else {
                            appContext.getString(R.string.favourites_no_updates)
                        }
                    } else {
                        appContext.getString(R.string.updates_check_still_running)
                    }
                    onContentMessage.call(message)
                }

                UpdateCheckRequest.InFlight,
                UpdateCheckRequest.TooSoon,
                UpdateCheckRequest.TrackerDisabled,
                -> {
                    onContentMessage.call(appContext.getString(request.messageRes()))
                }
            }
        }
    }

    /**
     * Entity ids of a selection expanded to the manga ids the favourite DAOs address
     * rows by. A row without a stored manga keeps the entity id (it has no manga to
     * address, and the legacy chain dropped such rows instead of acting on them).
     */
    private fun expandToMangaIds(ids: Collection<Long>): Set<Long> {
        val rows = libraryState.value.rowsByEntityId
        return ids.flatMapTo(LinkedHashSet()) { entityId ->
            val row = rows[entityId]
            when {
                row == null -> setOf(entityId)
                row.localMangaIds.isNotEmpty() -> row.localMangaIds
                else -> setOf(row.displayMangaId ?: entityId)
            }
        }
    }

    /**
     * Debug-only shadow comparison (favourites-komikku-alignment Phase 4): derives the
     * visible entity ids of the legacy aggregate chain next to the new snapshot path
     * and logs the first divergence. Removed in Phase 8 once the new path is verified.
     */
    fun startLibraryShadowComparison() {
        // Legacy entity-based shadow comparison removed with entity system
    }

    fun toggleSourceTag(tag: SourceTag) {
        globalFavoritesState.toggleSourceTag(tag)
    }

    fun resetFilters() {
        globalFavoritesState.resetFilters(clearGroupTab = spaceBinding.spaceId.value == null)
    }

    /** Whether the current space scopes the group tab, i.e. whether clearing keeps it. */
    internal fun isSpaceBound(): Boolean = activeSpaceId.value != null

    internal fun Flow<Set<ListFilterOption>>.combineWithSettings(): Flow<Set<ListFilterOption>> {
        val nsfwCombined = combine(
            settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
        ) { filters, skipNsfw ->
            if (skipNsfw) {
                filters + ListFilterOption.SFW
            } else {
                filters
            }
        }
        return combine(
            nsfwCombined,
            settings.observeAsFlow(AppSettings.KEY_QUICK_FILTER) { isQuickFilterEnabled },
        ) { filters, _ -> filters }
    }

    data class ImportSource(
        val source: ContentSource,
        val title: String,
        val folders: List<ContentFavoriteFolder>? = null,
    )

    val onActionDone = MutableEventFlow<ReversibleAction>()
    val importMessages = MutableEventFlow<String>()
    val syncMessages = MutableEventFlow<String>()
    private fun logImport(msg: String) = Unit
    private fun logSync(msg: String) = Unit

    private val categoriesStateFlow = favouritesRepository.observeCategoriesForLibrary()
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    val uiState = combine(
        categoriesStateFlow,
        libraryState,
        currentGroupTab,
        combine(selectedSourceTags, globalFavoritesState.appliedFilter, ::Pair),
        observeAllFavouritesVisibility(),
    ) { list, libState, groupTab, (sourceTags, quickFilters), showAll ->
        if (list == null || !libState.isInitialized) {
            return@combine FavoritesHostUiState()
        }

        buildFavoritesHostUiState(
            categories = list.map { FavouriteTabModel(it.id, it.title, it.order) },
            categoryCounts = libState.categoryCounts,
            groupTab = groupTab,
            hasSourceTags = sourceTags.isNotEmpty(),
            hasQuickFilters = quickFilters.isNotEmpty(),
            showAll = showAll,
        )
    }.runningFold(FavoritesHostUiState()) { previous, next ->
        if (next.isLoading && previous.categories.isNotEmpty()) {
            next.copy(categories = previous.categories)
        } else {
            next
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, FavoritesHostUiState())

    val isCategoriesLoaded = uiState
        .map { !it.isLoading }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    val categories = uiState
        .map { it.categories }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    val isEmpty = uiState
        .map { it.isEmpty }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    private companion object {

        /**
         * How long the pull-to-refresh action waits for the one-shot update check before
         * giving up on a result toast. The worker keeps running in the background (and
         * reports via its own notification) if the check is simply slow.
         */
        const val UPDATE_CHECK_AWAIT_MS = 60_000L
    }

    fun hide(categoryId: Long) {
        launchJob(Dispatchers.Default) {
            if (categoryId == NO_ID) {
                settings.isAllFavouritesVisible = false
            } else {
                favouritesRepository.updateCategory(categoryId, isVisibleInLibrary = false)
                val reverse = ReversibleHandle {
                    favouritesRepository.updateCategory(categoryId, isVisibleInLibrary = true)
                }
                onActionDone.call(ReversibleAction(R.string.category_hidden_done, reverse))
            }
        }
    }

    fun deleteCategory(categoryId: Long) {
        launchJob(Dispatchers.Default) {
            favouritesRepository.removeCategories(setOf(categoryId))
        }
    }

    fun setSortOrder(categoryId: Long, order: ListSortOrder) {
        launchJob(Dispatchers.Default) {
            if (categoryId == NO_ID) {
                if (order != ListSortOrder.MANUAL) settings.allFavoritesSortOrder = order
            } else if (order == ListSortOrder.MANUAL) {
                favouritesRepository.reorderCategory(categoryId, currentCategoryOrder(categoryId))
            } else {
                favouritesRepository.setCategoryOrder(categoryId, order)
            }
        }
    }

    private suspend fun currentCategoryOrder(categoryId: Long): List<Long> {
        val snapshot = favouriteLibrarySnapshotStore.observe().first()
        val order = favouritesRepository.getCategory(categoryId).order
        return org.skepsun.kototoro.favourites.domain.library.deriveFavouriteLibraryState(
            snapshot,
            org.skepsun.kototoro.favourites.domain.library.FavouriteLibraryDerivationInput(
                ordersByCategory = mapOf(categoryId to order),
            ),
        ).visibleIdsByCategory[categoryId].orEmpty()
    }

    fun categoryOrderItems(categoryId: Long): List<org.skepsun.kototoro.core.ui.compose.ReorderItem> {
        val state = libraryState.value
        val pinned = state.pinnedIdsByCategory[categoryId].orEmpty()
        return state.visibleIdsByCategory[categoryId].orEmpty().mapNotNull { id ->
            state.rowsByEntityId[id]?.let { row ->
                org.skepsun.kototoro.core.ui.compose.ReorderItem(
                    key = id.toString(), title = row.overrideTitle ?: row.title,
                    group = if (id in pinned) appContext.getString(R.string.source_pinned) else "",
                )
            }
        }
    }

    suspend fun saveCategoryOrder(categoryId: Long, ids: List<String>) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val order = org.skepsun.kototoro.core.util.mergeManualOrder(currentCategoryOrder(categoryId), ids.map(String::toLong))
        favouritesRepository.reorderCategory(categoryId, order)
    }

    private fun observeAllFavouritesVisibility() = settings.observeAsFlow(
        key = AppSettings.KEY_ALL_FAVOURITES_VISIBLE,
        valueProducer = { isAllFavouritesVisible },
    )

    suspend fun loadImportCandidates(): List<ImportSource> {
        val enabledSources = sourcesRepository.getEnabledSources()
        val candidates = ArrayList<ImportSource>()
        logImport("loadImportCandidates: enabled=${enabledSources.size}, hideNsfw=${settings.isNsfwContentDisabled}")
        for (item in enabledSources) {
            val unwrapped = item.unwrap()
            if (unwrapped.isLocal || unwrapped is ExternalContentSource) {
                logImport("skip ${item.name}: not a parser source (${unwrapped::class.simpleName})")
                continue
            }
            val parserSource = unwrapped
            val repository = mangaRepositoryFactory.create(parserSource) as? ParserContentRepository
            if (repository == null) {
                logImport("skip ${parserSource.name}: repository not parser")
                continue
            }
            val authProvider = repository.getAuthProvider()
            val favoritesProvider = repository.favoritesProvider()
            if (favoritesProvider == null) {
                logImport(
                    "skip ${parserSource.name}: no FavoritesProvider, parser=${repository.javaClass.simpleName}," +
                        " interfaces=${repository.javaClass.interfaces.joinToString { it.simpleName ?: it.name }}"
                )
                continue
            }
            val isAuthed = authProvider?.let { runCatching { it.isAuthorized() }.getOrDefault(false) } ?: true
            logImport("candidate ${parserSource.name}: authed=$isAuthed, nsfw=${parserSource.isNsfw()}, hasFavoritesProvider=true")
            if (!isAuthed) {
                logImport("skip ${parserSource.name}: unauthorized")
                continue
            }
            val categorizedProvider = repository.categorizedFavoritesProvider()
            val folders = organizedFolders(categorizedProvider)
            logImport("candidate ${parserSource.name}: folders=${folders?.size ?: "null"}")
            candidates.add(ImportSource(parserSource, parserSource.getTitle(appContext), folders))
        }
        logImport("loadImportCandidates: final=${candidates.size}, names=${candidates.joinToString { it.source.name }}")
        return candidates.sortedBy { it.title.lowercase() }
    }

    suspend fun loadFavoriteFolders(source: ContentSource): List<ContentFavoriteFolder> {
        val repository = mangaRepositoryFactory.create(source) as? ParserContentRepository ?: return emptyList()
        val catProvider = repository.categorizedFavoritesProvider() ?: return emptyList()
        return runCatching { catProvider.fetchFavoriteFolders() }.getOrDefault(emptyList())
    }

    fun importFavorites(sources: List<ImportSource>) {
        if (sources.isEmpty()) {
            importMessages.call(appContext.getString(R.string.import_favourites_none_selected))
            return
        }
        launchLoadingJob(Dispatchers.IO) {
            for (item in sources) {
                importMessages.call(appContext.getString(R.string.import_favourites_progress, item.title))
                logImport("import start source=${item.source.name}")
                val repository = mangaRepositoryFactory.create(item.source) as? ParserContentRepository ?: continue
                val catProvider = repository.categorizedFavoritesProvider()
                val favProvider = repository.favoritesProvider() ?: continue
                try {
                    if (catProvider != null && !item.folders.isNullOrEmpty()) {
                        for (folder in item.folders) {
                            val categoryTitle = if (item.folders.size == 1 && folder.id == "0") item.title else "${item.title}/${folder.title}"
                            val category = ensureCategory(categoryTitle)
                            importMessages.call(appContext.getString(R.string.import_favourites_progress, categoryTitle))
                            val favs = catProvider.fetchFavorites(folder.id)
                            logImport("import fetched source=${item.source.name} folder=${folder.title} count=${favs.size}")
                            if (favs.isNotEmpty()) {
                                favouritesRepository.addToCategory(category.id, favs)
                            }
                        }
                    } else {
                        val category = ensureCategory(item.title)
                        val favs = favProvider.fetchFavorites()
                        logImport("import fetched source=${item.source.name} count=${favs.size}")
                        if (favs.isNotEmpty()) {
                            favouritesRepository.addToCategory(category.id, favs)
                        }
                    }
                } catch (e: Exception) {
                    logImport("import failed source=${item.source.name} with exception: ${e.message}")
                    if (e is AuthRequiredException) {
                        importMessages.call(appContext.getString(R.string.import_favourites_auth_expired))
                    }
                }
            }
            importMessages.call(appContext.getString(R.string.import_favourites_done))
            logImport("import done")
        }
    }

    suspend fun loadSyncCandidates(): List<ImportSource> {
        val enabledSources = sourcesRepository.getEnabledSources()
        val candidates = ArrayList<ImportSource>()
        logSync("loadSyncCandidates: enabled=${enabledSources.size}, hideNsfw=${settings.isNsfwContentDisabled}")
        for (item in enabledSources) {
            val unwrapped = item.unwrap()
            if (unwrapped.isLocal || unwrapped is ExternalContentSource) {
                logSync("skip ${item.name}: not a parser source (${unwrapped::class.simpleName})")
                continue
            }
            val parserSource = unwrapped
            val repository = mangaRepositoryFactory.create(parserSource) as? ParserContentRepository
            if (repository == null) {
                logSync("skip ${parserSource.name}: repository not parser")
                continue
            }
            val authProvider = repository.getAuthProvider()
            val syncProvider = repository.favoritesSyncProvider()
            if (syncProvider == null) {
                logSync(
                    "skip ${parserSource.name}: no FavoritesSyncProvider, parser=${repository.javaClass.simpleName}," +
                        " interfaces=${repository.javaClass.interfaces.joinToString { it.simpleName ?: it.name }}"
                )
                continue
            }
            val isAuthed = authProvider?.let { runCatching { it.isAuthorized() }.getOrDefault(false) } ?: true
            logSync("candidate ${parserSource.name}: authed=$isAuthed, hasSyncProvider=true")
            if (!isAuthed) {
                logSync("skip ${parserSource.name}: unauthorized")
                continue
            }
            candidates.add(ImportSource(parserSource, parserSource.getTitle(appContext)))
        }
        logSync("loadSyncCandidates: final=${candidates.size}, names=${candidates.joinToString { it.source.name }}")
        return candidates.sortedBy { it.title.lowercase() }
    }

    fun syncFavorites(sources: List<ImportSource>) {
        if (sources.isEmpty()) {
            syncMessages.call(appContext.getString(R.string.sync_favourites_none_selected))
            return
        }
        launchLoadingJob(Dispatchers.IO) {
            for (item in sources) {
                syncMessages.call(appContext.getString(R.string.sync_favourites_progress, item.title))
                logSync("sync start source=${item.source.name}")
                val repository = mangaRepositoryFactory.create(item.source) as? ParserContentRepository ?: continue
                val syncProvider = repository.favoritesSyncProvider() ?: continue
                val favProvider = repository.favoritesProvider()
                val category = favouritesRepository.findCategoryByTitle(item.title)
                if (category == null) {
                    logSync("sync skip source=${item.source.name} no local category")
                    syncMessages.call(appContext.getString(R.string.sync_favourites_skip_no_category, item.title))
                    continue
                }
                val local = favouritesRepository.getContent(category.id)
                val remote = runCatching { favProvider?.fetchFavorites() ?: emptyList() }
                    .onFailure { logSync("sync ${item.source.name} fetch remote failed") }
                    .getOrDefault(emptyList())
                val localKeys = local.associateBy { it.url }
                val remoteKeys = remote.associateBy { it.url }
                // 先把远程新增的（本地没有的）合并进本地分�?
                val remoteExtras = remoteKeys.keys.minus(localKeys.keys).mapNotNull { remoteKeys[it] }
                if (remoteExtras.isNotEmpty()) {
                    logSync("sync merge remote extras source=${item.source.name} extras=${remoteExtras.size}")
                    favouritesRepository.addToCategory(category.id, remoteExtras)
                }
                val localMerged = local + remoteExtras
                val localMergedKeys = localMerged.associateBy { it.url }
                val toAdd = localMergedKeys.keys.minus(remoteKeys.keys).mapNotNull { localMergedKeys[it] }
                val toRemove = remoteKeys.keys.minus(localMergedKeys.keys).mapNotNull { remoteKeys[it] }
                logSync("sync source=${item.source.name} local=${localMerged.size} remote=${remote.size} add=${toAdd.size} remove=${toRemove.size}")
                toAdd.forEach { runCatching { syncProvider.addFavorite(it) }.onFailure { logSync("sync add fail ${item.source.name}") } }
                toRemove.forEach { runCatching { syncProvider.removeFavorite(it) }.onFailure { logSync("sync remove fail ${item.source.name}") } }
                syncMessages.call(appContext.getString(R.string.sync_favourites_source_done, item.title))
            }
            syncMessages.call(appContext.getString(R.string.sync_favourites_done))
            logSync("sync done")
        }
    }

    private suspend fun ensureCategory(title: String): FavouriteCategory {
        return favouritesRepository.findCategoryByTitle(title)
            ?: favouritesRepository.createCategory(
                title = title,
                sortOrder = org.skepsun.kototoro.list.domain.ListSortOrder.NEWEST,
                isTrackerEnabled = false,
                isVisibleOnShelf = true,
            )
    }

    private suspend fun organizedFolders(provider: CategorizedFavoritesProvider?): List<ContentFavoriteFolder>? {
        if (provider == null) return null
        return runCatching { provider.fetchFavoriteFolders() }.getOrNull()
    }
}
