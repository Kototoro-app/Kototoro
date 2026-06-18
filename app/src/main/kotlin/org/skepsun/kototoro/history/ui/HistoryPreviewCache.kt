package org.skepsun.kototoro.history.ui

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.core.prefs.ListMode
import org.skepsun.kototoro.explore.data.SourcePreset
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.history.domain.model.ContentWithHistory
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.ui.model.QuickFilter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryPreviewCache @Inject constructor() {

    private val previewFlow = MutableStateFlow<HistoryPreviewSnapshot?>(null)

    fun observe(): StateFlow<HistoryPreviewSnapshot?> = previewFlow.asStateFlow()

    fun update(snapshot: HistoryPreviewSnapshot) {
        previewFlow.value = snapshot
    }

    @VisibleForTesting
    fun clear() {
        previewFlow.value = null
    }
}

data class HistoryPreviewSnapshot(
    val items: List<ContentWithHistory>,
    val listMode: ListMode,
    val sortOrder: ListSortOrder,
    val isGroupingEnabled: Boolean,
    val isIncognito: Boolean,
    val groupTab: BrowseGroupTab,
    val sourceTags: Set<SourceTag>,
    val preset: SourcePreset?,
    val filters: Set<ListFilterOption>,
    val quickFilter: QuickFilter?,
    val isHistoryExcludeNsfw: Boolean,
)
