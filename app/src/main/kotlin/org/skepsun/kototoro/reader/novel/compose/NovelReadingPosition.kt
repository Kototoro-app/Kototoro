package org.skepsun.kototoro.reader.novel.compose

import org.skepsun.kototoro.reader.ui.ReaderState

/**
 * View-independent reading position. Both the legacy reader and Compose callbacks can publish
 * this value, so history and reading records do not depend on a rendering implementation.
 */
data class NovelReadingPosition(
	val chapterId: Long,
	val page: Int,
	val pageCount: Int,
	val chapterProgress: Float,
) {
	init {
		require(page >= 0)
		require(pageCount >= 0)
	}

	val normalizedChapterProgress: Float
		get() = chapterProgress.coerceIn(0f, 1f)

	fun toReaderState(): ReaderState = ReaderState(
		chapterId = chapterId,
		page = page,
		scroll = (normalizedChapterProgress * PROGRESS_SCALE).toInt(),
	)

	companion object {
		private const val PROGRESS_SCALE = 10_000f
	}
}
