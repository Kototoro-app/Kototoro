package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_PREFERENCES

class Migration37To38 : Migration(37, 38) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
			ALTER TABLE `$TABLE_PREFERENCES`
			ADD COLUMN `metadata_source_kind` TEXT
            """.trimIndent()
        )
        db.execSQL(
            """
			ALTER TABLE `$TABLE_PREFERENCES`
			ADD COLUMN `metadata_source_service` INTEGER
            """.trimIndent()
        )
        db.execSQL(
            """
			ALTER TABLE `$TABLE_PREFERENCES`
			ADD COLUMN `metadata_source_remote_id` INTEGER
            """.trimIndent()
        )
    }
}
