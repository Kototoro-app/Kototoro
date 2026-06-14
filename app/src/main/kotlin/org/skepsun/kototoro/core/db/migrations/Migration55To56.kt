package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_PREFERENCES

class Migration55To56 : Migration(55, 56) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_PREFERENCES`
			ADD COLUMN `title_override` TEXT
			""".trimIndent(),
		)
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_PREFERENCES`
			ADD COLUMN `cover_override` TEXT
			""".trimIndent(),
		)
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_PREFERENCES`
			ADD COLUMN `content_rating_override` TEXT
			""".trimIndent(),
		)
		db.execSQL(
			"""
			UPDATE `$TABLE_ENTITY_PREFERENCES`
			SET
				`title_override` = (
					SELECT p.`title_override`
					FROM `$TABLE_PREFERENCES` p
					INNER JOIN entity_binding b
						ON b.external_id = CAST(p.manga_id AS TEXT)
						AND b.source IN ('local_manga', '0')
					WHERE b.entity_id = `$TABLE_ENTITY_PREFERENCES`.`entity_id`
						AND p.`title_override` IS NOT NULL
					ORDER BY p.manga_id DESC
					LIMIT 1
				),
				`cover_override` = (
					SELECT p.`cover_override`
					FROM `$TABLE_PREFERENCES` p
					INNER JOIN entity_binding b
						ON b.external_id = CAST(p.manga_id AS TEXT)
						AND b.source IN ('local_manga', '0')
					WHERE b.entity_id = `$TABLE_ENTITY_PREFERENCES`.`entity_id`
						AND p.`cover_override` IS NOT NULL
					ORDER BY p.manga_id DESC
					LIMIT 1
				),
				`content_rating_override` = (
					SELECT p.`content_rating_override`
					FROM `$TABLE_PREFERENCES` p
					INNER JOIN entity_binding b
						ON b.external_id = CAST(p.manga_id AS TEXT)
						AND b.source IN ('local_manga', '0')
					WHERE b.entity_id = `$TABLE_ENTITY_PREFERENCES`.`entity_id`
						AND p.`content_rating_override` IS NOT NULL
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
					AND (
						p.`title_override` IS NOT NULL
						OR p.`cover_override` IS NOT NULL
						OR p.`content_rating_override` IS NOT NULL
					)
			)
			""".trimIndent(),
		)
	}
}
