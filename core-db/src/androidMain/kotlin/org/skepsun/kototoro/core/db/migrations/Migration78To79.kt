package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration78To79 : Migration(78, 79) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `replace_rules` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL DEFAULT '',
                `group` TEXT,
                `pattern` TEXT NOT NULL DEFAULT '',
                `replacement` TEXT NOT NULL DEFAULT '',
                `scope` TEXT,
                `scopeTitle` INTEGER NOT NULL DEFAULT 0,
                `scopeContent` INTEGER NOT NULL DEFAULT 1,
                `excludeScope` TEXT,
                `isEnabled` INTEGER NOT NULL DEFAULT 1,
                `isRegex` INTEGER NOT NULL DEFAULT 1,
                `timeoutMillisecond` INTEGER NOT NULL DEFAULT 3000,
                `sortOrder` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_replace_rules_sortOrder` " +
                "ON `replace_rules` (`sortOrder`)",
        )
    }
}
