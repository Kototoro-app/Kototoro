package org.skepsun.kototoro.discover.ui.category

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.model.ContentSource
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.list.domain.ContentListMapper
import org.skepsun.kototoro.list.ui.model.ContentCompactListModel
import org.skepsun.kototoro.list.ui.model.ContentDetailedListModel
import org.skepsun.kototoro.list.ui.model.ContentGridModel
import org.skepsun.kototoro.list.ui.model.EmptyState
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.list.ui.model.toErrorState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCatalog
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteCategory
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteItem
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteSortOption
import org.skepsun.kototoro.tracking.discovery.domain.displayScoreText
import org.skepsun.kototoro.tracking.discovery.domain.displaySecondaryTitle
import org.skepsun.kototoro.tracking.discovery.domain.displaySupportingText
import org.skepsun.kototoro.tracking.discovery.domain.displaySubtitle
import org.skepsun.kototoro.tracking.discovery.domain.displayTitle
import org.skepsun.kototoro.tracking.discovery.domain.isTrackingDateDrivenCategory
import org.skepsun.kototoro.tracking.discovery.domain.resolveTrackingSeason
import org.skepsun.kototoro.tracking.discovery.domain.trackingCalendarDate
import org.skepsun.kototoro.tracking.discovery.data.TrackingSiteCacheRepository
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import org.skepsun.kototoro.space.ui.toPrimaryContentType
import javax.inject.Inject
import org.skepsun.kototoro.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@HiltViewModel
class DiscoverCategoryViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val discoveryService: TrackingSiteDiscoveryService,
	private val contentListMapper: ContentListMapper,
	private val appSettings: AppSettings,
	private val cacheRepository: TrackingSiteCacheRepository,
	private val spaceBrowseScope: SpaceBrowseScope,
	val filterCoordinator: org.skepsun.kototoro.filter.ui.FilterCoordinator,
) : BaseViewModel() {

	private val _items = MutableStateFlow<List<TrackingSiteItem>>(emptyList())
	private val _page = MutableStateFlow(0)
	private val _contentState = MutableStateFlow<List<ListModel>>(listOf(LoadingState))
	private val _selectedCalendarDateMillis = MutableStateFlow<Long?>(null)

	val content: StateFlow<List<ListModel>> = _contentState.asStateFlow()
	val selectedCalendarDateMillis: StateFlow<Long?> = _selectedCalendarDateMillis.asStateFlow()

	private var isPageLoading = false
	private var currentService: ScrobblerService? = null
	private var currentCategory: String? = null
	private var currentSortOptionId: String? = null
	private var isFilterObserverPrimed = false
	private var canLoadMore = true

	fun initialize(serviceName: String, categoryId: String) {
		val service = ScrobblerService.entries.find { it.name == serviceName } ?: return
		if (currentService == service && currentCategory == categoryId) return
		currentService = service
		currentCategory = categoryId
		if (isTrackingDateDrivenCategory(categoryId)) {
			_selectedCalendarDateMillis.value = _selectedCalendarDateMillis.value ?: todayMillis()
		} else {
			_selectedCalendarDateMillis.value = null
		}
		currentSortOptionId = findCategoryMeta(service, categoryId)?.defaultSortOptionId
		canLoadMore = true
		// Show cached items instantly
		val cached = cacheRepository.readCategoryCache(service, resolveCacheKey(categoryId))
		if (cached != null && cached.isNotEmpty()) {
			_items.value = cached
			viewModelScope.launch {
				_contentState.value = cached.toDiscoverModels()
			}
		} else {
			refresh()
		}
	}

	init {
		viewModelScope.launch {
			filterCoordinator.observe()
				.distinctUntilChanged()
				.collect {
					if (!isFilterObserverPrimed) {
						isFilterObserverPrimed = true
						return@collect
					}
					if (currentService != null) {
						refresh()
					}
				}
		}
		viewModelScope.launch {
			spaceBrowseScope.groupTab
				.drop(1)
				.distinctUntilChanged()
				.collect {
					if (currentService != null) {
						refresh()
					}
				}
		}
	}

	fun applyDayFilter(day: Int) {
		val service = currentService ?: return
		val category = currentCategory ?: return
		_selectedCalendarDateMillis.value = alignSelectedDateToDay(day)
		if (category.startsWith("calendar")) {
			currentCategory = "calendar_$day"
			currentSortOptionId = findCategoryMeta(service, "calendar")?.defaultSortOptionId
		}
		refresh()
	}

	fun applyDateFilter(selectedDateMillis: Long) {
		val category = currentCategory ?: return
		if (!isTrackingDateDrivenCategory(category)) {
			return
		}
		if (category.startsWith("calendar")) {
			val day = Instant.ofEpochMilli(selectedDateMillis)
				.atZone(ZoneId.systemDefault())
				.toLocalDate()
				.dayOfWeek
				.value
			_selectedCalendarDateMillis.value = selectedDateMillis
			applyDayFilter(day)
			return
		}
		_selectedCalendarDateMillis.value = selectedDateMillis
		refresh()
	}

	fun selectToday() {
		applyDateFilter(todayMillis())
	}

	fun getCurrentSortOptions(): List<TrackingSiteSortOption> {
		val service = currentService ?: return emptyList()
		val category = currentCategory ?: return emptyList()
		return findCategoryMeta(service, category)?.sortOptions.orEmpty()
	}

	fun getSelectedSortOptionId(): String? = currentSortOptionId

	fun applySortOption(optionId: String): TrackingSiteSortOption? {
		val service = currentService ?: return null
		val option = getCurrentSortOptions().firstOrNull { it.id == optionId } ?: return null
		currentSortOptionId = option.id
		option.targetCategoryId?.let {
			currentCategory = it
		}
		refresh()
		return option
	}

	fun refresh() {
		val service = currentService ?: return
		val category = currentCategory ?: return
		cacheRepository.clearCategoryCache(service, resolveCacheKey(category))
		_items.value = emptyList()
		_page.value = 0
		canLoadMore = true
		viewModelScope.launch {
			loadingCounter.increment()
			try {
				loadData(service, category, 0)
			} finally {
				loadingCounter.decrement()
			}
		}
	}

	fun loadNextPage() {
		if (isPageLoading || !canLoadMore) return
		val service = currentService ?: return
		val category = currentCategory ?: return
		val nextPage = _page.value + 1
		viewModelScope.launch {
			isPageLoading = true
			try {
				loadData(service, category, nextPage)
			} finally {
				isPageLoading = false
			}
		}
	}



	private suspend fun loadData(service: ScrobblerService, category: String, pageRequested: Int) {
		val isFirstPage = pageRequested == 0
		if (isFirstPage) {
			_contentState.value = listOf(LoadingState)
		}

		val filterSnapshot = filterCoordinator.snapshot()
		val result = runCatching {
			withContext(Dispatchers.IO) {
				discoveryService.getTrending(
					TrackingSiteCatalog(
						service = service,
						contentType = spaceBrowseScope.groupTab.value?.toPrimaryContentType(),
						category = category,
						page = pageRequested,
						sortOrder = filterSnapshot.sortOrder,
						listFilter = filterSnapshot.listFilter,
						trackingSortKey = resolveCurrentTrackingSortKey(service, category),
						calendarDateMillis = _selectedCalendarDateMillis.value.takeIf {
							isTrackingDateDrivenCategory(category)
						},
					)
				)
			}
		}

		result.onSuccess { newItems ->
			if (newItems.isEmpty() && isFirstPage) {
				_contentState.value = listOf(createEmptyState(service, category))
				return@onSuccess
			}
			val currentItems = if (isFirstPage) emptyList() else _items.value
			val seenRemoteIds = currentItems.mapTo(HashSet()) { it.remoteId }
			val appendedItems = newItems.filter { seenRemoteIds.add(it.remoteId) }
			val updatedItems = currentItems + appendedItems
			val hasMore = appendedItems.isNotEmpty()

			_items.value = updatedItems
			val models = _items.value.toDiscoverModels()
			_contentState.value = if (hasMore) models + listOf(LoadingState) else models
			canLoadMore = hasMore
			_page.value = pageRequested
			// Save to cache
			cacheRepository.saveCategoryCache(service, resolveCacheKey(category), _items.value)

		}.onFailure { error ->
			val models = _items.value.toDiscoverModels()
			canLoadMore = false
			if (models.isEmpty()) {
				_contentState.value = listOf(error.toErrorState(canRetry = true))
			} else {
				_contentState.value = models
			}
		}
	}

	fun supportsDetails(serviceName: String): Boolean {
		val service = ScrobblerService.entries.find { it.name == serviceName } ?: return false
		return discoveryService.getCapabilities(service).supportsDetails
	}

	private fun findCategoryMeta(service: ScrobblerService, categoryId: String): TrackingSiteCategory? {
		val rootCategoryId = categoryId.substringBefore('_').takeIf { categoryId.startsWith("calendar_") } ?: categoryId
		return discoveryService.getCapabilities(service).discoveryCategories.firstOrNull { category ->
			category.id == categoryId || category.id == rootCategoryId
		}
	}

	private fun resolveCurrentTrackingSortKey(service: ScrobblerService, categoryId: String): String? {
		val selectedId = currentSortOptionId ?: return null
		return findCategoryMeta(service, categoryId)
			?.sortOptions
			?.firstOrNull { it.id == selectedId }
			?.trackingSortKey
	}

	private fun alignSelectedDateToDay(day: Int): Long {
		val baseDate = _selectedCalendarDateMillis.value
			?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
			?: LocalDate.now()
		return baseDate
			.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.of(day)))
			.atStartOfDay(ZoneId.systemDefault())
			.toInstant()
			.toEpochMilli()
	}

	private fun todayMillis(): Long {
		return LocalDate.now()
			.atStartOfDay(ZoneId.systemDefault())
			.toInstant()
			.toEpochMilli()
	}

	private fun createEmptyState(service: ScrobblerService, category: String): EmptyState {
		val selectedDate = trackingCalendarDate(_selectedCalendarDateMillis.value)
		return when {
			service == ScrobblerService.SIMKL && (
				category == "simkl_anime_airing" ||
					category == "simkl_tv_airing"
				) -> EmptyState(
				icon = R.drawable.ic_bangumi_outline,
				textPrimary = R.string.discover_empty_simkl_airing_title,
				textSecondary = R.string.discover_empty_simkl_airing_text,
				actionStringRes = 0,
			)
			service == ScrobblerService.ANILIST && category == "al_anime_airing" -> EmptyState(
				icon = R.drawable.ic_bangumi_outline,
				textPrimary = R.string.discover_empty_anilist_airing_title,
				textSecondary = R.string.discover_empty_anilist_airing_text,
				actionStringRes = 0,
			)
			(service == ScrobblerService.MAL && category == "seasonal") ||
				(service == ScrobblerService.SHIKIMORI && category == "shiki_seasonal")
			-> {
				val seasonLabel = selectedDate
					?.let(::formatSeasonLabel)
					.orEmpty()
				EmptyState(
					icon = R.drawable.ic_bangumi_outline,
					textPrimary = 0,
					textSecondary = 0,
					actionStringRes = 0,
					textPrimaryText = context.getString(
						R.string.discover_empty_tracking_seasonal_title,
						seasonLabel,
					),
					textSecondaryText = context.getString(
						R.string.discover_empty_tracking_seasonal_text,
						seasonLabel,
					),
				)
			}
			else -> EmptyState(
				icon = R.drawable.ic_bangumi_outline,
				textPrimary = R.string.discover_empty_title,
				textSecondary = R.string.discover_empty_text,
				actionStringRes = 0,
			)
		}
	}

	private fun resolveCacheKey(category: String): String {
		val datedCategory = if (!isTrackingDateDrivenCategory(category)) {
			category
		} else {
			val date = trackingCalendarDate(_selectedCalendarDateMillis.value) ?: return category.withSpaceCacheSuffix()
			when {
			category.startsWith("calendar") -> category
			category == "seasonal" || category == "shiki_seasonal" -> {
				val season = resolveTrackingSeason(date)
				"${category}_${season.shikimoriSeason}"
			}
			else -> "${category}_${date}"
			}
		}
		return datedCategory.withSpaceCacheSuffix()
	}

	private fun String.withSpaceCacheSuffix(): String {
		val groupTab = spaceBrowseScope.groupTab.value ?: return this
		return "${this}_space_${groupTab.id}"
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
		return contentListMapper.toListModelList(proxyContents, appSettings.listMode).map { model ->
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
			}
		}
	}

	private fun formatSeasonLabel(date: LocalDate): String {
		val season = resolveTrackingSeason(date)
		val seasonNameResId = when (season.malSeason) {
			"winter" -> R.string.tracking_season_winter
			"spring" -> R.string.tracking_season_spring
			"summer" -> R.string.tracking_season_summer
			else -> R.string.tracking_season_fall
		}
		return context.getString(
			R.string.tracking_season_label,
			context.getString(seasonNameResId),
			season.year,
		)
	}
}
