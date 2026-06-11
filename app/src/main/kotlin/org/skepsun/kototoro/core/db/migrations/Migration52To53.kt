package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING

/**
 * Migration 52 -> 53
 *
 * Adds explicit binding semantics so source identity is no longer inferred only from
 * overloaded `source` values. Existing rows remain confirmed legacy data; only source kind
 * is backfilled because it is required for safe read-time filtering.
 */
class Migration52To53 : Migration(52, 53) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_GRAPH_BINDING`
			ADD COLUMN `source_kind` TEXT NOT NULL DEFAULT 'UNKNOWN'
			""".trimIndent(),
		)
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_GRAPH_BINDING`
			ADD COLUMN `state` TEXT NOT NULL DEFAULT 'CONFIRMED'
			""".trimIndent(),
		)
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_GRAPH_BINDING`
			ADD COLUMN `created_by` TEXT NOT NULL DEFAULT 'LEGACY'
			""".trimIndent(),
		)
		db.execSQL(
			"""
			ALTER TABLE `$TABLE_ENTITY_GRAPH_BINDING`
			ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0
			""".trimIndent(),
		)
		db.execSQL(
			"""
			UPDATE `$TABLE_ENTITY_GRAPH_BINDING`
			SET `source_kind` = CASE
				WHEN `source` IN ('0', 'local_manga') THEN 'READING_SOURCE'
				WHEN `source` IN (
					'1', '2', '3', '4', '5', '6', '7',
					'shikimori', 'anilist', 'mal', 'kitsu', 'bangumi', 'mangaupdates', 'simkl'
				) THEN 'TRACKING_SOURCE'
				ELSE 'UNKNOWN'
			END
			""".trimIndent(),
		)
	}
}
