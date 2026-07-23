package org.skepsun.kototoro.reader.novel.compose

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.Immutable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.reader.novel.NovelChapterTranslation
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.reader.novel.tts.TtsState
import javax.inject.Inject

data class NovelComposeReaderUiState(
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
	val chapters: List<ContentChapter> = emptyList(),
	val currentChapterIndex: Int = 0,
	val loading: Boolean = false,
	val message: NovelReaderMessage? = null,
	val ttsControlsVisible: Boolean = false,
	val ttsState: TtsState = TtsState.IDLE,
	val ttsHighlightRange: IntRange? = null,
	val continuousChapters: List<NovelComposeChapterContent> = emptyList(),
)

@Immutable
data class NovelComposeChapterContent(
	val chapterIndex: Int,
	val chapterTitle: String,
	val content: String,
	val translation: NovelChapterTranslation?,
	val scrollPosition: NovelComposeScrollPosition? = null,
	val imageContext: NovelComposeImageContext = NovelComposeImageContext(),
)

data class NovelReaderMessage(val id: Long, val text: String, val durationMillis: Long)

val NovelComposeReaderUiState.hasOverlay: Boolean
	get() = settingsSheetVisible || chaptersSheetVisible || loading || message != null || ttsControlsVisible

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

	fun publishChapter(
		chapterIndex: Int,
		chapterTitle: String,
		content: String,
		settings: NovelReaderSettings,
		translation: NovelChapterTranslation?,
	) {
		val previous = _uiState.value
		val chapter = NovelComposeChapterContent(
			chapterIndex = chapterIndex,
			chapterTitle = chapterTitle,
			content = content,
			translation = translation,
			scrollPosition = previous.continuousChapters
				.firstOrNull { it.chapterIndex == chapterIndex }
				?.scrollPosition,
		)
		_uiState.value = _uiState.value.copy(
			chapterIndex = chapterIndex,
			chapterTitle = chapterTitle,
			content = content,
			settings = settings,
			translation = translation,
			scrollPosition = previous.scrollPosition.takeIf { previous.chapterIndex == chapterIndex },
			imageContext = NovelComposeImageContext(),
			continuousChapters = mergeContinuousChapterWindow(
				existing = previous.continuousChapters,
				incoming = chapter,
				continuous = settings.readingMode == org.skepsun.kototoro.reader.novel.ReadingMode.SCROLL,
			),
		)
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
		if (state.settings?.readingMode != org.skepsun.kototoro.reader.novel.ReadingMode.SCROLL) return
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
		_uiState.value = state.copy(
			chapterIndex = chapter.chapterIndex,
			chapterTitle = chapter.chapterTitle,
			content = chapter.content,
			translation = chapter.translation,
			scrollPosition = chapter.scrollPosition,
			imageContext = chapter.imageContext,
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
		_uiState.value = _uiState.value.copy(settings = settings, settingsSheetVisible = true)
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
			chapters = chapters,
			currentChapterIndex = currentChapterIndex,
		)
	}

	fun dismissChapters() {
		_uiState.value = _uiState.value.copy(chaptersSheetVisible = false)
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
		_uiState.value = _uiState.value.copy(ttsControlsVisible = true)
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
