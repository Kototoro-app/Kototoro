package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_ENTITY

/**
 * Migration 51 -> 52
 *
 * Adds a composite index on entity(type, access_count, last_accessed, id) to accelerate
 * findEntitiesByType queries used by pickCandidate during ingestion.
 */
class Migration51To52 : Migration(51, 52) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE INDEX IF NOT EXISTS `idx_entity_type_access`
			ON `$TABLE_ENTITY_GRAPH_ENTITY` (`type`, `access_count`, `last_accessed`, `id`)
			""".trimIndent(),
		)
	}
}
