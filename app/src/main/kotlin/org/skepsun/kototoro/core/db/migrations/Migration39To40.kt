package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration39To40 : Migration(39, 40) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracking_site_items ADD COLUMN primary_title TEXT")
        db.execSQL("ALTER TABLE tracking_site_items ADD COLUMN secondary_title TEXT")
        db.execSQL("ALTER TABLE tracking_site_items ADD COLUMN score_max REAL")
        db.execSQL("ALTER TABLE tracking_site_items ADD COLUMN progress_text TEXT")
        db.execSQL("ALTER TABLE tracking_site_items ADD COLUMN updated_at_text TEXT")
    }
}
