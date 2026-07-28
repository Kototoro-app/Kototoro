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
		val selected = when (currentChapterId) {
			lowerPage.chapterId -> safeLower
			upperPage.chapterId -> safeUpper
			else -> safeLower
		}
		Log.d(
			LOG_TAG,
			"resolveVisiblePageSelection: crossChapter lower=$safeLower(${lowerPage.chapterId}:${lowerPage.index}) " +
				"upper=$safeUpper(${upperPage.chapterId}:${upperPage.index}) currentChapterId=$currentChapterId -> $selected",
		)
		return selected
	}
	val selected = when {
		safeUpper >= lastIndex - boundsPageOffset -> safeUpper
		safeLower <= boundsPageOffset -> safeLower
		else -> (safeLower + safeUpper) / 2
	}
	Log.d(
		LOG_TAG,
		"resolveVisiblePageSelection: sameChapter lower=$safeLower(${lowerPage.chapterId}:${lowerPage.index}) " +
			"upper=$safeUpper(${upperPage.chapterId}:${upperPage.index}) currentChapterId=$currentChapterId -> $selected",
	)
	return selected
}

internal fun resolveReaderInitialPagePosition(pages: List<ReaderPage>, state: ReaderState?): Int {
	if (state == null) return 0
	return pages.indexOfFirst { page ->
		page.chapterId == state.chapterId && page.index == state.page
	}.takeIf { it >= 0 } ?: 0
}

private const val LOG_TAG = "ReaderDebug"
