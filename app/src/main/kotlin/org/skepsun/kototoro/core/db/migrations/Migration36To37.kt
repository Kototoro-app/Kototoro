package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_ENTITY
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_RELATION

class Migration36To37 : Migration(36, 37) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
			CREATE TABLE IF NOT EXISTS `$TABLE_ENTITY_GRAPH_ENTITY` (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`type` TEXT NOT NULL,
				`primary_name` TEXT NOT NULL,
				`aliases` TEXT,
				`created_at` INTEGER NOT NULL,
				`last_accessed` INTEGER NOT NULL,
				`access_count` INTEGER NOT NULL
			)
            """.trimIndent()
        )
        db.execSQL(
            """
			CREATE INDEX IF NOT EXISTS `idx_entity_name`
			ON `$TABLE_ENTITY_GRAPH_ENTITY` (`primary_name`)
            """.trimIndent()
        )
        db.execSQL(
            """
			CREATE TABLE IF NOT EXISTS `$TABLE_ENTITY_GRAPH_BINDING` (
				`entity_id` INTEGER NOT NULL,
				`source` TEXT NOT NULL,
				`external_id` TEXT NOT NULL,
				`confidence` REAL NOT NULL,
				`is_primary` INTEGER NOT NULL,
				PRIMARY KEY(`source`, `external_id`)
			)
            """.trimIndent()
        )
        db.execSQL(
            """
			CREATE INDEX IF NOT EXISTS `idx_binding_external`
			ON `$TABLE_ENTITY_GRAPH_BINDING` (`source`, `external_id`)
            """.trimIndent()
        )
        db.execSQL(
            """
			CREATE TABLE IF NOT EXISTS `$TABLE_ENTITY_GRAPH_RELATION` (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`from_entity_id` INTEGER NOT NULL,
				`to_entity_id` INTEGER NOT NULL,
				`type` TEXT NOT NULL,
				`weight` REAL NOT NULL,
				`created_at` INTEGER NOT NULL
			)
            """.trimIndent()
        )
        db.execSQL(
            """
			CREATE INDEX IF NOT EXISTS `idx_relation_from`
			ON `$TABLE_ENTITY_GRAPH_RELATION` (`from_entity_id`)
            """.trimIndent()
        )
        db.execSQL(
            """
			CREATE INDEX IF NOT EXISTS `idx_relation_to`
			ON `$TABLE_ENTITY_GRAPH_RELATION` (`to_entity_id`)
            """.trimIndent()
        )
        db.execSQL(
            """
			CREATE UNIQUE INDEX IF NOT EXISTS `idx_relation_unique`
			ON `$TABLE_ENTITY_GRAPH_RELATION` (`from_entity_id`, `to_entity_id`, `type`)
            """.trimIndent()
        )
    }
}
