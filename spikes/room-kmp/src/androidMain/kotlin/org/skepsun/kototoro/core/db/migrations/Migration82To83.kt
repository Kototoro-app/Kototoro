package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration82To83 : Migration(82, 83) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `media_notes` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `manga_id` INTEGER NOT NULL,
                `chapter_id` INTEGER NOT NULL,
                `chapter_index` INTEGER NOT NULL,
                `media_type` INTEGER NOT NULL,
                `page` INTEGER NOT NULL DEFAULT 0,
                `position_ms` INTEGER NOT NULL DEFAULT 0,
                `duration_ms` INTEGER NOT NULL DEFAULT 0,
                `image_path` TEXT,
                `quote_text` TEXT,
                `note` TEXT,
                `crop_left` REAL NOT NULL DEFAULT 0.0,
                `crop_top` REAL NOT NULL DEFAULT 0.0,
                `crop_right` REAL NOT NULL DEFAULT 1.0,
                `crop_bottom` REAL NOT NULL DEFAULT 1.0,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_notes_manga_id` " +
                "ON `media_notes` (`manga_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_notes_manga_id_chapter_id` " +
                "ON `media_notes` (`manga_id`, `chapter_id`)",
        )
    }
}
