package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration81To82 : Migration(81, 82) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `novel_markings` ADD COLUMN `color` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `novel_markings` ADD COLUMN `style` INTEGER NOT NULL DEFAULT 0")
    }
}
