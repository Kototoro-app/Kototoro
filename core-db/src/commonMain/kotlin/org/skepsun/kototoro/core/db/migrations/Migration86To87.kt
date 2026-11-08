package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** When each tracked work is due for its next check, kept apart from the backed-up tracks table. */
open class Migration86To87 : Migration(86, 87) {
    override fun migrate(connection: SQLiteConnection) = connection.execSQL(SQL)

    companion object {
        const val SQL = "CREATE TABLE IF NOT EXISTS `track_schedule` (`manga_id` INTEGER NOT NULL, " +
            "`fetch_interval` INTEGER NOT NULL, `next_check_time` INTEGER NOT NULL, `fail_count` INTEGER NOT NULL, " +
            "PRIMARY KEY(`manga_id`), FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"
    }
}
