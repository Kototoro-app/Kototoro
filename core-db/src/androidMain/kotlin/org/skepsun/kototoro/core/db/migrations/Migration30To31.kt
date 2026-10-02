package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration30To31 : Migration(30, 31) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // Add parent_chapter_id column to history table for EPUB internal chapter support
        db.execSQL("ALTER TABLE history ADD COLUMN parent_chapter_id INTEGER DEFAULT NULL")
    }
}
