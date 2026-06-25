package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_ENTITY

class Migration68To69 : Migration(68, 69) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			UPDATE `$TABLE_ENTITY_GRAPH_ENTITY`
			SET `sync_id` = (
				SELECT
					'projection:' ||
					length(b.source) || ':' || b.source || ':' ||
					length(b.external_id) || ':' || b.external_id
				FROM `$TABLE_ENTITY_GRAPH_BINDING` b
				WHERE b.entity_id = `$TABLE_ENTITY_GRAPH_ENTITY`.id
					AND b.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND b.source NOT IN ('local_manga', '0')
					AND b.source_kind != 'TRACKING_SOURCE'
				ORDER BY b.is_primary DESC, b.updated_at DESC, b.source ASC, b.external_id ASC
				LIMIT 1
			)
			WHERE (
				SELECT COUNT(*)
				FROM `$TABLE_ENTITY_GRAPH_BINDING` b
				WHERE b.entity_id = `$TABLE_ENTITY_GRAPH_ENTITY`.id
					AND b.state IN ('MANUAL', 'CONFIRMED', 'LEGACY')
					AND b.source NOT IN ('local_manga', '0')
					AND b.source_kind != 'TRACKING_SOURCE'
			) = 1
			""".trimIndent(),
		)
	}
}
