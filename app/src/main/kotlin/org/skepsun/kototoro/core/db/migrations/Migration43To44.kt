package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration43To44 : Migration(43, 44) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE json_sources ADD COLUMN icon_url TEXT DEFAULT NULL")
    }
}
