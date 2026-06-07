package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_ENTITY

/**
 * Migration 50 -> 51
 *
 * Adds a `name_hash` column to the entity table with a UNIQUE constraint on (type, name_hash).
 *
 * The column is used for fast entity deduplication and to close the race-condition window
 * in concurrent entity creation (VULN-3, VULN-4). Existing rows are backfilled with the
 * row-id so the UNIQUE constraint is satisfied immediately; a follow-up worker recomputes
 * the true normalised name hash to enable proper future dedup.
 */
class Migration50To51 : Migration(50, 51) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_GRAPH_ENTITY`
			ADD COLUMN `name_hash` INTEGER NOT NULL DEFAULT 0
			""".trimIndent(),
		)
		// Backfill existing rows with row-id so the UNIQUE constraint passes.
		// A follow-up worker will recompute the true normalised name hash.
		db.execSQL(
			"""
			UPDATE `$TABLE_ENTITY_GRAPH_ENTITY`
			SET `name_hash` = `id`
			WHERE `name_hash` = 0
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE UNIQUE INDEX `idx_entity_name_hash`
			ON `$TABLE_ENTITY_GRAPH_ENTITY` (`type`, `name_hash`)
			""".trimIndent(),
		)
	}
}
