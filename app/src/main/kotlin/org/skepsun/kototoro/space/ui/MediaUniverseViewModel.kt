package org.skepsun.kototoro.space.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_MANGA
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_WORK_HISTORY
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.work.domain.WorkAggregate
import org.skepsun.kototoro.work.domain.WorkAggregateRepository
import javax.inject.Inject
import javax.inject.Singleton

data class MediaUniverseItem(
	val content: Content,
	val inHistory: Boolean,
	val inFavorites: Boolean,
)

data class MediaUniverseUiState(
	val visible: Boolean = false,
	val loading: Boolean = false,
	val items: List<MediaUniverseItem> = emptyList(),
)

data class MediaUniverseContentState(
	val loaded: Boolean = false,
	val loading: Boolean = false,
	val items: List<MediaUniverseItem> = emptyList(),
)

@Singleton
class MediaUniverseStateSource @Inject constructor(
	private val workAggregateRepository: WorkAggregateRepository,
	database: MangaDatabase,
) {
	private val mutableState = MutableStateFlow(MediaUniverseContentState())
	val state: StateFlow<MediaUniverseContentState> = mutableState.asStateFlow()
	private val refreshRequests = MutableSharedFlow<Unit>(
		replay = 1,
		extraBufferCapacity = 1,
		onBufferOverflow = BufferOverflow.DROP_OLDEST,
	)

	init {
		processLifecycleScope.launch(Dispatchers.IO) {
			refreshRequests.collectLatest { load() }
		}
		processLifecycleScope.launch(Dispatchers.IO) {
			database.invalidationTracker.createFlow(
				TABLE_WORK_HISTORY,
				TABLE_WORK_FAVOURITES,
				TABLE_MANGA,
				TABLE_ENTITY_GRAPH_BINDING,
				TABLE_ENTITY_PREFERENCES,
				emitInitialState = false,
			).debounce(300L).collect {
				if (mutableState.value.loaded) {
					refreshRequests.tryEmit(Unit)
				}
			}
		}
	}

	fun loadIfNeeded() {
		val current = mutableState.value
		if (!current.loaded && !current.loading) {
			refreshRequests.tryEmit(Unit)
		}
	}

	private suspend fun load() {
		val previous = mutableState.value
		mutableState.value = previous.copy(loading = !previous.loaded)
		val items = try {
			mergeMediaUniverseItems(
				history = workAggregateRepository.findHistoryAggregates(spaceId = null),
				favorites = workAggregateRepository.findFavouriteAggregates(spaceId = null),
			)
		} catch (error: CancellationException) {
			throw error
		} catch (_: Throwable) {
			mutableState.value = previous.copy(loading = false)
			return
		}
		mutableState.value = MediaUniverseContentState(
			loaded = true,
			loading = false,
			items = items,
		)
	}
}

@HiltViewModel
class MediaUniverseViewModel @Inject constructor(
	private val stateSource: MediaUniverseStateSource,
) : ViewModel() {

	private val visible = MutableStateFlow(false)
	val uiState: StateFlow<MediaUniverseUiState> = combine(
		stateSource.state,
		visible,
	) { content, isVisible ->
		MediaUniverseUiState(
			visible = isVisible,
			loading = content.loading,
			items = content.items,
		)
	}.stateIn(
		scope = viewModelScope + Dispatchers.Default,
		started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
		initialValue = MediaUniverseUiState(),
	)

	fun open() {
		visible.value = true
		stateSource.loadIfNeeded()
	}

	fun dismiss() {
		visible.value = false
	}
}

internal fun mergeMediaUniverseItems(
	history: List<WorkAggregate>,
	favorites: List<WorkAggregate>,
): List<MediaUniverseItem> {
	val merged = LinkedHashMap<Any, MediaUniverseItem>()
	fun add(aggregate: WorkAggregate, inHistory: Boolean, inFavorites: Boolean) {
		val content = aggregate.displayProjection ?: return
		val key = aggregate.identity.entityId?.let { "entity:$it" } ?: "content:${content.id}"
		val existing = merged[key]
		merged[key] = MediaUniverseItem(
			content = existing?.content ?: content,
			inHistory = existing?.inHistory == true || inHistory,
			inFavorites = existing?.inFavorites == true || inFavorites,
		)
	}
	history.forEach { add(it, inHistory = true, inFavorites = false) }
	favorites.forEach { add(it, inHistory = false, inFavorites = true) }
	return merged.values.toList()
}
