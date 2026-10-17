package org.skepsun.kototoro.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.skepsun.kototoro.core.db.entity.ChapterEntity

@Dao
abstract class ChaptersDao {

    @Query("SELECT * FROM chapters WHERE manga_id = :mangaId ORDER BY `index` ASC")
    abstract suspend fun findAll(mangaId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE manga_id IN (:mangaIds) ORDER BY manga_id ASC, `index` ASC")
    abstract suspend fun findAllByMangaIds(mangaIds: Collection<Long>): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE chapter_id IN (:chapterIds)")
    abstract suspend fun findByIds(chapterIds: Collection<Long>): List<ChapterEntity>

    /** Chapter IDs are local to a work; caption lookups must not hydrate matching IDs from other works. */
    @Query(
        """
        SELECT c.* FROM chapters c
        INNER JOIN history h ON h.manga_id = c.manga_id AND h.chapter_id = c.chapter_id
        WHERE h.deleted_at = 0 AND c.manga_id IN (:mangaIds)
        """,
    )
    abstract suspend fun findReadingByMangaIds(mangaIds: Collection<Long>): List<ChapterEntity>

    @Query("DELETE FROM chapters WHERE manga_id = :mangaId")
    abstract suspend fun deleteAll(mangaId: Long)

    @Query(
        """
		DELETE FROM chapters
		WHERE manga_id NOT IN (SELECT manga_id FROM history WHERE deleted_at = 0)
			AND manga_id NOT IN (
				SELECT manga_id
				FROM favourites
				WHERE deleted_at = 0
			)
        """,
    )
    abstract suspend fun gc()

    @Transaction
    open suspend fun replaceAll(mangaId: Long, entities: Collection<ChapterEntity>) {
        deleteAll(mangaId)
        insert(entities)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(entities: Collection<ChapterEntity>)
}
