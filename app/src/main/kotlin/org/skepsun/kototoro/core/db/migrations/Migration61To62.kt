package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING

class Migration61To62 : Migration(61, 62) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE `tracks` ADD COLUMN `entity_id` INTEGER")
		db.execSQL(
			"""
			UPDATE `tracks`
			SET `entity_id` = (
				SELECT b.entity_id
				FROM `$TABLE_ENTITY_GRAPH_BINDING` b
				WHERE b.external_id = CAST(`tracks`.`manga_id` AS TEXT)
					AND b.source IN ('local_manga', '0')
					AND b.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
				ORDER BY CASE b.state
					WHEN 'MANUAL' THEN 0
					WHEN 'CONFIRMED' THEN 1
					WHEN 'LEGACY' THEN 2
					ELSE 3
				END,
				b.updated_at DESC,
				b.rowid DESC
				LIMIT 1
			)
			WHERE `entity_id` IS NULL
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `index_tracks_entity_id`
			ON `tracks` (`entity_id`)
			""".trimIndent(),
		)

		db.execSQL("ALTER TABLE `track_logs` ADD COLUMN `entity_id` INTEGER")
		db.execSQL(
			"""
			UPDATE `track_logs`
			SET `entity_id` = (
				SELECT b.entity_id
				FROM `$TABLE_ENTITY_GRAPH_BINDING` b
				WHERE b.external_id = CAST(`track_logs`.`manga_id` AS TEXT)
					AND b.source IN ('local_manga', '0')
					AND b.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
				ORDER BY CASE b.state
					WHEN 'MANUAL' THEN 0
					WHEN 'CONFIRMED' THEN 1
					WHEN 'LEGACY' THEN 2
					ELSE 3
				END,
				b.updated_at DESC,
				b.rowid DESC
				LIMIT 1
			)
			WHERE `entity_id` IS NULL
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `index_track_logs_entity_id`
			ON `track_logs` (`entity_id`)
			""".trimIndent(),
		)
	}
}
