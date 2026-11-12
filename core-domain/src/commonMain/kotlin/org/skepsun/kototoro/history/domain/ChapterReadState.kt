package org.skepsun.kototoro.history.domain

/** A chapter's place in reading order; enough for every platform to compare chapters without its own chapter model. */
class ChapterOrder(val id: Long, val number: Float, val volume: Int)

/** A known volume takes precedence over chapter numbers, which may restart in the next volume. */
fun chapterIsAfter(number: Float, volume: Int, currentNumber: Float, currentVolume: Int): Boolean =
    if (volume > 0 && currentVolume > 0 && volume != currentVolume) volume > currentVolume else number > currentNumber

private fun ChapterOrder.isAfter(other: ChapterOrder) = chapterIsAfter(number, volume, other.number, other.volume)

/**
 * The furthest chapter read once [openedChapterId] was opened. History only follows the last opened chapter, so going
 * back to an earlier one must not pull this back with it. [previousFurthestId] is 0 when nothing is recorded.
 */
fun furthestReadChapterId(previousFurthestId: Long, openedChapterId: Long, chapters: List<ChapterOrder>?): Long {
    if (previousFurthestId == 0L) return openedChapterId
    val previous = chapters?.firstOrNull { it.id == previousFurthestId } ?: return openedChapterId
    val opened = chapters.firstOrNull { it.id == openedChapterId } ?: return previousFurthestId
    return if (opened.isAfter(previous)) openedChapterId else previousFurthestId
}

/**
 * The chapter up to which everything counts as read: the later of the one the reader is on and the furthest one ever
 * read, so reopening chapter 10 after reading 20 leaves 11-20 read. Null when neither is known.
 */
fun chapterReadThrough(current: ChapterOrder?, furthest: ChapterOrder?): ChapterOrder? = when {
    current == null -> furthest
    furthest != null && furthest.isAfter(current) -> furthest
    else -> current
}
