package org.skepsun.kototoro.tracker.domain

import org.skepsun.kototoro.tracker.data.TrackEntity
import kotlin.math.roundToInt

/*
 * The tracker's platform-independent rules, shared by Android's CheckNewChaptersUseCase/TrackingRepository and the
 * Windows tracker: how a fresh chapter list compares with the last known chapter, and how a check updates the row.
 */

/** [isValid] is false when the comparison cannot be trusted (first check, or the last chapter disappeared). */
data class TrackComparison<C>(val newChapters: List<C>, val isValid: Boolean)

/** A track with no last chapter has never been checked successfully (or the work had no chapters then). */
fun isEmptyTrack(lastChapterId: Long): Boolean = lastChapterId == 0L

/** Chapters after the track's last chapter, in the branch the reader follows. */
fun <C> compareTrackedChapters(chapters: List<C>, id: (C) -> Long, lastChapterId: Long): TrackComparison<C> {
    if (isEmptyTrack(lastChapterId)) return TrackComparison(emptyList(), isValid = false)
    val newChapters = chapters.takeLastWhile { id(it) != lastChapterId }
    return when {
        newChapters.isEmpty() -> TrackComparison(emptyList(), isValid = chapters.lastOrNull()?.let(id) == lastChapterId)
        // The last known chapter is gone: re-anchor silently instead of reporting every chapter as new.
        newChapters.size == chapters.size -> TrackComparison(emptyList(), isValid = false)
        else -> TrackComparison(newChapters, isValid = true)
    }
}

/**
 * The row after a successful check. An invalid comparison resets the counter; a valid one without new chapters keeps
 * the unread counter that is still pending.
 */
fun TrackEntity.afterSuccessfulCheck(
    anchorMangaId: Long,
    lastChapterId: Long,
    newChapterCount: Int,
    isValid: Boolean,
    lastChapterDate: Long,
    now: Long,
): TrackEntity = TrackEntity(
    mangaId = anchorMangaId,
    lastChapterId = lastChapterId,
    newChapters = when {
        !isValid -> 0
        newChapterCount > 0 -> newChapterCount
        else -> newChapters
    },
    lastCheckTime = now,
    lastChapterDate = if (lastChapterDate == 0L) this.lastChapterDate else lastChapterDate,
    lastResult = if (newChapterCount > 0) TrackEntity.RESULT_HAS_UPDATE else TrackEntity.RESULT_NO_UPDATE,
    lastError = null,
)

/** A failed check keeps the last known state and records the error. */
fun TrackEntity.afterFailedCheck(error: String?, now: Long): TrackEntity = TrackEntity(
    mangaId = mangaId,
    lastChapterId = lastChapterId,
    newChapters = newChapters,
    lastCheckTime = now,
    lastChapterDate = lastChapterDate,
    lastResult = TrackEntity.RESULT_FAILED,
    lastError = error,
)

/** The date shown for a check: the newest new chapter's upload date (now if the source has none), else the last chapter's. */
fun trackedLastChapterDate(newChapterDates: List<Long>, lastChapterDate: Long?, now: Long): Long =
    newChapterDates.lastOrNull()?.let { if (it == 0L) now else it } ?: (lastChapterDate ?: 0L)

/**
 * Hours between tracker runs: a full check of all tracks every 18 hours at frequency 1, split into runs of
 * [batchSize] tracks, never more often than every 2 hours. A frequency of 0 or less disables scheduled checks (null).
 */
fun trackerCheckIntervalHours(trackCount: Int, batchSize: Int, frequency: Float): Int? {
    if (frequency <= 0f) return null
    val runsPerFullCheck = ((trackCount + batchSize - 1L) / batchSize).toInt().coerceAtLeast(1)
    // Integer 18 / runs, as Android's scheduler always computed it.
    return (18 / runsPerFullCheck / frequency).roundToInt().coerceAtLeast(2)
}

/** Track log entries keep the new chapter names one per line. */
fun trackLogChapters(names: List<String>): String = names.joinToString(separator = "\n")
