package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Community ratings, the outbox of unconfirmed Nostr events and cached tracker id searches (community plan §7.1). */
open class Migration89To90 : Migration(89, 90) {

    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(CREATE_RATINGS)
        connection.execSQL(CREATE_OUTBOX)
        connection.execSQL(CREATE_RESOLUTIONS)
    }

    companion object {
        const val CREATE_RATINGS = "CREATE TABLE IF NOT EXISTS `community_ratings` (`manga_id` INTEGER NOT NULL, " +
            "`stars` INTEGER NOT NULL, `address` TEXT NOT NULL, `event_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
            "PRIMARY KEY(`manga_id`))"
        const val CREATE_OUTBOX = "CREATE TABLE IF NOT EXISTS `community_outbox` (`event_id` TEXT NOT NULL, " +
            "`event_json` TEXT NOT NULL, `kind` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, " +
            "`next_attempt_at` INTEGER NOT NULL, `last_error` TEXT, PRIMARY KEY(`event_id`))"
        const val CREATE_RESOLUTIONS = "CREATE TABLE IF NOT EXISTS `community_resolutions` (`manga_id` INTEGER NOT NULL, " +
            "`service` TEXT NOT NULL, `status` TEXT NOT NULL, `remote_id` INTEGER, `checked_at` INTEGER NOT NULL, " +
            "`failures` INTEGER NOT NULL, PRIMARY KEY(`manga_id`, `service`))"
    }
}
