package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_ENTITY
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_RELATION

/**
 * Migration 49 -> 50
 *
 * Adds FOREIGN KEY constraints on entity_binding.entity_id and relation.from_entity_id / relation.to_entity_id
 * referencing entity(id) with ON DELETE CASCADE.
 *
 * Because SQLite does not support ALTER TABLE ADD CONSTRAINT, both tables are rebuilt via
 * copy -> drop -> rename.
 */
class Migration49To50 : Migration(49, 50) {

	override fun migrate(db: SupportSQLiteDatabase) {
		// Rebuild entity_binding with FK
		db.execSQL(
			"""
			CREATE TABLE `${TABLE_ENTITY_GRAPH_BINDING}_new` (
				`entity_id` INTEGER NOT NULL,
				`source` TEXT NOT NULL,
				`external_id` TEXT NOT NULL,
				`confidence` REAL NOT NULL,
				`is_primary` INTEGER NOT NULL,
				PRIMARY KEY(`source`, `external_id`),
				FOREIGN KEY(`entity_id`) REFERENCES `$TABLE_ENTITY_GRAPH_ENTITY`(`id`) ON DELETE CASCADE
			)
			""".trimIndent(),
		)
		db.execSQL(
			"INSERT INTO `${TABLE_ENTITY_GRAPH_BINDING}_new` SELECT * FROM `$TABLE_ENTITY_GRAPH_BINDING`",
		)
		db.execSQL("DROP TABLE `$TABLE_ENTITY_GRAPH_BINDING`")
		db.execSQL(
			"ALTER TABLE `${TABLE_ENTITY_GRAPH_BINDING}_new` RENAME TO `$TABLE_ENTITY_GRAPH_BINDING`",
		)
		db.execSQL(
			"CREATE INDEX `idx_binding_entity` ON `$TABLE_ENTITY_GRAPH_BINDING` (`entity_id`)",
		)
		db.execSQL(
			"CREATE INDEX `idx_binding_external` ON `$TABLE_ENTITY_GRAPH_BINDING` (`source`, `external_id`)",
		)

		// Rebuild relation with FK
		db.execSQL(
			"""
			CREATE TABLE `${TABLE_ENTITY_GRAPH_RELATION}_new` (
				`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				`from_entity_id` INTEGER NOT NULL,
				`to_entity_id` INTEGER NOT NULL,
				`type` TEXT NOT NULL,
				`weight` REAL NOT NULL,
				`created_at` INTEGER NOT NULL,
				FOREIGN KEY(`from_entity_id`) REFERENCES `$TABLE_ENTITY_GRAPH_ENTITY`(`id`) ON DELETE CASCADE,
				FOREIGN KEY(`to_entity_id`) REFERENCES `$TABLE_ENTITY_GRAPH_ENTITY`(`id`) ON DELETE CASCADE
			)
			""".trimIndent(),
		)
		db.execSQL(
			"INSERT INTO `${TABLE_ENTITY_GRAPH_RELATION}_new` SELECT * FROM `$TABLE_ENTITY_GRAPH_RELATION`",
		)
		db.execSQL("DROP TABLE `$TABLE_ENTITY_GRAPH_RELATION`")
		db.execSQL(
			"ALTER TABLE `${TABLE_ENTITY_GRAPH_RELATION}_new` RENAME TO `$TABLE_ENTITY_GRAPH_RELATION`",
		)
		db.execSQL(
			"CREATE INDEX `idx_relation_from` ON `$TABLE_ENTITY_GRAPH_RELATION` (`from_entity_id`)",
		)
		db.execSQL(
			"CREATE INDEX `idx_relation_to` ON `$TABLE_ENTITY_GRAPH_RELATION` (`to_entity_id`)",
		)
		db.execSQL(
			"CREATE UNIQUE INDEX `idx_relation_unique` ON `$TABLE_ENTITY_GRAPH_RELATION` (`from_entity_id`, `to_entity_id`, `type`)",
		)
	}
}
