package org.skepsun.kototoro.reader.novel.annotation

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelMarkingDao {

    @Query("SELECT * FROM novel_markings WHERE manga_id = :mangaId ORDER BY chapter_index, start_offset, created_at")
    fun observe(mangaId: Long): Flow<List<NovelMarkingEntity>>

    @Query("SELECT * FROM novel_markings ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<NovelMarkingEntity>>

    @Query(
        "SELECT * FROM novel_markings " +
            "WHERE manga_id = :mangaId AND chapter_id = :chapterId " +
            "AND start_offset = :startOffset AND end_offset = :endOffset LIMIT 1",
    )
    suspend fun find(
        mangaId: Long,
        chapterId: Long,
        startOffset: Int,
        endOffset: Int,
    ): NovelMarkingEntity?

    @Insert
    suspend fun insert(marking: NovelMarkingEntity): Long

    @Delete
    suspend fun delete(marking: NovelMarkingEntity)

    @Query("DELETE FROM novel_markings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE novel_markings SET note = :note, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?, updatedAt: Long)

    @Query("UPDATE novel_markings SET color = :color, style = :style, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStyle(id: Long, color: Int, style: Int, updatedAt: Long)
}
