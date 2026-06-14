package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_TRACKING_SITE_LINKS

class Migration60To61 : Migration(60, 61) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_TRACKING_SITE_LINKS`
			ADD COLUMN `entity_id` INTEGER
			""".trimIndent(),
		)
		db.execSQL(
			"""
			UPDATE `$TABLE_TRACKING_SITE_LINKS`
			SET `entity_id` = (
				SELECT b.entity_id
				FROM `$TABLE_ENTITY_GRAPH_BINDING` b
				WHERE b.external_id = CAST(`$TABLE_TRACKING_SITE_LINKS`.`manga_id` AS TEXT)
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
			CREATE INDEX IF NOT EXISTS `index_tracking_site_links_entity_id`
			ON `$TABLE_TRACKING_SITE_LINKS` (`entity_id`)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `index_tracking_site_links_service_entity_id`
			ON `$TABLE_TRACKING_SITE_LINKS` (`service`, `entity_id`)
			""".trimIndent(),
		)
		db.execSQL("ALTER TABLE `scrobblings` ADD COLUMN `entity_id` INTEGER")
		db.execSQL(
			"""
			UPDATE `scrobblings`
			SET `entity_id` = (
				SELECT b.entity_id
				FROM `$TABLE_ENTITY_GRAPH_BINDING` b
				WHERE b.external_id = CAST(`scrobblings`.`manga_id` AS TEXT)
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
			CREATE INDEX IF NOT EXISTS `index_scrobblings_entity_id`
			ON `scrobblings` (`entity_id`)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `index_scrobblings_scrobbler_entity_id`
			ON `scrobblings` (`scrobbler`, `entity_id`)
			""".trimIndent(),
		)
	}
}
