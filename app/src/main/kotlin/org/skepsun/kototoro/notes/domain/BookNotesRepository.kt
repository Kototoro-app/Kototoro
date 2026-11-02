package org.skepsun.kototoro.notes.domain

import android.content.Context
import androidx.core.net.toUri
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.notes.data.MediaNoteEntity
import java.io.File
import javax.inject.Inject

@Reusable
class BookNotesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangaDatabase,
) {

    fun observeSummaries(): Flow<List<BookNotesSummary>> = combine(
        database.getNovelMarkingDao().observeAll(),
        database.getBookmarksDao().observe(),
        database.getMediaNoteDao().observeAll(),
    ) { markings, bookmarksMap, mediaNotes ->
        // Counts come from the rule iOS shares; works are looked up by id once instead of scanning the keys per work.
        val worksById = bookmarksMap.keys.associateBy { it.manga.id }
        val counts = summarizeBookNotes(
            markings,
            bookmarksMap.entries.associate { (work, marks) -> work.manga.id to marks },
            mediaNotes,
        )
        if (counts.isEmpty()) return@combine emptyList<BookNotesSummary>()
        val ids = counts.map { it.mangaId }
        val historyMap = database.getHistoryDao().findAllByMangaIds(ids).associateBy { it.mangaId }

        counts.mapNotNull { count ->
            val mangaId = count.mangaId
            val manga = (worksById[mangaId] ?: database.getMangaDao().find(mangaId))?.toContent()
                ?: return@mapNotNull null
            val history = historyMap[mangaId]
            val progressPercent = history?.percent
            val progressText = when {
                progressPercent != null && progressPercent > 0f -> {
                    val p = (progressPercent * 100).toInt().coerceIn(0, 100)
                    context.getString(R.string.book_notes_reading_progress, p)
                }
                history != null -> context.getString(R.string.status_reading)
                else -> null
            }
            BookNotesSummary(
                mangaId = mangaId,
                title = manga.title,
                coverUrl = manga.coverUrl,
                contentType = manga.source.contentType,
                totalCount = count.total,
                highlightCount = count.highlights,
                thoughtCount = count.thoughts,
                bookmarkCount = count.bookmarks,
                readingProgressPercent = progressPercent,
                readingProgressText = progressText,
                lastUpdatedAt = count.lastUpdatedAt,
                isNsfw = manga.isNsfw(),
                source = manga.source.name,
            )
        }.sortedByDescending { it.lastUpdatedAt }
    }

    fun observeBookNotes(mangaId: Long): Flow<List<BookNoteItem>> = combine(
        database.getNovelMarkingDao().observe(mangaId),
        database.getBookmarksDao().observe(mangaId),
        database.getMediaNoteDao().observe(mangaId),
    ) { markings, bookmarks, mediaNotes ->
        val chapters = database.getChaptersDao().findAll(mangaId).associateBy { it.chapterId }

        val highlightItems = markings.map { m ->
            val chapter = chapters[m.chapterId]
            val chapterTitle = chapter?.title ?: context.getString(
                R.string.book_notes_chapter_fallback,
                m.chapterIndex + 1,
            )
            BookNoteItem.NovelHighlight(
                id = m.id,
                mangaId = m.mangaId,
                chapterId = m.chapterId,
                chapterIndex = m.chapterIndex,
                chapterTitle = chapterTitle,
                text = m.selectedText,
                note = m.note,
                startOffset = m.startOffset,
                endOffset = m.endOffset,
                createdAt = m.createdAt,
                updatedAt = m.updatedAt,
                color = m.color,
                style = m.style,
            )
        }

        val manga = database.getMangaDao().find(mangaId)?.toContent()
        val source = manga?.source

        val bookmarkItems = bookmarks.map { b ->
            val chapter = chapters[b.chapterId]
            val chapterIndex = chapter?.index ?: 0
            val chapterTitle = chapter?.title ?: context.getString(
                R.string.book_notes_page_fallback,
                b.page + 1,
            )
            val snapshotFile = File(context.filesDir, "bookmarks/manga_${b.mangaId}_chapter_${b.chapterId}_page_${b.page}.jpg")
            val localSnapshotUri = if (snapshotFile.exists() && snapshotFile.length() > 0) {
                snapshotFile.toUri().toString()
            } else if (b.imageUrl.startsWith("file://")) {
                b.imageUrl
            } else {
                null
            }
            BookNoteItem.BookmarkEntry(
                id = b.pageId,
                mangaId = b.mangaId,
                chapterId = b.chapterId,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                page = b.page,
                imageUrl = b.imageUrl,
                percent = b.percent,
                createdAt = b.createdAt,
                source = source,
                localSnapshotUri = localSnapshotUri,
            )
        }

        val mediaItems = mediaNotes.map { mn ->
            val chapter = chapters[mn.chapterId]
            val chapterTitle = chapter?.title ?: if (mn.mediaType == MediaNoteEntity.MEDIA_TYPE_VIDEO_TIMESTAMP) {
                context.getString(R.string.book_notes_chapter_fallback, mn.chapterIndex + 1)
            } else {
                context.getString(R.string.book_notes_page_fallback, mn.page + 1)
            }
            if (mn.mediaType == MediaNoteEntity.MEDIA_TYPE_VIDEO_TIMESTAMP) {
                BookNoteItem.VideoNote(
                    id = mn.id,
                    mangaId = mn.mangaId,
                    chapterId = mn.chapterId,
                    chapterIndex = mn.chapterIndex,
                    chapterTitle = chapterTitle,
                    positionMs = mn.positionMs,
                    durationMs = mn.durationMs,
                    snapshotUri = mn.imagePath,
                    quoteText = mn.quoteText,
                    note = mn.note,
                    createdAt = mn.createdAt,
                    updatedAt = mn.updatedAt,
                )
            } else {
                BookNoteItem.MangaCropNote(
                    id = mn.id,
                    mangaId = mn.mangaId,
                    chapterId = mn.chapterId,
                    chapterIndex = mn.chapterIndex,
                    chapterTitle = chapterTitle,
                    page = mn.page,
                    cropSnapshotUri = mn.imagePath,
                    note = mn.note,
                    createdAt = mn.createdAt,
                    updatedAt = mn.updatedAt,
                    cropLeft = mn.cropLeft,
                    cropTop = mn.cropTop,
                    cropRight = mn.cropRight,
                    cropBottom = mn.cropBottom,
                )
            }
        }

        (highlightItems + bookmarkItems + mediaItems).sortedWith(
            compareBy<BookNoteItem> { it.chapterIndex }
                .thenBy { item ->
                    when (item) {
                        is BookNoteItem.NovelHighlight -> item.startOffset.toLong()
                        is BookNoteItem.BookmarkEntry -> item.page.toLong()
                        is BookNoteItem.MangaCropNote -> item.page.toLong()
                        is BookNoteItem.VideoNote -> item.positionMs
                    }
                }
                .thenBy { it.createdAt },
        )
    }

    suspend fun deleteNote(item: BookNoteItem) {
        when (item) {
            is BookNoteItem.NovelHighlight -> database.getNovelMarkingDao().deleteById(item.id)
            is BookNoteItem.BookmarkEntry -> {
                database.getBookmarksDao().delete(item.id)
                val snapshotFile = File(context.filesDir, "bookmarks/manga_${item.mangaId}_chapter_${item.chapterId}_page_${item.page}.jpg")
                if (snapshotFile.exists()) {
                    snapshotFile.delete()
                }
            }
            is BookNoteItem.MangaCropNote -> {
                database.getMediaNoteDao().deleteById(item.id)
                item.cropSnapshotUri?.let { path ->
                    val file = File(path.removePrefix("file://"))
                    if (file.exists()) file.delete()
                }
            }
            is BookNoteItem.VideoNote -> {
                database.getMediaNoteDao().deleteById(item.id)
                item.snapshotUri?.let { path ->
                    val file = File(path.removePrefix("file://"))
                    if (file.exists()) file.delete()
                }
            }
        }
    }

    suspend fun updateThoughtNote(id: Long, note: String?) {
        database.getNovelMarkingDao().updateNote(
            id = id,
            note = note,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun updateMediaThoughtNote(id: Long, note: String?) {
        database.getMediaNoteDao().updateNote(
            id = id,
            note = note,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun saveMangaCropNote(
        mangaId: Long,
        chapterId: Long,
        chapterIndex: Int,
        page: Int,
        imagePath: String,
        note: String?,
        cropLeft: Float = 0f,
        cropTop: Float = 0f,
        cropRight: Float = 1f,
        cropBottom: Float = 1f,
    ): Long {
        val now = System.currentTimeMillis()
        return database.getMediaNoteDao().insert(
            MediaNoteEntity(
                mangaId = mangaId,
                chapterId = chapterId,
                chapterIndex = chapterIndex,
                mediaType = MediaNoteEntity.MEDIA_TYPE_MANGA_CROP,
                page = page,
                imagePath = imagePath,
                note = note,
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropRight = cropRight,
                cropBottom = cropBottom,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun saveVideoNote(
        mangaId: Long,
        chapterId: Long,
        chapterIndex: Int,
        positionMs: Long,
        durationMs: Long,
        imagePath: String?,
        quoteText: String?,
        note: String?,
    ): Long {
        val now = System.currentTimeMillis()
        return database.getMediaNoteDao().insert(
            MediaNoteEntity(
                mangaId = mangaId,
                chapterId = chapterId,
                chapterIndex = chapterIndex,
                mediaType = MediaNoteEntity.MEDIA_TYPE_VIDEO_TIMESTAMP,
                positionMs = positionMs,
                durationMs = durationMs,
                imagePath = imagePath,
                quoteText = quoteText,
                note = note,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
