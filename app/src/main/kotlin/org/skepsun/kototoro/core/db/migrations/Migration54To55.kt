package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES

class Migration54To55 : Migration(54, 55) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_PREFERENCES`
			ADD COLUMN `metadata_binding_source` TEXT
			""".trimIndent(),
		)
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_PREFERENCES`
			ADD COLUMN `metadata_binding_external_id` TEXT
			""".trimIndent(),
		)
		db.execSQL(
			"""
			UPDATE `$TABLE_ENTITY_PREFERENCES`
			SET `metadata_binding_source` = CAST(`metadata_source_service` AS TEXT),
				`metadata_binding_external_id` = CAST(`metadata_source_remote_id` AS TEXT)
			WHERE `metadata_source_kind` = 'tracking'
				AND `metadata_source_service` IS NOT NULL
				AND `metadata_source_remote_id` IS NOT NULL
				AND (`metadata_binding_source` IS NULL OR `metadata_binding_external_id` IS NULL)
			""".trimIndent(),
		)
	}
}
