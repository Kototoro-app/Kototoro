package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_ENTITY
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES

class Migration48To49 : Migration(48, 49) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_PREFERENCES`
			ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0
			""".trimIndent(),
		)
		db.execSQL(
			"""
			UPDATE `$TABLE_ENTITY_PREFERENCES`
			SET `updated_at` = COALESCE(
				(
					SELECT e.last_accessed
					FROM `$TABLE_ENTITY_GRAPH_ENTITY` e
					WHERE e.id = `$TABLE_ENTITY_PREFERENCES`.`entity_id`
				),
				0
			)
			""".trimIndent(),
		)
	}
}
