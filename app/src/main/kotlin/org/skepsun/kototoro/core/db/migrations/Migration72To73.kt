package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration72To73 : Migration(72, 73) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `space_route_preferences` (
				`space_id` TEXT NOT NULL,
				`route_key` TEXT NOT NULL,
				`payload` TEXT NOT NULL,
				`schema_version` INTEGER NOT NULL,
				`updated_at` INTEGER NOT NULL,
				PRIMARY KEY(`space_id`, `route_key`)
			)
			""".trimIndent(),
		)
	}
}
