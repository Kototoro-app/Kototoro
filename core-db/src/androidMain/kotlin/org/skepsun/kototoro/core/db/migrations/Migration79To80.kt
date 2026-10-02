package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration79To80 : Migration(79, 80) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `novel_markings` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `manga_id` INTEGER NOT NULL,
                `chapter_id` INTEGER NOT NULL,
                `chapter_index` INTEGER NOT NULL,
                `start_offset` INTEGER NOT NULL,
                `end_offset` INTEGER NOT NULL,
                `selected_text` TEXT NOT NULL,
                `note` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_novel_markings_manga_id` " +
                "ON `novel_markings` (`manga_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_novel_markings_manga_id_chapter_id` " +
                "ON `novel_markings` (`manga_id`, `chapter_id`)",
        )
    }
}
