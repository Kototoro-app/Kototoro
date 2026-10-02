package org.skepsun.kototoro.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.skepsun.kototoro.core.db.entity.EpubChapterMappingEntity

/**
 * Data Access Object for EPUB chapter mappings.
 *
 * Provides CRUD operations for managing the relationship between:
 * - Parent chapters (EPUB download links)
 * - Internal chapters (chapters extracted from EPUB files)
 *
 * Supports bidirectional lookup:
 * - Find internal chapters by parent chapter ID
 * - Find parent chapter by internal chapter ID
 */
@Dao
interface EpubChapterMappingDao {

    /**
     * Retrieves all internal chapters for a given parent chapter.
     * Used for displaying all chapters within an EPUB file.
     *
     * @param parentId ID of the parent EPUB download chapter
     * @return List of chapter mappings, ordered by chapter index
     */
    @Query("SELECT * FROM epub_chapter_mapping WHERE parentChapterId = :parentId ORDER BY chapterIndex")
    suspend fun getByParentId(parentId: Long): List<EpubChapterMappingEntity>

    /**
     * Retrieves a specific chapter mapping by internal chapter ID.
     * Used for navigation when a user clicks on an internal chapter.
     *
     * @param chapterId Internal chapter ID
     * @return Chapter mapping if found, null otherwise
     */
    @Query("SELECT * FROM epub_chapter_mapping WHERE internalChapterId = :chapterId")
    suspend fun getById(chapterId: Long): EpubChapterMappingEntity?

    /**
     * Inserts a single chapter mapping.
     * Uses REPLACE strategy to handle duplicates.
     *
     * @param mapping Chapter mapping to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mapping: EpubChapterMappingEntity)

    /**
     * Inserts multiple chapter mappings in a single transaction.
     * Uses REPLACE strategy to handle duplicates.
     *
     * @param mappings List of chapter mappings to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mappings: List<EpubChapterMappingEntity>)

    /**
     * Deletes all chapter mappings for a given parent chapter.
     * Used for cascade deletion when an EPUB file is removed.
     *
     * @param parentId ID of the parent EPUB download chapter
     */
    @Query("DELETE FROM epub_chapter_mapping WHERE parentChapterId = :parentId")
    suspend fun deleteByParentId(parentId: Long)

    /**
     * Deletes a specific chapter mapping by internal chapter ID.
     *
     * @param chapterId Internal chapter ID
     */
    @Query("DELETE FROM epub_chapter_mapping WHERE internalChapterId = :chapterId")
    suspend fun deleteById(chapterId: Long)

    /**
     * Counts the number of internal chapters for a given parent chapter.
     *
     * @param parentId ID of the parent EPUB download chapter
     * @return Number of internal chapters
     */
    @Query("SELECT COUNT(*) FROM epub_chapter_mapping WHERE parentChapterId = :parentId")
    suspend fun countByParentId(parentId: Long): Int

    /**
     * Checks if any mappings exist for a given parent chapter.
     *
     * @param parentId ID of the parent EPUB download chapter
     * @return True if mappings exist, false otherwise
     */
    @Query("SELECT EXISTS(SELECT 1 FROM epub_chapter_mapping WHERE parentChapterId = :parentId LIMIT 1)")
    suspend fun existsByParentId(parentId: Long): Boolean

    /**
     * Retrieves all chapter mappings for a given manga.
     * Uses LIKE to match the epub file path pattern: /path/to/epub/{mangaId}/
     *
     * @param mangaId ID of the manga
     * @return List of all chapter mappings for this manga, ordered by creation time and chapter index
     */
    @Query("SELECT * FROM epub_chapter_mapping WHERE epubFilePath LIKE '%/epub/' || :mangaId || '/%' ORDER BY createdAt, chapterIndex")
    suspend fun findByContentId(mangaId: Long): List<EpubChapterMappingEntity>

    /**
     * Finds a chapter mapping by manga ID and internal chapter ID.
     * Used for retrieving parent chapter ID when saving reading history.
     *
     * @param mangaId ID of the manga
     * @param internalChapterId Internal chapter ID
     * @return Chapter mapping if found, null otherwise
     */
    @Query("SELECT * FROM epub_chapter_mapping WHERE epubFilePath LIKE '%/epub/' || :mangaId || '/%' AND internalChapterId = :internalChapterId LIMIT 1")
    suspend fun findByInternalChapterId(mangaId: Long, internalChapterId: Long): EpubChapterMappingEntity?
}
