package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration33To34 : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
			CREATE TABLE IF NOT EXISTS tracking_site_items (
				service INTEGER NOT NULL,
				remote_id INTEGER NOT NULL,
				title TEXT NOT NULL,
				alt_titles TEXT,
				rating REAL,
				rank INTEGER,
				summary TEXT,
				tags TEXT,
				year INTEGER,
				authors TEXT,
				cover_url TEXT,
				total_episodes INTEGER,
				publish_date TEXT,
				site_url TEXT,
				cached_at INTEGER NOT NULL,
				updated_at INTEGER NOT NULL,
				PRIMARY KEY(service, remote_id)
			)
            """.trimIndent(),
        )
        db.execSQL(
            """
			CREATE INDEX IF NOT EXISTS index_tracking_site_items_service_updated_at
			ON tracking_site_items(service, updated_at DESC)
            """.trimIndent(),
        )
        db.execSQL(
            """
			CREATE TABLE IF NOT EXISTS tracking_site_links (
				service INTEGER NOT NULL,
				remote_id INTEGER NOT NULL,
				manga_id INTEGER NOT NULL,
				source_name TEXT,
				confidence REAL NOT NULL,
				is_manual INTEGER NOT NULL,
				created_at INTEGER NOT NULL,
				updated_at INTEGER NOT NULL,
				PRIMARY KEY(service, remote_id, manga_id)
			)
            """.trimIndent(),
        )
        db.execSQL(
            """
			CREATE INDEX IF NOT EXISTS index_tracking_site_links_manga_id
			ON tracking_site_links(manga_id)
            """.trimIndent(),
        )
        db.execSQL(
            """
			CREATE INDEX IF NOT EXISTS index_tracking_site_links_service_remote_id
			ON tracking_site_links(service, remote_id)
            """.trimIndent(),
        )
    }
}
