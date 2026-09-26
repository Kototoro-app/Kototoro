package org.skepsun.kototoro.core.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.migrations.Migration83To84

@RunWith(AndroidJUnit4::class)
class Migration83To84Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MangaDatabase::class.java,
    )

    @Test
    fun migrate83To84FullCutoverAndDisambiguation() {
        helper.createDatabase(TEST_DB, 83).use { db ->
            // 1. Insert seed manga
            db.execSQL(
                """
                INSERT INTO manga (manga_id, title, url, public_url, rating, nsfw, cover_url, source)
                VALUES (1, 'Manga Alpha', '/alpha', 'https://example.com/alpha', 4.5, 0, 'https://example.com/a.jpg', 'source_a'),
                       (2, 'Manga Beta', '/beta', 'https://example.com/beta', 4.0, 0, 'https://example.com/b.jpg', 'source_b')
                """.trimIndent(),
            )

            // 2. Insert chapters: Chapter 101 belongs to Manga 1; Chapter 555 belongs to Manga 2
            db.execSQL(
                """
                INSERT INTO chapters (chapter_id, manga_id, name, number, volume, url, upload_date, source, `index`)
                VALUES (101, 1, 'Chapter 1', 1.0, 0, '/alpha/1', 1000, 'source_a', 0),
                       (555, 2, 'Chapter 55', 55.0, 0, '/beta/55', 2000, 'source_b', 0)
                """.trimIndent(),
            )

            // 3. Insert Entity and Bindings linking both Manga 1 and Manga 2 to Entity 100
            db.execSQL(
                """
                INSERT INTO entity (id, type, primary_name, name_hash, created_at, last_accessed, access_count)
                VALUES (100, 'WORK', 'Work Omnibus', 100, 1000, 2000, 10)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO entity_binding (entity_id, source, external_id, confidence, is_primary)
                VALUES (100, 'local_manga', '1', 1.0, 1),
                       (100, 'local_manga', '2', 0.9, 0)
                """.trimIndent(),
            )

            // 4. Insert Entity Preferences (manual overrides that stripped shadow preferences)
            db.execSQL(
                """
                INSERT INTO entity_preferences (
                    entity_id, preferred_local_manga_id, title_override, cover_override, reading_status, updated_at
                ) VALUES (100, 1, 'Overridden Title', 'https://example.com/custom_cover.jpg', 'READING', 3000)
                """.trimIndent(),
            )

            // 5. Insert category and Work Favourites
            db.execSQL(
                """
                INSERT INTO favourite_categories (category_id, sort_key, title, `order`, track, show_in_lib, created_at, deleted_at)
                VALUES (5, 0, 'My List', 'NEWEST', 1, 1, 1000, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO work_favourites (
                    entity_id, category_id, anchor_manga_id, sort_key, pinned, created_at, deleted_at, updated_at
                ) VALUES (100, 5, 1, 10, 1, 1000, 0, 3000)
                """.trimIndent(),
            )

            // 6. Insert Work History where anchor is Manga 1, but read chapter is 555 (belongs to Manga 2!)
            db.execSQL(
                """
                INSERT INTO work_history (
                    entity_id, anchor_manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters
                ) VALUES (100, 1, 1000, 3000, 555, 15, 0.5, 0.75, 0, 60)
                """.trimIndent(),
            )

            // 7. Insert Work Stats
            db.execSQL(
                """
                INSERT INTO work_stats (entity_id, anchor_manga_id, started_at, duration, pages)
                VALUES (100, 1, 12345, 600, 20)
                """.trimIndent(),
            )

            // 8. Insert Tracks
            db.execSQL(
                """
                INSERT INTO tracks (owner_id, manga_id, entity_id, last_chapter_id, chapters_new, last_check_time, last_chapter_date, last_result)
                VALUES (100, 1, 100, 101, 2, 5000, 4000, 1)
                """.trimIndent(),
            )

            // 9. Insert Scrobblings
            db.execSQL(
                """
                INSERT INTO scrobblings (scrobbler, id, owner_id, entity_id, manga_id, target_id, status, chapter, rating, media_type)
                VALUES (1, 1, 100, 100, 1, 999, 'CURRENT', 10, 4.5, 'MANGA')
                """.trimIndent(),
            )

            // 10. Insert TrackingSiteLinks
            db.execSQL(
                """
                INSERT INTO tracking_site_links (service, remote_id, entity_id, manga_id, confidence, is_manual, created_at, updated_at)
                VALUES (1, 888, 100, 1, 1.0, 1, 1000, 2000)
                """.trimIndent(),
            )
        }

        // Run Migration 83 -> 84
        helper.runMigrationsAndValidate(TEST_DB, 84, true, Migration83To84()).use { migrated ->
            // Verification 1: Favourites table has restored data
            migrated.query("SELECT manga_id, category_id, pinned FROM favourites WHERE manga_id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(5L, cursor.getLong(1))
                assertEquals(1, cursor.getInt(2)) // pinned
            }

            // Verification 2: History table resolved true owner Manga 2 instead of Anchor 1
            migrated.query("SELECT manga_id, chapter_id, page FROM history").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2L, cursor.getLong(0)) // TRUE OWNER IS MANGA 2!
                assertEquals(555L, cursor.getLong(1))
                assertEquals(15, cursor.getInt(2))
                assertEquals(1, cursor.count)
            }

            // Verification 3: Preferences table received title and cover overrides from EntityPrefs
            migrated.query("SELECT manga_id, title_override, reading_status FROM preferences WHERE manga_id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals("Overridden Title", cursor.getString(1))
                assertEquals("READING", cursor.getString(2))
            }

            // Verification 4: Stats table populated and foreign key intact
            migrated.query("SELECT manga_id, started_at, duration, pages FROM stats WHERE manga_id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(12345L, cursor.getLong(1))
                assertEquals(600L, cursor.getLong(2))
                assertEquals(20, cursor.getInt(3))
            }

            // Verification 5: Tracks table migrated to manga_id as PK
            migrated.query("SELECT manga_id, last_chapter_id, chapters_new FROM tracks WHERE manga_id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(101L, cursor.getLong(1))
                assertEquals(2, cursor.getInt(2))
            }

            // Verification 6: Scrobblings migrated
            migrated.query("SELECT manga_id, target_id, status FROM scrobblings WHERE manga_id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(999L, cursor.getLong(1))
                assertEquals("CURRENT", cursor.getString(2))
            }

            // Verification 7: TrackingSiteLinks migrated without entity_id
            migrated.query("SELECT service, remote_id, manga_id FROM tracking_site_links WHERE manga_id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals(888L, cursor.getLong(1))
                assertEquals(1L, cursor.getLong(2))
            }

            // Verification 8: All 8 Entity & Work tables are physically DROPPED
            val droppedTables = listOf(
                "work_favourites",
                "work_history",
                "work_stats",
                "entity_preferences",
                "relation",
                "entity_binding",
                "entity",
                "work_migration_ledger",
            )
            for (tableName in droppedTables) {
                migrated.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                    arrayOf(tableName),
                ).use { cursor ->
                    assertFalse("Table $tableName must be dropped", cursor.moveToFirst())
                }
            }
        }
    }

    @Test
    fun migrate83To84KeepsIndicesPendingLegacyRowsAndEntityOnlyOwners() {
        helper.createDatabase(TEST_DB_EDGE, 83).use { db ->
            db.execSQL(
                """
                INSERT INTO manga (manga_id, title, url, public_url, rating, nsfw, cover_url, source)
                VALUES (1, 'Alpha', '/alpha', 'https://example.com/alpha', 0, 0, '', 'source_a'),
                       (2, 'Beta', '/beta', 'https://example.com/beta', 0, 0, '', 'source_a')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO entity (id, type, sync_id, primary_name, name_hash, created_at, last_accessed, access_count)
                VALUES (100, 'WORK', 'sync-100', 'Alpha work', 100, 0, 0, 0), (200, 'WORK', 'sync-200', 'Beta work', 200, 0, 0, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO entity_binding (entity_id, source, external_id, confidence, is_primary)
                VALUES (100, 'local_manga', '1', 1.0, 1), (200, 'local_manga', '2', 1.0, 1)
                """.trimIndent(),
            )

            // A legacy projection history row left behind by a restore whose normalization never
            // ran is NEWER than the work row: it must win instead of being overwritten.
            db.execSQL(
                """
                INSERT INTO history (manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters)
                VALUES (1, 100, 9000, 7, 3, 0, 0.9, 0, 10)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO work_history (
                    entity_id, anchor_manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters
                ) VALUES (100, 1, 50, 1000, 5, 1, 0, 0.2, 0, 10)
                """.trimIndent(),
            )

            // Entity-only rows (manga_id = 0) resolve through the entity's local binding.
            db.execSQL(
                """
                INSERT INTO tracking_site_links (service, remote_id, entity_id, manga_id, confidence, is_manual, created_at, updated_at)
                VALUES (1, 888, 200, 0, 1.0, 1, 1000, 2000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO scrobblings (scrobbler, id, owner_id, entity_id, manga_id, target_id, status, chapter, rating, media_type)
                VALUES (1, 1, 200, 200, 0, 999, 'CURRENT', 3, 0, 'MANGA')
                """.trimIndent(),
            )
        }

        // runMigrationsAndValidate fails if any expected v84 index is missing: v83 already owns
        // index names such as index_tracking_site_links_manga_id, so a rebuilt table only gets
        // them if they are created after the old table is dropped.
        helper.runMigrationsAndValidate(TEST_DB_EDGE, 84, true, Migration83To84()).use { migrated ->
            migrated.query("SELECT chapter_id, percent, updated_at FROM history WHERE manga_id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
                assertEquals(0.9f, cursor.getFloat(1))
                assertEquals(9000L, cursor.getLong(2))
                assertEquals(1, cursor.count)
            }
            migrated.query("SELECT manga_id FROM tracking_site_links WHERE remote_id = 888").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2L, cursor.getLong(0))
            }
            migrated.query("SELECT manga_id FROM scrobblings WHERE target_id = 999").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2L, cursor.getLong(0))
            }
            for (index in listOf(
                "index_track_logs_manga_id",
                "index_scrobblings_manga_id",
                "index_tracking_site_links_manga_id",
                "index_tracking_site_links_service_remote_id",
            )) {
                migrated.query("SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(index)).use {
                    assertTrue("index $index must exist after migration", it.moveToFirst())
                }
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-83-84-test.db"
        const val TEST_DB_EDGE = "migration-83-84-edge-test.db"
    }
}
