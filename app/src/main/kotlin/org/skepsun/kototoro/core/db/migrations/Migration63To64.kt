package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration63To64 : Migration(63, 64) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `scrobblings_new` (
				`scrobbler` INTEGER NOT NULL,
				`id` INTEGER NOT NULL,
				`owner_id` INTEGER NOT NULL,
				`entity_id` INTEGER,
				`manga_id` INTEGER NOT NULL,
				`target_id` INTEGER NOT NULL,
				`status` TEXT,
				`chapter` INTEGER NOT NULL,
				`comment` TEXT,
				`rating` REAL NOT NULL,
				`media_type` TEXT NOT NULL,
				`remote_title` TEXT,
				`remote_cover_url` TEXT,
				`remote_url` TEXT,
				PRIMARY KEY(`scrobbler`, `id`, `owner_id`, `media_type`)
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT INTO `scrobblings_new` (
				`scrobbler`,
				`id`,
				`owner_id`,
				`entity_id`,
				`manga_id`,
				`target_id`,
				`status`,
				`chapter`,
				`comment`,
				`rating`,
				`media_type`,
				`remote_title`,
				`remote_cover_url`,
				`remote_url`
			)
			SELECT
				`scrobbler`,
				`id`,
				COALESCE(`entity_id`, CASE WHEN `manga_id` != 0 THEN -`manga_id` ELSE 0 END) AS `owner_id`,
				`entity_id`,
				`manga_id`,
				`target_id`,
				`status`,
				`chapter`,
				`comment`,
				`rating`,
				`media_type`,
				`remote_title`,
				`remote_cover_url`,
				`remote_url`
			FROM `scrobblings`
			""".trimIndent(),
		)
		db.execSQL("DROP TABLE `scrobblings`")
		db.execSQL("ALTER TABLE `scrobblings_new` RENAME TO `scrobblings`")
		db.execSQL("CREATE INDEX IF NOT EXISTS `index_scrobblings_owner_id` ON `scrobblings` (`owner_id`)")
		db.execSQL("CREATE INDEX IF NOT EXISTS `index_scrobblings_entity_id` ON `scrobblings` (`entity_id`)")
		db.execSQL("CREATE INDEX IF NOT EXISTS `index_scrobblings_scrobbler_entity_id` ON `scrobblings` (`scrobbler`, `entity_id`)")
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `index_scrobblings_scrobbler_entity_id_target_id_media_type`
			ON `scrobblings` (`scrobbler`, `entity_id`, `target_id`, `media_type`)
			""".trimIndent(),
		)
	}
}
