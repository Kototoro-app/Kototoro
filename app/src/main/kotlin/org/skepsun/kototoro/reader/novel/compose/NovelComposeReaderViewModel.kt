package org.skepsun.kototoro.reader.novel.compose

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.Immutable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.reader.novel.NovelChapterTranslation
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.ReadingMode
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.reader.novel.tts.TtsState
import javax.inject.Inject

data class NovelComposeReaderUiState(
	val chromeEnabled: Boolean = false,
	val controlsVisible: Boolean = false,
	val workTitle: String = "",
	val chapterId: Long = 0L,
	val chapterIndex: Int = 0,
	val chapterTitle: String = "",
	val content: String = "",
	val settings: NovelReaderSettings? = null,
	val translation: NovelChapterTranslation? = null,
	val position: NovelReadingPosition? = null,
	val scrollPosition: NovelComposeScrollPosition? = null,
	val imageContext: NovelComposeImageContext = NovelComposeImageContext(),
	val settingsSheetVisible: Boolean = false,
	val chaptersSheetVisible: Boolean = false,
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
	get() = (!chromeEnabled && (settingsSheetVisible || chaptersSheetVisible || toolsSheetVisible)) ||
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
	}

	fun publishCurrentPageBookmarked(bookmarked: Boolean) {
		_uiState.value = _uiState.value.copy(isCurrentPageBookmarked = bookmarked)
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
		val state = _uiState.value
		val chapter = state.continuousChapters.firstOrNull { it.chapterIndex == chapterIndex } ?: return
		if (state.chapterIndex == chapterIndex) return
		val chapterWindow = if (state.settings?.readingMode == ReadingMode.PAGED) {
			state.continuousChapters.filter {
				it.chapterIndex in (chapterIndex - 1)..(chapterIndex + 1)
			}
		} else {
			state.continuousChapters
		}
		_uiState.value = state.copy(
			chapterId = chapter.chapterId,
			chapterIndex = chapter.chapterIndex,
			chapterTitle = chapter.chapterTitle,
			content = chapter.content,
			translation = chapter.translation,
			scrollPosition = chapter.scrollPosition,
			imageContext = chapter.imageContext,
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
	}

	fun publishScrollPosition(position: NovelComposeScrollPosition) {
		val state = _uiState.value
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

	fun showChapters(chapters: List<ContentChapter>, currentChapterIndex: Int) {
		_uiState.value = _uiState.value.copy(
			chaptersSheetVisible = true,
			settingsSheetVisible = false,
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
}

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
