package org.skepsun.kototoro.spikes.roomkmp

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.skepsun.kototoro.core.db.DATABASE_VERSION
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.getDatabaseMigrations

/**
 * S2 gate: the 85 legacy `Migration(SupportSQLiteDatabase)` classes must keep working when MangaDatabase is a
 * Room KMP database. The repo only keeps schema snapshots for some versions, so the chain is validated segment
 * by segment between the snapshots that exist (the pre-existing `migrateAll` test needs 1.json and cannot run).
 */
@RunWith(AndroidJUnit4::class)
class MigrationChainTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MangaDatabase::class.java,
    )

    private val migrations = getDatabaseMigrations(InstrumentationRegistry.getInstrumentation().targetContext)

    // Versions that have a schema JSON in app/schemas (copied into this module's device-test assets).
    private val snapshots = listOf(32, 33, 34, 35, 36, 37, 39, 40, 42, 68, 69, 70, 71, 72, 73, 76, 77, 78, 79, 80, 82, 83, 84)

    @Test
    fun migrationListIsContiguousAndEndsAtCurrentVersion() {
        val forward = migrations.filter { it.startVersion < it.endVersion }
        assertEquals(1, forward.first().startVersion)
        assertEquals(DATABASE_VERSION, forward.last().endVersion)
    }

    @Test
    fun migrateAcrossAvailableSnapshots() {
        for ((from, to) in snapshots.zipWithNext()) {
            helper.createDatabase(TEST_DB, from).close()
            val chain = migrations.filter { it.startVersion >= from && it.endVersion <= to }.toTypedArray()
            assertEquals("chain $from -> $to must be contiguous", to - from, chain.size)
            helper.runMigrationsAndValidate(TEST_DB, to, true, *chain).close()
        }
    }

    /**
     * Negative control: a no-op 83 -> 84 migration leaves the 8 Entity/Work tables in place, so Room's schema
     * validation must reject it. Proves the passing test above is not vacuous.
     */
    @Test
    fun noOpMigrationIsRejectedByValidation() {
        helper.createDatabase(TEST_DB, 83).close()
        val noOp = object : androidx.room.migration.Migration(83, 84) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
        }
        var rejected = false
        try {
            helper.runMigrationsAndValidate(TEST_DB, 84, true, noOp).close()
        } catch (e: IllegalStateException) {
            rejected = true
        }
        assertEquals("a migration that does not produce the v84 schema must fail validation", true, rejected)
    }

    private companion object {
        const val TEST_DB = "migration-chain-test"
    }
}
