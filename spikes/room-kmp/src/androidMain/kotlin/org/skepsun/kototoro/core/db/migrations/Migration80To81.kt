package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration80To81 : Migration(80, 81) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `dictionary_rules` (
                `name` TEXT NOT NULL,
                `urlRule` TEXT NOT NULL,
                `showRule` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `sortNumber` INTEGER NOT NULL,
                PRIMARY KEY(`name`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `translation_dictionaries` (
                `bookKey` TEXT NOT NULL,
                `pairsJson` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`bookKey`)
            )
            """.trimIndent(),
        )
    }
}
