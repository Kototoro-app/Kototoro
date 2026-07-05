package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_MANGA

class Migration69To70 : Migration(69, 70) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE `$TABLE_MANGA` ADD COLUMN `description` TEXT DEFAULT NULL")
	}
}
