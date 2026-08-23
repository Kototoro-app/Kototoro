package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.skepsun.kototoro.core.db.TABLE_SOURCES

/**
 * Room schema 77 → 78.
 *
 * Adds the two source-recovery tables:
 * - `source_origins` — strict source-key registry (keyed by `source_key`, indexed by `kind` and
 *   nullable `repository_url`);
 * - `source_refresh_state` — per-content refresh bookkeeping keyed by `(source_key, content_id)`
 *   and indexed on both key columns.
 *
 * Backfill: a minimal `source_origins` row (`source_key`, `kind`, `updated_at`) is created for
 * every existing row of the legacy [`TABLE_SOURCES`](org.skepsun.kototoro.core.db.TABLE_SOURCES)
 * table (`sources`, column `source`) whose key starts with one of the known stable prefixes
 * `MIHON_` / `ANIYOMI_` / `IREADER_` / `TSUNDOKU_`. The prefix match uses exact `substr` checks
 * (never `LIKE`, to avoid `_` acting as a single-character wildcard); unknown prefixes are left
 * untouched. Nothing else — no existing table, work/source or content type — is modified.
 */
class Migration77To78 : Migration(77, 78) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // -- source_origins ---------------------------------------------------------------
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `source_origins` (" +
                "`source_key` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`display_name` TEXT, " +
                "`content_type` TEXT, " +
                "`package_name` TEXT, " +
                "`source_id` TEXT, " +
                "`repository_url` TEXT, " +
                "`repository_name` TEXT, " +
                "`locator` TEXT, " +
                "`version_name` TEXT, " +
                "`version_code` INTEGER, " +
                "`signing_digest` TEXT, " +
                "`last_seen_at` INTEGER, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`source_key`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_source_origins_kind` ON `source_origins` (`kind`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_source_origins_repository_url` " +
                "ON `source_origins` (`repository_url`)",
        )

        // -- source_refresh_state --------------------------------------------------------
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `source_refresh_state` (" +
                "`source_key` TEXT NOT NULL, " +
                "`content_id` INTEGER NOT NULL, " +
                "`last_success_at` INTEGER, " +
                "`last_attempt_at` INTEGER, " +
                "`last_error` TEXT, " +
                "`updated_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`source_key`, `content_id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_source_refresh_state_source_key` " +
                "ON `source_refresh_state` (`source_key`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_source_refresh_state_content_id` " +
                "ON `source_refresh_state` (`content_id`)",
        )

        // -- conservative minimal origin backfill -----------------------------------------
        // Single evaluation of "now" shared by every inserted row.
        val nowMillis = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT OR IGNORE INTO source_origins (source_key, kind, updated_at)
            SELECT source,
                   CASE
                       WHEN substr(source, 1, 6) = 'MIHON_' THEN 'MIHON'
                       WHEN substr(source, 1, 8) = 'ANIYOMI_' THEN 'ANIYOMI'
                       WHEN substr(source, 1, 8) = 'IREADER_' THEN 'IREADER'
                       WHEN substr(source, 1, 9) = 'TSUNDOKU_' THEN 'TSUNDOKU'
                   END,
                   $nowMillis
            FROM $TABLE_SOURCES
            WHERE substr(source, 1, 6) = 'MIHON_'
               OR substr(source, 1, 8) = 'ANIYOMI_'
               OR substr(source, 1, 8) = 'IREADER_'
               OR substr(source, 1, 9) = 'TSUNDOKU_'
            """.trimIndent(),
        )
    }
}
