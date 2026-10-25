package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.library.BookNotesStore
import org.skepsun.kototoro.notes.data.MediaNoteEntity

/**
 * Android's book notes on iOS: panel crops and video time points in the shared `media_notes` table, and the notes
 * overview counts (highlights, thoughts, bookmarks) computed by the rule Android uses.
 */
class IosNotes internal constructor(private val store: BookNotesStore, private val library: IosLibrary) {

    /** Works with notes, newest first; works no longer in the library are left out. */
    suspend fun summaries(): List<IosNoteSummary> {
        val counts = store.summaries()
        val works = library.records.findMany(counts.map { it.mangaId }).associateBy { it.id }
        return counts.mapNotNull { count ->
            works[count.mangaId]?.let { IosNoteSummary(IosSourceContent(it), count.highlights, count.thoughts,
                count.bookmarks, count.lastUpdatedAt) }
        }
    }

    suspend fun mediaNotes(content: IosSourceContent): List<IosMediaNote> =
        store.mediaNotes(content.value.id).map(::IosMediaNote)

    /** A crop of page [page] of [chapter], saved at [imagePath]; [left]…[bottom] place it on the page (0–1). */
    suspend fun saveCrop(content: IosSourceContent, chapter: IosSourceChapter, page: Int, imagePath: String,
                         note: String?, left: Float, top: Float, right: Float, bottom: Float): Long =
        store.saveMangaCrop(content.value.id, chapter.value.id, chapterIndex(content, chapter), page, imagePath, note,
            left, top, right, bottom)

    suspend fun saveVideo(content: IosSourceContent, chapter: IosSourceChapter, positionMs: Long, durationMs: Long,
                          imagePath: String?, note: String?): Long =
        store.saveVideo(content.value.id, chapter.value.id, chapterIndex(content, chapter), positionMs, durationMs,
            imagePath, null, note)

    suspend fun updateNote(id: Long, note: String?) = store.updateNote(id, note)

    /** Removes the note; returns its picture's path so the caller can delete the file. */
    suspend fun delete(id: Long): String? {
        val path = store.find(id)?.imagePath
        store.delete(id)
        return path
    }

    private fun chapterIndex(content: IosSourceContent, chapter: IosSourceChapter): Int =
        content.value.chapters.orEmpty().indexOfFirst { it.id == chapter.value.id }.coerceAtLeast(0)
}

data class IosNoteSummary(val content: IosSourceContent, val highlights: Int, val thoughts: Int, val bookmarks: Int,
    val updatedAt: Long) {
    val total: Int get() = highlights + thoughts + bookmarks
}

class IosMediaNote internal constructor(value: MediaNoteEntity) {
    val id: Long = value.id
    val chapterId: String = value.chapterId.toString()
    val chapterIndex: Int = value.chapterIndex
    val isVideo: Boolean = value.mediaType == MediaNoteEntity.MEDIA_TYPE_VIDEO_TIMESTAMP
    val page: Int = value.page
    val positionMs: Long = value.positionMs
    val durationMs: Long = value.durationMs
    val imagePath: String? = value.imagePath
    val note: String? = value.note
    val createdAt: Long = value.createdAt
}
