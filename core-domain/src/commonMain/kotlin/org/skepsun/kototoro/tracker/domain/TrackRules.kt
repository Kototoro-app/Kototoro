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

/**
 * Chapters after the track's last chapter, in the branch the reader follows. Remote works find their new chapters
 * where the list is stored ([storeChapterSnapshot]); this anchor comparison is for local copies, which store none.
 *
 * When the last known chapter is gone (the source changed its chapter URLs, or the chapter was taken down and
 * re-uploaded), chapters uploaded after [lastChapterDate] are reported instead, given [uploadDate]; failing that,
 * the chapters after the reading position, [historyChapterId], as in Kotatsu-Redo. Either is only trusted while it
 * is a minority of the list: a source that re-dated everything would otherwise report every chapter as new, so then
 * the track is re-anchored silently.
 */
fun <C> compareTrackedChapters(
    chapters: List<C>,
    id: (C) -> Long,
    lastChapterId: Long,
    uploadDate: ((C) -> Long)? = null,
    lastChapterDate: Long = 0L,
    historyChapterId: Long = 0L,
): TrackComparison<C> {
    if (isEmptyTrack(lastChapterId)) return TrackComparison(emptyList(), isValid = false)
    val newChapters = chapters.takeLastWhile { id(it) != lastChapterId }
    return when {
        newChapters.isEmpty() -> TrackComparison(emptyList(), isValid = chapters.lastOrNull()?.let(id) == lastChapterId)
        // The last known chapter is gone: fall back to upload dates, then the reading position, else re-anchor
        // silently instead of reporting every chapter as new.
        newChapters.size == chapters.size -> (
            chaptersUploadedAfter(chapters, uploadDate, lastChapterDate)
                ?: chaptersAfterReading(chapters, id, historyChapterId)
            )?.let { TrackComparison(it, isValid = true) }
            ?: TrackComparison(emptyList(), isValid = false)
        else -> TrackComparison(newChapters, isValid = true)
    }
}

private fun <C> chaptersAfterReading(chapters: List<C>, id: (C) -> Long, historyChapterId: Long): List<C>? {
    if (historyChapterId == 0L) return null
    val index = chapters.indexOfFirst { id(it) == historyChapterId }
    if (index < 0) return null
    return chapters.drop(index + 1).takeIf { it.isNotEmpty() && it.size * 2 < chapters.size }
}

private fun <C> chaptersUploadedAfter(chapters: List<C>, uploadDate: ((C) -> Long)?, lastChapterDate: Long): List<C>? {
    if (uploadDate == null || lastChapterDate <= 0L) return null
    val newer = chapters.filter { uploadDate(it) > lastChapterDate }
    return newer.takeIf { it.isNotEmpty() && it.size * 2 < chapters.size }
}

/** New chapters uploaded longer ago than this are not reported; see [reportedNewChapters]. */
const val STALE_NEW_CHAPTER_DAYS = 90L

/**
 * The [newChapters] worth reporting, after Kotatsu-Redo: chapters at or before the reading position
 * ([readChapterId] in [branch]) were read already, and chapters uploaded more than [STALE_NEW_CHAPTER_DAYS] before
 * [now] are more likely back-filled by the source or the result of a wrong comparison than a release. Chapters
 * without an upload date are kept. The filtered chapters still count as known.
 */
fun <C> reportedNewChapters(
    newChapters: List<C>,
    branch: List<C>,
    id: (C) -> Long,
    uploadDate: (C) -> Long,
    readChapterId: Long,
    now: Long,
): List<C> {
    if (newChapters.isEmpty()) return newChapters
    val readIndex = if (readChapterId == 0L) -1 else branch.indexOfFirst { id(it) == readChapterId }
    val positions = branch.withIndex().associate { (index, chapter) -> id(chapter) to index }
    val staleBefore = now - STALE_NEW_CHAPTER_DAYS * 86_400_000L
    return newChapters.filter { chapter ->
        val position = positions[id(chapter)] ?: Int.MAX_VALUE
        val date = uploadDate(chapter)
        position > readIndex && (date <= 0L || date >= staleBefore)
    }
}

/** Where a track's anchor and pending counter stand after reading, see [trackAfterReading]. */
data class ReadingTrackState(val anchorIndex: Int, val pending: Int)

/**
 * The track after reading chapter [readIndex] of a branch whose chapters run to [lastIndex], for a counter whose
 * chapters carry no identities: the [pending] counted chapters are taken as the tail of the branch, as they always
 * were, so reading inside that tail counts down to the chapters after it.
 *
 * The anchor, the track's last chapter at [anchorIndex] (-1 when it is not in the branch), only moves forward to what
 * was read. Chapters after it that the tracker has not seen yet stay after it, so the next check still reports them;
 * moving the anchor to the end of the list the reader happened to load would swallow them. Without an anchor in the
 * branch nothing keeps them apart, and the anchor goes to the end as before.
 */
fun trackAfterReading(readIndex: Int, anchorIndex: Int, lastIndex: Int, pending: Int): ReadingTrackState {
    val anchor = if (anchorIndex >= 0) maxOf(anchorIndex, readIndex) else lastIndex
    val left = when {
        pending <= 0 -> 0
        readIndex < 0 -> pending
        readIndex >= lastIndex - pending + 1 -> lastIndex - readIndex
        else -> pending
    }
    return ReadingTrackState(anchorIndex = anchor, pending = left)
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
