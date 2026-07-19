package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration75To76 : Migration(75, 76) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE `manga` ADD COLUMN `source_data` TEXT DEFAULT NULL")
		db.execSQL("ALTER TABLE `chapters` ADD COLUMN `source_data` TEXT DEFAULT NULL")
	}
}
