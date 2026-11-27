package org.skepsun.kototoro.explore.ui

import androidx.compose.runtime.Immutable
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
import org.skepsun.kototoro.list.ui.model.ContentGridModel
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
    val rowCardModels: Map<String, List<ContentGridModel>> = emptyMap(),
    val isRefreshing: Boolean = false,
    /** Rows leave out works already in the library; [rowCardModels] is already filtered. */
    val hideInLibrary: Boolean = false,
    /** Selected content ids, across rows; only ids still shown in some row are kept. */
    val selectedIds: Set<Long> = emptySet(),
    /** The row the last selection change happened in: "select all" fills this row. */
    val selectionRowId: String? = null,
) {

    val isSelectionMode: Boolean
        get() = selectedIds.isNotEmpty()

    fun isSelected(contentId: Long): Boolean = contentId in selectedIds

    fun stateOf(rowId: String): DiscoveryFeedRowState =
        rowStates[rowId] ?: DiscoveryFeedRowState.Idle

    fun cardModelsOf(rowId: String): List<ContentGridModel> =
        rowCardModels[rowId].orEmpty()
}
