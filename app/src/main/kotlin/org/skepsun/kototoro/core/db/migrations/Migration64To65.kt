package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration64To65 : Migration(64, 65) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `tracks_new` (
				`owner_id` INTEGER NOT NULL,
				`manga_id` INTEGER NOT NULL,
				`entity_id` INTEGER,
				`last_chapter_id` INTEGER NOT NULL,
				`chapters_new` INTEGER NOT NULL,
				`last_check_time` INTEGER NOT NULL,
				`last_chapter_date` INTEGER NOT NULL,
				`last_result` INTEGER NOT NULL,
				`last_error` TEXT,
				PRIMARY KEY(`owner_id`),
				FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR REPLACE INTO `tracks_new` (
				`owner_id`,
				`manga_id`,
				`entity_id`,
				`last_chapter_id`,
				`chapters_new`,
				`last_check_time`,
				`last_chapter_date`,
				`last_result`,
				`last_error`
			)
			SELECT
				COALESCE(`entity_id`, CASE WHEN `manga_id` != 0 THEN -`manga_id` ELSE 0 END) AS `owner_id`,
				`manga_id`,
				`entity_id`,
				`last_chapter_id`,
				`chapters_new`,
				`last_check_time`,
				`last_chapter_date`,
				`last_result`,
				`last_error`
			FROM `tracks`
			ORDER BY `last_check_time` ASC
			""".trimIndent(),
		)
		db.execSQL("DROP TABLE `tracks`")
		db.execSQL("ALTER TABLE `tracks_new` RENAME TO `tracks`")
		db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tracks_manga_id` ON `tracks` (`manga_id`)")
		db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_entity_id` ON `tracks` (`entity_id`)")

		db.execSQL("ALTER TABLE `track_logs` ADD COLUMN `owner_id` INTEGER NOT NULL DEFAULT 0")
		db.execSQL(
			"""
			UPDATE `track_logs`
			SET `owner_id` = COALESCE(`entity_id`, CASE WHEN `manga_id` != 0 THEN -`manga_id` ELSE 0 END)
			WHERE `owner_id` = 0
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `index_track_logs_owner_id`
			ON `track_logs` (`owner_id`)
			""".trimIndent(),
		)
	}
}
