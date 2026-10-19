package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds the per-title "reverse chapter order" flag. This is the first migration shared by every platform:
 * Windows and iOS run [migrate] on their bundled driver, while Android's framework driver dispatches to the
 * `SupportSQLiteDatabase` overload, which the Android registration overrides with the same [SQL].
 */
open class Migration84To85 : Migration(84, 85) {

    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(SQL)
    }

    companion object {
        const val SQL = "ALTER TABLE `preferences` ADD COLUMN `chapters_reversed` INTEGER NOT NULL DEFAULT 0"
    }
}

/** Migrations that run on every platform's driver; desktop and iOS builders register exactly these. */
fun sharedDatabaseMigrations(): Array<Migration> = arrayOf(Migration84To85())
