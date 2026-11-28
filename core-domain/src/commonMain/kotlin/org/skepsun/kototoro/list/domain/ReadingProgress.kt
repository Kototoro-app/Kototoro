package org.skepsun.kototoro.list.domain

import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode.CHAPTERS_LEFT
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode.CHAPTERS_READ
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode.NONE
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode.PERCENT_LEFT
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode.PERCENT_READ
import kotlin.math.ceil
import kotlin.math.roundToInt

data class ReadingProgress(
    val percent: Float,
    val totalChapters: Int,
    val mode: ProgressIndicatorMode,
) {

    val percentLeft: Float
        get() = 1f - percent

    val chapters: Int
        get() = ceil(totalChapters * percent).toInt()

    val chaptersLeft: Int
        get() = totalChapters - chapters

    fun isValid() = when (mode) {
        NONE -> false
        PERCENT_READ,
        PERCENT_LEFT -> percent in 0f..1f

        CHAPTERS_READ,
        CHAPTERS_LEFT -> totalChapters > 0 && percent in 0f..1f
    }

    fun isCompleted() = isCompleted(percent)

    fun isReversed() = mode == PERCENT_LEFT || mode == CHAPTERS_LEFT

    companion object {

        const val PROGRESS_NONE = -1f
        const val PROGRESS_COMPLETED = 1f

        /** Repository/card progress snaps completed histories to one before validation. */
        fun fromHistory(percent: Float, totalChapters: Int, mode: ProgressIndicatorMode): ReadingProgress? =
            ReadingProgress(
                percent = normalizeCompleted(percent),
                totalChapters = totalChapters,
                mode = mode,
            ).takeIf { it.isValid() }

        internal fun estimateUnreadChapters(percent: Float?, totalChapters: Int?): Int? = when {
            percent == null -> null
            isCompleted(percent) -> 0
            else -> fromHistory(percent, totalChapters ?: 0, CHAPTERS_LEFT)?.chaptersLeft
        }

        fun isValid(percent: Float) = percent in 0f..1f

        fun isCompleted(percent: Float) = isReadingCompleted(percent)

        fun normalizeCompleted(percent: Float): Float =
            if (isCompleted(percent)) PROGRESS_COMPLETED else percent

        fun percentToString(percent: Float): String {
            if (!isValid(percent)) return "0"
            if (isCompleted(percent)) return "100"

            // Compare in the original fraction space so multiplication cannot shift an exact
            // whole percentage (such as 0.53f) or its neighboring Float across the boundary.
            val nearest = (percent * 100f).roundToInt()
            val wholePercent = if (percent < nearest / 100f) nearest - 1 else nearest
            return wholePercent.toString()
        }
    }
}
