package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_WORK_MIGRATION_LEDGER

class Migration65To66 : Migration(65, 66) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `$TABLE_WORK_MIGRATION_LEDGER` (
				`legacy_table` TEXT NOT NULL,
				`legacy_key` TEXT NOT NULL,
				`legacy_checksum` TEXT,
				`target_entity_id` INTEGER,
				`migration_version` INTEGER NOT NULL,
				`status` TEXT NOT NULL,
				`migrated_at` INTEGER NOT NULL,
				PRIMARY KEY(`legacy_table`, `legacy_key`, `migration_version`)
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `idx_work_migration_ledger_target_status`
			ON `$TABLE_WORK_MIGRATION_LEDGER` (`target_entity_id`, `status`)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `idx_work_migration_ledger_status`
			ON `$TABLE_WORK_MIGRATION_LEDGER` (`status`)
			""".trimIndent(),
		)
	}
}
