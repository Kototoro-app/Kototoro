package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_ENTITY

class Migration37To38 : Migration(37, 38) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE $TABLE_ENTITY_GRAPH_ENTITY ADD COLUMN cover_url TEXT DEFAULT NULL")
		db.execSQL("ALTER TABLE $TABLE_ENTITY_GRAPH_ENTITY ADD COLUMN description TEXT DEFAULT NULL")
	}
}
