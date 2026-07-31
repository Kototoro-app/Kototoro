package org.skepsun.kototoro.video.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.skepsun.kototoro.bookmarks.domain.BookmarksRepository
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.core.nav.ContentIntent
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.domain.DetailsInteractor
import org.skepsun.kototoro.details.domain.DetailsLoadUseCase
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesViewModel
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.DeleteLocalContentUseCase
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.video.data.VideoDownloadIndex
import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.details.ui.model.ChapterListItem.Companion.FLAG_DOWNLOADED
import org.skepsun.kototoro.parsers.model.ContentChapter
import kotlin.experimental.or

@HiltViewModel
class VideoChaptersViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    bookmarksRepository: BookmarksRepository,
    settings: AppSettings,
    @LocalStorageChanges
    localStorageChanges: SharedFlow<LocalContent?>,
    downloadScheduler: DownloadWorker.Scheduler,
    interactor: DetailsInteractor,
    savedStateHandle: SavedStateHandle,
    deleteLocalContentUseCase: DeleteLocalContentUseCase,
    private val detailsLoadUseCase: DetailsLoadUseCase,
    private val videoDownloadIndex: VideoDownloadIndex,
    mangaRepositoryFactory: org.skepsun.kototoro.core.parser.ContentRepository.Factory,
) : ChaptersPagesViewModel(
    settings = settings,
    interactor = interactor,
    bookmarksRepository = bookmarksRepository,
    historyRepository = historyRepository,
    downloadScheduler = downloadScheduler,
    deleteLocalContentUseCase = deleteLocalContentUseCase,
    mangaRepositoryFactory = mangaRepositoryFactory,
    localStorageChanges = localStorageChanges,
) {

    private val intent = ContentIntent(savedStateHandle)
    private var loadingJob: Job? = null
    private val requestedState = savedStateHandle.get<ReaderState>(ReaderIntent.EXTRA_STATE)
    private val observedLocalMangaId = MutableStateFlow(intent.mangaId.takeIf { it != 0L })

    init {
        if (requestedState != null) {
            readingState.value = requestedState
        }

        observedLocalMangaId
            .flatMapLatest { mangaId ->
                if (mangaId == null) {
                    flowOf(null)
                } else {
                    historyRepository.observeOne(mangaId)
                }
            }
            .onEach { h ->
                if (requestedState == null) {
                    val manga = mangaDetails.value?.toContent()
                    readingState.value = h
                        ?.takeIf { history -> manga?.findChapterById(history.chapterId) != null }
                        ?.let(::ReaderState)
                }
            }
            .withErrorHandling()
            .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

        loadingJob = doLoad(false)

        videoDownloadIndex.changes
            .onEach { changedContentId ->
                if (changedContentId == observedLocalMangaId.value) {
                    notifyDownloadChanged()
                }
            }
            .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0L)
    }

    fun reload(force: Boolean = true) {
        loadingJob = doLoad(force)
    }

    fun setCurrentChapter(chapter: ContentChapter) {
        selectedBranch.value = chapter.branch
        readingState.value = ReaderState(chapter.id, 0, 0)
    }

    fun getAllChapters(): List<ContentChapter> = mangaDetails.value?.allChapters.orEmpty()

    private fun doLoad(force: Boolean): Job = launchLoadingJob(Dispatchers.Default) {
        detailsLoadUseCase.invoke(intent, force).collect { details ->
            mangaDetails.value = details
            observedLocalMangaId.value = details.toContent().id
            if (details.allChapters.isNotEmpty()) {
                val manga = details.toContent()
                val hist = historyRepository.getOne(manga)
                selectedBranch.value = requestedState
                    ?.let { state -> manga.findChapterById(state.chapterId)?.branch }
                    ?: manga.getPreferredBranch(hist)
            }
        }
    }

    override suspend fun expandEpubChaptersIfNeeded(chapters: List<ChapterListItem>): List<ChapterListItem> {
        val manga = mangaDetails.value?.toContent() ?: return chapters
        val downloadedIds = videoDownloadIndex.getDownloadedChapterIds(manga.id)
        if (downloadedIds.isEmpty()) return chapters
        val downloadedOnly = isDownloadedOnly.value
        return chapters.mapNotNull { item ->
            val isDownloaded = item.chapter.id in downloadedIds || item.isDownloaded
            if (downloadedOnly && !isDownloaded) {
                return@mapNotNull null
            }
            if (isDownloaded && !item.isDownloaded) {
                item.copy(flags = item.flags or FLAG_DOWNLOADED)
            } else {
                item
            }
        }
    }
}
