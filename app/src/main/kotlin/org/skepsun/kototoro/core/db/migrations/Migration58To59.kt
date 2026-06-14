package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES

class Migration58To59 : Migration(58, 59) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `$TABLE_WORK_FAVOURITES` (
				`entity_id` INTEGER NOT NULL,
				`category_id` INTEGER NOT NULL,
				`sort_key` INTEGER NOT NULL,
				`pinned` INTEGER NOT NULL,
				`created_at` INTEGER NOT NULL,
				`deleted_at` INTEGER NOT NULL,
				`updated_at` INTEGER NOT NULL,
				PRIMARY KEY(`entity_id`, `category_id`),
				FOREIGN KEY(`entity_id`) REFERENCES `entity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
				FOREIGN KEY(`category_id`) REFERENCES `favourite_categories`(`category_id`) ON UPDATE NO ACTION ON DELETE CASCADE
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `idx_work_favourites_entity`
			ON `$TABLE_WORK_FAVOURITES` (`entity_id`)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `idx_work_favourites_category`
			ON `$TABLE_WORK_FAVOURITES` (`category_id`)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR REPLACE INTO `$TABLE_WORK_FAVOURITES` (
				`entity_id`,
				`category_id`,
				`sort_key`,
				`pinned`,
				`created_at`,
				`deleted_at`,
				`updated_at`
			)
			SELECT
				b.entity_id,
				f.category_id,
				f.sort_key,
				f.pinned,
				f.created_at,
				f.deleted_at,
				f.updated_at
			FROM `$TABLE_FAVOURITES` f
			INNER JOIN `$TABLE_ENTITY_GRAPH_BINDING` b
				ON b.external_id = CAST(f.manga_id AS TEXT)
				AND b.source IN ('local_manga', '0')
				AND b.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
			WHERE NOT EXISTS (
				SELECT 1
				FROM `$TABLE_FAVOURITES` f2
				INNER JOIN `$TABLE_ENTITY_GRAPH_BINDING` b2
					ON b2.external_id = CAST(f2.manga_id AS TEXT)
					AND b2.source IN ('local_manga', '0')
					AND b2.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
				WHERE b2.entity_id = b.entity_id
					AND f2.category_id = f.category_id
					AND (
						f2.updated_at > f.updated_at
						OR (f2.updated_at = f.updated_at AND f2.manga_id > f.manga_id)
					)
			)
			""".trimIndent(),
		)
	}
}
