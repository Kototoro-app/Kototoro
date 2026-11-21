package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * When an outbox event was first accepted, and by which relays (community plan §5): an event only one relay holds stays
 * until a second relay takes it or the index has had a run since, so one relay dropping it cannot lose it.
 */
open class Migration90To91 : Migration(90, 91) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(ADD_PUBLISHED_AT)
        connection.execSQL(ADD_ACCEPTED_RELAYS)
    }

    companion object {
        const val ADD_PUBLISHED_AT = "ALTER TABLE `community_outbox` ADD COLUMN `published_at` INTEGER NOT NULL DEFAULT 0"
        const val ADD_ACCEPTED_RELAYS = "ALTER TABLE `community_outbox` ADD COLUMN `accepted_relays` TEXT NOT NULL DEFAULT ''"
    }
}
