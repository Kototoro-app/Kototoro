package org.skepsun.kototoro.space.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.work.domain.WorkAggregate
import org.skepsun.kototoro.work.domain.WorkAggregateRepository
import javax.inject.Inject

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

@HiltViewModel
class MediaUniverseViewModel @Inject constructor(
	private val workAggregateRepository: WorkAggregateRepository,
) : ViewModel() {

	private val mutableUiState = MutableStateFlow(MediaUniverseUiState())
	val uiState: StateFlow<MediaUniverseUiState> = mutableUiState.asStateFlow()
	private var loadJob: Job? = null

	fun open() {
		if (mutableUiState.value.loading) return
		mutableUiState.update { it.copy(visible = true, loading = true) }
		loadJob = viewModelScope.launch(Dispatchers.IO) {
			val items = runCatching {
				mergeMediaUniverseItems(
					history = workAggregateRepository.findHistoryAggregates(spaceId = null),
					favorites = workAggregateRepository.findFavouriteAggregates(spaceId = null),
				)
			}.getOrElse { error ->
				if (error is CancellationException) throw error
				emptyList()
			}
			mutableUiState.update { state ->
				if (state.visible) state.copy(loading = false, items = items) else state
			}
		}
	}

	fun dismiss() {
		loadJob?.cancel()
		loadJob = null
		mutableUiState.value = MediaUniverseUiState()
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
