package org.skepsun.kototoro.space.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.favourites.domain.GlobalFavoritesState
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.space.domain.MAIN_LIST_ROUTE_KEY
import org.skepsun.kototoro.space.domain.SpaceFeatureFlagsRepository
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceListPreferences
import org.skepsun.kototoro.space.domain.SpaceRepository
import org.skepsun.kototoro.space.domain.SpaceRoutePreferencesRepository
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpaceRoutePreferencesController @Inject constructor(
	private val repository: SpaceRoutePreferencesRepository,
	private val spaceRepository: SpaceRepository,
	private val featureFlagsRepository: SpaceFeatureFlagsRepository,
	private val settings: AppSettings,
	private val globalFavoritesState: GlobalFavoritesState,
) {
	private val started = AtomicBoolean(false)
	private val mutex = Mutex()
	private val currentSpace = MutableStateFlow<SpaceId?>(null)
	private val applyingPreferences = MutableStateFlow(false)
	private val defaults = currentPreferences()
	private var jobs: List<Job> = emptyList()

	fun start() {
		if (!settings.isEntitySpaceEnabled) return
		if (!started.compareAndSet(false, true)) return
		jobs = listOf(
			processLifecycleScope.launch(Dispatchers.Default) {
				combine(
					spaceRepository.activeSpace,
					featureFlagsRepository.flags,
				) { spaceId, flags ->
					spaceId.takeIf { flags.effectiveRoutePreferencesEnabled }
				}.distinctUntilChanged().collect(::activate)
			},
			processLifecycleScope.launch(Dispatchers.Default) {
				combine(
					settings.observe(
						AppSettings.KEY_LIST_MODE,
						AppSettings.KEY_GRID_SIZE,
						AppSettings.KEY_HISTORY_ORDER,
						AppSettings.KEY_FAVORITES_ORDER,
					).map { currentPreferences() },
					globalFavoritesState.selectedSourceTags,
					currentSpace,
					applyingPreferences,
				) { preferences, sourceTags, spaceId, applying ->
					PreferenceChange(
						spaceId = spaceId,
						preferences = preferences.copy(sourceTags = sourceTags.mapTo(linkedSetOf(), SourceTag::id)),
						applying = applying,
					)
				}.distinctUntilChanged().collect { change ->
					val spaceId = change.spaceId ?: return@collect
					if (change.applying) return@collect
					mutex.withLock {
						if (currentSpace.value == spaceId && !applyingPreferences.value) {
							repository.save(spaceId, MAIN_LIST_ROUTE_KEY, change.preferences)
						}
					}
				}
			},
		)
	}

	fun stop() {
		if (!started.compareAndSet(true, false)) return
		jobs.forEach(Job::cancel)
		jobs = emptyList()
		currentSpace.value = null
		applyingPreferences.value = false
	}

	private suspend fun activate(spaceId: SpaceId?) {
		mutex.withLock {
			if (spaceId == null) {
				currentSpace.value = null
				return
			}
			applyingPreferences.value = true
			try {
				val preferences = repository.load(spaceId, MAIN_LIST_ROUTE_KEY) ?: defaults.also {
					repository.save(spaceId, MAIN_LIST_ROUTE_KEY, it)
				}
				currentSpace.value = spaceId
				settings.listMode = preferences.listMode.toListModeOr(defaults.listMode.toListModeOr(ListMode.GRID))
				settings.gridSize = preferences.gridSize.coerceIn(50, 150)
				settings.historySortOrder = preferences.historySortOrder.toSortOrderOr(ListSortOrder.LAST_READ)
				settings.allFavoritesSortOrder = preferences.favoritesSortOrder.toSortOrderOr(ListSortOrder.NEWEST)
				globalFavoritesState.setSelectedSourceTags(SourceTag.fromIds(preferences.sourceTags))
			} finally {
				applyingPreferences.value = false
			}
		}
	}

	private fun currentPreferences() = SpaceListPreferences(
		listMode = settings.listMode.name,
		gridSize = settings.gridSize,
		historySortOrder = settings.historySortOrder.name,
		favoritesSortOrder = settings.allFavoritesSortOrder.name,
		sourceTags = globalFavoritesState.selectedSourceTags.value.mapTo(linkedSetOf(), SourceTag::id),
	)

	private fun String.toListModeOr(fallback: ListMode): ListMode {
		return ListMode.entries.firstOrNull { it.name == this } ?: fallback
	}

	private fun String?.toSortOrderOr(fallback: ListSortOrder): ListSortOrder {
		return ListSortOrder.entries.firstOrNull { it.name == this } ?: fallback
	}

	private data class PreferenceChange(
		val spaceId: SpaceId?,
		val preferences: SpaceListPreferences,
		val applying: Boolean,
	)
}
