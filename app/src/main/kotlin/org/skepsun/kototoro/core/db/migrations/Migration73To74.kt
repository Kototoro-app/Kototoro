package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration73To74 : Migration(73, 74) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `space_definition` (
                `space_id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `sort_key` INTEGER NOT NULL,
                `enabled` INTEGER NOT NULL,
                `content_types` TEXT NOT NULL,
                `source_languages` TEXT NOT NULL,
                `source_kinds` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `deleted_at` INTEGER NOT NULL,
                PRIMARY KEY(`space_id`)
            )
            """.trimIndent(),
        )
    }
}
