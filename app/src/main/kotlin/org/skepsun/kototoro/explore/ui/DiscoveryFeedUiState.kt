package org.skepsun.kototoro.explore.ui

import androidx.compose.runtime.Immutable
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
import org.skepsun.kototoro.parsers.model.Content

@Immutable
sealed interface DiscoveryFeedRowState {
    data object Idle : DiscoveryFeedRowState

    data object Loading : DiscoveryFeedRowState

    data class Loaded(
        val items: List<Content>,
        val fetchedAtMs: Long,
    ) : DiscoveryFeedRowState

    data class Error(
        val throwable: Throwable,
        val message: String? = throwable.localizedMessage ?: throwable.message,
    ) : DiscoveryFeedRowState
}

@Immutable
data class DiscoveryFeedUiState(
    val allRows: List<DiscoveryFeedRow> = emptyList(),
    val visibleRows: List<DiscoveryFeedRow> = emptyList(),
    val rowStates: Map<String, DiscoveryFeedRowState> = emptyMap(),
    val isRefreshing: Boolean = false,
) {

    fun stateOf(rowId: String): DiscoveryFeedRowState =
        rowStates[rowId] ?: DiscoveryFeedRowState.Idle
}
