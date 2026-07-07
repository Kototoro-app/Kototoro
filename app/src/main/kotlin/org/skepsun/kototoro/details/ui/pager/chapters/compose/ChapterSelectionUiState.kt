package org.skepsun.kototoro.details.ui.pager.chapters.compose

data class ChapterSelectionUiState(
    val selectedCount: Int,
    val canSelectAll: Boolean,
    val canDownload: Boolean,
    val canDelete: Boolean,
    val canMarkCurrent: Boolean,
    val canBookmark: Boolean,
    val isBookmarkRemoveAction: Boolean,
    val onClearSelection: () -> Unit,
    val onSelectAll: () -> Unit,
    val onDownload: () -> Unit,
    val onDelete: () -> Unit,
    val onMarkCurrent: () -> Unit,
    val onBookmark: () -> Unit,
)
