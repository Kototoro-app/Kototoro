package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the content-type dimension to Work identity.
 *
 * A legacy entity that contains projections of more than one known content type
 * is intentionally left with a null type. The runtime projection filter and the
 * repair worker can then split it with projection-level state and provenance;
 * choosing a first or majority type during a schema migration would be lossy.
 */
class Migration74To75 : Migration(74, 75) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE `entity` ADD COLUMN `content_type` TEXT")
		db.execSQL(
		"""
		UPDATE `entity`
		SET `content_type` = (
			SELECT CASE
				WHEN COUNT(*) > 0
					AND COUNT(DISTINCT manga.`content_type`) = 1
					AND COUNT(*) = COUNT(manga.`content_type`)
					THEN MAX(manga.`content_type`)
				ELSE NULL
			END
			FROM `entity_binding` AS binding
			LEFT JOIN `manga` AS manga
				ON manga.`manga_id` = CAST(binding.`external_id` AS INTEGER)
			WHERE binding.`entity_id` = `entity`.`id`
				AND binding.`source` IN ('local_manga', '0')
				AND binding.`state` IN ('MANUAL', 'CONFIRMED', 'LEGACY')
				AND manga.`content_type` IS NOT NULL
		)
		""".trimIndent(),
		)
		db.execSQL("DROP INDEX IF EXISTS `idx_entity_name_hash`")
		db.execSQL(
		"CREATE UNIQUE INDEX IF NOT EXISTS `idx_entity_name_hash` " +
			"ON `entity` (`type`, `name_hash`, `content_type`)",
		)
	}
}
