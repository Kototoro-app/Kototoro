package org.skepsun.kototoro.bookmarks.domain

import android.database.SQLException
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.skepsun.kototoro.core.db.entity.ChapterEntity
import org.skepsun.kototoro.bookmarks.data.BookmarkEntity
import org.skepsun.kototoro.bookmarks.data.toBookmark
import org.skepsun.kototoro.bookmarks.data.toBookmarks
import org.skepsun.kototoro.bookmarks.data.toEntity
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toEntities
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.ui.util.ReversibleHandle
import org.skepsun.kototoro.core.util.ext.mapItems
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.parsers.model.Content
import javax.inject.Inject

@Reusable
class BookmarksRepository @Inject constructor(
    private val db: MangaDatabase,
) {

    fun observeBookmark(manga: Content, chapterId: Long, page: Int): Flow<Bookmark?> {
        return db.getBookmarksDao().observe(manga.id, chapterId, page).map { it?.toBookmark(manga) }
    }

    fun observeBookmarks(manga: Content): Flow<List<Bookmark>> {
        return db.getBookmarksDao().observe(manga.id).mapItems { it.toBookmark(manga) }
    }

    fun observeBookmarks(): Flow<Map<Content, List<Bookmark>>> {
        return db.getBookmarksDao().observe().map { map ->
            val res = LinkedHashMap<Content, List<Bookmark>>(map.size)
            for ((k, v) in map) {
                val manga = k.toContent()
                res[manga] = v.toBookmarks(manga).resolveChapterTitles(manga.id)
            }
            res
        }
    }

    suspend fun addBookmark(bookmark: Bookmark) {
        db.withTransaction {
            val tags = bookmark.manga.tags.toEntities()
            db.getTagsDao().upsert(tags)
            db.getMangaDao().upsert(bookmark.manga.toEntity(), tags)
            db.getBookmarksDao().insert(bookmark.toEntity())
        }
    }

    suspend fun updateBookmark(bookmark: Bookmark, imageUrl: String) {
        val entity = bookmark.toEntity().copy(
            imageUrl = imageUrl,
        )
        db.getBookmarksDao().upsert(listOf(entity))
    }

    suspend fun removeBookmark(mangaId: Long, chapterId: Long, page: Int) {
        check(db.getBookmarksDao().delete(mangaId, chapterId, page) != 0) {
            "Bookmark not found"
        }
    }

    suspend fun removeBookmark(bookmark: Bookmark) {
        removeBookmark(bookmark.manga.id, bookmark.chapterId, bookmark.page)
    }

    suspend fun removeBookmarks(ids: Set<Long>): ReversibleHandle {
        val entities = ArrayList<BookmarkEntity>(ids.size)
        db.withTransaction {
            val dao = db.getBookmarksDao()
            for (pageId in ids) {
                val e = dao.find(pageId)
                if (e != null) {
                    entities.add(e)
                }
                dao.delete(pageId)
            }
        }
        return BookmarksRestorer(entities)
    }

    private inner class BookmarksRestorer(
        private val entities: Collection<BookmarkEntity>,
    ) : ReversibleHandle {

        override suspend fun reverse() {
            db.withTransaction {
                for (e in entities) {
                    try {
                        db.getBookmarksDao().insert(e)
                    } catch (e: SQLException) {
                        e.printStackTraceDebug()
                    }
                }
            }
        }
    }

    private suspend fun List<Bookmark>.resolveChapterTitles(mangaId: Long): List<Bookmark> {
        if (isEmpty()) {
            return this
        }
        val chapterTitles = db.getChaptersDao()
            .findAll(mangaId)
            .associateBy(ChapterEntity::chapterId, ChapterEntity::title)
        val epubChapterMappings = db.getEpubChapterMappingDao()
            .findByContentId(mangaId)
            .associateBy({ it.internalChapterId }, { it.chapterTitle })
        return map { bookmark ->
            bookmark.copy(
                chapterTitle = chapterTitles[bookmark.chapterId]
                    ?: epubChapterMappings[bookmark.chapterId]
                    ?: bookmark.chapterTitle,
            )
        }
    }
}
