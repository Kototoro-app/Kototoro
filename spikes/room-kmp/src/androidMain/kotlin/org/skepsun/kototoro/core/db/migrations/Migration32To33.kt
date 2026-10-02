package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration32To33 : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
			CREATE TABLE IF NOT EXISTS extension_repos (
				type TEXT NOT NULL,
				base_url TEXT NOT NULL,
				name TEXT NOT NULL,
				short_name TEXT,
				website TEXT NOT NULL,
				signing_key_fingerprint TEXT NOT NULL,
				created_at INTEGER NOT NULL,
				updated_at INTEGER NOT NULL,
				last_success_at INTEGER NOT NULL DEFAULT 0,
				last_error TEXT,
				PRIMARY KEY(type, base_url)
			)
            """.trimIndent(),
        )
        db.execSQL(
            """
			CREATE INDEX IF NOT EXISTS index_extension_repos_type
			ON extension_repos(type)
            """.trimIndent(),
        )
        db.execSQL(
            """
			CREATE UNIQUE INDEX IF NOT EXISTS index_extension_repos_type_signing_key_fingerprint
			ON extension_repos(type, signing_key_fingerprint)
            """.trimIndent(),
        )
    }
}
