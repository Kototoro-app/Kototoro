package org.skepsun.kototoro.notes.domain

import org.skepsun.kototoro.bookmarks.data.BookmarkEntity
import org.skepsun.kototoro.notes.data.MediaNoteEntity
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingEntity

/**
 * What one work holds in the notes overview (Android's `BookNotesSummary` counts): highlights are markings and
 * media notes without a written thought, thoughts are those with one, plus the work's bookmarks.
 */
data class BookNotesCounts(
    val mangaId: Long,
    val highlights: Int,
    val thoughts: Int,
    val bookmarks: Int,
    val lastUpdatedAt: Long,
) {
    val total: Int get() = highlights + thoughts + bookmarks
}

/** Per-work counts, newest first, works without notes left out; one pass over each list. */
fun summarizeBookNotes(
    markings: List<NovelMarkingEntity>,
    bookmarks: Map<Long, List<BookmarkEntity>>,
    mediaNotes: List<MediaNoteEntity>,
): List<BookNotesCounts> {
    val markingsByWork = markings.groupBy { it.mangaId }
    val mediaByWork = mediaNotes.groupBy { it.mangaId }
    return (markingsByWork.keys + bookmarks.keys + mediaByWork.keys).mapNotNull { id ->
        val marks = markingsByWork[id].orEmpty()
        val media = mediaByWork[id].orEmpty()
        val marked = bookmarks[id].orEmpty()
        val thoughts = marks.count { !it.note.isNullOrBlank() } + media.count { !it.note.isNullOrBlank() }
        val highlights = marks.size + media.size - thoughts
        val last = maxOf(marks.maxOfOrNull { it.updatedAt } ?: 0L, media.maxOfOrNull { it.updatedAt } ?: 0L,
            marked.maxOfOrNull { it.createdAt } ?: 0L)
        BookNotesCounts(id, highlights, thoughts, marked.size, last).takeIf { it.total > 0 }
    }.sortedByDescending { it.lastUpdatedAt }
}
