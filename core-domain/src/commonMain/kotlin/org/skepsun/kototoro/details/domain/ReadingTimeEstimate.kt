package org.skepsun.kototoro.details.domain

import kotlin.math.roundToLong

/**
 * The details page's "time left" estimate, shared by Android and iOS. Only page-based works get one: a chapter is
 * taken as 20 pages at the reader's measured time per page (10 s without enough statistics). Novels (whose "pages"
 * are lines) and videos (whose episode length is unknown here) get none, rather than a number that means nothing.
 */
object ReadingTimeEstimate {
    const val PAGES_PER_CHAPTER = 20
    const val DEFAULT_SECONDS_PER_PAGE = 10L
    /** Pages read before a work's own pace replaces the reader's overall pace. */
    const val MIN_PAGES_FOR_WORK_PACE = 10

    /**
     * Seconds left for [chapterCount] chapters of a work of [contentType] (Android's content type names, `HENTAI_`
     * prefix allowed), [progress] (0–1) through it when reading has started; null below a minute or for works
     * without pages.
     */
    fun remainingSeconds(contentType: String, chapterCount: Int, progress: Float?, secondsPerPage: Long): Long? {
        val kind = contentType.removePrefix("HENTAI_")
        if (kind == "NOVEL" || kind == "VIDEO" || chapterCount <= 0) return null
        val pace = secondsPerPage.takeIf { it > 0 } ?: DEFAULT_SECONDS_PER_PAGE
        val total = PAGES_PER_CHAPTER * pace * chapterCount
        val left = progress?.let { (total * (1f - it.coerceIn(0f, 1f))).roundToLong() } ?: total
        return left.takeIf { it >= 60 }
    }
}
