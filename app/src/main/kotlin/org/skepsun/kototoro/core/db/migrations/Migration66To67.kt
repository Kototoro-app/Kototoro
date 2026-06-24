package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES

class Migration66To67 : Migration(66, 67) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE `$TABLE_WORK_FAVOURITES` ADD COLUMN `anchor_manga_id` INTEGER")
		db.execSQL(
			"""
			UPDATE `$TABLE_WORK_FAVOURITES`
			SET `anchor_manga_id` = (
				SELECT f.manga_id
				FROM `$TABLE_FAVOURITES` f
				INNER JOIN `$TABLE_ENTITY_GRAPH_BINDING` b
					ON b.external_id = CAST(f.manga_id AS TEXT)
					AND b.source IN ('local_manga', '0')
					AND b.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
				WHERE b.entity_id = `$TABLE_WORK_FAVOURITES`.entity_id
					AND f.category_id = `$TABLE_WORK_FAVOURITES`.category_id
					AND f.deleted_at = `$TABLE_WORK_FAVOURITES`.deleted_at
				ORDER BY f.updated_at DESC, f.manga_id DESC
				LIMIT 1
			)
			WHERE `anchor_manga_id` IS NULL
			""".trimIndent(),
		)
		db.execSQL(
			"""
			UPDATE `$TABLE_WORK_FAVOURITES`
			SET `anchor_manga_id` = (
				SELECT f.manga_id
				FROM `$TABLE_FAVOURITES` f
				INNER JOIN `$TABLE_ENTITY_GRAPH_BINDING` b
					ON b.external_id = CAST(f.manga_id AS TEXT)
					AND b.source IN ('local_manga', '0')
					AND b.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
				WHERE b.entity_id = `$TABLE_WORK_FAVOURITES`.entity_id
					AND f.category_id = `$TABLE_WORK_FAVOURITES`.category_id
					AND f.deleted_at = 0
				ORDER BY f.updated_at DESC, f.manga_id DESC
				LIMIT 1
			)
			WHERE `anchor_manga_id` IS NULL
				AND `deleted_at` = 0
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `idx_work_favourites_anchor_manga`
			ON `$TABLE_WORK_FAVOURITES` (`anchor_manga_id`)
			""".trimIndent(),
		)
	}
}
