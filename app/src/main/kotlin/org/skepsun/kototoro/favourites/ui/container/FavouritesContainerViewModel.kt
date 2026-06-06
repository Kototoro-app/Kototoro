package org.skepsun.kototoro.favourites.ui.container

import android.content.Context
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
import org.skepsun.kototoro.core.ui.BaseViewModel
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
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.explore.ui.model.SourceTag
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import org.skepsun.kototoro.core.jsonsource.SourceGroupManager

@HiltViewModel
class FavouritesContainerViewModel @Inject constructor(
	@ApplicationContext private val appContext: Context,
	private val settings: AppSettings,
	private val favouritesRepository: FavouritesRepository,
	private val sourcesRepository: ContentSourcesRepository,
	private val mangaRepositoryFactory: ContentRepository.Factory,
	mangaDataRepository: ContentDataRepository,
	networkState: NetworkState,
	internal val globalFavoritesState: GlobalFavoritesState,
	private val sourceGroupManager: SourceGroupManager,
) : BaseViewModel() {
	data class FavoritesHostUiState(
		val isLoading: Boolean = true,
		val categories: List<FavouriteTabModel> = emptyList(),
		val isEmpty: Boolean = false,
	)

	private sealed class ActiveCategoryCountsState {
		object Loading : ActiveCategoryCountsState()
		object NotFiltered : ActiveCategoryCountsState()
		data class Filtered(val categoryCounts: Map<Long, Int>) : ActiveCategoryCountsState()
	}

	val listMode = settings.observeAsFlow(AppSettings.KEY_LIST_MODE) { this.listMode }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.listMode)

	val currentGroupTab = globalFavoritesState.selectedGroupTab
	val selectedSourceTags = globalFavoritesState.selectedSourceTags
	val availableSourceTags = flowOf(SourceTag.quickFilterEntries.toSet())
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, SourceTag.quickFilterEntries.toSet())

	fun setSelectedGroupTab(tab: BrowseGroupTab) {
		if (globalFavoritesState.selectedGroupTab.value == tab) {
			globalFavoritesState.clearSelectedGroupTab()
		} else {
			globalFavoritesState.setSelectedGroupTab(tab)
		}
	}

	fun toggleSourceTag(tag: SourceTag) {
		globalFavoritesState.toggleSourceTag(tag)
	}

	private fun Flow<Set<ListFilterOption>>.combineWithSettings(): Flow<Set<ListFilterOption>> = combine(
		settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
	) { filters, skipNsfw ->
		if (skipNsfw) {
			filters + ListFilterOption.SFW
		} else {
			filters
		}
	}

	data class ImportSource(
		val source: ContentSource,
		val title: String,
		val folders: List<ContentFavoriteFolder>? = null,
	)

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val importMessages = MutableEventFlow<String>()
	val syncMessages = MutableEventFlow<String>()
	val organizeMessages = MutableEventFlow<String>()
	private fun logImport(msg: String) = Unit
	private fun logSync(msg: String) = Unit

	fun notifyEntityOrganizeResult(message: String?) {
		if (message.isNullOrBlank()) {
			return
		}
		organizeMessages.call(message)
	}

	private val categoriesStateFlow = favouritesRepository.observeCategoriesForLibrary()
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	private val activeCategoryCounts = combine(
		currentGroupTab,
		selectedSourceTags,
	) { groupTab, sourceTags ->
		groupTab to sourceTags
	}.flatMapLatest { (groupTab, sourceTags) ->
		if (groupTab == BrowseGroupTab.All && sourceTags.isEmpty()) {
			flowOf(ActiveCategoryCountsState.NotFiltered)
		} else {
			favouritesRepository.observeCategoryCountEntries()
				.map { entries ->
					ActiveCategoryCountsState.Filtered(
						buildActiveCategoryCounts(
							entries = entries,
							groupTab = groupTab,
							sourceTags = sourceTags,
						),
					) as ActiveCategoryCountsState
				}
				.onStart { emit(ActiveCategoryCountsState.Loading) }
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, ActiveCategoryCountsState.Loading)

	val uiState = combine(
		categoriesStateFlow,
		activeCategoryCounts,
		observeAllFavouritesVisibility(),
	) { list, countsState, showAll ->
		if (list == null) {
			return@combine FavoritesHostUiState()
		}

		val activeCounts = (countsState as? ActiveCategoryCountsState.Filtered)?.categoryCounts
		val filteredList = activeCounts?.let { counts ->
			list.filter { counts.getOrDefault(it.id, 0) > 0 }
		} ?: list
		
		val result = ArrayList<FavouriteTabModel>(if (showAll) filteredList.size + 1 else filteredList.size)
		if (showAll) {
			if (activeCounts == null || activeCounts.getOrDefault(NO_ID, 0) > 0) {
				result.add(FavouriteTabModel(NO_ID, null))
			}
		}
		filteredList.mapTo(result) { FavouriteTabModel(it.id, it.title) }

		val isEmpty = if (activeCounts != null) {
			list.all { activeCounts.getOrDefault(it.id, 0) == 0 } &&
				activeCounts.getOrDefault(NO_ID, 0) == 0
		} else {
			list.isEmpty() && !showAll
		}

		FavoritesHostUiState(
			isLoading = false,
			categories = result,
			isEmpty = isEmpty,
		)
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

	private fun buildActiveCategoryCounts(
		entries: List<org.skepsun.kototoro.favourites.data.FavouriteCategoryCountEntry>,
		groupTab: BrowseGroupTab,
		sourceTags: Set<SourceTag>,
	): Map<Long, Int> {
		val categoryCounts = mutableMapOf<Long, Int>()
		val allContentIds = HashSet<Long>(entries.size)
		for (entry in entries) {
			val contentGroup = sourceGroupManager.getContentGroupByName(entry.source, entry.isNsfw)
			val originGroup = sourceGroupManager.getOriginGroupByName(entry.source)
			val groupMatches = groupTab.matchesContentGroup(contentGroup)
			val originMatches = sourceTags.isEmpty() || sourceTags.any { it.matches(contentGroup, originGroup) }
			if (!groupMatches || !originMatches) {
				continue
			}
			categoryCounts[entry.categoryId] = (categoryCounts[entry.categoryId] ?: 0) + 1
			allContentIds += entry.mangaId
		}
		categoryCounts[NO_ID] = allContentIds.size
		return categoryCounts
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
