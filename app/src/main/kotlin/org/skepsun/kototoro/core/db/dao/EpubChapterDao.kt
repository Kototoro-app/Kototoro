package org.skepsun.kototoro.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.skepsun.kototoro.core.db.entity.EpubChapterEntity

@Dao
abstract class EpubChapterDao {

    @Query("SELECT * FROM epub_chapters WHERE novel_id = :novelId AND volume_id = :volumeId ORDER BY chapter_index")
    abstract suspend fun getChaptersByVolume(novelId: String, volumeId: String): List<EpubChapterEntity>

    @Query("SELECT * FROM epub_chapters WHERE id = :chapterId LIMIT 1")
    abstract suspend fun getChapterById(chapterId: Long): EpubChapterEntity?

    @Query("SELECT * FROM epub_chapters WHERE chapter_url = :chapterUrl LIMIT 1")
    abstract suspend fun getChapterByUrl(chapterUrl: String): EpubChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertChapters(chapters: List<EpubChapterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertChapter(chapter: EpubChapterEntity)

    @Query("DELETE FROM epub_chapters WHERE novel_id = :novelId AND volume_id = :volumeId")
    abstract suspend fun deleteVolume(novelId: String, volumeId: String)

    @Query("DELETE FROM epub_chapters WHERE novel_id = :novelId")
    abstract suspend fun deleteNovel(novelId: String)

    @Query("SELECT COUNT(*) FROM epub_chapters WHERE novel_id = :novelId AND volume_id = :volumeId")
    abstract suspend fun getChapterCount(novelId: String, volumeId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM epub_chapters WHERE novel_id = :novelId AND volume_id = :volumeId LIMIT 1)")
    abstract suspend fun isVolumeDownloaded(novelId: String, volumeId: String): Boolean

    @Query("SELECT DISTINCT volume_id FROM epub_chapters WHERE novel_id = :novelId")
    abstract suspend fun getDownloadedVolumes(novelId: String): List<String>
}
