package org.skepsun.kototoro.details.ui.pager.bookmarks.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.core.ui.adaptive.tvChipRing
import org.skepsun.kototoro.core.ui.adaptive.tvIconButtonRing
import org.skepsun.kototoro.core.ui.buttons.IconButton
import org.skepsun.kototoro.core.util.ext.findActivity
import org.skepsun.kototoro.core.util.ext.toExportFileName
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneState
import org.skepsun.kototoro.details.ui.compose.state.rememberDetailsPaneNestedScrollConnection
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesViewModel
import org.skepsun.kototoro.details.ui.pager.bookmarks.BookmarksViewModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.ui.ReaderNavigationCallback
import org.skepsun.kototoro.reader.ui.ReaderState

@Composable
fun BookmarksScreenRoot(
    activityViewModel: ChaptersPagesViewModel,
    router: AppRouter,
    viewModel: BookmarksViewModel,
    detailsPaneState: DetailsPaneState? = null,
) {
    val context = LocalContext.current
    val mangaDetails by activityViewModel.mangaDetails.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(mangaDetails) {
        viewModel.emit(mangaDetails)
    }

    val contentItems by viewModel.content.collectAsStateWithLifecycle(initialValue = emptyList())
    val gridScale by viewModel.gridScale.collectAsStateWithLifecycle(initialValue = 1f)
    val selectedItemIds = remember { mutableStateListOf<Long>() }
    val selectedIds = remember(selectedItemIds.toList()) {
        selectedItemIds.toSet()
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
                val targetState = ReaderState(bookmark.chapterId, bookmark.page, bookmark.scroll)
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
