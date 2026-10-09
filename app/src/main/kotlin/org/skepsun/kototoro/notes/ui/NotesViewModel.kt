package org.skepsun.kototoro.notes.ui

import org.skepsun.kototoro.list.domain.LibraryTextQuery
import org.skepsun.kototoro.search.domain.LibrarySearchScope
import org.skepsun.kototoro.search.domain.LibrarySearchQueries
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.notes.domain.BookNoteItem
import org.skepsun.kototoro.notes.domain.BookNotesRepository
import org.skepsun.kototoro.notes.domain.BookNotesSummary
import org.skepsun.kototoro.notes.domain.NoteType
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.ui.SpaceBindableViewModel
import org.skepsun.kototoro.space.ui.SpaceBrowseScope
import javax.inject.Inject

data class NotesUiState(
    val summaries: List<BookNotesSummary> = emptyList(),
    val searchQuery: String = "",
    val selectedMangaId: Long? = null,
    val selectedManga: Content? = null,
    val selectedBookNotes: List<BookNoteItem> = emptyList(),
    val selectedFilter: NoteType = NoteType.ALL,
    val bookSearchQuery: String = "",
    val isLoading: Boolean = true,
    val isGlobalNsfwDisabled: Boolean = false,
    val isNsfwFiltered: Boolean = false,
) {
    val totalNotesCount: Int get() = summaries.sumOf { it.totalCount }
    val totalBooksCount: Int get() = summaries.size

    val filteredSummaries: List<BookNotesSummary>
        get() = if (searchQuery.isBlank()) {
            summaries
        } else {
            LibraryTextQuery(searchQuery).let { query -> summaries.filter { query.matches(sequenceOf(it.title)) } }
        }

    val filteredBookNotes: List<BookNoteItem>
        get() {
            var items = selectedBookNotes
            if (selectedFilter != NoteType.ALL) {
                items = items.filter { it.noteType == selectedFilter }
            }
            if (bookSearchQuery.isNotBlank()) {
                items = items.filter { item ->
                    when (item) {
                        is BookNoteItem.NovelHighlight -> {
                            item.text.contains(bookSearchQuery, ignoreCase = true) ||
                                (item.note?.contains(bookSearchQuery, ignoreCase = true) == true)
                        }
                        is BookNoteItem.BookmarkEntry -> {
                            item.chapterTitle.contains(bookSearchQuery, ignoreCase = true) ||
                                (item.imageUrl?.contains(bookSearchQuery, ignoreCase = true) == true)
                        }
                        is BookNoteItem.MangaCropNote -> {
                            (item.note?.contains(bookSearchQuery, ignoreCase = true) == true) ||
                                item.chapterTitle.contains(bookSearchQuery, ignoreCase = true)
                        }
                        is BookNoteItem.VideoNote -> {
                            (item.note?.contains(bookSearchQuery, ignoreCase = true) == true) ||
                                (item.quoteText?.contains(bookSearchQuery, ignoreCase = true) == true) ||
                                item.chapterTitle.contains(bookSearchQuery, ignoreCase = true)
                        }
                    }
                }
            }
            return items
        }

    val notesGroupedByChapter: Map<String, List<BookNoteItem>>
        get() = filteredBookNotes.groupBy { it.chapterTitle }
}

private data class BookSelectionState(
    val mangaId: Long?,
    val manga: Content?,
    val notes: List<BookNoteItem>,
    val filter: NoteType,
    val bookQuery: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: BookNotesRepository,
    private val database: MangaDatabase,
    private val spaceContentPolicy: SpaceContentPolicy,
    private val settings: AppSettings,
    private val librarySearchQueries: LibrarySearchQueries,
    spaceBrowseScope: SpaceBrowseScope,
) : ViewModel(), SpaceBindableViewModel {

    private val spaceBinding = spaceBrowseScope.createBinding(viewModelScope + Dispatchers.Default)

    override fun bindSpace(spaceId: SpaceId?) = spaceBinding.bindSpace(spaceId)

    private val searchQuery = MutableStateFlow(librarySearchQueries.query(LibrarySearchScope.BOOKMARKS).value)

    init {
        // The overlay's "Bookmarks" tab drives the same book search as the inline field.
        viewModelScope.launch {
            librarySearchQueries.query(LibrarySearchScope.BOOKMARKS).collect { query ->
                if (query != searchQuery.value.trim()) {
                    searchQuery.value = query
                }
            }
        }
    }

    private val selectedMangaId = MutableStateFlow<Long?>(null)
    private val selectedFilter = MutableStateFlow(NoteType.ALL)
    private val bookSearchQuery = MutableStateFlow("")
    private val filterNsfw = MutableStateFlow(false)

    val isGlobalNsfwDisabled: StateFlow<Boolean> = settings.observeAsFlow(AppSettings.KEY_BOOKMARKS_EXCLUDE_NSFW) { isBookmarksExcludeNsfw }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.isBookmarksExcludeNsfw)

    fun toggleNsfwFilter() {
        filterNsfw.value = !filterNsfw.value
    }

    private val selectedBookData = selectedMangaId.flatMapLatest { mangaId ->
        if (mangaId == null) {
            flowOf(Pair<Content?, List<BookNoteItem>>(null, emptyList()))
        } else {
            combine(
                repository.observeBookNotes(mangaId),
                flowOf(database.getMangaDao().find(mangaId)?.toContent()),
            ) { notes, manga ->
                Pair(manga, notes)
            }
        }
    }

    private val policyAndFilter = combine(
        spaceBinding.spaceId,
        isGlobalNsfwDisabled,
        filterNsfw,
    ) { spaceId, globalNsfwDisabled, onScreenNsfwFiltered ->
        Triple(spaceId, globalNsfwDisabled, onScreenNsfwFiltered)
    }

    private val bookSelection = combine(
        selectedMangaId,
        selectedBookData,
        selectedFilter,
        bookSearchQuery,
    ) { mangaId, (manga, notes), filter, bookQuery ->
        BookSelectionState(mangaId, manga, notes, filter, bookQuery)
    }

    val uiState: StateFlow<NotesUiState> = combine(
        repository.observeSummaries(),
        policyAndFilter,
        searchQuery,
        bookSelection,
    ) { summaries, (spaceId, globalNsfwDisabled, onScreenNsfwFiltered), query, selection ->
        val shouldFilterNsfw = globalNsfwDisabled || onScreenNsfwFiltered
        val filteredByPolicy = summaries.filter { summary ->
            if (spaceId != null) {
                val allowedTypes = spaceContentPolicy.allowedTypes(spaceId)
                if (allowedTypes.isNotEmpty() && summary.contentType !in allowedTypes) {
                    return@filter false
                }
                val allowedSources = spaceContentPolicy.allowedSourceNames(spaceId)
                if (allowedSources != null && summary.source.isNotBlank() && summary.source !in allowedSources) {
                    return@filter false
                }
            }
            if (shouldFilterNsfw && summary.isNsfw) {
                return@filter false
            }
            true
        }

        NotesUiState(
            summaries = filteredByPolicy,
            searchQuery = query,
            selectedMangaId = selection.mangaId,
            selectedManga = selection.manga,
            selectedBookNotes = selection.notes,
            selectedFilter = selection.filter,
            bookSearchQuery = selection.bookQuery,
            isLoading = false,
            isGlobalNsfwDisabled = globalNsfwDisabled,
            isNsfwFiltered = onScreenNsfwFiltered,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NotesUiState(),
    )

    fun setSearchQuery(query: String) {
        searchQuery.value = query
        librarySearchQueries.set(LibrarySearchScope.BOOKMARKS, query)
    }

    fun selectBook(mangaId: Long) {
        selectedMangaId.value = mangaId
        selectedFilter.value = NoteType.ALL
        bookSearchQuery.value = ""
    }

    fun clearSelectedBook() {
        selectedMangaId.value = null
        bookSearchQuery.value = ""
    }

    fun setFilter(filter: NoteType) {
        selectedFilter.value = filter
    }

    fun setBookSearchQuery(query: String) {
        bookSearchQuery.value = query
    }

    fun deleteNote(item: BookNoteItem) {
        viewModelScope.launch {
            repository.deleteNote(item)
        }
    }

    fun updateThought(id: Long, note: String?) {
        viewModelScope.launch {
            repository.updateThoughtNote(id, note)
        }
    }
}
