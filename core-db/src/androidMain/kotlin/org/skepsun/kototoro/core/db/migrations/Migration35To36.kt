package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_SOURCE_PRESETS

class Migration35To36 : Migration(35, 36) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
			CREATE TABLE IF NOT EXISTS `$TABLE_SOURCE_PRESETS` (
				`preset_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`title` TEXT NOT NULL,
				`languages` TEXT NOT NULL,
				`sources` TEXT NOT NULL,
				`created_at` INTEGER NOT NULL,
				`sort_key` INTEGER NOT NULL,
				`deleted_at` INTEGER NOT NULL
			)
            """.trimIndent()
        )
    }
}
