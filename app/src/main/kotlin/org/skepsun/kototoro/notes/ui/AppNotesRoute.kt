package org.skepsun.kototoro.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.notes.domain.BookNoteItem
import org.skepsun.kototoro.reader.ui.ReaderState

@Composable
fun AppNotesRoute(
    contentPadding: PaddingValues,
    appRouter: AppRouter,
    modifier: Modifier = Modifier,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = uiState.selectedMangaId != null) {
        viewModel.clearSelectedBook()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (uiState.selectedMangaId == null) {
            NotesOverviewScreen(
                uiState = uiState,
                onBookClick = { mangaId -> viewModel.selectBook(mangaId) },
                onSearchQueryChange = { query -> viewModel.setSearchQuery(query) },
                onToggleNsfwFilter = { viewModel.toggleNsfwFilter() },
                contentPadding = contentPadding,
            )
        } else {
            BookNotesDetailScreen(
                uiState = uiState,
                onBack = { viewModel.clearSelectedBook() },
                onFilterChange = { filter -> viewModel.setFilter(filter) },
                onBookSearchQueryChange = { query -> viewModel.setBookSearchQuery(query) },
                onJumpToReader = { content, item ->
                    when (item) {
                        is BookNoteItem.NovelHighlight -> {
                            val state = ReaderState(chapterId = item.chapterId, page = 0, scroll = 0)
                            appRouter.openReader(
                                manga = content,
                                state = state,
                                markingId = item.id,
                                startOffset = item.startOffset,
                                endOffset = item.endOffset,
                                selectedText = item.text,
                            )
                        }
                        is BookNoteItem.BookmarkEntry -> {
                            val state = ReaderState(chapterId = item.chapterId, page = item.page, scroll = 0)
                            appRouter.openReader(manga = content, state = state)
                        }
                        is BookNoteItem.MangaCropNote -> {
                            val state = ReaderState(chapterId = item.chapterId, page = item.page, scroll = 0)
                            appRouter.openReader(manga = content, state = state)
                        }
                        is BookNoteItem.VideoNote -> Unit // Retained backup data has no playback route.
                    }
                },
                onDeleteNote = { item -> viewModel.deleteNote(item) },
                contentPadding = contentPadding,
            )
        }
    }
}
