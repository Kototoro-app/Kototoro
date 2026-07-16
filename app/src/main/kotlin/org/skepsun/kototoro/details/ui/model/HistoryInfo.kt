package org.skepsun.kototoro.details.ui.model

import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.details.data.ContentDetails
import org.skepsun.kototoro.details.data.ReadingTime
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.core.model.getMergeKey
import org.skepsun.kototoro.core.model.mergeRepeated
import org.skepsun.kototoro.core.model.isManga
import org.skepsun.kototoro.core.model.getContentType

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
	isMergeRepeatedChapters: Boolean = false,
): HistoryInfo {
	val contentType = manga?.toContent()?.source?.getContentType()
	val useMerge = isMergeRepeatedChapters && contentType.isManga()
	val chapters = if (manga?.chapters?.isEmpty() == true) {
		emptyList()
	} else if (useMerge && manga != null) {
		val allBranches = manga.chapters.keys.toList()
		val rawChapters = allBranches.flatMap { manga.chapters[it].orEmpty() }
		rawChapters.mergeRepeated()
	} else {
		manga?.chapters?.get(branch)
	}
	var currentChapter = if (history != null && !chapters.isNullOrEmpty()) {
		var index = chapters.findChapterByHistory(history)?.let(chapters::indexOf) ?: -1
		if (index == -1 && useMerge && manga != null) {
			val historyChapter = manga.allChapters.find { it.id == history.chapterId }
			if (historyChapter != null) {
				val historyKey = historyChapter.getMergeKey()
				index = chapters.indexOfFirst { it.getMergeKey() == historyKey }
			}
		}
		index
	} else {
		-2
	}
	if (history != null && history.percent >= 0.99999f && !chapters.isNullOrEmpty() && currentChapter >= 0) {
		val sortedChapters = chapters.sortedBy { it.number }
		val currentInSorted = sortedChapters.indexOfFirst { it.id == chapters[currentChapter].id }
		if (currentInSorted != -1 && currentInSorted + 1 < sortedChapters.size) {
			val nextChapter = sortedChapters[currentInSorted + 1]
			val nextIndexInRaw = chapters.indexOfFirst { it.id == nextChapter.id }
			if (nextIndexInRaw != -1) {
				currentChapter = nextIndexInRaw
			}
		}
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
