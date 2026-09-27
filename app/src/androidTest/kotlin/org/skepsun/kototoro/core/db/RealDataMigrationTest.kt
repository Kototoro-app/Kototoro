package org.skepsun.kototoro.core.db

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in migration check against a copy of a real user database.
 *
 * Push a v83 database copy into the debug app's database directory as [DB_NAME]
 * (e.g. `adb shell run-as <pkg> cp ... databases/realdata-83.db`); the test is skipped
 * otherwise. It opens the copy through the production migration chain, so Room's schema
 * validation runs on real data, and logs row counts under the `RealDataMigration` tag.
 * The app's own `kototoro-db` is never touched.
 */
@RunWith(AndroidJUnit4::class)
class RealDataMigrationTest {

    @Test
    fun realDatabaseCopyMigratesToCurrentSchema() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("no $DB_NAME pushed", context.getDatabasePath(DB_NAME).exists())

        val db = Room.databaseBuilder(context, MangaDatabase::class.java, DB_NAME)
            .addMigrations(*getDatabaseMigrations(context))
            .build()
        try {
            val sql = db.openHelper.writableDatabase
            assertEquals(DATABASE_VERSION, sql.version)
            for (table in listOf(
                "manga", "history", "favourites", "stats", "preferences",
                "tracks", "track_logs", "scrobblings", "tracking_site_links",
            )) {
                sql.query("SELECT COUNT(*) FROM $table").use {
                    it.moveToFirst()
                    Log.i(TAG, "$table=${it.getLong(0)}")
                }
            }
            for ((label, query) in listOf(
                "history.active" to "SELECT COUNT(*) FROM history WHERE deleted_at = 0",
                "favourites.active" to "SELECT COUNT(*) FROM favourites WHERE deleted_at = 0",
                "favourites.activeManga" to "SELECT COUNT(DISTINCT manga_id) FROM favourites WHERE deleted_at = 0",
                "prefs.readingStatus" to "SELECT COUNT(*) FROM preferences WHERE reading_status IS NOT NULL",
                "prefs.titleOverride" to "SELECT COUNT(*) FROM preferences WHERE title_override IS NOT NULL",
                "prefs.metadataSource" to "SELECT COUNT(*) FROM preferences WHERE metadata_source_kind IS NOT NULL",
            )) {
                sql.query(query).use {
                    it.moveToFirst()
                    Log.i(TAG, "$label=${it.getLong(0)}")
                }
            }
            sql.query("PRAGMA foreign_key_check").use {
                Log.i(TAG, "foreign_key_violations=${it.count}")
            }
            for (table in listOf("entity", "entity_binding", "work_history", "work_favourites")) {
                sql.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use {
                    assertEquals("$table must be dropped", false, it.moveToFirst())
                }
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TAG = "RealDataMigration"
        const val DB_NAME = "realdata-83.db"
    }
}
