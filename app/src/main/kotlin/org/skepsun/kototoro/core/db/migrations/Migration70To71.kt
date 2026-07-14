package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_MANGA

class Migration70To71 : Migration(70, 71) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE `$TABLE_MANGA` ADD COLUMN `content_type` TEXT DEFAULT NULL")
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `index_manga_content_type`
			ON `$TABLE_MANGA` (`content_type`)
			""".trimIndent(),
		)
	}
}
