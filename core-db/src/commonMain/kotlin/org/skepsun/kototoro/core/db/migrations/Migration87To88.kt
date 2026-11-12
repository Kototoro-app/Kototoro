package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** The furthest chapter ever read, so reopening an earlier chapter does not mark the later ones unread again. */
open class Migration87To88 : Migration(87, 88) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(ADD_COLUMN)
        connection.execSQL(BACKFILL)
    }

    companion object {
        const val ADD_COLUMN = "ALTER TABLE `history` ADD COLUMN `furthest_chapter_id` INTEGER NOT NULL DEFAULT 0"
        const val BACKFILL = "UPDATE `history` SET `furthest_chapter_id` = `chapter_id`"
    }
}
