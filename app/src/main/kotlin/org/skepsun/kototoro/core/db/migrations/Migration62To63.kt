package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration62To63 : Migration(62, 63) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `index_scrobblings_scrobbler_entity_id_target_id_media_type`
			ON `scrobblings` (`scrobbler`, `entity_id`, `target_id`, `media_type`)
			""".trimIndent(),
		)
	}
}
