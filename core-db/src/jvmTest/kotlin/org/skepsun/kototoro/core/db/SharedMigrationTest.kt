package org.skepsun.kototoro.core.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.db.migrations.sharedDatabaseMigrations
import org.skepsun.kototoro.tracker.data.TrackScheduleEntity
import java.io.File

/**
 * Upgrades a real v84 database file on the bundled driver, the way Windows and iOS open an existing library.
 * The v84 tables come from the exported schema, so this fails if a shared migration and the entities disagree.
 */
class SharedMigrationTest {

    @Test
    fun `bundled driver upgrades a v84 library and keeps its rows`(@TempDir dir: File) = runBlocking {
        val path = File(dir, "library.db").absolutePath
        createVersion84(path)

        val db = Room.databaseBuilder<MangaDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*sharedDatabaseMigrations())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            // Opening validates the full upgrade chain against the current entities.
            val log = db.getTrackLogsDao().dump().single()
            assertEquals("Chapter 1", log.chapters)
            assertEquals("", log.chapterMetadata, "old reading state remains unknown")
            assertTrue(log.isUnread)
            val prefs = db.getPreferencesDao().find(MANGA_ID)
            assertEquals(2, prefs?.mode)
            assertFalse(prefs!!.isChaptersReversed)

            db.getPreferencesDao().upsert(prefs.copy(isChaptersReversed = true))
            assertTrue(db.getPreferencesDao().find(MANGA_ID)!!.isChaptersReversed)

            // v87: the schedule table exists and keeps a row for the work.
            val schedule = TrackScheduleEntity(MANGA_ID, fetchInterval = 3, nextCheckTime = 456, failCount = 1)
            db.getTrackScheduleDao().upsert(schedule)
            assertEquals(schedule, db.getTrackScheduleDao().find(MANGA_ID))

            // v88: the furthest read chapter starts at the chapter the history already pointed at.
            val history = db.getHistoryDao().find(MANGA_ID)!!
            assertEquals(HISTORY_CHAPTER_ID, history.furthestChapterId)
            // Reading an earlier chapter keeps it, and a writer that does not know it (0) leaves it alone.
            db.getHistoryDao().upsert(history.copy(chapterId = 5L, furthestChapterId = 0L, updatedAt = 3))
            assertEquals(5L, db.getHistoryDao().find(MANGA_ID)!!.chapterId)
            assertEquals(HISTORY_CHAPTER_ID, db.getHistoryDao().find(MANGA_ID)!!.furthestChapterId)
        } finally {
            db.close()
        }
    }

    private fun createVersion84(path: String) {
        val schema = File(SCHEMA_DIR, "84.json").readText()
        val database = Json.parseToJsonElement(schema).jsonObject.getValue("database").jsonObject
        val statements = database.getValue("entities").jsonArray.flatMap { entity ->
            val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
            buildList {
                add(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    add(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
        } + database.getValue("setupQueries").jsonArray.map { it.jsonPrimitive.content }
        BundledSQLiteDriver().open(path).use { connection ->
            statements.forEach(connection::execSQL)
            connection.execSQL(
                "INSERT INTO manga (manga_id, title, url, public_url, rating, nsfw, cover_url, source) " +
                    "VALUES ($MANGA_ID, 't', 'u', 'p', 0, 0, 'c', 's')",
            )
            connection.execSQL(
                "INSERT INTO preferences (manga_id, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale, cf_book) " +
                    "VALUES ($MANGA_ID, 2, 0, 0, 0, 0, 0)",
            )
            connection.execSQL("INSERT INTO track_logs(manga_id, chapters, created_at, unread) VALUES ($MANGA_ID, 'Chapter 1', 123, 1)")
            connection.execSQL(
                "INSERT INTO history(manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters) " +
                    "VALUES ($MANGA_ID, 1, 2, $HISTORY_CHAPTER_ID, 3, 0, 0.5, 0, 20)",
            )
            connection.execSQL("PRAGMA user_version = 84")
        }
    }

    private companion object {
        const val MANGA_ID = 7L
        const val HISTORY_CHAPTER_ID = 20L
        val SCHEMA_DIR = File("../app/schemas/org.skepsun.kototoro.core.db.MangaDatabase")
    }
}
