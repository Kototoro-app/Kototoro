package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_PREFERENCES

class Migration56To57 : Migration(56, 57) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_PREFERENCES`
			ADD COLUMN `reading_status` TEXT
			""".trimIndent(),
		)
		db.execSQL(
			"""
			UPDATE `$TABLE_ENTITY_PREFERENCES`
			SET `reading_status` = (
				SELECT p.`reading_status`
				FROM `$TABLE_PREFERENCES` p
				INNER JOIN entity_binding b
					ON b.external_id = CAST(p.manga_id AS TEXT)
					AND b.source IN ('local_manga', '0')
				WHERE b.entity_id = `$TABLE_ENTITY_PREFERENCES`.`entity_id`
					AND p.`reading_status` IS NOT NULL
				ORDER BY p.manga_id DESC
				LIMIT 1
			)
			WHERE EXISTS (
				SELECT 1
				FROM `$TABLE_PREFERENCES` p
				INNER JOIN entity_binding b
					ON b.external_id = CAST(p.manga_id AS TEXT)
					AND b.source IN ('local_manga', '0')
				WHERE b.entity_id = `$TABLE_ENTITY_PREFERENCES`.`entity_id`
					AND p.`reading_status` IS NOT NULL
			)
			""".trimIndent(),
		)
	}
}
