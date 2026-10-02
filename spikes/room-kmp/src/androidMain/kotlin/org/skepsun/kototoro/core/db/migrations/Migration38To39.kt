package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_PREFERENCES

class Migration38To39 : Migration(38, 39) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
			ALTER TABLE `$TABLE_PREFERENCES`
			ADD COLUMN `ignored_tracking_suggestion_service` INTEGER
            """.trimIndent()
        )
        db.execSQL(
            """
			ALTER TABLE `$TABLE_PREFERENCES`
			ADD COLUMN `ignored_tracking_suggestion_remote_id` INTEGER
            """.trimIndent()
        )
    }
}
