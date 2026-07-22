package org.skepsun.kototoro.reader.novel.compose

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.reader.novel.NovelChapterTranslation
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
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
)

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

	fun publishChapter(
		chapterIndex: Int,
		chapterTitle: String,
		content: String,
		settings: NovelReaderSettings,
		translation: NovelChapterTranslation?,
	) {
		_uiState.value = _uiState.value.copy(
			chapterIndex = chapterIndex,
			chapterTitle = chapterTitle,
			content = content,
			settings = settings,
			translation = translation,
			imageContext = NovelComposeImageContext(),
		)
	}

	fun publishImageContext(imageContext: NovelComposeImageContext) {
		_uiState.value = _uiState.value.copy(imageContext = imageContext)
	}

	fun publishTranslation(translation: NovelChapterTranslation?) {
		_uiState.value = _uiState.value.copy(translation = translation)
	}

	fun publishPosition(position: NovelReadingPosition) {
		_uiState.value = _uiState.value.copy(position = position)
	}

	fun publishScrollPosition(position: NovelComposeScrollPosition) {
		_uiState.value = _uiState.value.copy(scrollPosition = position)
	}
}
