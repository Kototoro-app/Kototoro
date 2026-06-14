package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_HISTORY
import org.skepsun.kototoro.core.db.TABLE_WORK_HISTORY

class Migration57To58 : Migration(57, 58) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `$TABLE_WORK_HISTORY` (
				`entity_id` INTEGER NOT NULL,
				`anchor_manga_id` INTEGER NOT NULL,
				`created_at` INTEGER NOT NULL,
				`updated_at` INTEGER NOT NULL,
				`chapter_id` INTEGER NOT NULL,
				`page` INTEGER NOT NULL,
				`scroll` REAL NOT NULL,
				`percent` REAL NOT NULL,
				`deleted_at` INTEGER NOT NULL,
				`chapters` INTEGER NOT NULL,
				`parent_chapter_id` INTEGER,
				PRIMARY KEY(`entity_id`),
				FOREIGN KEY(`entity_id`) REFERENCES `entity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `idx_work_history_anchor_manga`
			ON `$TABLE_WORK_HISTORY` (`anchor_manga_id`)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `idx_work_history_updated_at`
			ON `$TABLE_WORK_HISTORY` (`updated_at`)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR REPLACE INTO `$TABLE_WORK_HISTORY` (
				`entity_id`,
				`anchor_manga_id`,
				`created_at`,
				`updated_at`,
				`chapter_id`,
				`page`,
				`scroll`,
				`percent`,
				`deleted_at`,
				`chapters`,
				`parent_chapter_id`
			)
			SELECT
				b.entity_id AS entity_id,
				COALESCE(p.preferred_local_manga_id, h.manga_id) AS anchor_manga_id,
				h.created_at,
				h.updated_at,
				h.chapter_id,
				h.page,
				h.scroll,
				h.percent,
				h.deleted_at,
				h.chapters,
				h.parent_chapter_id
			FROM `$TABLE_HISTORY` h
			INNER JOIN `$TABLE_ENTITY_GRAPH_BINDING` b
				ON b.external_id = CAST(h.manga_id AS TEXT)
				AND b.source IN ('local_manga', '0')
				AND b.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
			LEFT JOIN `$TABLE_ENTITY_PREFERENCES` p
				ON p.entity_id = b.entity_id
			WHERE NOT EXISTS (
				SELECT 1
				FROM `$TABLE_HISTORY` h2
				INNER JOIN `$TABLE_ENTITY_GRAPH_BINDING` b2
					ON b2.external_id = CAST(h2.manga_id AS TEXT)
					AND b2.source IN ('local_manga', '0')
					AND b2.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
				WHERE b2.entity_id = b.entity_id
					AND (
						h2.updated_at > h.updated_at
						OR (h2.updated_at = h.updated_at AND h2.manga_id > h.manga_id)
					)
			)
			""".trimIndent(),
		)
	}
}
