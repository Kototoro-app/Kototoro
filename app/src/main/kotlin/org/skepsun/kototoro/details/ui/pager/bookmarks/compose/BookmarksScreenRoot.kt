package org.skepsun.kototoro.details.ui.pager.bookmarks.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.core.util.ext.findActivity
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneState
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesViewModel
import org.skepsun.kototoro.details.ui.pager.bookmarks.BookmarksViewModel
import org.skepsun.kototoro.reader.ui.ReaderNavigationCallback

@Composable
fun BookmarksScreenRoot(
    activityViewModel: ChaptersPagesViewModel,
    router: AppRouter,
    viewModel: BookmarksViewModel,
    detailsPaneState: DetailsPaneState? = null,
) {
    val context = LocalContext.current
    val contentItems by viewModel.content.collectAsStateWithLifecycle(initialValue = emptyList())
    val gridScale by viewModel.gridScale.collectAsStateWithLifecycle(initialValue = 1f)
    val selectedItemIds = remember { mutableStateListOf<Long>() }
    val selectedIds = remember(selectedItemIds.toList()) {
        selectedItemIds.toSet()
    }

    val mangaDetails by activityViewModel.mangaDetails.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(mangaDetails) {
        viewModel.emit(mangaDetails)
    }

    BookmarksScreen(
        items = contentItems,
        gridMinSize = (120.dp / gridScale.coerceIn(0.5f, 1.5f)),
        selectedItemIds = selectedIds,
        detailsPaneState = detailsPaneState,
        onItemClick = { item ->
            val bookmark = item as Bookmark
            if (selectedItemIds.isNotEmpty()) {
                if (selectedItemIds.contains(bookmark.pageId)) {
                    selectedItemIds.remove(bookmark.pageId)
                } else {
                    selectedItemIds.add(bookmark.pageId)
                }
            } else {
                val navigationCallback = (context as? ReaderNavigationCallback)
                    ?: (context.findActivity() as? ReaderNavigationCallback)
                if (navigationCallback?.onBookmarkSelected(bookmark) == true) {
                    return@BookmarksScreen
                }
                val targetState = org.skepsun.kototoro.reader.ui.ReaderState(bookmark.chapterId, bookmark.page, bookmark.scroll)
                (activityViewModel as? DetailsViewModel)?.recordDetailsJump(targetState, "detail_bookmark")
                router.openReader(
                    ReaderIntent.Builder(context)
                        .manga(bookmark.manga)
                        .state(targetState)
                        .build(),
                )
            }
        },
        onItemLongClick = { item ->
            val bookmark = item as Bookmark
            if (selectedItemIds.contains(bookmark.pageId)) {
                selectedItemIds.remove(bookmark.pageId)
            } else {
                selectedItemIds.add(bookmark.pageId)
            }
        },
        onSelectionActionClick = { actionId ->
            if (actionId == R.id.action_delete) {
                viewModel.removeBookmarks(selectedIds)
            }
            selectedItemIds.clear()
        },
        onClearSelection = { selectedItemIds.clear() },
    )
}
