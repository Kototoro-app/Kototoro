package org.skepsun.kototoro.core.db

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the legacy migration chain against the schema snapshots that actually exist in app/schemas.
 *
 * [MangaDatabaseTest.migrateAll] needs 1.json and can never run: the repository only keeps snapshots for
 * versions 32-37, 39, 40, 42, 68-73, 76-80 and 82-84. This test walks the chain segment by segment between the
 * snapshots that do exist, through the same MigrationTestHelper validation. It also guards the :core-db
 * extraction: the migrations and MangaDatabase now live in a separate KMP module.
 */
@RunWith(AndroidJUnit4::class)
class MangaDatabaseSnapshotChainTest {

	@get:Rule
	val helper: MigrationTestHelper = MigrationTestHelper(
		InstrumentationRegistry.getInstrumentation(),
		MangaDatabase::class.java,
	)

	private val migrations = getDatabaseMigrations(InstrumentationRegistry.getInstrumentation().targetContext)

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

	/** Negative control: proves the validation above is not vacuous. */
	@Test
	fun noOpMigrationIsRejectedByValidation() {
		helper.createDatabase(TEST_DB, 83).close()
		val noOp = object : Migration(83, 84) {
			override fun migrate(db: SupportSQLiteDatabase) = Unit
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
		const val TEST_DB = "snapshot-chain-test"
	}
}
