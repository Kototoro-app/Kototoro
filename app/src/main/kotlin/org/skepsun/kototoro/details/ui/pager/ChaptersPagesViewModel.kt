package org.skepsun.kototoro.details.ui.pager

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okio.FileNotFoundException
import org.skepsun.kototoro.R
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import org.skepsun.kototoro.bookmarks.domain.BookmarksRepository
import org.skepsun.kototoro.core.model.getLocale
import org.skepsun.kototoro.core.model.getPreferredBranch
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.toChipModel
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsStateFlow
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.ui.chapters.chapterBranchOptions
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.core.ui.util.ReversibleAction
import org.skepsun.kototoro.core.util.LocaleStringComparator
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.core.util.ext.combine
import org.skepsun.kototoro.core.util.ext.requireValue
import org.skepsun.kototoro.core.util.ext.sortedWithSafe
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.domain.DetailsInteractor
import org.skepsun.kototoro.details.domain.calculateGroupedChapterProgress
import org.skepsun.kototoro.details.ui.DetailsActivity
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.details.ui.mapChapters
import org.skepsun.kototoro.details.ui.model.ChapterListItem
import org.skepsun.kototoro.download.ui.worker.DownloadTask
import org.skepsun.kototoro.download.ui.worker.DownloadTaskKind
import org.skepsun.kototoro.download.ui.worker.DownloadWorker
import org.skepsun.kototoro.download.ui.worker.ExecutionChapterRef
import org.skepsun.kototoro.reader.translate.data.ChapterTranslationStore
import org.skepsun.kototoro.reader.translate.data.TranslatedChapters
import org.skepsun.kototoro.reader.translate.data.resolveChapterTranslationKey
import org.skepsun.kototoro.reader.translate.data.resolveChapterTranslationLanguagePair
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.local.domain.DeleteLocalContentUseCase
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.reader.ui.ReaderActivity
import org.skepsun.kototoro.reader.ui.ReaderState
import org.skepsun.kototoro.reader.ui.ReaderViewModel
import org.skepsun.kototoro.video.domain.resolveVideoCandidates
import org.skepsun.kototoro.video.ui.VideoPlayerActivity
import org.skepsun.kototoro.video.ui.VideoChaptersViewModel
import java.time.Instant

abstract class ChaptersPagesViewModel(
    @JvmField protected val settings: AppSettings,
    @JvmField protected val interactor: DetailsInteractor,
    private val bookmarksRepository: BookmarksRepository,
    private val historyRepository: HistoryRepository,
    private val downloadScheduler: DownloadWorker.Scheduler,
    private val deleteLocalContentUseCase: DeleteLocalContentUseCase,
    protected val mangaRepositoryFactory: ContentRepository.Factory,
    private val localStorageChanges: SharedFlow<LocalContent?>,
    private val chapterTranslationStore: ChapterTranslationStore,
) : BaseViewModel() {

    val mangaDetails = MutableStateFlow<ContentDetails?>(null)
    val readingState = MutableStateFlow<ReaderState?>(null)

    data class QualityProbeResult(val snapshot: Set<Long>, val qualities: List<String>)
    val onShowVideoQualityDialog = MutableEventFlow<QualityProbeResult>()

    val onActionDone = MutableEventFlow<ReversibleAction>()
    val onDownloadStarted = MutableEventFlow<Unit>()
    val onShowMessage = MutableEventFlow<Int>()
    val onContentRemoved = MutableEventFlow<Content>()

    private val chaptersQuery = MutableStateFlow("")
    val chapterQuery: StateFlow<String> = chaptersQuery
    val selectedBranch = MutableStateFlow<String?>(null)

    val manga = mangaDetails.map { x -> x?.toContent() }
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    val coverUrl = mangaDetails.map { x -> x?.coverUrl }
        .withErrorHandling()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

    val isChaptersReversed = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_REVERSE_CHAPTERS,
        valueProducer = { isChaptersReverse },
    )

    val isChaptersInGridView = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_GRID_VIEW_CHAPTERS,
        valueProducer = { isChaptersGridView },
    )

    val isHideReadChapters = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_HIDE_READ_CHAPTERS,
        valueProducer = { isHideReadChapters },
    )

    val isMergeRepeatedChapters = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_MERGE_REPEATED_CHAPTERS,
        valueProducer = { isMergeRepeatedChapters },
    )

    val showMergeRepeatedChapters = mangaDetails.map { it?.chapters?.size ?: 0 > 1 }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    val isDownloadedOnly = MutableStateFlow(false)

    val newChaptersCount = mangaDetails.flatMapLatest { d ->
        if (d?.isLocal == false) {
            interactor.observeNewChapters(d.id)
        } else {
            flowOf(0)
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

    val emptyReason: StateFlow<EmptyContentReason?> = combine(
        mangaDetails,
        isLoading,
        onError.onStart { emit(null) },
    ) { details, loading, error ->
        when {
            details == null || loading -> null
            details.chapters.isNotEmpty() -> null
            details.toContent().state == ContentState.RESTRICTED -> EmptyContentReason.RESTRICTED
            error != null -> EmptyContentReason.LOADING_ERROR
            else -> EmptyContentReason.NO_CHAPTERS
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(), null)

    val bookmarks = mangaDetails.flatMapLatest {
        if (it != null) {
            bookmarksRepository.observeBookmarks(it.toContent()).withErrorHandling()
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

    private val downloadInvalidation = MutableStateFlow(0)

    private val translationRefresh = MutableStateFlow(0)

    /**
     * Re-reads stored translations when the user edits anything that changes the produced pixels, so a
     * finished chapter flips to "made with other settings" without leaving the screen.
     */
    private val translationRecipeRefresh: Flow<String?> = settings.observeChanges()
        .filter { key -> key == null || key.startsWith(TRANSLATION_PREF_PREFIX) }
        // Preference listeners only fire on change, so without an initial value the five-way combine below
        // would never emit and the chapter list would stay at its initial empty state.
        .onStart { emit(null) }

    /**
     * Chapters whose background translation already finished, split by whether it still matches the
     * current translation settings.
     *
     * The rendered pages live in an evictable page cache, so this marker is the only stable signal the
     * chapter list can use for the "translated" badge; the recipe fingerprint lets the same record say
     * "made with older settings, offer a re-translate".
     */
    private val translatedChaptersFlow: Flow<TranslatedChapters> = combine(
        mangaDetails,
        translationRefresh,
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_READER_TRANSLATION_SOURCE_LANG,
            valueProducer = { readerTranslationSourceLanguage },
        ),
        settings.observeAsStateFlow(
            scope = viewModelScope + Dispatchers.Default,
            key = AppSettings.KEY_READER_TRANSLATION_TARGET_LANG,
            valueProducer = { readerTranslationTargetLanguage },
        ),
        translationRecipeRefresh,
    ) { details, _, sourceLanguage, targetLanguage, _ ->
        val content = details?.toContent() ?: return@combine TranslatedChapters.EMPTY
        val languagePair = resolveChapterTranslationLanguagePair(
            preferredSourceLanguage = sourceLanguage,
            contentLanguage = content.source.getLocale()?.language,
            targetLanguage = targetLanguage,
        )
        val key = settings.resolveChapterTranslationKey(
            sourceLang = languagePair.first,
            targetLang = languagePair.second,
        )
        val states = chapterTranslationStore.chapterStates(mangaId = content.id, key = key)
        if (states.isEmpty()) {
            TranslatedChapters.EMPTY
        } else {
            TranslatedChapters(
                ready = states.filterValues { it.isPrepared && !it.isStale }.keys.toSet(),
                stale = states.filterValues { it.isPrepared && it.isStale }.keys.toSet(),
                partial = buildMap {
                    for ((chapterId, state) in states) {
                        val progress = state.progress ?: continue
                        if (state.isPartial) put(chapterId, progress)
                    }
                },
            )
        }
    }
        // Belt and braces: the chapter list itself must never be held empty while waiting for a badge input.
        .onStart { emit(TranslatedChapters.EMPTY) }
        .distinctUntilChanged()

    val translatedChapterIds: StateFlow<Set<Long>> = translatedChaptersFlow
        .map { it.ready + it.stale }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptySet())

    /**
     * Persists how far a chapter translation got, for the chapter list to show.
     *
     * The interactive reader has no "job" to write this: it translates the pages it shows. Calling this after
     * each page turns the reader's lazy progress into the same ledger the background job fills, so a chapter
     * translated inside the reader is marked translated (or partially translated) exactly like a job's output.
     */
    protected suspend fun recordChapterTranslationProgress(
        mangaId: Long,
        chapterId: Long,
        donePages: Int,
        totalPages: Int,
    ) {
        if (mangaId == 0L || chapterId == 0L || totalPages <= 0 || donePages <= 0) return
        val content = mangaDetails.value?.toContent() ?: return
        val languagePair = resolveChapterTranslationLanguagePair(
            preferredSourceLanguage = settings.readerTranslationSourceLanguage,
            contentLanguage = content.source.getLocale()?.language,
            targetLanguage = settings.readerTranslationTargetLanguage,
        )
        val key = settings.resolveChapterTranslationKey(
            sourceLang = languagePair.first,
            targetLang = languagePair.second,
        )
        chapterTranslationStore.markProgress(
            mangaId = mangaId,
            chapterId = chapterId,
            key = key,
            donePages = donePages,
            totalPages = totalPages,
        )
        if (donePages >= totalPages) {
            chapterTranslationStore.markPrepared(
                mangaId = mangaId,
                chapterId = chapterId,
                key = key,
                pages = totalPages,
            )
        }
    }

    /** Ids whose stored translation came from other settings; the row offers a re-translate for them. */
    val staleTranslationChapterIds: StateFlow<Set<Long>> = translatedChaptersFlow
        .map { it.stale }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptySet())

    private val translatedChapterIdsFlow: Flow<Set<Long>> = translatedChaptersFlow.map { it.ready }

    private val baseChaptersFlow = combine(
        mangaDetails,
        readingState.map { it?.chapterId ?: 0L }.distinctUntilChanged(),
        selectedBranch,
        newChaptersCount,
        bookmarks,
        isChaptersInGridView,
        isDownloadedOnly,
        isMergeRepeatedChapters,
    ) { manga, currentChapterId, branch, news, bookmarks, grid, downloadedOnly, mergeRepeated ->
        val baseChapters = if (mergeRepeated && manga != null) {
            val allBranches = manga.chapters.keys.toList()
            if (allBranches.size > 1) {
                allBranches.flatMap { b ->
                    manga.mapChapters(
                        currentChapterId = currentChapterId,
                        newCount = news,
                        branch = b,
                        bookmarks = bookmarks,
                        isGrid = grid,
                        isDownloadedOnly = downloadedOnly,
                        shareProgressAcrossBranches = true,
                    )
                }
            } else {
                manga.mapChapters(
                    currentChapterId = currentChapterId,
                    newCount = news,
                    branch = branch,
                    bookmarks = bookmarks,
                    isGrid = grid,
                    isDownloadedOnly = downloadedOnly,
                )
            }
        } else {
            manga?.mapChapters(
                currentChapterId = currentChapterId,
                newCount = news,
                branch = branch,
                bookmarks = bookmarks,
                isGrid = grid,
                isDownloadedOnly = downloadedOnly,
            ).orEmpty()
        }
        expandEpubChaptersIfNeeded(baseChapters)
    }

    val chapters = combine(
        combine(baseChaptersFlow, downloadInvalidation, translatedChaptersFlow) { list, _, translated ->
            if (translated.isEmpty) {
                list
            } else {
                list.map { item ->
                    val isReady = item.chapter.id in translated.ready
                    val isStale = item.chapter.id in translated.stale
                    val partial = translated.partial[item.chapter.id]
                    if (isReady || isStale || partial != null) {
                        item.copy(
                            isTranslated = isReady,
                            isTranslationStale = isStale,
                            translationProgress = partial,
                        )
                    } else {
                        item
                    }
                }
            }
        },
        isChaptersReversed,
        isHideReadChapters,
        isMergeRepeatedChapters,
        chaptersQuery,
    ) { list, reversed, hideReadChapters, mergeRepeatedChapters, query ->
        val ordered = if (reversed) list.asReversed() else list
        val filtered = if (hideReadChapters) ordered.filterReadBeforeCurrent() else ordered
        val merged = if (mergeRepeatedChapters) filtered.mergeRepeated() else filtered
        merged.filterSearch(query)
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

    protected fun notifyDownloadChanged() {
        downloadInvalidation.value = downloadInvalidation.value + 1
    }

    /**
     * Expand EPUB chapters by loading mappings from database.
     * This is a hook that can be overridden by subclasses to provide EPUB expansion.
     * Default implementation returns chapters as-is.
     */
    protected open suspend fun expandEpubChaptersIfNeeded(chapters: List<ChapterListItem>): List<ChapterListItem> {
        return chapters
    }

    val quickFilter = combine(
        mangaDetails,
        selectedBranch,
    ) { details, branch ->
        chapterBranchOptions(
            chapters = details?.allChapters.orEmpty(),
            branch = ContentChapter::branch,
            comparator = LocaleStringComparator(),
        ).map {
            val option = ListFilterOption.Branch(titleText = it.name, chaptersCount = it.chaptersCount)
            option.toChipModel(isChecked = it.name == branch)
        }
    }

    init {
        launchJob(Dispatchers.Default) {
            localStorageChanges
                .collect { onDownloadComplete(it) }
        }
        launchJob(Dispatchers.Default) {
            chapterTranslationStore.changes
                .collect { translationRefresh.update { it + 1 } }
        }
        launchJob(Dispatchers.Default) {
            mangaDetails.collect { details ->
                val content = details?.toContent() ?: return@collect
                android.util.Log.d(
                    "ChaptersPagesViewModel",
                    "mangaDetails.collect: mangaId=${content.id}, chapterCount=${content.chapters?.size ?: 0}, selectedBranch=${selectedBranch.value}, branches=${details.chapters.mapValues { it.value.size }}",
                )
                if (content.chapters.isNullOrEmpty()) {
                    return@collect
                }
                val currentBranch = selectedBranch.value
                android.util.Log.d(
                    "ChaptersPagesViewModel",
                    "mangaDetails.collect: currentBranch=$currentBranch, currentBranchCount=${details.chapters[currentBranch].orEmpty().size}",
                )
                if (currentBranch == null || details.chapters[currentBranch].isNullOrEmpty()) {
                    val preferredBranch = content.getPreferredBranch(null)
                    android.util.Log.d(
                        "ChaptersPagesViewModel",
                        "mangaDetails.collect: switching selectedBranch from $currentBranch to $preferredBranch",
                    )
                    selectedBranch.value = preferredBranch
                }
            }
        }
    }

    fun setChaptersReversed(newValue: Boolean) {
        settings.isChaptersReverse = newValue
    }

    fun setChaptersInGridView(newValue: Boolean) {
        settings.isChaptersGridView = newValue
    }

    fun setHideReadChapters(newValue: Boolean) {
        settings.isHideReadChapters = newValue
    }

    fun setMergeRepeatedChapters(newValue: Boolean) {
        settings.isMergeRepeatedChapters = newValue
    }

    fun setSelectedBranch(branch: String?) {
        selectedBranch.value = branch
    }

    fun performChapterSearch(query: String?) {
        chaptersQuery.value = query?.trim().orEmpty()
    }

    fun getContentOrNull(): Content? = mangaDetails.value?.toContent()

    fun requireContent() = mangaDetails.requireValue().toContent()

    fun markChapterAsCurrent(chapterId: Long) {
        launchJob(Dispatchers.Default) {
            val manga = mangaDetails.requireValue()
            val progress = calculateGroupedChapterProgress(
                chapters = manga.allChapters,
                chapterId = chapterId,
                chapterPercent = 0f,
            ) ?: error("Chapter not found")
            historyRepository.addOrUpdate(
                manga = manga.toContent(),
                chapterId = chapterId,
                page = 0,
                scroll = 0,
                percent = progress.percent,
                force = true,
            )
        }
    }

    fun download(chapterId: Long, isMeteredNetworkAllowed: Boolean, preferredQuality: String? = null) {
        download(setOf(chapterId), isMeteredNetworkAllowed, preferredQuality)
    }

    fun setBookmarksForChapters(chapterIds: Set<Long>, removeExisting: Boolean) {
        launchJob(Dispatchers.Default) {
            val manga = mangaDetails.value?.toContent() ?: return@launchJob
            val chapterItems = chapters.value.filter { it.chapter.id in chapterIds }
            val bookmarksByChapter = bookmarks.value
                .filter { it.chapterId in chapterIds }
                .groupBy { it.chapterId }
            if (removeExisting) {
                val bookmarkIdsToRemove = bookmarksByChapter.values
                    .flatten()
                    .mapTo(LinkedHashSet()) { it.pageId }
                if (bookmarkIdsToRemove.isNotEmpty()) {
                    bookmarksRepository.removeBookmarks(bookmarkIdsToRemove)
                }
                return@launchJob
            }
            for (item in chapterItems) {
                if (item.chapter.id in bookmarksByChapter) {
                    continue
                }
                val bookmark = Bookmark(
                    manga = manga,
                    pageId = item.chapter.id,
                    chapterId = item.chapter.id,
                    page = 0,
                    scroll = 0,
                    imageUrl = manga.coverUrl.orEmpty(),
                    createdAt = Instant.now(),
                    percent = 0f,
                )
                bookmarksRepository.addBookmark(bookmark)
            }
        }
    }

    fun probeAndDownload(snapshot: Set<Long>) {
        launchJob(Dispatchers.Default) {
            val manga = mangaDetails.value?.toContent() ?: return@launchJob
            if (manga.source.getContentType() != ContentType.VIDEO) return@launchJob

            val chapterId = snapshot.firstOrNull() ?: return@launchJob
            val chapter = chapters.value.find { it.chapter.id == chapterId }?.chapter ?: return@launchJob
            val repo = mangaRepositoryFactory.create(manga.source)

            val qualities = runCatchingCancellable {
                repo.resolveVideoCandidates(chapter)
                    .map { it.title.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            }.getOrNull()

            if (!qualities.isNullOrEmpty()) {
                onShowVideoQualityDialog.call(QualityProbeResult(snapshot, qualities))
            } else {
                // Fallback to default flow without specific quality
                onShowVideoQualityDialog.call(QualityProbeResult(snapshot, emptyList()))
            }
        }
    }

    fun download(snapshot: Set<Long>, isMeteredNetworkAllowed: Boolean, preferredQuality: String? = null) {
        val manga = mangaDetails.value?.toContent() ?: return
        val items = chapters.value.filter { it.chapter.id in snapshot }

            val task = DownloadTask.createExecutionTask(
                executionMangaId = manga.id,
                displayMangaId = manga.id,
                isPaused = false,
                isSilent = false,
                executionChapterIds = items.map { it.chapter.id }.toLongArray(),
                executionChapterRefs = items.map { ExecutionChapterRef.fromChapter(it.chapter) },
                destination = null,
                format = null,
                allowMeteredNetwork = isMeteredNetworkAllowed,
                preferredQuality = preferredQuality,
        )
        launchJob(Dispatchers.Default) {
            downloadScheduler.schedule(setOf(manga to task))
            onDownloadStarted.call(Unit)
        }
    }

    /**
     * Background "translate" action of a chapter row: downloads the chapter first when it is still
     * remote, then translates it as a whole chapter.
     */
    fun translate(
        snapshot: Set<Long>,
        isMeteredNetworkAllowed: Boolean,
        isRetranslation: Boolean = false,
    ) {
        if (snapshot.isEmpty()) {
            return
        }
        if (settings.readerTranslationApiEndpoint.isBlank() && settings.readerTranslationOnnxModelId.isBlank()) {
            // Without an engine the job would finish without translating anything and still look successful.
            onShowMessage.call(R.string.novel_reader_translation_not_configured)
            return
        }
        val manga = mangaDetails.value?.toContent() ?: return
        val items = chapters.value.filter { it.chapter.id in snapshot }
        if (items.isEmpty()) {
            return
        }
        val task = DownloadTask.createExecutionTask(
            executionMangaId = manga.id,
            displayMangaId = manga.id,
            isPaused = false,
            isSilent = snapshot.size == 1,
            executionChapterIds = items.map { it.chapter.id }.toLongArray(),
            executionChapterRefs = items.map { ExecutionChapterRef.fromChapter(it.chapter) },
            destination = null,
            format = null,
            allowMeteredNetwork = isMeteredNetworkAllowed,
            kind = DownloadTaskKind.TRANSLATE,
            isRetranslation = isRetranslation,
        )
        launchJob(Dispatchers.Default) {
            downloadScheduler.schedule(setOf(manga to task))
            onDownloadStarted.call(Unit)
        }
    }

    /** Chapters of this content whose translation job is running right now. */
    val activeTranslationChapterIds: StateFlow<Set<Long>> = downloadScheduler.observeWorks()
        .map { works ->
            val mangaId = mangaDetails.value?.toContent()?.id ?: return@map emptySet<Long>()
            val running = works.filter { !it.state.isFinished }
            if (running.isEmpty()) return@map emptySet<Long>()
            withContext(Dispatchers.IO) {
                val chapters = mutableSetOf<Long>()
                for (work in running) {
                    val task = downloadScheduler.getTask(work.id) ?: continue
                    if (task.kind != DownloadTaskKind.TRANSLATE) continue
                    if (task.executionMangaId != mangaId && task.displayMangaId != mangaId) continue
                    chapters += task.executionChapterIds?.toList().orEmpty()
                }
                chapters
            }
        }
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Stops translation jobs for this content without touching downloads. */
    fun cancelTranslation() {
        val manga = mangaDetails.value?.toContent() ?: return
        launchJob(Dispatchers.Default) {
            downloadScheduler.cancelTranslation(manga.id)
            onActionDone.call(ReversibleAction(R.string.translation_cancelled, null))
        }
    }

    fun prepareTranslation(snapshot: Set<Long>, isMeteredNetworkAllowed: Boolean) {
        schedulePreparation(snapshot, DownloadTaskKind.PREPARE_TRANSLATION, isMeteredNetworkAllowed)
    }

    fun prepareSuperResolution(snapshot: Set<Long>) {
        schedulePreparation(snapshot, DownloadTaskKind.PREPARE_SUPER_RESOLUTION, true)
    }

    private fun schedulePreparation(
        snapshot: Set<Long>,
        kind: DownloadTaskKind,
        isMeteredNetworkAllowed: Boolean,
    ) {
        val manga = mangaDetails.value?.toContent() ?: return
        val items = chapters.value.filter { it.chapter.id in snapshot }
            .filter { it.isDownloaded || it.chapter.source.isLocal }
        if (items.isEmpty()) {
            return
        }
            val task = DownloadTask.createExecutionTask(
                executionMangaId = manga.id,
                displayMangaId = manga.id,
                isPaused = false,
                isSilent = snapshot.size == 1,
                executionChapterIds = items.map { it.chapter.id }.toLongArray(),
                executionChapterRefs = items.map { ExecutionChapterRef.fromChapter(it.chapter) },
                destination = null,
                format = null,
                allowMeteredNetwork = isMeteredNetworkAllowed,
                kind = kind,
        )
        launchJob(Dispatchers.Default) {
            downloadScheduler.schedule(setOf(manga to task))
            onDownloadStarted.call(Unit)
        }
    }

    fun deleteLocal() {
        val m = mangaDetails.value?.local?.manga
        if (m == null) {
            errorEvent.call(FileNotFoundException())
            return
        }
        launchLoadingJob(Dispatchers.Default) {
            deleteLocalContentUseCase(m)
            onContentRemoved.call(m)
        }
    }

    private fun List<ChapterListItem>.filterSearch(query: String): List<ChapterListItem> {
        if (query.isEmpty() || this.isEmpty()) {
            return this
        }
        return filter { it.contains(query) }
    }

    private fun List<ChapterListItem>.filterReadBeforeCurrent(): List<ChapterListItem> {
        val currentIndex = indexOfFirst { it.isCurrent }
        if (currentIndex <= 0) {
            return this
        }
        return filterIndexed { index, item ->
            index >= currentIndex || item.isUnread || item.isCurrent
        }
    }



    protected open suspend fun onDownloadComplete(downloadedContent: LocalContent?) {
        downloadedContent ?: return
        mangaDetails.update {
            interactor.updateLocal(it, downloadedContent)
        }
    }

    private companion object {

        /** Every preference that can change a translation recipe starts with this prefix. */
        const val TRANSLATION_PREF_PREFIX = "reader_translation"

    }

}

internal fun List<ChapterListItem>.mergeRepeated(): List<ChapterListItem> {
    if (this.isEmpty()) return this
    val groups = this.groupBy { item ->
        val ch = item.chapter
        if (ch.number > 0f) {
            val volKey = if (ch.volume > 0) ch.volume else null
            "num_${volKey}_${ch.number}"
        } else {
            val titleKey = ch.title?.lowercase()?.trim()
            if (!titleKey.isNullOrBlank()) {
                "title_$titleKey"
            } else {
                "unique_${ch.id}_${ch.url}"
            }
        }
    }
    return groups.map { (_, groupList) ->
        if (groupList.size == 1) {
            groupList.first()
        } else {
            groupList.maxWithOrNull(
                compareBy<ChapterListItem> { it.chapter.uploadDate }
                    .thenBy { it.isDownloaded }
                    .thenBy { it.chapter.scanlator?.isNotBlank() == true }
                    .thenBy { it.chapter.title?.length ?: 0 }
            ) ?: groupList.first()
        }
    }
}
