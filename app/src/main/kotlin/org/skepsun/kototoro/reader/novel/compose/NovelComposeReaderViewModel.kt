package org.skepsun.kototoro.reader.novel.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.skepsun.kototoro.reader.novel.NovelChapterTranslation
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.ReadingMode
import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.reader.novel.tts.TtsState
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingEntity
import org.skepsun.kototoro.bookmarks.domain.Bookmark
import javax.inject.Inject

enum class NovelChaptersSheetTab {
    CHAPTERS,
    NOTES,
    SEARCH,
}

data class NovelComposeReaderUiState(
    val chromeEnabled: Boolean = false,
    val controlsVisible: Boolean = false,
    val workTitle: String = "",
    val chapterId: Long = 0L,
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val content: String = "",
    val replaceRulesEnabled: Boolean = true,
    val replaceRules: List<ReplaceRule> = emptyList(),
    val replaceRulesOrigin: String = "",
    val disabledReplaceRuleIds: Set<Long> = emptySet(),
    val settings: NovelReaderSettings? = null,
    val translation: NovelChapterTranslation? = null,
    val position: NovelReadingPosition? = null,
    val scrollPosition: NovelComposeScrollPosition? = null,
    val imageContext: NovelComposeImageContext = NovelComposeImageContext(),
    val settingsSheetVisible: Boolean = false,
    val replaceRulesSheetVisible: Boolean = false,
    val markingsSheetVisible: Boolean = false,
    val chaptersSheetVisible: Boolean = false,
    val chaptersSheetInitialTab: NovelChaptersSheetTab = NovelChaptersSheetTab.CHAPTERS,
    val toolsSheetVisible: Boolean = false,
    val chapters: List<ContentChapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val loading: Boolean = false,
    val message: NovelReaderMessage? = null,
    val ttsControlsVisible: Boolean = false,
    val ttsState: TtsState = TtsState.IDLE,
    val ttsHighlightRange: IntRange? = null,
    val progressValue: Float = 0f,
    val progressMax: Float = 0f,
    val progressLabel: String = "",
    val currentPageText: String = "",
    val currentPageStart: Int = 0,
    val currentPageEnd: Int = 0,
    val isCurrentPageBookmarked: Boolean = false,
    val pageRequest: NovelPageRequest? = null,
    val scrollRequest: NovelScrollRequest? = null,
    val continuousChapters: List<NovelComposeChapterContent> = emptyList(),
    val novelMarkings: List<NovelMarkingEntity> = emptyList(),
    val novelBookmarks: List<Bookmark> = emptyList(),
    val bookmarkRequest: NovelBookmarkRequest? = null,
    val bookmarkPositions: Map<Long, NovelBookmarkResolvedPosition> = emptyMap(),
    val textSelection: NovelTextSelection? = null,
    val selectedMarking: NovelMarkingEntity? = null,
    val selectedMarkingAnchor: Offset? = null,
    val selectedMarkingRect: androidx.compose.ui.geometry.Rect? = null,
    val pendingMarkingTarget: NovelMarkingTarget? = null,
    val markingHighlightRange: IntRange? = null,
    val markingHighlightText: String? = null,
    val activeMarkingColor: Int = 0,
    val activeMarkingStyle: Int = 0,
)

@Immutable
data class NovelMarkingTarget(
    val markingId: Long = 0L,
    val chapterId: Long = 0L,
    val chapterIndex: Int = 0,
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val selectedText: String = "",
)

@Immutable
data class NovelPageRequest(
    val id: Long,
    val chapterId: Long,
    val chapterIndex: Int,
    val page: Int,
)

@Immutable
data class NovelScrollRequest(
    val id: Long,
    val deltaPages: Int = 0,
    val blockIndex: Int? = null,
)

@Immutable
data class NovelComposeChapterContent(
    val chapterId: Long = 0L,
    val chapterIndex: Int,
    val chapterTitle: String,
    val content: String,
    val translation: NovelChapterTranslation?,
    val scrollPosition: NovelComposeScrollPosition? = null,
    val imageContext: NovelComposeImageContext = NovelComposeImageContext(),
)

data class NovelReaderMessage(val id: Long, val text: String, val durationMillis: Long)

val NovelComposeReaderUiState.hasOverlay: Boolean
    get() = (!chromeEnabled && (settingsSheetVisible || replaceRulesSheetVisible || markingsSheetVisible || chaptersSheetVisible || toolsSheetVisible)) ||
        loading ||
        message != null ||
        (!chromeEnabled && ttsControlsVisible)

/** Renderer-neutral continuous-scroll anchor for configuration and process restoration. */
data class NovelComposeScrollPosition(
    val firstVisibleBlock: Int,
    val firstVisibleBlockOffsetPx: Int,
) {
    init {
        require(firstVisibleBlock >= 0)
        require(firstVisibleBlockOffsetPx >= 0)
    }
}

data class NovelComposeImageContext(
    val epubFilePath: String? = null,
    val chapterPath: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** State owner for the Compose novel surface. Rendering implementations publish into this state. */
@HiltViewModel
class NovelComposeReaderViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(NovelComposeReaderUiState())
    val uiState = _uiState.asStateFlow()
    private var nextMessageId = 0L
    private var nextPageRequestId = 0L
    private var nextScrollRequestId = 0L
    private var nextBookmarkRequestId = 0L

    fun publishChrome(
        enabled: Boolean = true,
        controlsVisible: Boolean = _uiState.value.controlsVisible,
        workTitle: String = _uiState.value.workTitle,
    ) {
        _uiState.value = _uiState.value.copy(
            chromeEnabled = enabled,
            controlsVisible = controlsVisible,
            workTitle = workTitle,
        )
    }

    fun publishProgress(value: Float, max: Float, label: String) {
        _uiState.value = _uiState.value.copy(
            progressValue = value.coerceIn(0f, max.coerceAtLeast(0f)),
            progressMax = max.coerceAtLeast(0f),
            progressLabel = label,
        )
    }

    fun publishChapter(
        chapterId: Long,
        chapterIndex: Int,
        chapterTitle: String,
        content: String,
        settings: NovelReaderSettings,
        translation: NovelChapterTranslation?,
    ) {
        val previous = _uiState.value
        val sameChapter = previous.chapterId == chapterId
        val chapter = NovelComposeChapterContent(
            chapterId = chapterId,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            content = content,
            translation = translation,
            scrollPosition = previous.continuousChapters
                .firstOrNull { it.chapterIndex == chapterIndex }
                ?.scrollPosition,
            imageContext = previous.continuousChapters
                .firstOrNull { it.chapterIndex == chapterIndex }
                ?.imageContext
                ?: previous.imageContext.takeIf { sameChapter }
                ?: NovelComposeImageContext(),
        )
        _uiState.value = _uiState.value.copy(
            chapterId = chapterId,
            chapterIndex = chapterIndex,
            currentChapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            content = content,
            settings = settings,
            translation = translation,
            position = previous.position.takeIf { sameChapter },
            currentPageText = previous.currentPageText.takeIf { sameChapter }.orEmpty(),
            currentPageStart = previous.currentPageStart.takeIf { sameChapter } ?: 0,
            currentPageEnd = previous.currentPageEnd.takeIf { sameChapter } ?: 0,
            scrollPosition = previous.scrollPosition.takeIf { previous.chapterIndex == chapterIndex },
            imageContext = previous.imageContext.takeIf { sameChapter } ?: NovelComposeImageContext(),
            textSelection = null,
            selectedMarking = null,
            selectedMarkingAnchor = null,
            pendingMarkingTarget = previous.pendingMarkingTarget,
            markingHighlightRange = previous.markingHighlightRange,
            markingHighlightText = previous.markingHighlightText,
                continuousChapters = if (settings.readingMode == ReadingMode.PAGED) {
                    listOf(chapter)
                } else {
                    mergeContinuousChapterWindow(
                        existing = previous.continuousChapters,
                        incoming = chapter,
                        continuous = true,
                    )
                },
        )
    }

    fun requestPage(page: Int) {
        val state = _uiState.value
        val request = NovelPageRequest(
            id = ++nextPageRequestId,
            chapterId = state.chapterId,
            chapterIndex = state.chapterIndex,
            page = page.coerceAtLeast(0),
        )
        _uiState.value = _uiState.value.copy(
            pageRequest = request,
            bookmarkRequest = null,
        )
        android.util.Log.d(
            NOVEL_PAGER_LOG_TAG,
            "request id=${request.id} chapter=${request.chapterIndex}/${request.chapterId} page=${request.page}",
        )
    }

    fun consumePageRequest(requestId: Long) {
        val state = _uiState.value
        if (state.pageRequest?.id != requestId) return
        _uiState.value = state.copy(pageRequest = null)
        android.util.Log.d(NOVEL_PAGER_LOG_TAG, "consume request id=$requestId")
    }

    fun requestScrollByPage(delta: Int) {
        if (delta == 0) return
        _uiState.value = _uiState.value.copy(
            scrollRequest = NovelScrollRequest(++nextScrollRequestId, deltaPages = delta),
        )
    }

    fun requestScrollToBlock(blockIndex: Int) {
        _uiState.value = _uiState.value.copy(
            scrollRequest = NovelScrollRequest(
                id = ++nextScrollRequestId,
                blockIndex = blockIndex.coerceAtLeast(0),
            ),
        )
    }

    fun publishPagedPosition(
        page: Int,
        pageCount: Int,
        charStart: Int,
        charEnd: Int,
        text: String,
    ) {
        val safeCount = pageCount.coerceAtLeast(0)
        val safePage = page.coerceIn(0, (safeCount - 1).coerceAtLeast(0))
        _uiState.value = _uiState.value.copy(
            position = NovelReadingPosition(
                chapterId = _uiState.value.chapterId,
                page = safePage,
                pageCount = safeCount,
                chapterProgress = if (safeCount > 1) safePage.toFloat() / (safeCount - 1) else 0f,
            ),
            progressValue = safePage.toFloat(),
            progressMax = (safeCount - 1).coerceAtLeast(0).toFloat(),
            progressLabel = "${safePage + 1} / ${safeCount.coerceAtLeast(1)}",
            currentPageText = text,
            currentPageStart = charStart.coerceAtLeast(0),
            currentPageEnd = charEnd.coerceAtLeast(charStart),
            isCurrentPageBookmarked = false,
        )
        refreshCurrentPageBookmarked()
    }

    fun publishCurrentPageBookmarked(bookmarked: Boolean) {
        _uiState.value = _uiState.value.copy(isCurrentPageBookmarked = bookmarked)
    }

    fun requestBookmark(bookmark: Bookmark) {
        _uiState.value = _uiState.value.copy(
            bookmarkRequest = NovelBookmarkRequest(++nextBookmarkRequestId, bookmark),
            pageRequest = null,
            scrollRequest = null,
            pendingMarkingTarget = null,
        )
    }

    fun consumeBookmarkRequest(requestId: Long): Boolean {
        if (_uiState.value.bookmarkRequest?.id != requestId) return false
        _uiState.value = _uiState.value.copy(bookmarkRequest = null)
        return true
    }

    fun cancelBookmarkRequest() {
        _uiState.value = _uiState.value.copy(bookmarkRequest = null)
    }

    fun publishBookmarkPositions(positions: Map<Long, NovelBookmarkResolvedPosition>) {
        if (_uiState.value.bookmarkPositions != positions) {
            _uiState.value = _uiState.value.copy(bookmarkPositions = positions)
        }
        refreshCurrentPageBookmarked()
    }

    fun publishBookmarkText(chapterId: Long, text: String) {
        if (_uiState.value.chapterId == chapterId && _uiState.value.currentPageText != text) {
            _uiState.value = _uiState.value.copy(currentPageText = text)
        }
    }

    fun refreshCurrentPageBookmarked() {
        val state = _uiState.value
        val position = state.position
        val bookmarked = position != null && state.novelBookmarks.any {
            state.bookmarkPositions[it.pageId]?.let { target ->
                target.chapterId == position.chapterId && target.segmentIndex == position.page
            } == true
        }
        if (state.isCurrentPageBookmarked != bookmarked) {
            _uiState.value = state.copy(isCurrentPageBookmarked = bookmarked)
        }
    }

    fun publishImageContext(imageContext: NovelComposeImageContext) {
        val state = _uiState.value
        _uiState.value = state.copy(
            imageContext = imageContext,
            continuousChapters = state.continuousChapters.map { chapter ->
                if (chapter.chapterIndex == state.chapterIndex) chapter.copy(imageContext = imageContext) else chapter
            },
        )
    }

    fun publishAdjacentChapter(chapter: NovelComposeChapterContent) {
        val state = _uiState.value
        _uiState.value = state.copy(
            continuousChapters = mergeContinuousChapterWindow(
                existing = state.continuousChapters,
                incoming = chapter,
                continuous = true,
            ),
        )
    }

    fun focusContinuousChapter(chapterIndex: Int) {
        if (_uiState.value.bookmarkRequest != null) return
        val state = _uiState.value
        val chapter = state.continuousChapters.firstOrNull { it.chapterIndex == chapterIndex }
        val chapterTitle = chapter?.chapterTitle
            ?: state.chapters.getOrNull(chapterIndex)?.title
            ?: state.chapterTitle
        if (state.chapterIndex == chapterIndex && state.chapterTitle == chapterTitle && chapterTitle.isNotBlank()) return
        val chapterWindow = if (state.settings?.readingMode == ReadingMode.PAGED) {
            state.continuousChapters.filter {
                it.chapterIndex in (chapterIndex - 1)..(chapterIndex + 1)
            }
        } else {
            state.continuousChapters
        }
        _uiState.value = state.copy(
            chapterId = chapter?.chapterId ?: state.chapterId,
            chapterIndex = chapterIndex,
            currentChapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            content = chapter?.content ?: state.content,
            translation = chapter?.translation ?: state.translation,
            scrollPosition = chapter?.scrollPosition ?: state.scrollPosition,
            imageContext = chapter?.imageContext ?: state.imageContext,
            continuousChapters = chapterWindow,
        )
    }

    fun publishTranslation(translation: NovelChapterTranslation?) {
        val state = _uiState.value
        _uiState.value = state.copy(
            translation = translation,
            continuousChapters = state.continuousChapters.map { chapter ->
                if (chapter.chapterIndex == state.chapterIndex) chapter.copy(translation = translation) else chapter
            },
        )
    }

    fun publishPosition(position: NovelReadingPosition) {
        _uiState.value = _uiState.value.copy(position = position)
        refreshCurrentPageBookmarked()
    }

    fun publishScrollPosition(position: NovelComposeScrollPosition) {
        val state = _uiState.value
        if (state.bookmarkRequest != null) return
        _uiState.value = state.copy(
            scrollPosition = position,
            continuousChapters = state.continuousChapters.map { chapter ->
                if (chapter.chapterIndex == state.chapterIndex) chapter.copy(scrollPosition = position) else chapter
            },
        )
    }

    fun showSettings(settings: NovelReaderSettings) {
        _uiState.value = _uiState.value.copy(
            settings = settings,
            settingsSheetVisible = true,
            replaceRulesSheetVisible = false,
            markingsSheetVisible = false,
            chaptersSheetVisible = false,
            toolsSheetVisible = false,
        )
    }

    fun dismissSettings() {
        _uiState.value = _uiState.value.copy(settingsSheetVisible = false)
    }

    fun publishSettings(settings: NovelReaderSettings) {
        _uiState.value = _uiState.value.copy(settings = settings)
    }

    fun publishReplaceRulesEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(replaceRulesEnabled = enabled)
    }

    fun publishReplaceRules(
        rules: List<ReplaceRule>,
        origin: String = _uiState.value.replaceRulesOrigin,
        disabledRuleIds: Set<Long> = _uiState.value.disabledReplaceRuleIds,
    ) {
        _uiState.value = _uiState.value.copy(
            replaceRules = rules,
            replaceRulesOrigin = origin,
            disabledReplaceRuleIds = disabledRuleIds,
        )
    }

    fun publishReplaceRuleEnabled(ruleId: Long, enabled: Boolean) {
        val disabledRuleIds = _uiState.value.disabledReplaceRuleIds.toMutableSet()
        if (enabled) disabledRuleIds.remove(ruleId) else disabledRuleIds.add(ruleId)
        _uiState.value = _uiState.value.copy(disabledReplaceRuleIds = disabledRuleIds)
    }

    fun showReplaceRules() {
        _uiState.value = _uiState.value.copy(
            replaceRulesSheetVisible = true,
            settingsSheetVisible = false,
            markingsSheetVisible = false,
            chaptersSheetVisible = false,
            toolsSheetVisible = false,
        )
    }

    fun dismissReplaceRules() {
        _uiState.value = _uiState.value.copy(replaceRulesSheetVisible = false)
    }

    fun showMarkings() {
        _uiState.value = _uiState.value.copy(
            chaptersSheetVisible = false,
            markingsSheetVisible = true,
            settingsSheetVisible = false,
            replaceRulesSheetVisible = false,
            toolsSheetVisible = false,
        )
    }

    fun dismissMarkings() {
        _uiState.value = _uiState.value.copy(markingsSheetVisible = false)
    }

    /**
     * 预先把完整章节列表与当前章节索引同步到状态，保证底栏章节切换按钮
     * 在首次进入时就按真实章数可用，而不是要等打开章节面板后才生效。
     */
    fun publishChapterLibrary(chapters: List<ContentChapter>, currentChapterIndex: Int) {
        if (chapters.isEmpty()) return
        val state = _uiState.value
        _uiState.value = state.copy(
            chapters = chapters,
            currentChapterIndex = currentChapterIndex.coerceIn(chapters.indices),
        )
    }

    fun showChapters(
        chapters: List<ContentChapter>,
        currentChapterIndex: Int,
        initialTab: NovelChaptersSheetTab = NovelChaptersSheetTab.CHAPTERS,
    ) {
        _uiState.value = _uiState.value.copy(
            chaptersSheetVisible = true,
            chaptersSheetInitialTab = initialTab,
            settingsSheetVisible = false,
            replaceRulesSheetVisible = false,
            markingsSheetVisible = false,
            toolsSheetVisible = false,
            chapters = chapters,
            currentChapterIndex = currentChapterIndex,
        )
    }

    fun dismissChapters() {
        _uiState.value = _uiState.value.copy(chaptersSheetVisible = false)
    }

    fun showTools() {
        _uiState.value = _uiState.value.copy(
            toolsSheetVisible = true,
            settingsSheetVisible = false,
            replaceRulesSheetVisible = false,
            markingsSheetVisible = false,
            chaptersSheetVisible = false,
        )
    }

    fun dismissTools() {
        _uiState.value = _uiState.value.copy(
            toolsSheetVisible = false,
            ttsControlsVisible = false,
        )
    }

    fun dismissControlPanels() {
        _uiState.value = _uiState.value.copy(
            settingsSheetVisible = false,
            replaceRulesSheetVisible = false,
            markingsSheetVisible = false,
            chaptersSheetVisible = false,
            toolsSheetVisible = false,
            ttsControlsVisible = false,
        )
    }

    fun setLoading(loading: Boolean) {
        _uiState.value = _uiState.value.copy(loading = loading)
    }

    fun showMessage(text: String, durationMillis: Long) {
        _uiState.value = _uiState.value.copy(
            message = NovelReaderMessage(++nextMessageId, text, durationMillis),
        )
    }

    fun dismissMessage(id: Long) {
        _uiState.value = _uiState.value.takeIf { it.message?.id == id }?.copy(message = null) ?: _uiState.value
    }

    fun showTtsControls() {
        _uiState.value = _uiState.value.copy(
            ttsControlsVisible = true,
            toolsSheetVisible = true,
        )
    }

    fun hideTtsControls() {
        _uiState.value = _uiState.value.copy(ttsControlsVisible = false)
    }

    fun publishTtsState(state: TtsState) {
        _uiState.value = _uiState.value.copy(
            ttsState = state,
            ttsHighlightRange = if (state == TtsState.IDLE) null else _uiState.value.ttsHighlightRange,
        )
    }

    fun publishTtsHighlight(range: IntRange?) {
        _uiState.value = _uiState.value.copy(ttsHighlightRange = range)
    }

    fun publishNovelMarkings(markings: List<NovelMarkingEntity>) {
        val pendingStyles = pendingNovelMarkingStyles.toMap()
        val mergedMarkings = markings.map { marking ->
            pendingStyles[marking.id]?.let { style ->
                marking.copy(color = style.color, style = style.style)
            } ?: marking
        }
        pendingNovelMarkingStyles.entries.toList().forEach { (id, style) ->
            val persisted = markings.firstOrNull { it.id == id }
            if (persisted != null && persisted.color == style.color && persisted.style == style.style) {
                pendingNovelMarkingStyles.remove(id)
            }
        }
        val selectedMarking = _uiState.value.selectedMarking?.let { selected ->
            mergedMarkings.firstOrNull { it.id == selected.id } ?: selected
        }
        _uiState.value = _uiState.value.copy(
            novelMarkings = mergedMarkings,
            selectedMarking = selectedMarking,
        )
    }

    fun publishNovelBookmarks(bookmarks: List<Bookmark>) {
        _uiState.value = _uiState.value.copy(novelBookmarks = bookmarks)
        refreshCurrentPageBookmarked()
    }

    private var activeSelectionOwnerId: String? = null

    fun publishTextSelection(selection: NovelTextSelection?) {
        if (selection == null) {
            activeSelectionOwnerId = null
            _uiState.value = _uiState.value.copy(
                textSelection = null,
                selectedMarking = null,
                selectedMarkingAnchor = null,
                selectedMarkingRect = null,
            )
            return
        }
        activeSelectionOwnerId = selection.ownerId
        _uiState.value = _uiState.value.copy(
            textSelection = selection,
            selectedMarking = null,
            selectedMarkingAnchor = null,
            selectedMarkingRect = null,
        )
    }

    fun clearTextSelection(ownerId: String? = null) {
        if (ownerId == null || activeSelectionOwnerId == ownerId) {
            publishTextSelection(null)
        }
    }

    fun publishSelectedMarking(
        marking: NovelMarkingEntity?,
        rect: androidx.compose.ui.geometry.Rect? = null,
        anchor: Offset? = null,
    ) {
        activeSelectionOwnerId = null
        val resolvedAnchor = anchor ?: rect?.let { Offset(it.left + it.width / 2f, it.top) }
        _uiState.value = _uiState.value.copy(
            selectedMarking = marking,
            textSelection = null,
            selectedMarkingAnchor = resolvedAnchor,
            selectedMarkingRect = rect,
        )
    }

    fun publishNovelMarkingStyle(markingId: Long, color: Int, style: Int) {
        pendingNovelMarkingStyles[markingId] = NovelMarkingStyleOverride(color, style)
        val state = _uiState.value
        _uiState.value = state.copy(
            novelMarkings = state.novelMarkings.map { marking ->
                marking.takeUnless { it.id == markingId } ?: marking.copy(color = color, style = style)
            },
            selectedMarking = state.selectedMarking?.let { marking ->
                if (marking.id == markingId) marking.copy(color = color, style = style) else marking
            },
        )
    }

    fun publishActiveMarkingStyle(color: Int, style: Int) {
        _uiState.value = _uiState.value.copy(
            activeMarkingColor = color,
            activeMarkingStyle = style,
        )
    }

    fun publishMarkingHighlight(range: IntRange?) {
        _uiState.value = _uiState.value.copy(markingHighlightRange = range)
    }

    fun jumpToMarking(target: NovelMarkingTarget) {
        highlightJob?.cancel()
        _uiState.value = _uiState.value.copy(
            pendingMarkingTarget = target,
            markingHighlightRange = target.startOffset until target.endOffset,
            markingHighlightText = target.selectedText,
        )
        highlightJob = viewModelScope.launch {
            delay(2500L)
            if (_uiState.value.markingHighlightText == target.selectedText) {
                _uiState.value = _uiState.value.copy(
                    markingHighlightRange = null,
                    markingHighlightText = null,
                )
            }
        }
    }

    fun onMarkingJumpResolved() {
        _uiState.value = _uiState.value.copy(pendingMarkingTarget = null)
    }

    private var highlightJob: Job? = null

    private val pendingNovelMarkingStyles = mutableMapOf<Long, NovelMarkingStyleOverride>()

    fun triggerTransientHighlight(range: IntRange?, text: String? = null, durationMs: Long = 1500L) {
        highlightJob?.cancel()
        if (range == null && text == null) {
            _uiState.value = _uiState.value.copy(
                markingHighlightRange = null,
                markingHighlightText = null,
            )
            return
        }
        highlightJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                markingHighlightRange = range,
                markingHighlightText = text,
            )
            delay(durationMs)
            if (_uiState.value.markingHighlightRange == range && _uiState.value.markingHighlightText == text) {
                _uiState.value = _uiState.value.copy(
                    markingHighlightRange = null,
                    markingHighlightText = null,
                )
            }
        }
    }
}

private data class NovelMarkingStyleOverride(
    val color: Int,
    val style: Int,
)

internal const val NOVEL_PAGER_LOG_TAG = "NovelPager"

internal fun mergeContinuousChapterWindow(
    existing: List<NovelComposeChapterContent>,
    incoming: NovelComposeChapterContent,
    continuous: Boolean,
): List<NovelComposeChapterContent> {
        if (!continuous || existing.isEmpty()) return listOf(incoming)
    val existingIndex = existing.indexOfFirst { it.chapterIndex == incoming.chapterIndex }
    if (existingIndex >= 0) {
        return existing.toMutableList().apply { this[existingIndex] = incoming }
    }
    val first = existing.first().chapterIndex
    val last = existing.last().chapterIndex
    return when (incoming.chapterIndex) {
        first - 1 -> listOf(incoming) + existing
        last + 1 -> existing + incoming
        else -> listOf(incoming)
    }
}
