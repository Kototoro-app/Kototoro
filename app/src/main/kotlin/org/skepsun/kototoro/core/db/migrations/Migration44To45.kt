package org.skepsun.kototoro.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration44To45 : Migration(44, 45) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
			CREATE TABLE IF NOT EXISTS reading_sessions (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				manga_id INTEGER NOT NULL,
				start_at INTEGER NOT NULL,
				end_at INTEGER NOT NULL,
				start_chapter_id INTEGER NOT NULL,
				start_page INTEGER NOT NULL,
				start_scroll INTEGER NOT NULL,
				end_chapter_id INTEGER NOT NULL,
				end_page INTEGER NOT NULL,
				end_scroll INTEGER NOT NULL,
				start_percent REAL NOT NULL,
				end_percent REAL NOT NULL,
				FOREIGN KEY(manga_id) REFERENCES manga(manga_id) ON DELETE CASCADE
			)
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_sessions_manga_id_start_at ON reading_sessions(manga_id, start_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_sessions_manga_id_end_at ON reading_sessions(manga_id, end_at)")

        db.execSQL(
            """
			CREATE TABLE IF NOT EXISTS reading_jump_points (
				id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
				manga_id INTEGER NOT NULL,
				created_at INTEGER NOT NULL,
				from_chapter_id INTEGER NOT NULL,
				from_page INTEGER NOT NULL,
				from_scroll INTEGER NOT NULL,
				from_percent REAL NOT NULL,
				to_chapter_id INTEGER NOT NULL,
				to_page INTEGER NOT NULL,
				to_scroll INTEGER NOT NULL,
				to_percent REAL NOT NULL,
				source TEXT NOT NULL,
				FOREIGN KEY(manga_id) REFERENCES manga(manga_id) ON DELETE CASCADE
			)
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_jump_points_manga_id_created_at ON reading_jump_points(manga_id, created_at)")
    }
}
