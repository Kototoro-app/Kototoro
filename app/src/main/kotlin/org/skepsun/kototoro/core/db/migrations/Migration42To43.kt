package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration42To43 : Migration(42, 43) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE scrobblings ADD COLUMN remote_title TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE scrobblings ADD COLUMN remote_cover_url TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE scrobblings ADD COLUMN remote_url TEXT DEFAULT NULL")
    }
}
