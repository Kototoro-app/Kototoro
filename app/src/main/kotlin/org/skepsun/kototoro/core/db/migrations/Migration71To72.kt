package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration71To72 : Migration(71, 72) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `space_session` (
				`space_id` TEXT NOT NULL,
				`selected_top_level` TEXT NOT NULL,
				`resume_kind` TEXT NOT NULL,
				`resume_entity_id` INTEGER,
				`resume_projection_id` INTEGER,
				`resume_route` TEXT,
				`route_schema_version` INTEGER NOT NULL,
				`last_accessed` INTEGER NOT NULL,
				`updated_at` INTEGER NOT NULL,
				PRIMARY KEY(`space_id`)
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `space_navigation_entry` (
				`space_id` TEXT NOT NULL,
				`stack_key` TEXT NOT NULL,
				`position` INTEGER NOT NULL,
				`route_kind` TEXT NOT NULL,
				`route_payload` TEXT,
				`route_schema_version` INTEGER NOT NULL,
				`updated_at` INTEGER NOT NULL,
				PRIMARY KEY(`space_id`, `stack_key`, `position`)
			)
			""".trimIndent(),
		)
	}
}
