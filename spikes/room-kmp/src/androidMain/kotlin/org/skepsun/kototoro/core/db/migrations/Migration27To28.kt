package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration27To28 : Migration(27, 28) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // Add updated_at column to favourites table
        // Initialize with created_at for existing records
        db.execSQL("ALTER TABLE favourites ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE favourites SET updated_at = created_at WHERE updated_at = 0")
    }
}
