package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_ENTITY

class Migration67To68 : Migration(67, 68) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE `$TABLE_ENTITY_GRAPH_ENTITY` ADD COLUMN `sync_id` TEXT NOT NULL DEFAULT ''")
		db.execSQL(
			"""
			UPDATE `$TABLE_ENTITY_GRAPH_ENTITY`
			SET `sync_id` = lower(hex(randomblob(4))) || '-' ||
				lower(hex(randomblob(2))) || '-' ||
				lower(hex(randomblob(2))) || '-' ||
				lower(hex(randomblob(2))) || '-' ||
				lower(hex(randomblob(6)))
			WHERE `sync_id` = ''
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE UNIQUE INDEX IF NOT EXISTS `idx_entity_sync_id`
			ON `$TABLE_ENTITY_GRAPH_ENTITY` (`sync_id`)
			""".trimIndent(),
		)
	}
}
