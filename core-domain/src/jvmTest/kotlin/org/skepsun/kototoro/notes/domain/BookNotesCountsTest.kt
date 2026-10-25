package org.skepsun.kototoro.notes.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.skepsun.kototoro.bookmarks.data.BookmarkEntity
import org.skepsun.kototoro.notes.data.MediaNoteEntity
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingEntity

class BookNotesCountsTest {

    private fun marking(work: Long, note: String?, at: Long) = NovelMarkingEntity(mangaId = work, chapterId = 1,
        chapterIndex = 0, startOffset = 0, endOffset = 1, selectedText = "x", note = note, createdAt = at, updatedAt = at)

    private fun media(work: Long, note: String?, at: Long) = MediaNoteEntity(mangaId = work, chapterId = 1,
        chapterIndex = 0, mediaType = MediaNoteEntity.MEDIA_TYPE_MANGA_CROP, note = note, createdAt = at, updatedAt = at)

    private fun bookmark(work: Long, at: Long) = BookmarkEntity(mangaId = work, pageId = at, chapterId = 1, page = 0,
        scroll = 0, imageUrl = "", createdAt = at, percent = 0f)

    @Test
    fun countsHighlightsThoughtsAndBookmarksPerWorkNewestFirst() {
        val counts = summarizeBookNotes(
            markings = listOf(marking(1, null, 10), marking(1, "thought", 20)),
            bookmarks = mapOf(2L to listOf(bookmark(2, 50)), 3L to emptyList()),
            mediaNotes = listOf(media(1, " ", 30), media(2, "idea", 40)),
        )
        assertEquals(listOf(2L, 1L), counts.map { it.mangaId })
        assertEquals(BookNotesCounts(2, highlights = 0, thoughts = 1, bookmarks = 1, lastUpdatedAt = 50), counts[0])
        // A blank note is a highlight, not a thought.
        assertEquals(BookNotesCounts(1, highlights = 2, thoughts = 1, bookmarks = 0, lastUpdatedAt = 30), counts[1])
    }
}
