package org.skepsun.kototoro.core.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity
import org.skepsun.kototoro.core.db.entity.SourceRefreshStateEntity
import org.skepsun.kototoro.core.db.migrations.Migration77To78

/**
 * Migration 77 → 78 coverage: backfill of `source_origins` from the legacy `sources` table
 * (stable prefixes only, unknown prefixes untouched, original rows unchanged) plus the basic
 * CRUD contract of [org.skepsun.kototoro.core.db.dao.SourceOriginsDao] and
 * [org.skepsun.kototoro.core.db.dao.SourceRefreshStateDao].
 */
@RunWith(AndroidJUnit4::class)
class SourceOriginsMigration77To78Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MangaDatabase::class.java,
    )

    private lateinit var db: MangaDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MangaDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun migrate77To78BackfillsKnownPrefixesAndSkipsUnknown() {
        helper.createDatabase(TEST_DB, 77).use { legacy ->
            legacy.execSQL(
                """
                INSERT INTO sources (source, enabled, sort_key, added_in, used_at, pinned, cf_state)
                VALUES ('MIHON_123', 1, 0, 78, 0, 0, 0)
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                INSERT INTO sources (source, enabled, sort_key, added_in, used_at, pinned, cf_state)
                VALUES ('WEIRD_XYZ', 1, 1, 78, 0, 0, 0)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 78, true, Migration77To78()).use { migrated ->
            // Stable prefix is backfilled with its mapped kind.
            migrated.query("SELECT source_key, kind FROM source_origins").use { cursor ->
                cursor.moveToFirst()
                assertEquals("MIHON_123", cursor.getString(0))
                assertEquals("MIHON", cursor.getString(1))
                assertEquals(1, cursor.count)
            }
            // Unknown prefix is not guessed.
            migrated.query(
                "SELECT COUNT(*) FROM source_origins WHERE source_key = 'WEIRD_XYZ'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            // The legacy sources rows stay untouched.
            migrated.query("SELECT COUNT(*) FROM sources").use { cursor ->
                cursor.moveToFirst()
                assertEquals(2, cursor.getInt(0))
            }
            migrated.query("SELECT source, enabled FROM sources ORDER BY source").use { cursor ->
                cursor.moveToFirst()
                assertEquals("MIHON_123", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                cursor.moveToNext()
                assertEquals("WEIRD_XYZ", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
            // The refresh-state table exists and is empty.
            migrated.query("SELECT COUNT(*) FROM source_refresh_state").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun sourceOriginsDaoCrud() = runTest {
        val dao = db.getSourceOriginsDao()
        val origin = SourceOriginEntity(
            sourceKey = "TSUNDOKU_9001",
            kind = "TSUNDOKU",
            displayName = "Tsundoku Fixture",
            contentType = "MANGA",
            sourceId = "9001",
            updatedAt = 1000L,
        )
        assertNull(dao.getByKey("TSUNDOKU_9001"))
        assertEquals(0, dao.countByKey("TSUNDOKU_9001"))
        assertEquals(emptyList<SourceOriginEntity>(), dao.findAll())

        dao.upsert(origin)
        assertEquals(origin, dao.getByKey("TSUNDOKU_9001"))
        assertEquals(1, dao.countByKey("TSUNDOKU_9001"))
        assertEquals(listOf(origin), dao.findAll())
        assertEquals(listOf(origin), dao.observeAll().first())

        // Upsert overwrites.
        dao.upsert(origin.copy(displayName = "Renamed", updatedAt = 2000L))
        assertEquals("Renamed", dao.getByKey("TSUNDOKU_9001")?.displayName)
        assertEquals(1, dao.countByKey("TSUNDOKU_9001"))

        dao.deleteByKey("TSUNDOKU_9001")
        assertNull(dao.getByKey("TSUNDOKU_9001"))
        assertEquals(0, dao.countByKey("TSUNDOKU_9001"))
    }

    @Test
    fun sourceRefreshStateDaoCrud() = runTest {
        val dao = db.getSourceRefreshStateDao()
        val state = SourceRefreshStateEntity(
            sourceKey = "TSUNDOKU_9001",
            contentId = 42L,
            lastSuccessAt = 1500L,
            lastAttemptAt = 1500L,
            updatedAt = 1500L,
        )
        assertNull(dao.get("TSUNDOKU_9001", 42L))
        assertEquals(emptyList<SourceRefreshStateEntity>(), dao.findBySource("TSUNDOKU_9001"))
        assertEquals(emptyList<SourceRefreshStateEntity>(), dao.observeBySource("TSUNDOKU_9001").first())

        dao.upsert(state)
        assertEquals(state, dao.get("TSUNDOKU_9001", 42L))
        assertEquals(listOf(state), dao.findBySource("TSUNDOKU_9001"))
        assertEquals(listOf(state), dao.observeBySource("TSUNDOKU_9001").first())

        // A second content row for the same source.
        dao.upsert(state.copy(contentId = 43L, updatedAt = 1600L))
        assertEquals(2, dao.findBySource("TSUNDOKU_9001").size)

        // Upsert overwrites within the composite key.
        dao.upsert(state.copy(lastError = "boom", updatedAt = 1700L))
        assertEquals("boom", dao.get("TSUNDOKU_9001", 42L)?.lastError)
        assertEquals(2, dao.findBySource("TSUNDOKU_9001").size)

        dao.delete("TSUNDOKU_9001", 42L)
        assertNull(dao.get("TSUNDOKU_9001", 42L))
        assertNotNull(dao.get("TSUNDOKU_9001", 43L))

        dao.deleteBySource("TSUNDOKU_9001")
        assertEquals(emptyList<SourceRefreshStateEntity>(), dao.findBySource("TSUNDOKU_9001"))
    }

    private companion object {

        const val TEST_DB = "source-origins-test-db"
    }
}
