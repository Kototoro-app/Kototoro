package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration40To41 : Migration(40, 41) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
			CREATE TABLE IF NOT EXISTS `scrobblings_new` (
				`scrobbler` INTEGER NOT NULL,
				`id` INTEGER NOT NULL,
				`manga_id` INTEGER NOT NULL,
				`target_id` INTEGER NOT NULL,
				`status` TEXT,
				`chapter` INTEGER NOT NULL,
				`comment` TEXT,
				`rating` REAL NOT NULL,
				`media_type` TEXT NOT NULL DEFAULT '',
				PRIMARY KEY(`scrobbler`, `id`, `manga_id`, `media_type`)
			)
            """.trimIndent(),
        )
        db.execSQL(
            """
			INSERT INTO `scrobblings_new` (
				`scrobbler`,
				`id`,
				`manga_id`,
				`target_id`,
				`status`,
				`chapter`,
				`comment`,
				`rating`,
				`media_type`
			)
			SELECT
				`scrobbler`,
				`id`,
				`manga_id`,
				`target_id`,
				`status`,
				`chapter`,
				`comment`,
				`rating`,
				''
			FROM `scrobblings`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `scrobblings`")
        db.execSQL("ALTER TABLE `scrobblings_new` RENAME TO `scrobblings`")
    }
}
