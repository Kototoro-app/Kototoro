package org.skepsun.kototoro.core.library

import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.notes.data.MediaNoteEntity
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingEntity
import org.skepsun.kototoro.notes.domain.summarizeBookNotes
import org.skepsun.kototoro.notes.domain.BookNotesCounts

/** Android's media notes for the hosts without its repository: panel crops of a page and video time points. */
class BookNotesStore(
    private val database: MangaDatabase,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val media get() = database.getMediaNoteDao()

    suspend fun summaries(): List<BookNotesCounts> = summarizeBookNotes(
        database.getNovelMarkingDao().observeAll().first(),
        database.getBookmarksDao().observe().first().entries.associate { (work, marks) -> work.manga.id to marks },
        media.observeAll().first(),
    )

    suspend fun mediaNotes(mangaId: Long): List<MediaNoteEntity> = media.observe(mangaId).first()

    suspend fun find(id: Long): MediaNoteEntity? = media.findById(id)

    suspend fun saveMangaCrop(
        mangaId: Long, chapterId: Long, chapterIndex: Int, page: Int, imagePath: String, note: String?,
        cropLeft: Float = 0f, cropTop: Float = 0f, cropRight: Float = 1f, cropBottom: Float = 1f,
    ): Long = now().let { time ->
        media.insert(MediaNoteEntity(mangaId = mangaId, chapterId = chapterId, chapterIndex = chapterIndex,
            mediaType = MediaNoteEntity.MEDIA_TYPE_MANGA_CROP, page = page, imagePath = imagePath,
            note = note?.takeIf(String::isNotBlank), cropLeft = cropLeft, cropTop = cropTop, cropRight = cropRight,
            cropBottom = cropBottom, createdAt = time, updatedAt = time))
    }

    suspend fun saveVideo(
        mangaId: Long, chapterId: Long, chapterIndex: Int, positionMs: Long, durationMs: Long, imagePath: String?,
        quoteText: String?, note: String?,
    ): Long = now().let { time ->
        media.insert(MediaNoteEntity(mangaId = mangaId, chapterId = chapterId, chapterIndex = chapterIndex,
            mediaType = MediaNoteEntity.MEDIA_TYPE_VIDEO_TIMESTAMP, positionMs = positionMs, durationMs = durationMs,
            imagePath = imagePath, quoteText = quoteText, note = note?.takeIf(String::isNotBlank),
            createdAt = time, updatedAt = time))
    }

    suspend fun updateNote(id: Long, note: String?) = media.updateNote(id, note?.takeIf(String::isNotBlank), now())

    suspend fun delete(id: Long) = media.deleteById(id)
}

/**
 * Android's novel highlights (`novel_markings`) for the hosts without its repository: toggling a highlight over a
 * range of a chapter, its written thought, and removal. Offsets are each platform's own chapter text; readers place
 * a highlight with `relocateNovelMarking`, which follows the stored words when the texts differ.
 */
class NovelMarkingStore(
    private val database: MangaDatabase,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val dao get() = database.getNovelMarkingDao()

    suspend fun forWork(mangaId: Long): List<NovelMarkingEntity> = dao.observe(mangaId).first()

    /** Adds a highlight over `start until end`, or removes the identical one; true when one was added. */
    suspend fun toggle(mangaId: Long, chapterId: Long, chapterIndex: Int, start: Int, end: Int, text: String): Boolean {
        dao.find(mangaId, chapterId, start, end)?.let { dao.delete(it); return false }
        val time = now()
        dao.insert(NovelMarkingEntity(mangaId = mangaId, chapterId = chapterId, chapterIndex = chapterIndex,
            startOffset = start, endOffset = end, selectedText = text, createdAt = time, updatedAt = time))
        return true
    }

    suspend fun updateNote(id: Long, note: String?) = dao.updateNote(id, note?.takeIf(String::isNotBlank), now())

    suspend fun delete(id: Long) = dao.deleteById(id)
}
