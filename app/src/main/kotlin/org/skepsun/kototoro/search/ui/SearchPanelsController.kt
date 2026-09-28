package org.skepsun.kototoro.search.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.skepsun.kototoro.parsers.model.Content

@Immutable
data class SearchPanelsState(
    val filterOpen: Boolean = false,
    val previewContentId: Long? = null,
    /** In memory only; after process death it is picked up from the list again. */
    val previewContent: Content? = null,
)

/**
 * The tablet works list's filter drawer and preview card, owned by the view model so they outlive
 * a trip to the details screen (the screen's composition does not). A list that is empty or still
 * loading never closes the preview: only the user does.
 */
class SearchPanelsController(
    private val savedStateHandle: SavedStateHandle,
    defaultFilterOpen: Boolean,
) {
    private val _state = MutableStateFlow(
        SearchPanelsState(
            filterOpen = savedStateHandle[KEY_FILTER_OPEN] ?: defaultFilterOpen,
            previewContentId = savedStateHandle[KEY_PREVIEW_ID],
        ),
    )
    val state: StateFlow<SearchPanelsState> = _state.asStateFlow()

    fun setFilterOpen(open: Boolean) {
        savedStateHandle[KEY_FILTER_OPEN] = open
        _state.update { it.copy(filterOpen = open) }
    }

    fun openPreview(content: Content) {
        savedStateHandle[KEY_PREVIEW_ID] = content.id
        _state.update { it.copy(previewContentId = content.id, previewContent = content) }
    }

    fun updatePreviewContent(content: Content) {
        _state.update { if (it.previewContentId == content.id) it.copy(previewContent = content) else it }
    }

    fun closePreview() {
        savedStateHandle.remove<Long>(KEY_PREVIEW_ID)
        _state.update { it.copy(previewContentId = null, previewContent = null) }
    }

    fun restorePreviewFrom(candidates: List<Content>) {
        val current = _state.value
        if (current.previewContent != null) return
        val id = current.previewContentId ?: return
        val match = candidates.firstOrNull { it.id == id } ?: return
        _state.update { if (it.previewContentId == id) it.copy(previewContent = match) else it }
    }

    private companion object {
        const val KEY_FILTER_OPEN = "search_panels_filter_open"
        const val KEY_PREVIEW_ID = "search_panels_preview_id"
    }
}
