package org.skepsun.kototoro.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing the mapping between EPUB internal chapters and their parent download chapters.
 *
 * This table stores the relationship between:
 * - Parent chapters (EPUB download links)
 * - Internal chapters (chapters extracted from EPUB files)
 *
 * The mapping enables:
 * - Navigation from internal chapter IDs to EPUB files
 * - Grouping of internal chapters by EPUB file
 * - Cascade deletion when EPUB files are removed
 */
@Entity(
    tableName = "epub_chapter_mapping",
    indices = [Index(value = ["parentChapterId"])]
)
data class EpubChapterMappingEntity(
    /**
     * Unique ID for the internal chapter.
     * Generated using formula: parentChapterId + (chapterIndex * 1000000) + 1
     */
    @PrimaryKey
    @ColumnInfo(name = "internalChapterId")
    val internalChapterId: Long,

    /**
     * ID of the parent EPUB download chapter.
     * Used for grouping and cascade deletion.
     */
    @ColumnInfo(name = "parentChapterId")
    val parentChapterId: Long,

    /**
     * Full path to the EPUB file on disk.
     * Example: /storage/emulated/0/Android/data/org.skepsun.kototoro/files/epub/chapter_12345_volume1.epub
     */
    @ColumnInfo(name = "epubFilePath")
    val epubFilePath: String,

    /**
     * Display name of the EPUB file (for grouping in UI).
     * Example: "Volume 1" or "chapter_12345_volume1.epub"
     */
    @ColumnInfo(name = "epubFileName")
    val epubFileName: String,

    /**
     * 0-based index of the chapter within the EPUB file.
     * Used to locate the specific chapter when reading.
     */
    @ColumnInfo(name = "chapterIndex")
    val chapterIndex: Int,

    /**
     * Title of the chapter from EPUB metadata.
     * Example: "Chapter 1: The Beginning"
     */
    @ColumnInfo(name = "chapterTitle")
    val chapterTitle: String,

    /**
     * Timestamp when the mapping was created.
     * Defaults to current system time.
     */
    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis()
)
