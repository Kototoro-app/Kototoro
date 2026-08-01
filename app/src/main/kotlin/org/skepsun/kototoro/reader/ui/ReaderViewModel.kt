package org.skepsun.kototoro.reader.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.bookmarks.domain.BookmarksRepository
import org.skepsun.kototoro.core.exceptions.EmptyContentException
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.nav.ContentIntent
import org.skepsun.kototoro.core.nav.ReaderIntent
import org.skepsun.kototoro.core.os.AppShortcutManager
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ReaderControl
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.ReaderTranslationMode
import org.skepsun.kototoro.core.prefs.ReaderTranslationPipelineMode
import org.skepsun.kototoro.core.prefs.TriStateOption
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.firstNotNull
import org.skepsun.kototoro.core.util.ext.requireValue
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.domain.DetailsInteractor
import org.skepsun.kototoro.details.domain.DetailsLoadUseCase
import org.skepsun.kototoro.details.ui.pager.ChaptersPagesViewModel
import org.skepsun.kototoro.space.domain.awaitCompletion
import org.skepsun.kototoro.details.ui.pager.EmptyContentReason
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.history.domain.HistoryUpdateUseCase
import org.skepsun.kototoro.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.domain.DeleteLocalContentUseCase
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.parsers.util.sizeOrZero
import org.skepsun.kototoro.readingrecord.data.ReadingRecordRepository
import org.skepsun.kototoro.reader.domain.ChaptersLoader
import org.skepsun.kototoro.reader.domain.DetectReaderModeUseCase
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.core.model.getMergeKey
import org.skepsun.kototoro.core.model.mergeRepeated
import org.skepsun.kototoro.core.model.isManga
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.looksLikeLocalVideoContent
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.domain.PageLoader
import org.skepsun.kototoro.reader.domain.ReaderPageEnhancementController
import org.skepsun.kototoro.reader.domain.TranslationLayerState
import org.skepsun.kototoro.reader.translate.domain.normalizeReaderTranslationLanguageTag
import org.skepsun.kototoro.reader.translate.domain.resolveReaderTranslationSourceLanguage
import org.skepsun.kototoro.reader.ui.config.ReaderSettings
import org.skepsun.kototoro.reader.ui.pager.ReaderUiState
import org.skepsun.kototoro.scrobbling.discord.ui.DiscordRpc
import org.skepsun.kototoro.stats.domain.StatsCollector
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import javax.inject.Inject

private const val BOUNDS_PAGE_OFFSET = 2
private const val WEBTOON_CHAPTER_PRELOAD_OFFSET = 10
private const val PREFETCH_LIMIT = 10
private const val LOG_TAG = "ReaderViewModel"
private const val READER_WINDOW_LOG_TAG = "ReaderWindow"

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val dataRepository: ContentDataRepository,
    private val historyRepository: HistoryRepository,
    private val bookmarksRepository: BookmarksRepository,
    settings: AppSettings,
    private val pageLoader: PageLoader,
    private val enhancementController: ReaderPageEnhancementController,
    private val chaptersLoader: ChaptersLoader,
    private val appShortcutManager: AppShortcutManager,
    private val detailsLoadUseCase: DetailsLoadUseCase,
    private val historyUpdateUseCase: HistoryUpdateUseCase,
    private val readingRecordRepository: ReadingRecordRepository,
    private val detectReaderModeUseCase: DetectReaderModeUseCase,
    private val statsCollector: StatsCollector,
    private val discordRpc: DiscordRpc,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalContent?>,
    interactor: DetailsInteractor,
    deleteLocalContentUseCase: DeleteLocalContentUseCase,
    downloadScheduler: DownloadWorker.Scheduler,
    mangaRepositoryFactory: org.skepsun.kototoro.core.parser.ContentRepository.Factory,
    readerSettingsProducerFactory: ReaderSettings.Producer.Factory,
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
    data class TranslationPageTaskSnapshot(
        val pageId: Long,
        val pageIndex: Int,
        val state: TranslationLayerState,
        val updatedAtMs: Long?,
        val log: String,
        val failCode: String?,
    )

    data class ChapterTranslationProgress(
        val chapterId: Long,
        val readyCount: Int,
        val runningCount: Int,
        val failedCount: Int,
        val totalCount: Int,
    ) {
        val processedCount: Int
            get() = readyCount + failedCount

        val hasStarted: Boolean
            get() = readyCount > 0 || runningCount > 0 || failedCount > 0

        val isFinished: Boolean
            get() = hasStarted && runningCount == 0 && processedCount >= totalCount
    }

    private val intent = ContentIntent(savedStateHandle)

    private var loadingJob: Job? = null
    private var pageSaveJob: Job? = null
    private var bookmarkJob: Job? = null
    private var stateChangeJob: Job? = null
    @Volatile
    private var readerWindowGeneration = 0L
    private data class ReaderAdjacentLoadRequest(
        val chapterId: Long,
        val isNext: Boolean,
        val generation: Long,
    )

    private val readerAdjacentLoadLock = Any()
    private val pendingReaderAdjacentLoads = mutableSetOf<ReaderAdjacentLoadRequest>()
    private val completedReaderAdjacentLoads = mutableSetOf<ReaderAdjacentLoadRequest>()

    val readerMode = MutableStateFlow<ReaderMode?>(null)
    val onPageSaved = MutableEventFlow<Collection<Uri>>()
    val onLoadingError = MutableEventFlow<Throwable>()
    val onShowToast = MutableEventFlow<Int>()
    val onAskNsfwIncognito = MutableEventFlow<Unit>()
    val onRedirectToReader = MutableEventFlow<Content>()
    val uiState = MutableStateFlow<ReaderUiState?>(null)
    val targetPagePosition = MutableStateFlow<Int?>(null)
    val translationLayerState = MutableStateFlow(TranslationLayerState.IDLE)
    val chapterTranslationProgress = MutableStateFlow<ChapterTranslationProgress?>(null)
    val translationTaskPanelVersion = MutableStateFlow(0L)
    private val translationStateByPageId = linkedMapOf<Long, TranslationLayerState>()
    private val translationStateUpdatedAtByPageId = linkedMapOf<Long, Long>()
    private val pageReloadNonces = linkedMapOf<Long, Long>()
    private var sessionStartAt: Long = 0L
    private var sessionStartState: ReaderState? = null
    private var sessionStartPercent: Float = PROGRESS_NONE

    val isIncognitoMode = MutableStateFlow(savedStateHandle.get<Boolean>(ReaderIntent.EXTRA_INCOGNITO))

    val isMenuVisible = MutableStateFlow(false)

    val content = MutableStateFlow(ReaderContent(emptyList(), null))

    // 避免切换章节/模式后首次 onCurrentPageChanged 触发边界加载，将其忽略一次
    private val skipBoundaryLoadOnce = AtomicBoolean(false)
    private val suppressTransientCrossChapterUpdates = AtomicBoolean(false)
    @Volatile
    private var transientRestoreAnchorState: ReaderState? = null

    val pageAnimation = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_ANIMATION,
        valueProducer = { readerAnimation },
    )

    val readerControls = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_CONTROLS,
        valueProducer = { readerControls },
    )

    val isInfoBarEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_BAR,
        valueProducer = { isReaderBarEnabled },
    )

    val isInfoBarTransparent = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_BAR_TRANSPARENT,
        valueProducer = { isReaderBarTransparent },
    )

    val isKeepScreenOnEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_SCREEN_ON,
        valueProducer = { isReaderKeepScreenOn },
    )

    val isWebtoonZooEnabled = observeIsWebtoonZoomEnabled()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

    val isWebtoonGapsEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_WEBTOON_GAPS,
        valueProducer = { isWebtoonGapsEnabled },
    )

    val isWebtoonPullGestureEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_WEBTOON_PULL_GESTURE,
        valueProducer = { isWebtoonPullGestureEnabled },
    )


    val defaultWebtoonZoomOut = observeIsWebtoonZoomEnabled().flatMapLatest {
        if (it) {
            observeWebtoonZoomOut()
        } else {
            flowOf(0f)
        }
    }.flowOn(Dispatchers.Default)

    val isZoomControlsEnabled = getObserveIsZoomControlEnabled().flatMapLatest { zoom ->
        if (zoom) {
            combine(readerMode, isWebtoonZooEnabled) { mode, ze -> ze || mode != ReaderMode.WEBTOON }
        } else {
            flowOf(false)
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

    val readerSettingsProducer = readerSettingsProducerFactory.create(
        manga.mapNotNull { it?.id },
    )

    val isContentNsfw = manga.map { it?.contentRating == ContentRating.ADULT }

    val isBookmarkAdded = readingState.flatMapLatest { state ->
        val manga = mangaDetails.value?.toContent()
        if (state == null || manga == null) {
            flowOf(false)
        } else {
            bookmarksRepository.observeBookmark(manga, state.chapterId, state.page)
                .map {
                    it != null && it.chapterId == state.chapterId && it.page == state.page
                }
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    init {
        initIncognitoMode()
        observeTranslationLayerState()
        observeTranslationDebugLogs()
        listenToDoublePageEvents()
        observeMergeRepeatedChapters()
        observeWebtoonBoundaryMode()
        loadImpl()
		launchJob(Dispatchers.Default) {
			val mangaId = manga.filterNotNull().first().id
			if (!isIncognitoMode.firstNotNull()) {
				appShortcutManager.notifyContentOpened(mangaId)
			}
        }
    }


    private val widePageIds = mutableSetOf<Long>()

    private fun listenToDoublePageEvents() {
        launchJob(Dispatchers.Default) {
            pageLoader.widePageDetectedEvent.collect { pageId ->
                if (!settings.isReaderSplitPagesEnabled) return@collect
                if (widePageIds.add(pageId)) {
                    rebuildPages()
                }
            }
        }
    }

    private fun rebuildPages() {
        val currentContent = content.value
        val newPages = getSplitPagesSnapshot()
        if (newPages != currentContent.pages) {
            content.value = currentContent.copy(pages = newPages)
        }
    }

    private fun getSplitPagesSnapshot(
        currentChapterId: Long? = readingState.value?.chapterId,
    ): List<org.skepsun.kototoro.reader.ui.pager.ReaderPage> {
        val originalPages = if (readerMode.value != ReaderMode.WEBTOON && currentChapterId != null) {
            chaptersLoader.snapshotReaderWindow(currentChapterId, BOUNDS_PAGE_OFFSET)
        } else {
            chaptersLoader.snapshot()
        }
        if (!settings.isReaderSplitPagesEnabled) return originalPages.map { it.withReloadNonce() }
        val newPages = mutableListOf<org.skepsun.kototoro.reader.ui.pager.ReaderPage>()
        val mode = readerMode.value
        val isRtl = mode == org.skepsun.kototoro.core.prefs.ReaderMode.REVERSED

        for (page in originalPages) {
            if (widePageIds.contains(page.id)) {
                if (isRtl) {
                    newPages.add(page.copy(split = org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit.RIGHT).withReloadNonce())
                    newPages.add(page.copy(split = org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit.LEFT).withReloadNonce())
                } else {
                    newPages.add(page.copy(split = org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit.LEFT).withReloadNonce())
                    newPages.add(page.copy(split = org.skepsun.kototoro.reader.ui.pager.ReaderPageSplit.RIGHT).withReloadNonce())
                }
            } else {
                newPages.add(page.withReloadNonce())
            }
        }
        return newPages
    }

    private fun org.skepsun.kototoro.reader.ui.pager.ReaderPage.withReloadNonce() = copy(
        reloadNonce = pageReloadNonces[id] ?: 0L,
    )

    private fun markPageForReload(pageId: Long) {
        pageReloadNonces[pageId] = (pageReloadNonces[pageId] ?: 0L) + 1L
    }

    fun reload() {
        loadingJob?.cancel()
        loadImpl()
    }

    fun refreshTranslationDisplay() {
        launchJob(Dispatchers.Default) {
            content.value.pages
                .distinctBy { it.id }
                .forEach { page ->
                    pageLoader.invalidateTask(page.toContentPage())
                    markPageForReload(page.id)
                }
            rebuildPages()
        }
    }

    fun retranslateCurrent() {
        launchJob(Dispatchers.Default) {
            val page = getCurrentPage() ?: return@launchJob
            pageLoader.invalidateTask(page)
            enhancementController.invalidateTranslationTask(page.id)
            enhancementController.invalidateTranslationCacheForPage(page.id)
            markPageForReload(page.id)
            rebuildPages()
            onShowToast.call(R.string.reader_translation_retranslate_started)
        }
    }

    fun retranslateFailedInCurrentChapter() {
        launchJob(Dispatchers.Default) {
            val pages = getCurrentChapterPages().orEmpty()
            if (pages.isEmpty()) return@launchJob
            var retries = 0
            pages.forEach { page ->
                if (translationStateByPageId[page.id] == TranslationLayerState.FAILED) {
                    pageLoader.invalidateTask(page)
                    enhancementController.invalidateTranslationTask(page.id)
                    enhancementController.invalidateTranslationCacheForPage(page.id)
                    markPageForReload(page.id)
                    retries++
                }
            }
            if (retries == 0) {
                onShowToast.call(R.string.reader_translation_retry_failed_none)
                return@launchJob
            }
            rebuildPages()
            onShowToast.call(R.string.reader_translation_retry_failed_started)
        }
    }

    fun retranslateCurrentChapter() {
        launchJob(Dispatchers.Default) {
            val chapterPages = getCurrentChapterPages().orEmpty()
            if (chapterPages.isEmpty()) return@launchJob
            chapterPages.forEach { page ->
                pageLoader.invalidateTask(page)
                enhancementController.invalidateTranslationTask(page.id)
                enhancementController.invalidateTranslationCacheForPage(page.id)
                markPageForReload(page.id)
            }
            rebuildPages()
            onShowToast.call(R.string.reader_translation_retranslate_chapter_started)
        }
    }

    fun retryTranslationForPage(pageId: Long) {
        launchJob(Dispatchers.Default) {
            val page = getCurrentChapterPages().orEmpty().firstOrNull { it.id == pageId }
            if (page != null) {
                pageLoader.invalidateTask(page)
            } else {
                pageLoader.invalidateTask(pageId)
            }
            enhancementController.invalidateTranslationTask(pageId)
            enhancementController.invalidateTranslationCacheForPage(pageId)
            markPageForReload(pageId)
            val currentPageId = getCurrentPage()?.id
            if (currentPageId == pageId) {
                rebuildPages()
            }
        }
    }

    fun getCurrentChapterTranslationTaskSnapshots(): List<TranslationPageTaskSnapshot> {
        val pages = getCurrentChapterPages().orEmpty()
        return pages.mapIndexed { index, page ->
            val log = enhancementController.getTranslationDebugLog(page.id)
            TranslationPageTaskSnapshot(
                pageId = page.id,
                pageIndex = index,
                state = translationStateByPageId[page.id] ?: TranslationLayerState.IDLE,
                updatedAtMs = translationStateUpdatedAtByPageId[page.id],
                log = log,
                failCode = Regex("""fail_code=([A-Z_]+)""")
                    .findAll(log)
                    .lastOrNull()
                    ?.groupValues
                    ?.getOrNull(1),
            )
        }
    }

    fun isTranslationBypassedForCurrentContent(): Boolean {
        val sourceLang = resolveCurrentTranslationSourceLanguage()
        if (sourceLang.isBlank()) return false
        val targetLang = settings.readerTranslationTargetLanguage
            .normalizeReaderTranslationLanguageTag()
            .orEmpty()
        return sourceLang == targetLang
    }

    fun getTranslationBypassHint(context: Context): String? {
        if (!isTranslationBypassedForCurrentContent()) return null
        val targetLang = settings.readerTranslationTargetLanguage
        return context.getString(R.string.reader_translation_bypass_hint, targetLang)
    }

    fun shouldShowTranslationToggle(): Boolean {
        return !isTranslationBypassedForCurrentContent()
    }

    fun hasTranslationEngineConfigured(): Boolean {
        return when (settings.readerTranslationPipelineMode) {
            ReaderTranslationPipelineMode.END_TO_END_API -> settings.readerE2eApiEndpoint.isNotBlank()
            ReaderTranslationPipelineMode.TWO_STAGE -> when (settings.readerTranslationMode) {
                ReaderTranslationMode.API_ONLY -> settings.readerTranslationApiEndpoint.isNotBlank()
                ReaderTranslationMode.LOCAL_ONLY,
                ReaderTranslationMode.LOCAL_FIRST -> true
            }
        }
    }

    private fun resolveCurrentTranslationSourceLanguage(): String {
        return resolveReaderTranslationSourceLanguage(
            preferredLanguage = settings.readerTranslationSourceLanguage,
            contentLanguage = getContentOrNull()?.source?.getLocale()?.language,
        )
    }

    fun onPause() {
        finishReadingSession()
        getContentOrNull()?.let {
            statsCollector.onPause(it.id)
        }
    }

    fun onStop() {
        discordRpc.clearRpc()
    }

    fun onIdle() {
        discordRpc.setIdle()
    }

    fun switchMode(newMode: ReaderMode) {
        launchJob {
            val manga = checkNotNull(getContentOrNull())
            dataRepository.saveReaderMode(
                manga = manga,
                mode = newMode,
            )
            readerMode.value = newMode
            content.update {
                it.copy(pages = getSplitPagesSnapshot(), state = getCurrentState())
            }
        }
    }

    fun saveCurrentState(state: ReaderState? = null) {
        if (state != null) {
            Log.d(
                LOG_TAG,
                "saveCurrentState: incoming=$state, previous=${readingState.value}",
            )
            readingState.value = state
            savedStateHandle[ReaderIntent.EXTRA_STATE] = state
        }
        if (isIncognitoMode.value != false) {
            return
        }
        val readerState = state ?: readingState.value ?: return
        ensureReadingSession(readerState)
        historyUpdateUseCase.invokeAsync(
            manga = getContentOrNull() ?: return,
            readerState = readerState,
            percent = computePercent(readerState.chapterId, readerState.page),
        )
    }

    suspend fun flushForSpaceSwitch(state: ReaderState?) {
        if (state != null) {
            readingState.value = state
            savedStateHandle[ReaderIntent.EXTRA_STATE] = state
        }
        if (isIncognitoMode.value != false) return
        val readerState = state ?: readingState.value ?: return
        val content = getContentOrNull() ?: return
        ensureReadingSession(readerState)
        historyUpdateUseCase(
            manga = content,
            readerState = readerState,
            percent = computePercent(readerState.chapterId, readerState.page),
        )
        finishReadingSession(allowShort = true, continueFromEnd = false)?.awaitCompletion()
    }

    fun getCurrentState() = readingState.value

    fun getCurrentChapterPages(): List<ContentPage>? {
        val chapterId = readingState.value?.chapterId ?: return null
        return chaptersLoader.getPages(chapterId)
    }

    fun skipBoundaryLoadNext() {
        skipBoundaryLoadOnce.set(true)
        suppressTransientCrossChapterUpdates.set(true)
    }

    fun beginTransientStateSuppression(anchorState: ReaderState?) {
        transientRestoreAnchorState = anchorState
        suppressTransientCrossChapterUpdates.set(anchorState != null)
        Log.d(
            LOG_TAG,
            "beginTransientStateSuppression: anchorState=$anchorState",
        )
    }

    fun clearTransientCrossChapterSuppression() {
        suppressTransientCrossChapterUpdates.set(false)
        transientRestoreAnchorState = null
        Log.d(LOG_TAG, "clearTransientCrossChapterSuppression")
    }

    fun saveCurrentPage(
        pageSaveHelper: PageSaveHelper
    ) {
        val prevJob = pageSaveJob
        pageSaveJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            val state = checkNotNull(getCurrentState())
            val targetPage = targetPagePosition.value ?: state.page
            val currentContent = manga.requireValue()
            val pages = content.value.pages
            val page = pages.find { it.chapterId == state.chapterId && it.index == targetPage }
                ?: pages.find { it.chapterId == state.chapterId && it.index == state.page }
                ?: throw IllegalStateException("Cannot find current page")

            val task = PageSaveHelper.Task(
                manga = currentContent,
                chapterId = state.chapterId,
                pageNumber = targetPage + 1,
                page = page.toContentPage(),
            )
            val dest = pageSaveHelper.save(setOf(task))
            onPageSaved.call(dest)
        }
    }

    fun getCurrentPage(): ContentPage? {
        val state = readingState.value ?: return null
        return content.value.pages.find {
            it.chapterId == state.chapterId && it.index == state.page
        }?.toContentPage()
    }

    fun switchChapter(id: Long, page: Int) {
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            Log.d(
                LOG_TAG,
                "switchChapter: targetChapterId=$id, targetPage=$page, previousState=${readingState.value}",
            )
            content.value = ReaderContent(emptyList(), null)
            readerWindowGeneration++
            chaptersLoader.loadSingleChapter(id)
            val newState = ReaderState(id, page, 0)
            content.value = ReaderContent(getSplitPagesSnapshot(id), newState)
            Log.d(
                LOG_TAG,
                "switchChapter: loaded targetChapterId=$id, pages=${content.value.pages.size}, newState=$newState",
            )
            saveCurrentState(newState)
        }
    }

    fun switchChapterBy(delta: Int) {
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            val prevState = readingState.requireValue()
            val newChapterId = if (delta != 0) {
                val readingChapters = getReadingChapters()
                var index = readingChapters.indexOfFirst { x -> x.id == prevState.chapterId }
                if (index < 0 && isMergeRepeatedChapters.value) {
                    val currentChapter = chaptersLoader.peekChapter(prevState.chapterId)
                    if (currentChapter != null) {
                        val currentKey = currentChapter.getMergeKey()
                        index = readingChapters.indexOfFirst { it.getMergeKey() == currentKey }
                    }
                }
                if (index < 0) {
                    return@launchLoadingJob
                }
                index += delta
                (readingChapters.getOrNull(index) ?: return@launchLoadingJob).id
            } else {
                prevState.chapterId
            }
            Log.d(
                LOG_TAG,
                "switchChapterBy: delta=$delta, prevState=$prevState, newChapterId=$newChapterId",
            )
            content.value = ReaderContent(emptyList(), null)
            readerWindowGeneration++
            chaptersLoader.loadSingleChapter(newChapterId)
            val newState = ReaderState(
                chapterId = newChapterId,
                page = if (delta == 0) prevState.page else 0,
                scroll = if (delta == 0) prevState.scroll else 0,
            )
            skipBoundaryLoadOnce.set(true)
            content.value = ReaderContent(getSplitPagesSnapshot(newChapterId), newState)
            Log.d(
                LOG_TAG,
                "switchChapterBy: applied newState=$newState, pages=${content.value.pages.size}",
            )
            saveCurrentState(newState)
        }
    }

    @MainThread
    fun onCurrentPageChanged(lowerPos: Int, upperPos: Int) {
        onCurrentPageChanged(content.value.pages, lowerPos, upperPos, selectedPageKey = null)
    }

    @MainThread
    fun onWebtoonPageChanged(lowerPageKey: Long, upperPageKey: Long, activePageKey: Long) {
        val pages = content.value.pages
        val lowerPos = pages.indexOfFirst { it.readerKey == lowerPageKey }
        val upperPos = pages.indexOfFirst { it.readerKey == upperPageKey }
        val activePage = pages.firstOrNull { it.readerKey == activePageKey }
        if (lowerPos < 0 || upperPos < lowerPos || activePage == null) {
            Log.d(
                READER_WINDOW_LOG_TAG,
                "drop stale viewport lowerKey=$lowerPageKey upperKey=$upperPageKey activeKey=$activePageKey " +
                    "windowSize=${pages.size} generation=$readerWindowGeneration",
            )
            return
        }
        val continuousWebtoon = readerMode.value == ReaderMode.WEBTOON && !isWebtoonPullGestureEnabled.value
        if (continuousWebtoon && suppressTransientCrossChapterUpdates.get()) {
            Log.d(
                READER_WINDOW_LOG_TAG,
                "clear legacy suppression for stable viewport anchor=$transientRestoreAnchorState " +
                    "active=${activePage.chapterId}:${activePage.index}",
            )
            clearTransientCrossChapterSuppression()
        }
        onCurrentPageChanged(pages, lowerPos, upperPos, selectedPageKey = activePageKey)
    }

    private fun onCurrentPageChanged(
        pages: List<org.skepsun.kototoro.reader.ui.pager.ReaderPage>,
        lowerPos: Int,
        upperPos: Int,
        selectedPageKey: Long?,
    ) {
        val prevJob = stateChangeJob
        targetPagePosition.value = null
        stateChangeJob = launchJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            val continuousWebtoon = readerMode.value == ReaderMode.WEBTOON && !isWebtoonPullGestureEnabled.value
            if (!continuousWebtoon) {
                loadingJob?.join()
                ensureActive()
                if (pages !== content.value.pages) {
                    Log.d(
                        READER_WINDOW_LOG_TAG,
                        "drop stale paged callback captured=${pages.windowSummary()} " +
                            "current=${content.value.pages.windowSummary()} generation=$readerWindowGeneration",
                    )
                    return@launchJob
                }
            }
            val selectedPos = if (selectedPageKey != null) {
                resolveWebtoonVisiblePageSelection(
                    pages = pages,
                    lowerPos = lowerPos,
                    upperPos = upperPos,
                    currentChapterId = readingState.value?.chapterId,
                    activePageKey = selectedPageKey,
                    boundsPageOffset = BOUNDS_PAGE_OFFSET,
                )
            } else {
                resolveVisiblePageSelection(
                    pages = pages,
                    lowerPos = lowerPos,
                    upperPos = upperPos,
                    currentChapterId = readingState.value?.chapterId,
                    boundsPageOffset = BOUNDS_PAGE_OFFSET,
                )
            }
            val selectedPage = pages.getOrNull(selectedPos)
            if (selectedPageKey == null) {
                Log.d(
                    LOG_TAG,
                    "onCurrentPageChanged: lower=$lowerPos, upper=$upperPos, selected=$selectedPos, " +
                        "selectedPage=${selectedPage?.chapterId}:${selectedPage?.index}, " +
                        "currentState=${readingState.value}, pages=${pages.size}, " +
                        "skipBoundary=${skipBoundaryLoadOnce.get()}, anchorState=$transientRestoreAnchorState, " +
                        "suppressTransientCrossChapter=${suppressTransientCrossChapterUpdates.get()}",
                )
            }
            val currentState = readingState.value
            val anchorState = transientRestoreAnchorState ?: currentState
            if (
                suppressTransientCrossChapterUpdates.get() &&
                anchorState != null &&
                selectedPage != null &&
                pages.any { it.chapterId == anchorState.chapterId && it.index == anchorState.page } &&
                (
                    selectedPage.chapterId != anchorState.chapterId ||
                        kotlin.math.abs(selectedPage.index - anchorState.page) > 1
                    )
            ) {
                Log.d(
                    LOG_TAG,
                    "onCurrentPageChanged: ignore transient restore update " +
                        "selected=${selectedPage.chapterId}:${selectedPage.index}, " +
                        "anchor=${anchorState.chapterId}:${anchorState.page}",
                )
                return@launchJob
            }
            val promotedChapter = selectedPage?.chapterId?.takeIf { activeChapterId ->
                currentState != null &&
                    activeChapterId != currentState.chapterId &&
                    (continuousWebtoon || readerMode.value != ReaderMode.WEBTOON)
            }
            selectedPage?.let { page ->
                readingState.update { cs ->
                    cs?.copy(chapterId = page.chapterId, page = page.index)
                }
                updateTranslationStateForCurrentPage(page.id)
                if (
                    suppressTransientCrossChapterUpdates.get() &&
                    anchorState != null &&
                    page.chapterId == anchorState.chapterId &&
                    kotlin.math.abs(page.index - anchorState.page) <= 1
                ) {
                    clearTransientCrossChapterSuppression()
                }
            }
            notifyStateChanged()
            if (promotedChapter != null) {
                readerWindowGeneration++
                val promotedPages = getSplitPagesSnapshot()
                content.value = ReaderContent(promotedPages, null)
                val promotedForward = pages.indexOfFirst { it.chapterId == promotedChapter } >
                    pages.indexOfFirst { it.chapterId == currentState?.chapterId }
                Log.d(
                    READER_WINDOW_LOG_TAG,
                    "promote chapter=$promotedChapter forward=$promotedForward generation=$readerWindowGeneration " +
                        "window=${promotedPages.windowSummary()}",
                )
                return@launchJob
            }
            loadingJob?.join()
            if (pages !== content.value.pages) return@launchJob
            if (pages.isEmpty() || loadingJob?.isActive == true) {
                return@launchJob
            }
            if (skipBoundaryLoadOnce.getAndSet(false) && !continuousWebtoon) {
                return@launchJob
            }
            ensureActive()
            val autoLoadAllowed = readerMode.value != ReaderMode.WEBTOON || !isWebtoonPullGestureEnabled.value
            if (autoLoadAllowed) {
                val currentChapterId = readingState.value?.chapterId
                val chapterStart = pages.indexOfFirst { it.chapterId == currentChapterId }
                val chapterEnd = pages.indexOfLast { it.chapterId == currentChapterId }
                val chapterPreloadOffset = if (continuousWebtoon) {
                    WEBTOON_CHAPTER_PRELOAD_OFFSET
                } else {
                    BOUNDS_PAGE_OFFSET
                }
                if (chapterEnd >= 0 && upperPos >= chapterEnd - chapterPreloadOffset) {
                    if (currentChapterId != null) {
                        loadReaderAdjacentChapter(currentChapterId, isNext = true)
                    }
                }
                if (chapterStart >= 0 && lowerPos <= chapterStart + chapterPreloadOffset) {
                    if (currentChapterId != null) {
                        loadReaderAdjacentChapter(currentChapterId, isNext = false)
                    }
                }
            }
            if (pageLoader.isPrefetchApplicable()) {
                pageLoader.prefetch(pages.trySublist(upperPos + 1, upperPos + PREFETCH_LIMIT))
            }
        }
    }

    fun toggleBookmark() {
        if (bookmarkJob?.isActive == true) {
            return
        }
        bookmarkJob = launchJob(Dispatchers.Default) {
            loadingJob?.join()
            val state = checkNotNull(getCurrentState())
            if (isBookmarkAdded.value) {
                val manga = requireContent()
                bookmarksRepository.removeBookmark(manga.id, state.chapterId, state.page)
                onShowToast.call(R.string.bookmark_removed)
            } else {
                val page = checkNotNull(getCurrentPage()) { "Page not found" }
                val bookmark = Bookmark(
                    manga = requireContent(),
                    pageId = page.id,
                    chapterId = state.chapterId,
                    page = state.page,
                    scroll = state.scroll,
                    imageUrl = page.preview.ifNullOrEmpty { page.url },
                    createdAt = Instant.now(),
                    percent = computePercent(state.chapterId, state.page),
                )
                bookmarksRepository.addBookmark(bookmark)
                onShowToast.call(R.string.bookmark_added)
            }
        }
    }

    fun downloadCurrentChapter() {
        val chapterId = readingState.value?.chapterId ?: return
        download(chapterId, isMeteredNetworkAllowed = true)
    }

    fun setTargetPageBySide(rawX: Float, width: Int, isDoublePage: Boolean) {
        val mode = readerMode.value ?: return
        if (isDoublePage && width > 0) {
            val state = readingState.value ?: return
            val isRtl = mode == ReaderMode.REVERSED
            val isRightSide = rawX > width / 2f

            // In LTR: left is page, right is page + 1
            // In RTL: right is page, left is page + 1
            val isSecondPage = if (isRtl) !isRightSide else isRightSide
            targetPagePosition.value = if (isSecondPage) state.page + 1 else state.page
        } else {
            targetPagePosition.value = null
        }
    }

    fun setIncognitoMode(value: Boolean, dontAskAgain: Boolean) {
        isIncognitoMode.value = value
        if (dontAskAgain) {
            settings.incognitoModeForNsfw = if (value) TriStateOption.ENABLED else TriStateOption.DISABLED
        }
    }

    private fun loadImpl() {
        loadingJob = launchLoadingJob(Dispatchers.Default + EventExceptionHandler(onLoadingError)) {
            var exception: Exception? = null
            var loadedDetails: ContentDetails? = null
            try {
                detailsLoadUseCase(intent, force = false)
                    .collect { details ->
                        loadedDetails = details
                        val translatedLanguage = savedStateHandle[ReaderIntent.EXTRA_TRANSLATED_LANGUAGE]
                            ?: details.toContent().source.locale
                        val sourceLanguage: String? = savedStateHandle[ReaderIntent.EXTRA_SOURCE_LANGUAGE]
                        pageLoader.setTranslationLanguageContext(
                            translatedLanguage = translatedLanguage,
                            sourceLanguage = sourceLanguage,
                            branch = selectedBranch.value ?: savedStateHandle[ReaderIntent.EXTRA_BRANCH],
                        )
                        if (mangaDetails.value == null) {
                            mangaDetails.value = details
                        }
                        chaptersLoader.init(details)
                        val manga = details.toContent()
                        val contentType = if (manga.looksLikeLocalVideoContent()) {
                            ContentType.VIDEO
                        } else {
                            manga.source.getContentType()
                        }
                        if (contentType == ContentType.NOVEL || contentType == ContentType.HENTAI_NOVEL ||
                            contentType == ContentType.VIDEO || contentType == ContentType.HENTAI_VIDEO
                        ) {
                            onRedirectToReader.call(manga)
                            return@collect
                        }
                        // obtain state
                        if (readingState.value == null) {
                            val newState = getStateFromIntent(manga)
                            if (newState == null) {
                                return@collect // manga not loaded yet if cannot get state
                            }
                            readingState.value = newState
                            val mode = runCatchingCancellable {
                                detectReaderModeUseCase(manga, newState)
                            }.getOrDefault(settings.defaultReaderMode)
                            val branch = chaptersLoader.peekChapter(newState.chapterId)?.branch
                            selectedBranch.value = branch
                            pageLoader.setTranslationLanguageContext(
                                translatedLanguage = translatedLanguage,
                                sourceLanguage = sourceLanguage,
                                branch = branch,
                            )
                            readerMode.value = mode
                            try {
                                chaptersLoader.loadSingleChapter(newState.chapterId)
                            } catch (e: Exception) {
                                readingState.value = null // try next time
                                exception = e.mergeWith(exception)
                                return@collect
                            }
                        } else {
                            val state = readingState.value!!
                            if (chaptersLoader.isChapterLocal(state.chapterId)) {
                                val loadedPages = chaptersLoader.getPages(state.chapterId)
                                val hasRemotePages = loadedPages.any {
                                    val uri = android.net.Uri.parse(it.url)
                                    val scheme = uri.scheme
                                    scheme != "file" && scheme != "zip" && scheme != "file+zip" &&
                                    scheme != "content" && scheme != "epub" && scheme != "localepub"
                                }
                                if (hasRemotePages) {
                                    android.util.Log.d("ReaderViewModel", "Reloading chapter ${state.chapterId} pages from local storage")
                                    try {
                                        chaptersLoader.loadSingleChapter(state.chapterId)
                                    } catch (e: Exception) {
                                        exception = e.mergeWith(exception)
                                    }
                                }
                            }
                        }
                        mangaDetails.value = details

                        // save state
                        if (isIncognitoMode.value == false) {
                            readingState.value?.let {
                                val percent = computePercent(it.chapterId, it.page)
                                historyUpdateUseCase(manga, it, percent)
                            }
                        }
                        notifyStateChanged()
                        skipBoundaryLoadOnce.set(true)
                        content.value = ReaderContent(getSplitPagesSnapshot(), readingState.value)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                exception = e.mergeWith(exception)
            }
            if (readingState.value == null) {
                val loadedContent = loadedDetails // for smart cast
                if (loadedContent != null) {
                    mangaDetails.value = loadedContent
                }
                val loadingError = when {
                    exception != null -> exception
                    loadedContent == null || !loadedContent.isLoaded -> null
                    loadedContent.isRestricted -> EmptyContentException(
                        EmptyContentReason.RESTRICTED,
                        loadedContent.toContent(),
                        null,
                    )

                    loadedContent.allChapters.isEmpty() -> EmptyContentException(
                        EmptyContentReason.NO_CHAPTERS,
                        loadedContent.toContent(),
                        null,
                    )

                    else -> null
                } ?: IllegalStateException("Unable to load manga. This should never happen. Please report")
                onLoadingError.call(loadingError)
            } else exception?.let { e ->
                // manga has been loaded but error occurred
                errorEvent.call(e)
            }
        }
    }

    @AnyThread
    private fun loadReaderAdjacentChapter(currentId: Long, isNext: Boolean) {
        val generation = readerWindowGeneration
        val request = ReaderAdjacentLoadRequest(currentId, isNext, generation)
        synchronized(readerAdjacentLoadLock) {
            if (request in completedReaderAdjacentLoads || !pendingReaderAdjacentLoads.add(request)) {
                return
            }
        }
        val prevJob = loadingJob
        val job = launchLoadingJob(Dispatchers.Default) {
            prevJob?.join()
            if (generation != readerWindowGeneration || readingState.value?.chapterId != currentId) {
                Log.d(
                    READER_WINDOW_LOG_TAG,
                    "skip adjacent load chapter=$currentId next=$isNext generation=$generation " +
                        "currentGeneration=$readerWindowGeneration state=${readingState.value}",
                )
                return@launchLoadingJob
            }
            Log.d(READER_WINDOW_LOG_TAG, "load adjacent chapter=$currentId next=$isNext generation=$generation")
            val loaded = chaptersLoader.loadReaderAdjacentChapter(mangaDetails.requireValue(), currentId, isNext)
            if (generation != readerWindowGeneration || readingState.value?.chapterId != currentId) {
                Log.d(
                    READER_WINDOW_LOG_TAG,
                    "drop adjacent result chapter=$currentId next=$isNext loaded=$loaded generation=$generation " +
                        "currentGeneration=$readerWindowGeneration state=${readingState.value}",
                )
                return@launchLoadingJob
            }
            val updatedPages = getSplitPagesSnapshot()
            if (updatedPages != content.value.pages) {
                content.value = ReaderContent(updatedPages, null)
                Log.d(
                    READER_WINDOW_LOG_TAG,
                    "publish adjacent chapter=$currentId next=$isNext loaded=$loaded generation=$generation " +
                        "window=${updatedPages.windowSummary()}",
                )
            } else {
                Log.d(
                    READER_WINDOW_LOG_TAG,
                    "keep adjacent window chapter=$currentId next=$isNext generation=$generation unchanged",
                )
            }
        }
        job.invokeOnCompletion { cause ->
            synchronized(readerAdjacentLoadLock) {
                pendingReaderAdjacentLoads.remove(request)
                if (cause == null) {
                    completedReaderAdjacentLoads.add(request)
                }
            }
        }
        loadingJob = job
    }

    private fun List<org.skepsun.kototoro.reader.ui.pager.ReaderPage>.windowSummary(): String {
        return groupBy { it.chapterId }.entries.joinToString(prefix = "[", postfix = "]") { (chapterId, pages) ->
            "$chapterId:${pages.first().index}-${pages.last().index}(${pages.size})"
        }
    }

    private fun <T> List<T>.trySublist(fromIndex: Int, toIndex: Int): List<T> {
        val fromIndexBounded = fromIndex.coerceAtMost(lastIndex)
        val toIndexBounded = toIndex.coerceIn(fromIndexBounded, lastIndex)
        return if (fromIndexBounded == toIndexBounded) {
            emptyList()
        } else {
            subList(fromIndexBounded, toIndexBounded)
        }
    }

    private fun observeTranslationLayerState() {
        launchJob(Dispatchers.Default) {
            enhancementController.observeTranslationStatusUpdates().collect { event ->
                translationStateByPageId[event.pageId] = event.state
                translationStateUpdatedAtByPageId[event.pageId] = System.currentTimeMillis()
                translationTaskPanelVersion.update { it + 1 }
                updateCurrentChapterTranslationProgress()
                if (event.state == TranslationLayerState.READY) {
                    markPageForReload(event.pageId)
                    rebuildPages()
                }
                val currentPageId = getCurrentPage()?.id
                if (currentPageId == event.pageId) {
                    translationLayerState.value = event.state
                }
            }
        }
    }

    private fun observeTranslationDebugLogs() {
        launchJob(Dispatchers.Default) {
            enhancementController.observeTranslationDebugLogUpdates().collect {
                translationTaskPanelVersion.update { it + 1 }
            }
        }
    }

    private fun updateTranslationStateForCurrentPage(pageId: Long) {
        translationLayerState.value = translationStateByPageId[pageId] ?: TranslationLayerState.IDLE
    }

    private fun updateCurrentChapterTranslationProgress() {
        val chapterId = getCurrentState()?.chapterId ?: run {
            chapterTranslationProgress.value = null
            return
        }
        val pages = chaptersLoader.getPages(chapterId).orEmpty()
        if (pages.isEmpty()) {
            chapterTranslationProgress.value = null
            return
        }
        var readyCount = 0
        var runningCount = 0
        var failedCount = 0
        for (page in pages) {
            when (translationStateByPageId[page.id] ?: TranslationLayerState.IDLE) {
                TranslationLayerState.READY -> readyCount++
                TranslationLayerState.GENERATING -> runningCount++
                TranslationLayerState.FAILED -> failedCount++
                TranslationLayerState.IDLE -> Unit
            }
        }
        val progress = ChapterTranslationProgress(
            chapterId = chapterId,
            readyCount = readyCount,
            runningCount = runningCount,
            failedCount = failedCount,
            totalCount = pages.size,
        )
        chapterTranslationProgress.value = progress.takeIf { it.hasStarted }
    }

    @WorkerThread
    private fun notifyStateChanged() {
        val state = getCurrentState() ?: return
        val chapter = chaptersLoader.peekChapter(state.chapterId) ?: return
        val m = mangaDetails.value ?: return
        val readingChapters = getReadingChapters()
        var chapterIndex = readingChapters.indexOfFirst { it.id == chapter.id }
        if (chapterIndex < 0 && isMergeRepeatedChapters.value) {
            val currentKey = chapter.getMergeKey()
            chapterIndex = readingChapters.indexOfFirst { it.getMergeKey() == currentKey }
        }
        val newState = ReaderUiState(
            mangaName = m.toContent().title,
            chapter = chapter,
            chapterIndex = chapterIndex,
            chaptersTotal = readingChapters.size,
            totalPages = chaptersLoader.getPagesCount(chapter.id),
            currentPage = state.page,
            percent = computePercent(state.chapterId, state.page),
            incognito = isIncognitoMode.value == true,
        )
        uiState.value = newState
        if (isIncognitoMode.value == false) {
            statsCollector.onStateChanged(m.id, state)
            discordRpc.updateRpc(m.toContent(), newState)
        }
        updateCurrentChapterTranslationProgress()
    }

    private fun computePercent(chapterId: Long, pageIndex: Int): Float {
        val readingChapters = getReadingChapters()
        val chaptersCount = readingChapters.size
        var chapterIndex = readingChapters.indexOfFirst { x -> x.id == chapterId }
        if (chapterIndex < 0 && isMergeRepeatedChapters.value) {
            val currentChapter = chaptersLoader.peekChapter(chapterId)
            if (currentChapter != null) {
                val currentKey = currentChapter.getMergeKey()
                chapterIndex = readingChapters.indexOfFirst { it.getMergeKey() == currentKey }
            }
        }
        val pagesCount = chaptersLoader.getPagesCount(chapterId)
        if (chaptersCount == 0 || pagesCount == 0 || chapterIndex == -1) {
            return PROGRESS_NONE
        }
        val pagePercent = (pageIndex + 1) / pagesCount.toFloat()
        val ppc = 1f / chaptersCount
        return ppc * chapterIndex + ppc * pagePercent
    }

    fun recordExplicitJump(toState: ReaderState, source: String) {
        val fromState = readingState.value ?: return
        finishReadingSession(allowShort = true, continueFromEnd = false)
        recordJumpPointIfNeeded(fromState, toState, source, force = true)
    }

    private fun ensureReadingSession(state: ReaderState) {
        if (sessionStartState != null) return
        sessionStartAt = System.currentTimeMillis()
        sessionStartState = state
        sessionStartPercent = computePercent(state.chapterId, state.page)
    }

    private fun finishReadingSession(
        allowShort: Boolean = false,
        continueFromEnd: Boolean = true,
    ): kotlinx.coroutines.Job? {
        if (isIncognitoMode.value != false) return null
        val manga = getContentOrNull() ?: return null
        val startState = sessionStartState ?: readingState.value ?: return null
        val endState = readingState.value ?: startState
        val startAt = sessionStartAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val endAt = System.currentTimeMillis()
        val startPercent = sessionStartPercent
        val endPercent = computePercent(endState.chapterId, endState.page)
        if (continueFromEnd) {
            sessionStartAt = endAt
            sessionStartState = endState
            sessionStartPercent = endPercent
        } else {
            sessionStartAt = 0L
            sessionStartState = null
            sessionStartPercent = PROGRESS_NONE
        }
        return launchJob(Dispatchers.Default) {
            readingRecordRepository.recordSession(
                manga = manga,
                startAt = startAt,
                endAt = endAt,
                startState = startState,
                startPercent = startPercent,
                endState = endState,
                endPercent = endPercent,
                allowShort = allowShort,
            )
        }
    }

    private fun recordJumpPointIfNeeded(
        fromState: ReaderState?,
        toState: ReaderState,
        source: String,
        force: Boolean = false,
    ) {
        if (isIncognitoMode.value != false) return
        val manga = getContentOrNull() ?: return
        val from = fromState ?: return
        if (from == toState || (!force && !isExplicitJump(from, toState))) return
        launchJob(Dispatchers.Default) {
            readingRecordRepository.recordJumpPoint(
                manga = manga,
                fromState = from,
                fromPercent = computePercent(from.chapterId, from.page),
                toState = toState,
                toPercent = computePercent(toState.chapterId, toState.page),
                source = source,
            )
        }
    }

    private fun isExplicitJump(fromState: ReaderState, toState: ReaderState): Boolean {
        if (fromState.chapterId == toState.chapterId) {
            return kotlin.math.abs(fromState.page - toState.page) > 1
        }
        val chapters = mangaDetails.value?.allChapters.orEmpty()
        val fromIndex = chapters.indexOfFirst { it.id == fromState.chapterId }
        val toIndex = chapters.indexOfFirst { it.id == toState.chapterId }
        if (fromIndex < 0 || toIndex < 0) return true
        return kotlin.math.abs(fromIndex - toIndex) > 1
    }

    private fun observeIsWebtoonZoomEnabled() = settings.observeAsFlow(
        key = AppSettings.KEY_WEBTOON_ZOOM,
        valueProducer = { isWebtoonZoomEnabled },
    )

    private fun observeWebtoonZoomOut() = settings.observeAsFlow(
        key = AppSettings.KEY_WEBTOON_ZOOM_OUT,
        valueProducer = { defaultWebtoonZoomOut },
    )

    private fun getObserveIsZoomControlEnabled() = settings.observeAsFlow(
        key = AppSettings.KEY_READER_ZOOM_BUTTONS,
        valueProducer = { isReaderZoomButtonsEnabled },
    )

    private fun initIncognitoMode() {
        if (isIncognitoMode.value != null) {
            return
        }
        launchJob(Dispatchers.Default) {
            try {
                interactor.observeIncognitoMode(manga)
                    .collect {
                        when (it) {
                            TriStateOption.ENABLED -> isIncognitoMode.value = true
                            TriStateOption.ASK -> {
                                onAskNsfwIncognito.call(Unit)
                                return@collect
                            }

                            TriStateOption.DISABLED -> isIncognitoMode.value = false
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "initIncognitoMode failed", e)
                if (isIncognitoMode.value == null) {
                    isIncognitoMode.value = false
                }
            }
        }
    }

    private suspend fun getStateFromIntent(manga: Content): ReaderState? {
        // check if we have at least some chapters loaded
        if (manga.chapters.isNullOrEmpty()) {
            return null
        }
        // specific state is requested
        val requestedState: ReaderState? = savedStateHandle[ReaderIntent.EXTRA_STATE]
        if (requestedState != null) {
            return if (manga.findChapterById(requestedState.chapterId) != null) {
                requestedState
            } else {
                null
            }
        }

        val requestedBranch: String? = savedStateHandle[ReaderIntent.EXTRA_BRANCH]
        // continue reading
        val history = historyRepository.getOne(manga)
        if (history != null) {
            val chapter = manga.findChapterById(history.chapterId)
            // specified branch is requested
            return if (ReaderIntent.EXTRA_BRANCH in savedStateHandle) {
                if (chapter != null && chapter.branch == requestedBranch) {
                    ReaderState(history)
                } else {
                    ReaderState(manga, requestedBranch)
                }
            } else {
                if (chapter != null) {
                    ReaderState(history)
                } else {
                    ReaderState(manga, manga.getPreferredBranch(null))
                }
            }
        }

        // start from beginning
        val preferredBranch = requestedBranch ?: manga.getPreferredBranch(null)
        return ReaderState(manga, preferredBranch)
    }

    private fun Exception.mergeWith(other: Exception?): Exception = if (other == null) {
        this
    } else {
        other.addSuppressed(this)
        other
    }

    private fun observeMergeRepeatedChapters() {
        launchJob(Dispatchers.Default) {
            isMergeRepeatedChapters
                .collect { useMerge ->
                    val currentState = readingState.value ?: return@collect
                    Log.d(LOG_TAG, "isMergeRepeatedChapters changed to $useMerge")
                    
                    // Keep only the current chapter's pages in memory, clearing any preloaded next/prev chapters
                    chaptersLoader.keepOnlyChapter(currentState.chapterId)
                    
                    // Re-calculate UI state and content snapshot
                    content.value = ReaderContent(getSplitPagesSnapshot(), currentState)
                    notifyStateChanged()
                }
        }
    }

    private fun observeWebtoonBoundaryMode() {
        launchJob(Dispatchers.Default) {
            isWebtoonPullGestureEnabled.collect { isPullEnabled ->
                val currentState = readingState.value ?: return@collect
                if (readerMode.value != ReaderMode.WEBTOON) return@collect
                readerWindowGeneration++
                if (isPullEnabled) {
                    chaptersLoader.keepOnlyChapter(currentState.chapterId)
                }
                content.value = ReaderContent(getSplitPagesSnapshot(), currentState)
            }
        }
    }

    private fun getReadingChapters(): List<ContentChapter> {
        val details = mangaDetails.value ?: return emptyList()
        val contentType = details.toContent().source.getContentType()
        val useMerge = isMergeRepeatedChapters.value && contentType.isManga()
        return if (useMerge) {
            val allBranches = details.chapters.keys.toList()
            val rawChapters = allBranches.flatMap { details.chapters[it].orEmpty() }
            rawChapters.mergeRepeated()
        } else {
            val branch = selectedBranch.value
            if (branch != null) {
                details.chapters[branch].orEmpty()
            } else {
                details.allChapters
            }
        }
    }
}
