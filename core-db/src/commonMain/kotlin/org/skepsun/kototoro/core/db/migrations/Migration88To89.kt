package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Why each suggestion is one: its direction, and the tag it is suggested for. Stored suggestions keep no direction. */
open class Migration88To89 : Migration(88, 89) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(ADD_DIRECTION)
        connection.execSQL(ADD_REASON)
    }

    companion object {
        const val ADD_DIRECTION = "ALTER TABLE `suggestions` ADD COLUMN `direction` TEXT NOT NULL DEFAULT ''"
        const val ADD_REASON = "ALTER TABLE `suggestions` ADD COLUMN `reason` TEXT"
    }
}
