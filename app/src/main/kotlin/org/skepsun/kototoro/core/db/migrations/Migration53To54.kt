package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_RELATION

/**
 * Migration 53 -> 54
 *
 * Adds relation provenance fields. Existing rows are retained as legacy relations so they
 * remain available for repair and non-work entity navigation, but new tracking ingestion can
 * write source-aware relations that details pages can filter deterministically.
 */
class Migration53To54 : Migration(53, 54) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_GRAPH_RELATION`
			ADD COLUMN `source_binding_source` TEXT NOT NULL DEFAULT ''
			""".trimIndent(),
		)
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_GRAPH_RELATION`
			ADD COLUMN `source_binding_external_id` TEXT NOT NULL DEFAULT ''
			""".trimIndent(),
		)
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_GRAPH_RELATION`
			ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'LEGACY'
			""".trimIndent(),
		)
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_GRAPH_RELATION`
			ADD COLUMN `state` TEXT NOT NULL DEFAULT 'LEGACY'
			""".trimIndent(),
		)
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_GRAPH_RELATION`
			ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0
			""".trimIndent(),
		)
		db.execSQL("DROP INDEX IF EXISTS `idx_relation_unique`")
		db.execSQL(
			"""
			CREATE UNIQUE INDEX `idx_relation_unique`
			ON `$TABLE_ENTITY_GRAPH_RELATION` (
				`from_entity_id`,
				`to_entity_id`,
				`type`,
				`source_binding_source`,
				`source_binding_external_id`,
				`origin`
			)
			""".trimIndent(),
		)
	}
}
