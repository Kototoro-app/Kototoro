package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database migration from version 28 to 29.
 *
 * Adds the epub_chapter_mapping table to support EPUB reader improvements.
 * This table stores the relationship between parent EPUB download chapters
 * and their internal chapters extracted from EPUB files.
 *
 * Changes:
 * - Creates epub_chapter_mapping table with all required fields
 * - Creates index on parentChapterId for efficient lookup
 */
class Migration28To29 : Migration(28, 29) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // Create the epub_chapter_mapping table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS epub_chapter_mapping (
                internalChapterId INTEGER PRIMARY KEY NOT NULL,
                parentChapterId INTEGER NOT NULL,
                epubFilePath TEXT NOT NULL,
                epubFileName TEXT NOT NULL,
                chapterIndex INTEGER NOT NULL,
                chapterTitle TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Create index on parentChapterId for efficient queries
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_epub_chapter_mapping_parentChapterId 
            ON epub_chapter_mapping(parentChapterId)
            """.trimIndent()
        )
    }
}
