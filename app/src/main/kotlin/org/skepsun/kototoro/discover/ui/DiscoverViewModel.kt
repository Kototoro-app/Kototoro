package org.skepsun.kototoro.discover.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.yield
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.discover.ui.model.DiscoverCarouselRow
import org.skepsun.kototoro.discover.ui.model.DiscoverItem
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.favourites.domain.GlobalFavoritesState
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.toErrorState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.data.TrackingSiteCacheRepository
import org.skepsun.kototoro.tracking.discovery.domain.PreferredTrackingSiteProvider
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCatalog
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCategory
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItem
import org.skepsun.kototoro.tracking.discovery.domain.displayScoreText
import org.skepsun.kototoro.tracking.discovery.domain.displaySecondaryTitle
import org.skepsun.kototoro.tracking.discovery.domain.displaySupportingText
import org.skepsun.kototoro.tracking.discovery.domain.displaySubtitle
import org.skepsun.kototoro.tracking.discovery.domain.displayTitle
import javax.inject.Inject
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.scopedToSpace
import org.skepsun.kototoro.space.ui.toPrimaryContentType

@HiltViewModel
class DiscoverViewModel @Inject constructor(
	private val preferredTrackingSiteProvider: PreferredTrackingSiteProvider,
	private val discoveryService: TrackingSiteDiscoveryService,
	private val cacheRepository: TrackingSiteCacheRepository,
	private val contentListMapper: ContentListMapper,
	private val globalFavoritesState: GlobalFavoritesState,
	val settings: AppSettings,
	spaceBrowseScope: SpaceBrowseScope,
) : BaseViewModel(), SpaceBindableViewModel {
	private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)

	private val refreshTrigger = MutableStateFlow(0)
	private val searchQuery = MutableStateFlow("")
	private val selectedServiceOverride = MutableStateFlow<ScrobblerService?>(null)
	private val selectedCategoryOverride = MutableStateFlow<String?>(null)
	private val forceLoadRecommendations = MutableStateFlow(false)
	private val currentGroupTab = globalFavoritesState.selectedGroupTab.scopedToSpace(
		spaceGroupTab = spaceBinding.groupTab,
		coroutineScope = viewModelScope + Dispatchers.Default,
	)
	override fun bindSpace(spaceId: org.skepsun.kototoro.space.domain.SpaceId?) = spaceBinding.bindSpace(spaceId)

	private val preferredService = preferredTrackingSiteProvider.preferredSite
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			ScrobblerService.BANGUMI,
		)

	private val isBrowseTrackingRecommendationsEnabled = settings.observe(
		AppSettings.KEY_BROWSE_TRACKING_RECOMMENDATIONS,
	).mapLatest {
		settings.isBrowseTrackingRecommendationsEnabled
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		settings.isBrowseTrackingRecommendationsEnabled,
	)

	private val isBrowseMoreTrackingRecommendationsEnabled = settings.observe(
		AppSettings.KEY_BROWSE_MORE_TRACKING_RECOMMENDATIONS,
	).mapLatest {
		settings.isBrowseMoreTrackingRecommendationsEnabled
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		settings.isBrowseMoreTrackingRecommendationsEnabled,
	)

	private val browseRecommendationPrefs = combine(
		isBrowseTrackingRecommendationsEnabled,
		isBrowseMoreTrackingRecommendationsEnabled,
		forceLoadRecommendations,
	) { isEnabled, isMoreEnabled, forceLoad ->
		BrowseRecommendationPrefs(
			isEnabled = isEnabled || forceLoad,
			isMoreEnabled = isMoreEnabled || forceLoad,
		)
	}

	val availableServices = preferredService
		.map(::resolveAvailableServices)
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			resolveAvailableServices(ScrobblerService.BANGUMI),
		)

	val query: StateFlow<String> = searchQuery.asStateFlow()

	val activeService = combine(
		preferredService,
		availableServices,
		selectedServiceOverride,
		searchQuery,
	) { preferredService, availableServices, selectedService, query ->
		resolveActiveService(preferredService, availableServices, selectedService, query)
	}
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			ScrobblerService.BANGUMI,
		)

	val activeCategory = combine(activeService, selectedCategoryOverride) { service, category ->
		val caps = discoveryService.getCapabilities(service)
		if (caps.discoveryCategories.isNotEmpty()) {
			category ?: caps.discoveryCategories.first().id
		} else {
			null
		}
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		null,
	)

	val trackingSiteCategories = activeService.map { service ->
		discoveryService.getCapabilities(service).discoveryCategories
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		emptyList(),
	)

	private val _items = MutableStateFlow<List<TrackingSiteItem>>(emptyList())
	private val _page = MutableStateFlow(0)
	private val _contentState = MutableStateFlow<List<ListModel>>(listOf(LoadingState))
	private val _bangumiRecommendationLoadFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
	private var isPageLoading = false
	private var lastHandledRefreshVersion = 0

	val content: StateFlow<List<ListModel>> = _contentState.asStateFlow()
	val bangumiRecommendationLoadFailures = _bangumiRecommendationLoadFailures.asSharedFlow()

	private var loadJob: kotlinx.coroutines.Job? = null

	init {
		viewModelScope.launch {
			combine(
				activeService,
				activeCategory,
				refreshTrigger,
				searchQuery,
				browseRecommendationPrefs,
				currentGroupTab,
			) { values: Array<Any?> ->
				val browsePrefs = values[4] as BrowseRecommendationPrefs
				DiscoverRequest(
					service = values[0] as ScrobblerService,
					category = values[1] as? String,
					query = (values[3] as String).trim(),
					refreshVersion = values[2] as Int,
					isEnabled = browsePrefs.isEnabled,
					isMoreEnabled = browsePrefs.isMoreEnabled,
					groupTab = values[5] as BrowseGroupTab,
				)
			}
				.debounce(200) // Wait for all StateFlows to settle
				.distinctUntilChanged()
				.collect { request ->
					if (!request.shouldLoad()) {
						loadJob?.cancel()
						_items.value = emptyList()
						_page.value = 0
						_contentState.value = emptyList()
						return@collect
					}
					val service = request.service
					val category = request.category
					val query = request.query
					val forceRefresh = request.refreshVersion != lastHandledRefreshVersion
					lastHandledRefreshVersion = request.refreshVersion

					loadJob?.cancel()
					_items.value = emptyList()
					_page.value = 0
					if (query.isBlank() && !forceRefresh && tryShowCachedDiscoverContent(service, request.isMoreEnabled)) {
						return@collect
					}
					loadJob = viewModelScope.launch {
						loadData(service, category, query, 0, request.isMoreEnabled)
					}
				}
		}

		viewModelScope.launch {
			preferredService.drop(1).collect {
				selectedServiceOverride.value = null
				selectedCategoryOverride.value = null
			}
		}

		viewModelScope.launch {
			currentGroupTab.drop(1).collect {
				selectedCategoryOverride.value = null
			}
		}

		viewModelScope.launch {
			combine(
				settings.observe(AppSettings.KEY_LIST_MODE),
				currentGroupTab,
				settings.observe(AppSettings.KEY_BROWSE_MORE_TRACKING_RECOMMENDATIONS),
			) { _, _, _ -> }.drop(1).collect {
				remapContentState()
			}
		}
	}

	private suspend fun remapContentState() {
		val service = activeService.value
		val query = searchQuery.value.trim()
		if (!shouldLoadBrowseRecommendations(query)) {
			_contentState.value = emptyList()
			return
		}
		val currentTab = getCurrentBrowseGroupTab()
		
		if (query.isNotBlank()) {
			val models = _items.value.toDiscoverModels()
			val hasLoading = _contentState.value.lastOrNull() is LoadingState
			_contentState.value = if (hasLoading) models + listOf(LoadingState) else models
		} else {
			val caps = discoveryService.getCapabilities(service)
			if (caps.discoveryCategories.isEmpty()) {
				val cached = cacheRepository.readCategoryCache(service, "root_trending") ?: return
				val flat = cached.toDiscoverModels()
				_contentState.value = flat.ifEmpty { listOf(org.skepsun.kototoro.list.ui.model.EmptyState(icon = R.drawable.ic_bangumi_outline, textPrimary = R.string.discover_empty_title, textSecondary = R.string.discover_empty_text, actionStringRes = 0)) }
			} else {
				val visibleCategories = resolveVisibleCategoriesForTab(
					service = service,
					categories = caps.discoveryCategories,
					currentTab = currentTab,
				).let { categories ->
					if (isBrowseMoreTrackingRecommendationsEnabled.value) categories else categories.take(1)
				}
				val rows = visibleCategories.mapNotNull { cat ->
					val cached = cacheRepository.readCategoryCache(service, cat.id)
					if (cached != null && cached.isNotEmpty()) {
						DiscoverCarouselRow(category = cat, items = cached.toDiscoverModels())
					} else null
				}
				if (rows.isNotEmpty()) {
					_contentState.value = rows
				} else {
					_contentState.value = listOf(org.skepsun.kototoro.list.ui.model.EmptyState(icon = R.drawable.ic_bangumi_outline, textPrimary = R.string.discover_empty_title, textSecondary = R.string.discover_empty_text, actionStringRes = 0))
				}
			}
		}
	}

	private suspend fun tryShowCachedDiscoverContent(service: ScrobblerService, isMoreEnabled: Boolean): Boolean {
		val currentTab = getCurrentBrowseGroupTab()
		val caps = discoveryService.getCapabilities(service)
		if (caps.discoveryCategories.isEmpty()) {
			val cached = cacheRepository.readCategoryCache(service, "root_trending") ?: return false
			val flat = cached.toDiscoverModels()
			_contentState.value = flat.ifEmpty {
				listOf(
					EmptyState(
						icon = R.drawable.ic_bangumi_outline,
						textPrimary = R.string.discover_empty_title,
						textSecondary = R.string.discover_empty_text,
						actionStringRes = 0,
					),
				)
			}
			return true
		}

		val visibleCategories = resolveVisibleCategoriesForTab(
			service = service,
			categories = caps.discoveryCategories,
			currentTab = currentTab,
		).let { categories ->
			if (isMoreEnabled) categories else categories.take(1)
		}
		val scheduleCategoryId = getScheduleCategory(service)?.id
		if (scheduleCategoryId != null &&
			visibleCategories.firstOrNull()?.id == scheduleCategoryId &&
			cacheRepository.readCategoryCache(service, scheduleCategoryId).isNullOrEmpty()
		) {
			return false
		}
		val rows = visibleCategories.mapNotNull { cat ->
			val cached = cacheRepository.readCategoryCache(service, cat.id)
			if (cached.isNullOrEmpty()) {
				return@mapNotNull null
			}
			DiscoverCarouselRow(category = cat, items = cached.toDiscoverModels())
		}
		if (rows.isEmpty()) {
			return false
		}
		_contentState.value = rows
		return true
	}

	private fun isCategoryVisibleInTab(
		categoryId: String,
		service: ScrobblerService,
		currentTab: BrowseGroupTab,
	): Boolean {
		if (currentTab == BrowseGroupTab.All) return true

		val isVideo = categoryId.contains("anime") || categoryId.contains("movie") || categoryId.contains("ova") || 
			categoryId.contains("tv") || categoryId.contains("calendar") || categoryId.contains("real") || 
			categoryId.contains("seasonal") || (service == ScrobblerService.KITSU && !categoryId.contains("manga"))
			
		val isNovel = categoryId.contains("novel") || categoryId.contains("light_novel") || 
			(service == ScrobblerService.BANGUMI && categoryId == "book")
			
		val isManga = categoryId.contains("manga") || categoryId.contains("doujin") || 
			categoryId.contains("oneshots") || categoryId.contains("manhwa") || 
			categoryId.contains("manhua") || categoryId.contains("mu_") || 
			categoryId.contains("al_manga") || categoryId.contains("shiki_manga") || 
			(service == ScrobblerService.BANGUMI && categoryId == "book")

		return when (currentTab) {
			BrowseGroupTab.Video -> isVideo
			BrowseGroupTab.Novel -> isNovel
			BrowseGroupTab.Content -> isManga
			else -> true
		}
	}

	fun loadNextPage() {
		if (isPageLoading) return
		val query = searchQuery.value.trim()
		if (!shouldLoadBrowseRecommendations(query)) return
		val nextPage = _page.value + 1
		val service = activeService.value
		val category = activeCategory.value
		viewModelScope.launch {
			isPageLoading = true
			try {
				loadData(service, category, query, nextPage)
			} finally {
				isPageLoading = false
			}
		}
	}

	private suspend fun loadData(
		service: ScrobblerService,
		category: String?,
		query: String,
		pageRequested: Int,
		isMoreEnabled: Boolean = isBrowseMoreTrackingRecommendationsEnabled.value,
	) {
		if (!shouldLoadBrowseRecommendations(query)) {
			_contentState.value = emptyList()
			return
		}
		val isFirstPage = pageRequested == 0

		if (isFirstPage) {
			_contentState.value = listOf(LoadingState)
		}

		if (query.isBlank()) {
			// Multi-carousel layout for base UI!
			val caps = discoveryService.getCapabilities(service)
			if (caps.discoveryCategories.isEmpty()) {
				val cached = cacheRepository.readCategoryCache(service, "root_trending")
				if (cached != null) {
					val flat = cached.toDiscoverModels()
					_contentState.value = flat.ifEmpty { listOf(EmptyState(icon = R.drawable.ic_bangumi_outline, textPrimary = R.string.discover_empty_title, textSecondary = R.string.discover_empty_text, actionStringRes = 0)) }
					return
				}

				val result = runCatching { discoveryService.getTrending(TrackingSiteCatalog(service = service, page = 0)) }
				if (result.isFailure && service == ScrobblerService.BANGUMI) {
					_bangumiRecommendationLoadFailures.tryEmit(Unit)
				}
				val items = result.getOrNull() ?: emptyList()
				if (items.isNotEmpty()) {
					cacheRepository.saveCategoryCache(service, "root_trending", items)
				}
				val flat = items.toDiscoverModels()
				_contentState.value = flat.ifEmpty { listOf(EmptyState(icon = R.drawable.ic_bangumi_outline, textPrimary = R.string.discover_empty_title, textSecondary = R.string.discover_empty_text, actionStringRes = 0)) }
				return
			}
			
			// Parallel fetch top 10 for every category (with retry)
			val visibleCategories = resolveVisibleCategoriesForTab(
				service = service,
				categories = caps.discoveryCategories,
				currentTab = getCurrentBrowseGroupTab(),
			).let { categories ->
				if (isMoreEnabled) categories else categories.take(1)
			}
			val categoryResults = visibleCategories.map { cat ->

				viewModelScope.async(Dispatchers.IO) {
					val cached = cacheRepository.readCategoryCache(service, cat.id)
					if (cached != null && cached.isNotEmpty()) {
						return@async DiscoverCarouselRow(category = cat, items = cached.toDiscoverModels()) to false
					}

					var items = emptyList<TrackingSiteItem>()
					var requestFailed = false
					for (attempt in 1..3) {
						items = runCatching {
							discoveryService.getTrending(TrackingSiteCatalog(service = service, category = cat.id, page = 0))
						}.getOrElse {
							requestFailed = true
							emptyList()
						}
						if (items.isNotEmpty()) break
						if (attempt < 3) kotlinx.coroutines.delay(800L * attempt)
					}
					
					val row = if (items.isNotEmpty()) {
						cacheRepository.saveCategoryCache(service, cat.id, items)
						DiscoverCarouselRow(category = cat, items = items.toDiscoverModels())
					} else null
					row to requestFailed
				}
			}.awaitAll()
			if (service == ScrobblerService.BANGUMI && categoryResults.any { it.second }) {
				_bangumiRecommendationLoadFailures.tryEmit(Unit)
			}
			val rows = categoryResults.mapNotNull { it.first }
			
			_contentState.value = rows.ifEmpty { listOf(EmptyState(icon = R.drawable.ic_bangumi_outline, textPrimary = R.string.discover_empty_title, textSecondary = R.string.discover_empty_text, actionStringRes = 0)) }
		} else {
			// Search Query layout (flat list pagination)
			val result = runCatching {
				val catalog = TrackingSiteCatalog(
					service = service,
					query = query.ifEmpty { null },
					contentType = getCurrentBrowseGroupTab().toPrimaryContentType(),
					category = category,
					page = pageRequested,
				)
				discoveryService.search(catalog)
			}

			val loadedItems = result.getOrElse { error ->
				if (_items.value.isEmpty()) {
					_contentState.value = listOf(error.toErrorState(canRetry = true))
				}
				return
			}

			if (loadedItems.isEmpty() && isFirstPage) {
				_contentState.value = listOf(EmptyState(icon = R.drawable.ic_bangumi_outline, textPrimary = R.string.discover_search_empty_title, textSecondary = R.string.discover_search_empty_text, actionStringRes = 0))
				return
			}

			val accumulated = if (isFirstPage) loadedItems else _items.value + loadedItems
			_items.value = accumulated.distinctBy { it.remoteId }
			val hasMore = loadedItems.isNotEmpty()
			val models = _items.value.toDiscoverModels()
			_contentState.value = if (hasMore) models + listOf(LoadingState) else models

			_page.value = pageRequested
		}
	}

	private fun getCurrentBrowseGroupTab(): BrowseGroupTab {
		return currentGroupTab.value
	}

	private fun resolveVisibleCategoriesForTab(
		service: ScrobblerService,
		categories: List<TrackingSiteCategory>,
		currentTab: BrowseGroupTab,
	): List<TrackingSiteCategory> {
		val uniqueCategories = categories.distinctBy { it.id }
		if (currentTab == BrowseGroupTab.All) {
			return uniqueCategories.withScheduleCategoryFirst(service)
		}
		val filtered = uniqueCategories.filter { category ->
			isCategoryVisibleInTab(category.id, service, currentTab)
		}
		return filtered.ifEmpty { uniqueCategories }.withScheduleCategoryFirst(service)
	}

	private fun List<TrackingSiteCategory>.withScheduleCategoryFirst(
		service: ScrobblerService,
	): List<TrackingSiteCategory> {
		val scheduleCategory = getScheduleCategory(service) ?: return this
		val index = indexOfFirst { it.id == scheduleCategory.id }
		if (index <= 0) {
			return this
		}
		return listOf(this[index]) + filterIndexed { itemIndex, _ -> itemIndex != index }
	}

	fun refresh() {
		if (!shouldLoadBrowseRecommendations()) return
		refreshTrigger.value += 1
	}

	fun submitQuery(query: String) {
		searchQuery.value = query.trim()
	}

	fun clearQuery() {
		searchQuery.value = ""
	}

	fun selectService(service: ScrobblerService) {
		if (!isServiceSelectable(service)) {
			return
		}
		selectedServiceOverride.value = service
		selectedCategoryOverride.value = null // reset category
		globalFavoritesState.clearSelectedGroupTab()
		preferredTrackingSiteProvider.setPreferredSite(service)
	}

	fun setForceLoad(forceLoad: Boolean) {
		forceLoadRecommendations.value = forceLoad
	}

	fun selectCategory(categoryId: String) {
		selectedCategoryOverride.value = categoryId
	}

	fun isServiceSelectable(service: ScrobblerService): Boolean {
		return if (searchQuery.value.isBlank()) {
			supportsTrending(service)
		} else {
			supportsSearch(service)
		}
	}

	fun supportsTrending(service: ScrobblerService): Boolean {
		return discoveryService.getCapabilities(service).supportsTrending
	}

	fun supportsSearch(service: ScrobblerService): Boolean {
		return discoveryService.getCapabilities(service).supportsSearch
	}

	fun supportsDetails(service: ScrobblerService): Boolean {
		return discoveryService.getCapabilities(service).supportsDetails
	}

	fun getScheduleCategory(service: ScrobblerService): TrackingSiteCategory? {
		val categories = discoveryService.getCapabilities(service).discoveryCategories
		val priority = listOf(
			"calendar",
			"al_anime_airing",
			"simkl_anime_airing",
			"seasonal",
			"shiki_seasonal",
			"anime_airing",
			"simkl_tv_airing",
			"anime_upcoming",
			"al_anime_upcoming",
		)
		return priority.firstNotNullOfOrNull { targetId ->
			categories.firstOrNull { it.id == targetId }
		}
	}

	private fun shouldLoadBrowseRecommendations(query: String = searchQuery.value.trim()): Boolean {
		return forceLoadRecommendations.value || isBrowseTrackingRecommendationsEnabled.value || query.isNotBlank()
	}

	private fun resolveAvailableServices(preferred: ScrobblerService): List<ScrobblerService> {
		return buildList {
			// Always include services that support trending discovery
			for (service in ScrobblerService.entries) {
				if (supportsTrending(service)) add(service)
			}
			// Ensure preferred is included if it supports search
			if (preferred !in this && supportsSearch(preferred)) {
				add(preferred)
			}
		}
	}

	private fun resolveActiveService(
		preferred: ScrobblerService,
		availableServices: List<ScrobblerService>,
		selected: ScrobblerService?,
		query: String,
	): ScrobblerService {
		if (selected != null && selected in availableServices && canUseService(selected, query)) {
			return selected
		}
		return if (canUseService(preferred, query)) preferred else ScrobblerService.BANGUMI
	}

	private fun canUseService(service: ScrobblerService, query: String): Boolean {
		return if (query.isBlank()) {
			supportsTrending(service)
		} else {
			supportsSearch(service)
		}
	}

	private suspend fun List<TrackingSiteItem>.toDiscoverModels(): List<ListModel> {
		val itemById = associateBy { it.remoteId }
		val proxyContents = this.map { item ->
			Content(
				id = item.remoteId,
				title = item.displayTitle(),
				altTitles = setOfNotNull(item.displaySecondaryTitle()),
				url = item.url ?: "",
				publicUrl = item.url ?: "",
				rating = ((item.score ?: 0f) / (item.scoreMax ?: 10f)).coerceIn(0f, 1f),
				contentRating = null,
				coverUrl = item.coverUrl,
				tags = emptySet(),
				state = org.skepsun.kototoro.parsers.model.ContentState.ONGOING,
				authors = emptySet(),
				source = ContentSource("TRACKING_${item.service.name}"),
				chapters = item.totalEpisodes?.let { count -> List(count) { org.skepsun.kototoro.parsers.model.ContentChapter(0L, null, 0f, 0, "", null, 0L, null, ContentSource("TRACKING_${item.service.name}")) } },
			)
		}
		val mode = settings.listMode
		return contentListMapper.toListModelList(proxyContents, mode).map { model ->
			val contentModel = model as? org.skepsun.kototoro.list.ui.model.ContentListModel ?: return@map model
			val item = itemById[contentModel.id] ?: return@map model
			val secondaryTitle = item.displaySecondaryTitle()
			val supportingText = item.displaySupportingText()
			val scoreText = item.displayScoreText()
			when (contentModel) {
				is ContentCompactListModel -> contentModel.copy(
					subtitle = secondaryTitle,
					supportingText = supportingText,
					metadataTrackingService = item.service,
					scoreText = scoreText,
				)
				is ContentDetailedListModel -> contentModel.copy(
					subtitle = secondaryTitle,
					supportingText = supportingText,
					metadataTrackingService = item.service,
					scoreText = scoreText,
				)
				is ContentGridModel -> contentModel.copy(
					subtitle = secondaryTitle,
					metadataTrackingService = item.service,
					scoreText = scoreText,
				)
				else -> model
			}
		}
	}
}

private data class DiscoverRequest(
	val service: ScrobblerService,
	val category: String?,
	val query: String,
	val refreshVersion: Int,
	val isEnabled: Boolean,
	val isMoreEnabled: Boolean,
	val groupTab: BrowseGroupTab,
) {
	fun shouldLoad(): Boolean = isEnabled || query.isNotBlank()
}

private data class BrowseRecommendationPrefs(
	val isEnabled: Boolean,
	val isMoreEnabled: Boolean,
)
