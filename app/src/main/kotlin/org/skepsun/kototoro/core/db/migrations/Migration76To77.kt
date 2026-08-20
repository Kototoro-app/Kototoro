package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration76To77 : Migration(76, 77) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `restore_checkpoint` (" +
				"`id` TEXT NOT NULL, " +
				"`mode` TEXT NOT NULL, " +
				"`sections_json` TEXT NOT NULL, " +
				"`done_json` TEXT NOT NULL, " +
				"`mapping_json` TEXT, " +
				"`updated_at` INTEGER NOT NULL, " +
				"PRIMARY KEY(`id`))",
		)
	}
}
