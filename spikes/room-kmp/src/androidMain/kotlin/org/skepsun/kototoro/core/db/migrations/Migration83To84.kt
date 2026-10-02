package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database migration 83 -> 84:
 * Fully cuts over from Entity/Work ownership back to projection-first (Manga-first) architecture.
 *
 * All user state (favourites, history, stats, preferences, tracks, scrobblings) is restored
 * directly to the respective projection (manga_id), resolving chapter mismatches, restoring
 * custom preferences, and physically dropping the 8 Entity/Work tables.
 */
class Migration83To84 : Migration(83, 84) {

    override fun migrate(db: SupportSQLiteDatabase) {
        ProjectionOwnershipMigrationResolver.migrate(db)
    }
}
