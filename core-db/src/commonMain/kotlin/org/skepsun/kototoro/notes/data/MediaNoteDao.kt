package org.skepsun.kototoro.notes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaNoteDao {

    @Query("SELECT * FROM media_notes WHERE manga_id = :mangaId ORDER BY chapter_index, page, position_ms, created_at")
    fun observe(mangaId: Long): Flow<List<MediaNoteEntity>>

    @Query("SELECT * FROM media_notes ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<MediaNoteEntity>>

    @Query("SELECT * FROM media_notes WHERE manga_id = :mangaId AND chapter_id = :chapterId ORDER BY page, position_ms, created_at")
    fun observeByChapter(mangaId: Long, chapterId: Long): Flow<List<MediaNoteEntity>>

    @Query("SELECT * FROM media_notes WHERE id = :id")
    suspend fun findById(id: Long): MediaNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MediaNoteEntity): Long

    @Query("DELETE FROM media_notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM media_notes WHERE manga_id = :mangaId")
    suspend fun deleteByMangaId(mangaId: Long)

    @Query("UPDATE media_notes SET note = :note, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?, updatedAt: Long)
}
