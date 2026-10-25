package org.skepsun.kototoro.desktop.runtime

import org.skepsun.kototoro.core.source.SourceChapter

/**
 * Android's `DeleteReadChaptersUseCase` cannot read a per-chapter flag because Android never stored one: a chapter is
 * "read" when it sits before the chapter the reading history stopped at, on the same branch of the chapter list. Windows
 * keeps the same history rows, so it derives the answer with the same walk — number order, one branch, everything
 * strictly before the current chapter.
 *
 * Returns an empty set when the history points at a chapter the list does not contain (a stale or remote-only id), which
 * is Android's `findById(...) ?: return null`: without the current chapter there is no "before it" to trust.
 */
fun desktopReadChapterIds(chapters: List<SourceChapter>, currentChapterId: Long): Set<Long> {
    val sorted = chapters.sortedBy { it.number }
    val current = sorted.firstOrNull { it.id == currentChapterId } ?: return emptySet()
    return sorted
        .filter { it.branch == current.branch }
        .takeWhile { it.id != current.id }
        .mapTo(LinkedHashSet()) { it.id }
}

/**
 * The chapter universe one saved work is judged in: the rows the library restored from the source, plus the chapters that
 * were downloaded (they carry their own number and branch, so a work whose source list never made it into the database
 * still yields an order). Android merges the same two lists and keeps the first row per id.
 */
fun desktopChapterCandidates(saved: List<SourceChapter>, downloaded: List<SourceChapter>): List<SourceChapter> =
    (saved + downloaded).distinctBy { it.id }

/**
 * The saved chapters one work has already read: the walk above against the merged chapter universe, narrowed to the
 * chapters that really are saved. Nothing outside [downloaded] is ever returned, so a source list that mentions a
 * thousand chapters cannot make the cleanup try to delete what the work never stored.
 */
fun desktopReadSavedChapters(
    saved: List<SourceChapter>,
    downloaded: List<SourceChapter>,
    currentChapterId: Long,
): List<SourceChapter> {
    val read = desktopReadChapterIds(desktopChapterCandidates(saved, downloaded), currentChapterId)
    return downloaded.distinctBy { it.id }.filter { it.id in read }
}
