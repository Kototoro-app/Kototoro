package org.skepsun.kototoro.details.ui.model

import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.data.ReadingTime
import org.skepsun.kototoro.parsers.model.ContentChapter

private const val EPUB_HISTORY_MATCH_WINDOW = 1_000_000L

data class HistoryInfo(
	val totalChapters: Int,
	val currentChapter: Int,
	val history: ContentHistory?,
	val isIncognitoMode: Boolean,
	val isChapterMissing: Boolean,
	val canDownload: Boolean,
	val estimatedTime: ReadingTime?,
) {
	val isValid: Boolean
		get() = totalChapters >= 0

	val canContinue
		get() = currentChapter >= 0

	val percent: Float
		get() = if (history != null && (canContinue || isChapterMissing)) {
			history.percent
		} else {
			0f
		}
}

fun HistoryInfo(
	manga: ContentDetails?,
	branch: String?,
	history: ContentHistory?,
	isIncognitoMode: Boolean,
	estimatedTime: ReadingTime?,
): HistoryInfo {
	val chapters = if (manga?.chapters?.isEmpty() == true) {
		emptyList()
	} else {
		manga?.chapters?.get(branch)
	}
	val currentChapter = if (history != null && !chapters.isNullOrEmpty()) {
		chapters.findChapterByHistory(history)?.let(chapters::indexOf) ?: -1
	} else {
		-2
	}
	// Check if chapter is missing
	// For EPUB chapters, also check if the history chapter ID is a parent chapter ID
	// by checking if any internal chapter ID is within 1000000 of the history chapter ID
	val isChapterMissing = if (history != null && manga?.isLoaded == true) {
		manga.allChapters.findChapterByHistory(history) == null
	} else {
		false
	}
	
	if (history != null && manga?.isLoaded == true) {
		android.util.Log.d("HistoryInfo", "Checking chapter: history.chapterId=${history.chapterId}")
		android.util.Log.d("HistoryInfo", "Total allChapters: ${manga.allChapters.size}")
		android.util.Log.d("HistoryInfo", "First 3 chapter IDs: ${manga.allChapters.take(3).map { it.id }}")
		android.util.Log.d("HistoryInfo", "currentChapter index=$currentChapter")
		if (currentChapter >= 0 && chapters != null && currentChapter < chapters.size) {
			android.util.Log.d("HistoryInfo", "Matched chapter: id=${chapters[currentChapter].id}, title=${chapters[currentChapter].title}")
		}
		android.util.Log.d("HistoryInfo", "isChapterMissing=$isChapterMissing")
	}
	
	return HistoryInfo(
		totalChapters = chapters?.size ?: -1,
		currentChapter = currentChapter,
		history = history,
		isIncognitoMode = isIncognitoMode,
		isChapterMissing = isChapterMissing,
		canDownload = manga?.isLocal == false,
		estimatedTime = estimatedTime,
	)
}

internal fun List<ContentChapter>.findChapterByHistory(history: ContentHistory?): ContentChapter? {
	history ?: return null
	firstOrNull { it.id == history.chapterId }?.let {
		return it
	}

	val parentChapter = history.parentChapterId?.let { parentId ->
		firstOrNull { it.id == parentId }
	}
	if (parentChapter != null) {
		firstOrNull { chapter ->
			chapter.id == history.chapterId &&
				chapter.isEpubInternalChapter() &&
				chapter.url.startsWith(parentChapter.url)
		}?.let {
			return it
		}
	}

	val canUseNearbyMatch = history.parentChapterId != null || any { it.isEpubInternalChapter() }
	if (!canUseNearbyMatch) {
		return null
	}
	return asSequence()
		.filter { history.parentChapterId != null || it.isEpubInternalChapter() }
		.mapNotNull { chapter ->
			val diff = kotlin.math.abs(chapter.id - history.chapterId)
			if (diff in 1..EPUB_HISTORY_MATCH_WINDOW) {
				chapter to diff
			} else {
				null
			}
		}
		.minByOrNull { it.second }
		?.first
}

private fun ContentChapter.isEpubInternalChapter(): Boolean =
	url.startsWith("epub://") ||
		url.startsWith("localepub://") ||
		url.contains("#chapter/")
