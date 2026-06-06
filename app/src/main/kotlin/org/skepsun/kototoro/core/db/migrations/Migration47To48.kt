package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_ENTITY
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_PREFERENCES

class Migration47To48 : Migration(47, 48) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `$TABLE_ENTITY_PREFERENCES` (
				`entity_id` INTEGER NOT NULL,
				`preferred_local_manga_id` INTEGER,
				`metadata_source_kind` TEXT,
				`metadata_source_service` INTEGER,
				`metadata_source_remote_id` INTEGER,
				PRIMARY KEY(`entity_id`),
				FOREIGN KEY(`entity_id`) REFERENCES `$TABLE_ENTITY_GRAPH_ENTITY`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
			)
			""".trimIndent(),
		)

		db.execSQL(
			"""
			INSERT INTO `$TABLE_ENTITY_PREFERENCES` (
				`entity_id`,
				`preferred_local_manga_id`,
				`metadata_source_kind`,
				`metadata_source_service`,
				`metadata_source_remote_id`
			)
			SELECT
				m.entity_id,
				m.preferred_local_manga_id,
				p.metadata_source_kind,
				p.metadata_source_service,
				p.metadata_source_remote_id
			FROM `$TABLE_PREFERENCES` p
			INNER JOIN entity_binding b
				ON b.external_id = CAST(p.manga_id AS TEXT)
				AND b.source IN ('local_manga', '0')
			INNER JOIN (
				SELECT
					b2.entity_id AS entity_id,
					MAX(p2.manga_id) AS selected_manga_id,
					CAST(MAX(p2.manga_id) AS INTEGER) AS preferred_local_manga_id
				FROM `$TABLE_PREFERENCES` p2
				INNER JOIN entity_binding b2
					ON b2.external_id = CAST(p2.manga_id AS TEXT)
					AND b2.source IN ('local_manga', '0')
				WHERE p2.metadata_source_kind IS NOT NULL
				GROUP BY b2.entity_id
			) m
				ON m.entity_id = b.entity_id
				AND m.selected_manga_id = p.manga_id
			WHERE p.metadata_source_kind IS NOT NULL
			ON CONFLICT(`entity_id`) DO UPDATE SET
				metadata_source_kind = excluded.metadata_source_kind,
				metadata_source_service = excluded.metadata_source_service,
				metadata_source_remote_id = excluded.metadata_source_remote_id
			""".trimIndent(),
		)

		db.execSQL(
			"""
			INSERT OR IGNORE INTO `$TABLE_ENTITY_PREFERENCES` (`entity_id`, `preferred_local_manga_id`)
			SELECT
				b.entity_id,
				MIN(CAST(b.external_id AS INTEGER)) AS preferred_local_manga_id
			FROM entity_binding b
			WHERE b.source IN ('local_manga', '0')
			GROUP BY b.entity_id
			""".trimIndent(),
		)
	}
}
