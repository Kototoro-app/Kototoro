package org.skepsun.kototoro.reader.ui

import android.util.Log
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

internal fun resolveVisiblePageSelection(
	pages: List<ReaderPage>,
	lowerPos: Int,
	upperPos: Int,
	currentChapterId: Long?,
	boundsPageOffset: Int,
): Int {
	val centerPos = (lowerPos + upperPos) / 2
	if (lowerPos < 0 || upperPos < 0 || pages.isEmpty()) {
		Log.d(
			LOG_TAG,
			"resolveVisiblePageSelection: invalid range lower=$lowerPos upper=$upperPos pages=${pages.size} -> $centerPos",
		)
		return centerPos
	}
	val lastIndex = pages.lastIndex
	val safeLower = lowerPos.coerceIn(0, lastIndex)
	val safeUpper = upperPos.coerceIn(0, lastIndex)
	val lowerPage = pages[safeLower]
	val upperPage = pages[safeUpper]
	if (lowerPage.chapterId != upperPage.chapterId) {
		val selected = currentChapterId?.let { chapterId ->
			(safeUpper downTo safeLower).firstOrNull { pages[it].chapterId == chapterId }
		} ?: safeLower
		Log.d(
			LOG_TAG,
			"resolveVisiblePageSelection: crossChapter lower=$safeLower(${lowerPage.chapterId}:${lowerPage.index}) " +
				"upper=$safeUpper(${upperPage.chapterId}:${upperPage.index}) currentChapterId=$currentChapterId -> $selected",
		)
		return selected
	}
	val chapterStart = pages.indexOfFirst { it.chapterId == lowerPage.chapterId }
	val chapterEnd = pages.indexOfLast { it.chapterId == upperPage.chapterId }
	val selected = when {
		chapterEnd >= 0 && safeUpper >= chapterEnd - boundsPageOffset -> safeUpper
		chapterStart >= 0 && safeLower <= chapterStart + boundsPageOffset -> safeLower
		else -> (safeLower + safeUpper) / 2
	}
	Log.d(
		LOG_TAG,
		"resolveVisiblePageSelection: sameChapter lower=$safeLower(${lowerPage.chapterId}:${lowerPage.index}) " +
			"upper=$safeUpper(${upperPage.chapterId}:${upperPage.index}) currentChapterId=$currentChapterId -> $selected",
	)
	return selected
}

internal fun resolveWebtoonVisiblePageSelection(
	pages: List<ReaderPage>,
	lowerPos: Int,
	upperPos: Int,
	currentChapterId: Long?,
	activePageKey: Long,
	boundsPageOffset: Int,
): Int {
	val activePos = pages.indexOfFirst { it.readerKey == activePageKey }
	if (activePos < 0 || lowerPos !in pages.indices || upperPos !in pages.indices || lowerPos > upperPos) {
		return resolveVisiblePageSelection(pages, lowerPos, upperPos, currentChapterId, boundsPageOffset)
	}
	if (currentChapterId == null || pages[activePos].chapterId == currentChapterId) return activePos

	return (upperPos downTo lowerPos).firstOrNull { pages[it].chapterId == currentChapterId }
		?: activePos
}

internal fun resolveReaderInitialPagePosition(pages: List<ReaderPage>, state: ReaderState?): Int {
	if (state == null) return 0
	return pages.indexOfFirst { page ->
		page.chapterId == state.chapterId && page.index == state.page
	}.takeIf { it >= 0 } ?: 0
}

internal fun resolveReaderCurrentPagePosition(
	pages: List<ReaderPage>,
	currentPageKey: Long?,
	fallbackState: ReaderState?,
): Int {
	currentPageKey?.let { pageKey ->
		pages.indexOfFirst { it.readerKey == pageKey }.takeIf { it >= 0 }?.let { return it }
	}
	return resolveReaderInitialPagePosition(pages, fallbackState)
}

internal fun resolvePagedReaderAnchorPosition(
	pageKeys: List<Long>,
	anchorPageKey: Long?,
	fallbackPosition: Int,
): Int? {
	if (pageKeys.isEmpty()) return null
	anchorPageKey?.let { key ->
		pageKeys.indexOf(key).takeIf { it >= 0 }?.let { return it }
	}
	return fallbackPosition.coerceIn(pageKeys.indices)
}

private const val LOG_TAG = "ReaderDebug"
