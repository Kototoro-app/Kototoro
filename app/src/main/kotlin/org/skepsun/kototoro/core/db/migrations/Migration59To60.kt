package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_WORK_STATS

class Migration59To60 : Migration(59, 60) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `$TABLE_WORK_STATS` (
				`entity_id` INTEGER NOT NULL,
				`anchor_manga_id` INTEGER NOT NULL,
				`started_at` INTEGER NOT NULL,
				`duration` INTEGER NOT NULL,
				`pages` INTEGER NOT NULL,
				PRIMARY KEY(`entity_id`, `started_at`),
				FOREIGN KEY(`entity_id`) REFERENCES `entity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `idx_work_stats_entity`
			ON `$TABLE_WORK_STATS` (`entity_id`)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR REPLACE INTO `$TABLE_WORK_STATS` (
				`entity_id`,
				`anchor_manga_id`,
				`started_at`,
				`duration`,
				`pages`
			)
			SELECT
				b.entity_id,
				COALESCE(p.preferred_local_manga_id, s.manga_id) AS anchor_manga_id,
				s.started_at,
				s.duration,
				s.pages
			FROM `stats` s
			INNER JOIN `$TABLE_ENTITY_GRAPH_BINDING` b
				ON b.external_id = CAST(s.manga_id AS TEXT)
				AND b.source IN ('local_manga', '0')
				AND b.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
			LEFT JOIN `$TABLE_ENTITY_PREFERENCES` p
				ON p.entity_id = b.entity_id
			""".trimIndent(),
		)
	}
}
