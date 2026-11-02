package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Chapter identity and reading state are independent of whether an update notification was seen. */
open class Migration85To86 : Migration(85, 86) {
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(SQL)

    companion object {
        const val SQL = "ALTER TABLE `track_logs` ADD COLUMN `chapter_metadata` TEXT NOT NULL DEFAULT ''"
    }
}
