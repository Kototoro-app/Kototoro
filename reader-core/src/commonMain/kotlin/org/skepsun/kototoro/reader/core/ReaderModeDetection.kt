package org.skepsun.kototoro.reader.core

import kotlin.math.roundToInt

/**
 * Automatic reading-mode detection, shared so every platform measures the same page the same way.
 *
 * Android's `DetectReaderModeUseCase` and the Windows reader both ask this object: a content whose
 * page is far taller than wide is a webtoon, and a paged reader would only show thin slices of it.
 */
object ReaderModeDetection {

    /** A page counts as a webtoon page while `width * MIN_WEBTOON_RATIO` stays below its height. */
    const val MIN_WEBTOON_RATIO = 1.8f

    /**
     * The page to measure: the one at 30% of the chapter, which skips a cover or a colour spread at
     * the front without walking the whole chapter. Callers fetch it with `getOrNull`, because a
     * chapter short enough to round up to [pageCount] has no such page.
     */
    fun probePageIndex(pageCount: Int): Int = (pageCount * PROBE_POSITION).roundToInt()

    /** Whether a page of [width] by [height] is tall enough to read as a webtoon strip. */
    fun isWebtoonPage(width: Int, height: Int): Boolean = width * MIN_WEBTOON_RATIO < height

    private const val PROBE_POSITION = 0.3
}
